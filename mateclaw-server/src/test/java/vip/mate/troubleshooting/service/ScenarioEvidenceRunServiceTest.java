package vip.mate.troubleshooting.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.engine.Criterion;
import vip.mate.troubleshooting.engine.CriterionEvaluator;
import vip.mate.troubleshooting.engine.DiagnosisRuleEvaluator;
import vip.mate.troubleshooting.evidence.EvidenceSourceRouter;
import vip.mate.troubleshooting.evidence.PlaybookEvidenceCollector;
import vip.mate.troubleshooting.model.AnomalyCriterion;
import vip.mate.troubleshooting.model.ConclusionType;
import vip.mate.troubleshooting.model.Confidence;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.DiagnosisRule;
import vip.mate.troubleshooting.model.DiagnosisStatus;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.model.NorthStarTimings;
import vip.mate.troubleshooting.model.PlaybookVersionRef;
import vip.mate.troubleshooting.model.ScenarioDiagnosisDraft;
import vip.mate.troubleshooting.model.SopEntry;
import vip.mate.troubleshooting.statemachine.DiagnosisStateMachine;
import vip.mate.troubleshooting.synthesis.ApprovedPlaybookVersion;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The step that was missing between "a scenario investigation is waiting" and
 * "the aggregate knows how to accept evidence".
 */
class ScenarioEvidenceRunServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-30T12:00:00Z");
    private static final String SELECTOR = "csdp:scenario:message_send_failed";
    private static final String SCENARIO_KEY = "message_send_failed";

    private final TroubleshootingPersistenceService persistence =
            mock(TroubleshootingPersistenceService.class);
    private final TroubleshootingPlaybookVersionService versions =
            mock(TroubleshootingPlaybookVersionService.class);
    private final EvidenceSourceRouter router = mock(EvidenceSourceRouter.class);
    private final DiagnosisStateMachine stateMachine = new DiagnosisStateMachine(
            Clock.fixed(NOW, ZoneOffset.UTC), prefix -> prefix + "-fixed");
    private final CriterionEvaluator criteria = new CriterionEvaluator();
    private final DiagnosisRuleEvaluator rules = new DiagnosisRuleEvaluator();
    private final ScenarioEvidenceRunService service = new ScenarioEvidenceRunService(
            persistence, versions, stateMachine, criteria, rules,
            new PlaybookEvidenceCollector(router));

    @Test
    @DisplayName("跑完取证计划后诊断不再停在待取证，结论来自 Playbook 自己的规则")
    void runningThePlanUnblocksTheInvestigationWithThePlaybooksOwnConclusion() {
        awaiting();
        when(router.collect(eq(7L), any(EvidenceRequest.class), any(IncidentContext.class)))
                .thenReturn(evidence(EvidenceStatus.ANOMALY, Map.of("match_count", 3)));
        when(persistence.update(eq(7L), any(Diagnosis.class), eq(4)))
                .thenAnswer(call -> new StoredDiagnosis(call.getArgument(1), 5, false));

        StoredDiagnosis result = service.run(7L, "diag-scenario", "alice");

        Diagnosis advanced = result.diagnosis();
        assertThat(advanced.status()).isEqualTo(DiagnosisStatus.READY_FOR_HUMAN);
        assertThat(advanced.abstained()).isFalse();
        assertThat(advanced.conclusionType()).isEqualTo(ConclusionType.LOCATED);
        assertThat(advanced.rootCause())
                .as("结论必须是 Playbook 写的那一条，不是这里现编的")
                .isEqualTo("消息发送路径异常待核查");
        assertThat(advanced.evidence())
                .extracting(EvidenceResult::queryId)
                .containsExactly("SYNTH-LOG-SEARCH");
    }

    @Test
    @DisplayName("取证没回来时保持弃权，仍然不能被确认")
    void evidenceThatNeverArrivesLeavesTheInvestigationWaiting() {
        awaiting();
        when(router.collect(eq(7L), any(EvidenceRequest.class), any(IncidentContext.class)))
                .thenReturn(evidence(EvidenceStatus.MISSING, Map.of()));
        when(persistence.update(eq(7L), any(Diagnosis.class), eq(4)))
                .thenAnswer(call -> new StoredDiagnosis(call.getArgument(1), 5, false));

        Diagnosis advanced = service.run(7L, "diag-scenario", "alice").diagnosis();

        assertThat(advanced.status()).isEqualTo(DiagnosisStatus.NEEDS_INVESTIGATION);
        assertThat(advanced.abstained()).isTrue();
        assertThatThrownBy(() -> stateMachine.confirm(advanced, "alice"))
                .isInstanceOf(MateClawException.class);
    }

    /**
     * Re-running the plan after a person has read the conclusion would rewrite
     * something they may have acted on. That is a new investigation, not a
     * silent update to an old one.
     */
    @Test
    @DisplayName("已进入人工环节的诊断拒绝重跑，而不是悄悄改写结论")
    void itRefusesToRerunAnInvestigationAHumanHasAlreadySeen() {
        Diagnosis ready = stateMachine.recordScenarioEvidence(
                awaitingDiagnosis(), playbook(),
                List.of(evidence(EvidenceStatus.ANOMALY, Map.of("match_count", 3))),
                vip.mate.troubleshooting.engine.PlaybookEvidenceAssessment.assess(
                        playbook(),
                        List.of(evidence(EvidenceStatus.ANOMALY, Map.of("match_count", 3))),
                        criteria, rules, true),
                "alice");
        when(persistence.get(7L, "diag-ready")).thenReturn(new StoredDiagnosis(ready, 5, false));

        assertThatThrownBy(() -> service.run(7L, "diag-ready", "bob"))
                .isInstanceOf(MateClawException.class)
                .satisfies(error -> assertThat(((MateClawException) error).getCode())
                        .isEqualTo(409))
                .hasMessageContaining("no longer waiting");

        verifyNothingWritten();
    }

    @Test
    @DisplayName("错误码诊断不走这道门")
    void anErrorCodeInvestigationIsNotAScenarioEvidencePlan() {
        Diagnosis errorCode = mock(Diagnosis.class);
        when(errorCode.investigationMode())
                .thenReturn(vip.mate.troubleshooting.model.InvestigationMode.ERROR_CODE_PLAYBOOK);
        when(persistence.get(7L, "diag-903001"))
                .thenReturn(new StoredDiagnosis(errorCode, 2, false));

        assertThatThrownBy(() -> service.run(7L, "diag-903001", "alice"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("only a scenario investigation");

        verifyNothingWritten();
    }

    /**
     * D18: a Workspace asset's evidence is collected by that asset's own
     * authorized tool. Pushing it through the Router would return MISSING and
     * file "we looked and found nothing" about a source never consulted.
     */
    @Test
    @DisplayName("证据由资产工具负责的场景，这里拒绝执行而不是伪造一条取不到")
    void itDeclinesPlansWhoseRequiredEvidenceBelongsToAnAssetTool() {
        Diagnosis waiting = awaitingDiagnosis();
        when(persistence.get(7L, "diag-asset"))
                .thenReturn(new StoredDiagnosis(waiting, 4, false));
        when(versions.findByRef(eq(7L), any(PlaybookVersionRef.class)))
                .thenReturn(Optional.of(approved(assetBackedPlaybook())));

        assertThatThrownBy(() -> service.run(7L, "diag-asset", "alice"))
                .isInstanceOf(MateClawException.class)
                .satisfies(error -> assertThat(((MateClawException) error).getCode())
                        .isEqualTo(409))
                .hasMessageContaining("asset tool");

        verify(router, never()).collect(
                anyLong(), any(EvidenceRequest.class), any(IncidentContext.class));
        verifyNothingWritten();
    }

    /**
     * The frozen version, never the currently active one. Judging old evidence
     * by rules approved later makes the recorded reasoning stop matching the
     * conclusion printed beside it.
     */
    @Test
    @DisplayName("按诊断冻结的那个版本求值，不按当前生效版本")
    void itEvaluatesAgainstTheFrozenVersionRatherThanWhateverIsActiveNow() {
        awaiting();
        when(router.collect(eq(7L), any(EvidenceRequest.class), any(IncidentContext.class)))
                .thenReturn(evidence(EvidenceStatus.ANOMALY, Map.of("match_count", 3)));
        when(persistence.update(eq(7L), any(Diagnosis.class), eq(4)))
                .thenAnswer(call -> new StoredDiagnosis(call.getArgument(1), 5, false));

        service.run(7L, "diag-scenario", "alice");

        verify(versions).findByRef(7L, new PlaybookVersionRef("playbook-scenario", 1));
        verify(versions, never()).activeRef(anyLong(), any());
    }

    private void awaiting() {
        when(persistence.get(7L, "diag-scenario"))
                .thenReturn(new StoredDiagnosis(awaitingDiagnosis(), 4, false));
        when(versions.findByRef(eq(7L), any(PlaybookVersionRef.class)))
                .thenReturn(Optional.of(approved(playbook())));
    }

    private void verifyNothingWritten() {
        verify(persistence, never()).update(anyLong(), any(Diagnosis.class), org.mockito
                .ArgumentMatchers.anyInt());
    }

    private Diagnosis awaitingDiagnosis() {
        return stateMachine.initializeScenarioAwaitingEvidence(new ScenarioDiagnosisDraft(
                "diag-scenario", "case-scenario", "run-scenario",
                incident(), SCENARIO_KEY, playbook(),
                new PlaybookVersionRef("playbook-scenario", 1),
                "operator",
                NorthStarTimings.concluded(
                        NOW.minusSeconds(120), NOW.minusSeconds(110), NOW.minusSeconds(110)),
                false, true,
                List.of("取证尚未执行")));
    }

    private IncidentContext incident() {
        return new IncidentContext(
                "inc-message-send", "CSDP", "csdp-session-service", null,
                "会话消息发送失败", "P1", "待确认", null,
                NOW.minusSeconds(120), null,
                "web:scenario", IncidentCompleteness.SYMPTOM, "会话消息发送失败");
    }

    private static EvidenceResult evidence(EvidenceStatus status, Map<String, Object> observed) {
        return new EvidenceResult(
                "SYNTH-LOG-SEARCH", "L", "search",
                status, "会话消息发送失败日志计数", observed,
                "guance", NOW.minusSeconds(30));
    }

    private static SopEntry playbook() {
        return playbook(new EvidenceRequest(
                "SYNTH-LOG-SEARCH", "log_search", "定位失败请求",
                Map.of("search_term", "message_send_failed"), "-15m", true));
    }

    private static SopEntry assetBackedPlaybook() {
        return playbook(new EvidenceRequest(
                "SYNTH-LOG-SEARCH", "synthetic_probe", "执行资产工具取证",
                Map.of("assetType", "deployment_topology", "toolKey", "topology_synthetic_probe"),
                "-15m", true));
    }

    private static SopEntry playbook(EvidenceRequest request) {
        return new SopEntry(
                "playbook-scenario",
                SopEntry.CURRENT_CONTRACT_VERSION,
                "CSDP", "scenario:" + SCENARIO_KEY, "csdp-session-service",
                "会话消息发送失败路径核查", "会话状态冲突", "message", "会话平台组",
                "approved", true,
                List.of(request),
                List.of(new AnomalyCriterion(
                        "send_failure_present", "SYNTH-LOG-SEARCH", "存在失败日志",
                        new Criterion.NumericGte("match_count", 1))),
                List.of(new DiagnosisRule(
                        "RULE-MESSAGE-SEND-PATH",
                        List.of("send_failure_present"),
                        "消息发送路径异常待核查", "收敛到会话状态冲突特征",
                        Confidence.MEDIUM, false)),
                List.of());
    }

    private static ApprovedPlaybookVersion approved(SopEntry playbook) {
        return new ApprovedPlaybookVersion(
                "playbook-scenario", 1, SELECTOR, "APPROVED",
                "MANUAL_WRITE", "record-scenario", null, null,
                "reviewer", "内网核实通过", null,
                null, null, null,
                playbook, NOW.minusSeconds(600), NOW.minusSeconds(600));
    }
}
