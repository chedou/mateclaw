package vip.mate.troubleshooting.statemachine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.model.ActionOutcomeStatus;
import vip.mate.troubleshooting.model.ActionType;
import vip.mate.troubleshooting.model.ApprovalStatus;
import vip.mate.troubleshooting.model.ClosureOutcome;
import vip.mate.troubleshooting.model.Confidence;
import vip.mate.troubleshooting.engine.PlaybookEvidenceAssessment;
import vip.mate.troubleshooting.engine.CriterionEvaluator;
import vip.mate.troubleshooting.engine.DiagnosisRuleEvaluator;
import vip.mate.troubleshooting.model.ConclusionType;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.DiagnosisStatus;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.ExecutionStatus;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.model.NorthStarTimings;
import vip.mate.troubleshooting.model.PlaybookVersionRef;
import vip.mate.troubleshooting.model.RecommendedAction;
import vip.mate.troubleshooting.model.RouteMode;
import vip.mate.exception.MateClawException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosisStateMachineTest {

    private DiagnosisStateMachine stateMachine;

    @BeforeEach
    void setUp() {
        AtomicInteger sequence = new AtomicInteger();
        stateMachine = new DiagnosisStateMachine(
                Clock.fixed(Instant.parse("2026-07-25T01:02:03Z"), ZoneOffset.UTC),
                prefix -> prefix + "-" + sequence.incrementAndGet());
    }

    /**
     * The transition that unstuck every scenario Diagnosis in the system.
     *
     * <p>A scenario Diagnosis is created {@code abstained} on purpose — naming a
     * scenario selects an evidence plan, it does not assert a cause — and
     * {@code confirm} refuses an abstained Diagnosis until new evidence arrives.
     * Both halves were right, and nothing supplied that evidence, so every
     * scenario Diagnosis (including the shipped deployment-topology one) was
     * permanently stuck in {@code NEEDS_INVESTIGATION}.</p>
     */
    @Test
    void scenarioEvidenceUnlocksConfirmationOnceTheRulesActuallyMatch() {
        Diagnosis scenario = awaitingScenario();
        assertEquals(DiagnosisStatus.NEEDS_INVESTIGATION, scenario.status());
        assertTrue(scenario.abstained(), "指定场景不等于断言原因，弃权是对的");
        assertThrows(MateClawException.class, () -> stateMachine.confirm(scenario, "operator"));

        List<EvidenceResult> evidence = List.of(scenarioEvidence(4));
        Diagnosis investigated = stateMachine.recordScenarioEvidence(
                scenario, scenarioPlaybook(), evidence,
                PlaybookEvidenceAssessment.assess(
                        scenarioPlaybook(), evidence,
                        new CriterionEvaluator(), new DiagnosisRuleEvaluator(), true),
                "orchestrator");

        assertEquals(DiagnosisStatus.READY_FOR_HUMAN, investigated.status());
        assertFalse(investigated.abstained());
        assertEquals(ConclusionType.LOCATED, investigated.conclusionType());
        assertEquals("消息发送路径异常待核查", investigated.rootCause());
        assertEquals(List.of("send_failure_present"), investigated.triggeredSignals());

        Diagnosis confirmed = stateMachine.confirm(investigated, "operator");
        assertEquals(DiagnosisStatus.CONFIRMED, confirmed.status());
    }

    /**
     * Evidence that rules the cause out is an answer, and advances.
     *
     * <p>This is the {@code EXCLUDED} / {@code UNEVALUATED} distinction reaching
     * the lifecycle: "we checked and it is not this" is a conclusion a human can
     * act on — it closes a branch — while "we could not check" is not. Treating
     * them the same in either direction would be wrong, and the tempting error
     * is to hold an excluded result back as if nothing had been learned.</p>
     */
    @Test
    void evidenceThatRulesTheCauseOutIsStillAnAnswerAndAdvances() {
        List<EvidenceResult> ruledOut = List.of(scenarioEvidence(0));
        Diagnosis investigated = stateMachine.recordScenarioEvidence(
                awaitingScenario(), scenarioPlaybook(), ruledOut,
                PlaybookEvidenceAssessment.assess(
                        scenarioPlaybook(), ruledOut,
                        new CriterionEvaluator(), new DiagnosisRuleEvaluator(), true),
                "orchestrator");

        assertEquals(ConclusionType.EXCLUDED, investigated.conclusionType());
        assertEquals(DiagnosisStatus.READY_FOR_HUMAN, investigated.status());
        assertFalse(investigated.abstained(), "排除是结论，不是弃权");
        assertTrue(investigated.warnings().stream()
                .anyMatch(warning -> warning.contains("这是排除，不是定位")));
    }

    /**
     * The half that matters more. Evidence arriving is not evidence answering:
     * a required request that never came back leaves the investigation exactly
     * where it was, or "we looked" would start reading as "we found".
     */
    @Test
    void missingEvidenceLeavesTheInvestigationExactlyWhereItWas() {
        List<EvidenceResult> nothing = List.of(new EvidenceResult(
                "SYNTH-LOG-SEARCH", "L", "", EvidenceStatus.MISSING,
                "取证失败", Map.of(), "recorded-replay",
                Instant.parse("2026-07-25T01:00:30Z")));
        Diagnosis investigated = stateMachine.recordScenarioEvidence(
                awaitingScenario(), scenarioPlaybook(), nothing,
                PlaybookEvidenceAssessment.assess(
                        scenarioPlaybook(), nothing,
                        new CriterionEvaluator(), new DiagnosisRuleEvaluator(), true),
                "orchestrator");

        assertEquals(ConclusionType.INSUFFICIENT_EVIDENCE, investigated.conclusionType());
        assertEquals(DiagnosisStatus.NEEDS_INVESTIGATION, investigated.status());
        assertTrue(investigated.abstained());
        assertThrows(
                MateClawException.class,
                () -> stateMachine.confirm(investigated, "operator"));
    }

    /** An error-code Diagnosis is not a scenario investigation and must not take this door. */
    @Test
    void onlyAScenarioInvestigationAcceptsScenarioEvidence() {
        assertThrows(MateClawException.class, () -> stateMachine.recordScenarioEvidence(
                readyDiagnosis(), scenarioPlaybook(), List.of(),
                PlaybookEvidenceAssessment.assess(
                        scenarioPlaybook(), List.of(),
                        new CriterionEvaluator(), new DiagnosisRuleEvaluator(), true),
                "orchestrator"));
    }

    private Diagnosis awaitingScenario() {
        return stateMachine.initializeScenarioAwaitingEvidence(
                new vip.mate.troubleshooting.model.ScenarioDiagnosisDraft(
                        "diag-scenario-1", "case-scenario-1", "run-scenario-1",
                        scenarioIncident(), "message_send_failed", scenarioPlaybook(),
                        new PlaybookVersionRef("playbook-scenario", 1),
                        "operator",
                        NorthStarTimings.concluded(
                                Instant.parse("2026-07-25T01:00:00Z"),
                                Instant.parse("2026-07-25T01:00:10Z"),
                                Instant.parse("2026-07-25T01:00:10Z")),
                        false, true,
                        java.util.List.of("取证尚未执行")));
    }

    private EvidenceResult scenarioEvidence(int matchCount) {
        return new EvidenceResult(
                "SYNTH-LOG-SEARCH", "L", "recorded://message-send",
                matchCount > 0 ? EvidenceStatus.ANOMALY : EvidenceStatus.NORMAL,
                "会话消息发送失败日志计数",
                Map.of("match_count", matchCount),
                "recorded-replay", Instant.parse("2026-07-25T01:00:30Z"));
    }

    @Test
    void fullHumanControlledFlowNeverExecutesTheManualWrite() {
        Diagnosis diagnosis = stateMachine.confirm(readyDiagnosis(), "on-call");
        diagnosis = stateMachine.transfer(diagnosis, "DBA 值班", "携带完整证据", "on-call");
        diagnosis = stateMachine.approveAction(
                diagnosis, "restart-mongodb", "维护窗口已确认", "dba");

        RecommendedAction approved = action(diagnosis, "restart-mongodb");
        assertEquals(ApprovalStatus.APPROVED_NOT_EXECUTED, approved.approvalStatus());
        assertEquals(ExecutionStatus.BLOCKED, approved.executionStatus());
        assertTrue(diagnosis.pendingWrites().isEmpty());
        assertTrue(diagnosis.timeline().getLast().event().contains("系统未执行"));

        diagnosis = stateMachine.recordActionOutcome(
                diagnosis,
                "restart-mongodb",
                ActionOutcomeStatus.SUCCEEDED,
                "DBA 已在外部系统完成恢复",
                true,
                "dba");
        diagnosis = stateMachine.close(
                diagnosis,
                ClosureOutcome.RECOVERED,
                "业务探测恢复",
                true,
                "补充连接池预警阈值",
                true,
                "on-call");

        assertEquals(DiagnosisStatus.CLOSED, diagnosis.status());
        assertTrue(diagnosis.closure().recoveryVerified());
        assertEquals(1, diagnosis.knowledgeCandidates().size());
        assertEquals("DBA 值班", diagnosis.knowledgeCandidates().getFirst().ownerTeam());
        assertFalse(diagnosis.writeExecutionEnabled());
        assertEquals(ExecutionStatus.BLOCKED,
                action(diagnosis, "restart-mongodb").executionStatus());
    }

    @Test
    void abstainedDiagnosisCannotBeConfirmed() {
        Diagnosis ready = readyDiagnosis();
        Diagnosis abstained = Diagnosis.initial(
                ready.diagnosisId(),
                ready.caseId(),
                ready.runId(),
                ready.incident(),
                ready.routeMode(),
                DiagnosisStatus.NEEDS_INVESTIGATION,
                "等待人工深查",
                "证据不足",
                Confidence.LOW,
                true,
                ready.sopKey(),
                ready.sopTitle(),
                ready.sourcePlaybookVersionRef(),
                ready.evidence(),
                ready.triggeredSignals(),
                List.of(),
                null,
                ready.rehearsal(),
                ready.fixtureMode(),
                ready.warnings());

        MateClawException error = assertThrows(
                MateClawException.class,
                () -> stateMachine.confirm(abstained, "on-call"));

        assertEquals(409, error.getCode());
    }

    @Test
    void transferAndApprovalRejectSkippedConfirmation() {
        Diagnosis diagnosis = readyDiagnosis();

        assertThrows(
                MateClawException.class,
                () -> stateMachine.transfer(diagnosis, "DBA", "跳步", "on-call"));
        assertThrows(
                MateClawException.class,
                () -> stateMachine.approveAction(diagnosis, "restart-mongodb", "跳步", "dba"));
    }

    @Test
    void outcomeRequiresApprovalAndRecoveryVerificationRequiresSuccess() {
        Diagnosis confirmed = stateMachine.confirm(readyDiagnosis(), "on-call");

        assertThrows(
                MateClawException.class,
                () -> stateMachine.recordActionOutcome(
                        confirmed,
                        "restart-mongodb",
                        ActionOutcomeStatus.SUCCEEDED,
                        "尚未批准",
                        true,
                        "dba"));

        Diagnosis approved = stateMachine.approveAction(
                confirmed, "restart-mongodb", "窗口确认", "dba");
        assertThrows(
                MateClawException.class,
                () -> stateMachine.recordActionOutcome(
                        approved,
                        "restart-mongodb",
                        ActionOutcomeStatus.FAILED,
                        "失败",
                        true,
                        "dba"));
    }

    @Test
    void recoveredClosureRequiresVerifiedSuccessfulExternalOutcome() {
        Diagnosis confirmed = stateMachine.confirm(readyDiagnosis(), "on-call");
        Diagnosis approved = stateMachine.approveAction(
                confirmed, "restart-mongodb", "窗口确认", "dba");

        assertThrows(
                MateClawException.class,
                () -> stateMachine.close(
                        approved,
                        ClosureOutcome.RECOVERED,
                        "不能跳过结果登记",
                        true,
                        null,
                        false,
                        "on-call"));
    }

    @Test
    void firstHumanConfirmationRecordsHandoffAndAdoptionCostExactlyOnce() {
        Instant reportedAt = Instant.parse("2026-07-25T01:00:00Z");
        Instant readyAt = Instant.parse("2026-07-25T01:00:10Z");
        Instant conclusionAt = Instant.parse("2026-07-25T01:01:00Z");
        Diagnosis timed = readyDiagnosis(NorthStarTimings.concluded(
                reportedAt, readyAt, conclusionAt));

        Diagnosis confirmed = stateMachine.confirm(timed, "on-call");
        Diagnosis transferred = stateMachine.transfer(
                confirmed, "DBA", "携带证据", "on-call");

        assertEquals(Instant.parse("2026-07-25T01:02:03Z"), confirmed.timings().handoffAt());
        assertEquals(java.time.Duration.ofSeconds(63), confirmed.timings().adoptCost());
        assertEquals(confirmed.timings(), transferred.timings());
    }

    private Diagnosis readyDiagnosis() {
        return readyDiagnosis(NorthStarTimings.unrecorded());
    }

    private IncidentContext scenarioIncident() {
        return new IncidentContext(
                "inc-message-send", "CSDP", "csdp-session-service", null,
                "会话消息发送失败", "P1", "待确认", null,
                Instant.parse("2026-07-25T01:00:00Z"), null,
                "web:scenario", IncidentCompleteness.SYMPTOM, "会话消息发送失败");
    }

    private vip.mate.troubleshooting.model.SopEntry scenarioPlaybook() {
        return new vip.mate.troubleshooting.model.SopEntry(
                "playbook-scenario",
                vip.mate.troubleshooting.model.SopEntry.CURRENT_CONTRACT_VERSION,
                "CSDP", "scenario:message_send_failed", "csdp-session-service",
                "会话消息发送失败路径核查", "会话状态冲突", "message", "会话平台组",
                "approved", true,
                java.util.List.of(new vip.mate.troubleshooting.model.EvidenceRequest(
                        "SYNTH-LOG-SEARCH", "log_search", "定位失败请求",
                        Map.of("search_term", "message_send_failed"), "-15m", true)),
                java.util.List.of(new vip.mate.troubleshooting.model.AnomalyCriterion(
                        "send_failure_present", "SYNTH-LOG-SEARCH", "存在失败日志",
                        new vip.mate.troubleshooting.engine.Criterion.NumericGte(
                                "match_count", 1))),
                java.util.List.of(new vip.mate.troubleshooting.model.DiagnosisRule(
                        "RULE-MESSAGE-SEND-PATH",
                        java.util.List.of("send_failure_present"),
                        "消息发送路径异常待核查", "收敛到会话状态冲突特征",
                        Confidence.MEDIUM, false)),
                java.util.List.of());
    }

    private Diagnosis readyDiagnosis(NorthStarTimings timings) {
        IncidentContext incident = new IncidentContext(
                "inc-903001",
                "CSDP",
                "csdp-wechat",
                "903001",
                "数据库访问异常",
                "P0",
                "所有客户",
                "trace-903001",
                Instant.parse("2026-07-25T01:00:00Z"),
                "14:31",
                "alert_fixture",
                IncidentCompleteness.STRUCTURED,
                "fixture error_code=903001");
        EvidenceResult evidence = new EvidenceResult(
                "mongo-metrics",
                "fixture",
                "fixture://mongo",
                EvidenceStatus.ANOMALY,
                "connections saturated",
                Map.of("connections_current", 95, "connections_available", 5),
                "fixture",
                Instant.parse("2026-07-25T01:01:00Z"));
        RecommendedAction readonly = new RecommendedAction(
                "retain-evidence",
                ActionType.AUTO_READONLY,
                "保留取证结果",
                "只读",
                false,
                ApprovalStatus.NOT_REQUIRED,
                ExecutionStatus.COMPLETED);
        RecommendedAction write = RecommendedAction.manualWrite(
                "restart-mongodb",
                "按恢复方案重启 MongoDB",
                "仅允许外部人工执行");

        return Diagnosis.initial(
                "diag-903001",
                "case-903001",
                "run-903001-001",
                incident,
                RouteMode.DETERMINISTIC,
                vip.mate.troubleshooting.model.InvestigationMode.ERROR_CODE_PLAYBOOK,
                vip.mate.troubleshooting.model.RouteAuthority.EXPLICIT,
                vip.mate.troubleshooting.model.ConclusionType.LOCATED,
                timings,
                DiagnosisStatus.READY_FOR_HUMAN,
                "证据一致",
                "MongoDB 连接数饱和",
                Confidence.HIGH,
                false,
                "csdp:903001",
                "MongoDB 连接异常取证与处置",
                "DBA 值班",
                new PlaybookVersionRef("playbook-903001", 1),
                List.of(evidence),
                List.of("conn_saturated"),
                List.of(readonly, write),
                "DBA 值班",
                false,
                true,
                List.of("fixture only"),
                List.of());
    }

    private RecommendedAction action(Diagnosis diagnosis, String actionId) {
        return diagnosis.recommendedActions().stream()
                .filter(item -> item.actionId().equals(actionId))
                .findFirst()
                .orElseThrow();
    }
}
