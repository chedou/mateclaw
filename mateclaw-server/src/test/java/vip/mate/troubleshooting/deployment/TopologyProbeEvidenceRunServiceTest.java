package vip.mate.troubleshooting.deployment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.engine.Criterion;
import vip.mate.troubleshooting.engine.CriterionEvaluator;
import vip.mate.troubleshooting.engine.DiagnosisRuleEvaluator;
import vip.mate.troubleshooting.engine.PlaybookEvidenceAssessment;
import vip.mate.troubleshooting.model.AnomalyCriterion;
import vip.mate.troubleshooting.model.ConclusionType;
import vip.mate.troubleshooting.model.Confidence;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.DiagnosisRule;
import vip.mate.troubleshooting.model.DiagnosisStatus;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.model.NorthStarTimings;
import vip.mate.troubleshooting.model.PlaybookVersionRef;
import vip.mate.troubleshooting.model.ScenarioDiagnosisDraft;
import vip.mate.troubleshooting.model.SopEntry;
import vip.mate.troubleshooting.model.TroubleshootingTopologyProbeRunEntity;
import vip.mate.troubleshooting.repository.TroubleshootingTopologyProbeRunMapper;
import vip.mate.troubleshooting.service.StoredDiagnosis;
import vip.mate.troubleshooting.service.TroubleshootingPersistenceService;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TopologyProbeEvidenceRunServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-30T12:00:00Z");
    private static final String SELECTOR = "csdp:scenario:deployment_topology_probe";

    private final TroubleshootingPersistenceService persistence =
            mock(TroubleshootingPersistenceService.class);
    private final DeploymentTopologyLibraryService library =
            mock(DeploymentTopologyLibraryService.class);
    private final DeploymentTopologyScenarioPolicy scenarioPolicy =
            mock(DeploymentTopologyScenarioPolicy.class);
    private final TroubleshootingTopologyProbeRunMapper mapper =
            mock(TroubleshootingTopologyProbeRunMapper.class);
    private final TopologyProbeEvidenceRunPersistenceService runPersistence =
            mock(TopologyProbeEvidenceRunPersistenceService.class);
    private final DiagnosisStateMachine stateMachine = new DiagnosisStateMachine(
            Clock.fixed(NOW, ZoneOffset.UTC), prefix -> prefix + "-fixed");
    private final CriterionEvaluator criteria = new CriterionEvaluator();
    private final DiagnosisRuleEvaluator rules = new DiagnosisRuleEvaluator();
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final TopologyProbeEvidenceRunService service =
            new TopologyProbeEvidenceRunService(
                    persistence,
                    library,
                    scenarioPolicy,
                    mapper,
                    runPersistence,
                    stateMachine,
                    criteria,
                    rules,
                    objectMapper,
                    Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void persistsOneSafeEvidenceRunUnderTheExistingDiagnosis() {
        awaiting("diag-1", 2);
        when(library.analyze(7L, "topology-1")).thenReturn(failing());

        TopologyProbeEvidenceRun run = service.run(
                7L, "diag-1", "topology-1", "alice");

        assertThat(run.diagnosisId()).isEqualTo("diag-1");
        assertThat(run.topologyId()).isEqualTo("topology-1");
        assertThat(run.scenarioKey()).isEqualTo("deployment_topology_probe");
        assertThat(run.toolKey()).isEqualTo("topology_synthetic_probe");
        assertThat(run.result().persisted()).isTrue();
        assertThat(run.startedAt()).isEqualTo(NOW);
        assertThat(run.completedAt()).isEqualTo(NOW);

        TroubleshootingTopologyProbeRunEntity entity = captureRun("diag-1");
        assertThat(entity.getWorkspaceId()).isEqualTo(7L);
        assertThat(entity.getDiagnosisId()).isEqualTo("diag-1");
        assertThat(entity.getScenarioKey()).isEqualTo("deployment_topology_probe");
        assertThat(entity.getToolKey()).isEqualTo("topology_synthetic_probe");
        assertThat(entity.getResultJson())
                .contains("NETWORK_PROBLEM_DETECTED")
                .doesNotContainIgnoringCase("api-key")
                .doesNotContainIgnoringCase("dql")
                .doesNotContainIgnoringCase("rawResponse");
    }

    /**
     * The gap this service shipped with: the tool answered, its answer was filed
     * in a table of its own, and the Diagnosis that asked the question sat in
     * {@code NEEDS_INVESTIGATION} forever with nothing able to move it.
     */
    @Test
    @DisplayName("拨测结果写回 Diagnosis，命中失败节点后不再卡在待取证")
    void aFailingProbeAnswersTheEvidenceRequestAndUnblocksTheInvestigation() {
        awaiting("diag-1", 2);
        when(library.analyze(7L, "topology-1")).thenReturn(failing());

        TopologyProbeEvidenceRun run = service.run(7L, "diag-1", "topology-1", "alice");

        assertThat(run.conclusionUpdated()).isTrue();
        Diagnosis advanced = captureAdvanced("diag-1");
        assertThat(advanced.conclusionType()).isEqualTo(ConclusionType.LOCATED);
        assertThat(advanced.status()).isEqualTo(DiagnosisStatus.READY_FOR_HUMAN);
        assertThat(advanced.abstained()).isFalse();
        assertThat(advanced.evidence())
                .extracting(EvidenceResult::queryId)
                .containsExactly("EV-TOPOLOGY");
        assertThat(advanced.triggeredSignals()).contains("failed_probe_present");
    }

    @Test
    @DisplayName("全覆盖且无失败节点是「排除」，同样是结论，也要推进")
    void aFullyHealthyProbeExcludesTheCandidateRatherThanStalling() {
        awaiting("diag-healthy", 2);
        when(library.analyze(7L, "topology-1")).thenReturn(healthy());

        service.run(7L, "diag-healthy", "topology-1", "alice");

        Diagnosis advanced = captureAdvanced("diag-healthy");
        assertThat(advanced.conclusionType()).isEqualTo(ConclusionType.EXCLUDED);
        assertThat(advanced.status()).isEqualTo(DiagnosisStatus.READY_FOR_HUMAN);
    }

    /**
     * The failure mode worth its own test. "No failures among the nodes we
     * reached" must not be filed as "no failures", or the console would report
     * the network ruled out on the strength of nodes nobody looked at.
     */
    @Test
    @DisplayName("覆盖不完整且未见失败，不得当作「已排除」")
    void partialCoverageWithoutFailuresIsNotAClearBillOfHealth() {
        awaiting("diag-partial", 2);
        when(library.analyze(7L, "topology-1")).thenReturn(partial());

        service.run(7L, "diag-partial", "topology-1", "alice");

        Diagnosis advanced = captureAdvanced("diag-partial");
        assertThat(advanced.conclusionType())
                .as("覆盖不完整不能反证候选根因")
                .isEqualTo(ConclusionType.INSUFFICIENT_EVIDENCE);
        assertThat(advanced.status()).isEqualTo(DiagnosisStatus.NEEDS_INVESTIGATION);
        assertThat(advanced.abstained()).isTrue();
    }

    @Test
    @DisplayName("一个拨测节点都没观测到时保持弃权，不产出结论")
    void anUnobservedProbeLeavesTheInvestigationWhereItWas() {
        awaiting("diag-blind", 2);
        when(library.analyze(7L, "topology-1")).thenReturn(unobserved());

        TopologyProbeEvidenceRun run = service.run(7L, "diag-blind", "topology-1", "alice");

        assertThat(run.conclusionUpdated()).isTrue();
        Diagnosis advanced = captureAdvanced("diag-blind");
        assertThat(advanced.conclusionType()).isEqualTo(ConclusionType.INSUFFICIENT_EVIDENCE);
        assertThat(advanced.status()).isEqualTo(DiagnosisStatus.NEEDS_INVESTIGATION);
    }

    /**
     * A repeat probe on an investigation a human has already read is a
     * look-again, not a re-decision. Silently rewriting a conclusion someone may
     * have acted on is worse than declining to.
     */
    @Test
    @DisplayName("已进入人工环节后重跑只记录运行，不改写结论，并如实说明")
    void aRepeatProbeAfterAHumanHasSeenItRecordsTheRunWithoutRewritingTheConclusion() {
        List<EvidenceResult> firstPass = List.of(
                TopologyProbeEvidence.from(probeRequest(), "topology-1", failing()));
        Diagnosis ready = stateMachine.recordScenarioEvidence(
                awaitingDiagnosis(),
                topologyPlaybook(),
                firstPass,
                PlaybookEvidenceAssessment.assess(
                        topologyPlaybook(), firstPass, criteria, rules, false),
                "alice");
        when(persistence.get(7L, "diag-ready"))
                .thenReturn(new StoredDiagnosis(ready, 5, false));
        when(scenarioPolicy.probePlaybook(eq(7L), any(Diagnosis.class)))
                .thenReturn(Optional.of(approvedVersion()));
        when(scenarioPolicy.requiredProbeRequest(any())).thenReturn(Optional.of(probeRequest()));
        when(library.analyze(7L, "topology-1")).thenReturn(healthy());

        TopologyProbeEvidenceRun run = service.run(7L, "diag-ready", "topology-1", "bob");

        assertThat(run.conclusionUpdated())
                .as("重跑不改写人已看过的结论，且这件事写在运行记录上")
                .isFalse();
        ArgumentCaptor<Diagnosis> captor = ArgumentCaptor.forClass(Diagnosis.class);
        verify(runPersistence).insertIfDiagnosisOpen(
                eq(7L), eq("diag-ready"),
                any(TroubleshootingTopologyProbeRunEntity.class),
                captor.capture(), anyInt());
        assertThat(captor.getValue()).isNull();
    }

    @Test
    void refusesToAppendNewEvidenceToAClosedDiagnosis() {
        Diagnosis diagnosis = mock(Diagnosis.class);
        when(diagnosis.status()).thenReturn(DiagnosisStatus.CLOSED);
        when(persistence.get(7L, "diag-closed"))
                .thenReturn(new StoredDiagnosis(diagnosis, 3, false));

        assertThatThrownBy(() -> service.run(
                7L, "diag-closed", "topology-1", "alice"))
                .isInstanceOf(MateClawException.class)
                .satisfies(error -> assertThat(((MateClawException) error).getCode())
                        .isEqualTo(409))
                .hasMessageContaining("closed");

        verify(library, never()).analyze(7L, "topology-1");
        verifyNothingPersisted();
    }

    @Test
    void doesNotPersistWhenDiagnosisClosesDuringEvidenceCollection() {
        awaiting("diag-racing-close", 3);
        when(library.analyze(7L, "topology-1")).thenReturn(failing());
        org.mockito.Mockito.doThrow(new MateClawException(
                        "err.troubleshooting.topology_probe_diagnosis_closed",
                        409,
                        "diagnosis was closed while topology evidence was being collected"))
                .when(runPersistence).insertIfDiagnosisOpen(
                        eq(7L), eq("diag-racing-close"),
                        any(TroubleshootingTopologyProbeRunEntity.class),
                        any(), anyInt());

        assertThatThrownBy(() -> service.run(
                7L, "diag-racing-close", "topology-1", "alice"))
                .isInstanceOf(MateClawException.class)
                .satisfies(error -> assertThat(((MateClawException) error).getCode())
                        .isEqualTo(409))
                .hasMessageContaining("closed");

        verify(library).analyze(7L, "topology-1");
    }

    @Test
    void refusesToRunWhenDiagnosisDidNotMatchTheTopologyScenarioPlaybook() {
        Diagnosis diagnosis = mock(Diagnosis.class);
        when(diagnosis.status()).thenReturn(DiagnosisStatus.NEEDS_INVESTIGATION);
        when(scenarioPolicy.probePlaybook(7L, diagnosis)).thenReturn(Optional.empty());
        when(persistence.get(7L, "diag-error-code"))
                .thenReturn(new StoredDiagnosis(diagnosis, 2, false));

        assertThatThrownBy(() -> service.run(
                7L, "diag-error-code", "topology-1", "alice"))
                .isInstanceOf(MateClawException.class)
                .satisfies(error -> assertThat(((MateClawException) error).getCode())
                        .isEqualTo(409))
                .hasMessageContaining("did not match");

        verify(library, never()).analyze(7L, "topology-1");
        verifyNothingPersisted();
    }

    @Test
    void listsOnlyTheRequestedDiagnosisHistoryNewestFirst() throws Exception {
        TopologyProbeEvidenceRun stored = new TopologyProbeEvidenceRun(
                "topology-run-1",
                "diag-1",
                "topology-1",
                "deployment_topology_probe",
                "topology_synthetic_probe",
                failing(),
                NOW.minusSeconds(5),
                NOW,
                "alice",
                true);
        TroubleshootingTopologyProbeRunEntity entity = new TroubleshootingTopologyProbeRunEntity();
        entity.setWorkspaceId(7L);
        entity.setRunId("topology-run-1");
        entity.setDiagnosisId("diag-1");
        entity.setTopologyId("topology-1");
        entity.setResultJson(objectMapper.writeValueAsString(stored));
        when(persistence.get(7L, "diag-1"))
                .thenReturn(new StoredDiagnosis(mock(Diagnosis.class), 1, false));
        when(mapper.listByDiagnosis(7L, "diag-1", 50)).thenReturn(List.of(entity));

        assertThat(service.list(7L, "diag-1", 50))
                .extracting(TopologyProbeEvidenceRun::runId)
                .containsExactly("topology-run-1");

        verify(mapper).listByDiagnosis(7L, "diag-1", 50);
        verify(mapper, never()).listByDiagnosis(8L, "diag-1", 50);
    }

    private void awaiting(String diagnosisId, int version) {
        when(persistence.get(7L, diagnosisId))
                .thenReturn(new StoredDiagnosis(awaitingDiagnosis(), version, false));
        when(scenarioPolicy.probePlaybook(eq(7L), any(Diagnosis.class)))
                .thenReturn(Optional.of(approvedVersion()));
        when(scenarioPolicy.requiredProbeRequest(any())).thenReturn(Optional.of(probeRequest()));
    }

    private TroubleshootingTopologyProbeRunEntity captureRun(String diagnosisId) {
        ArgumentCaptor<TroubleshootingTopologyProbeRunEntity> captor =
                ArgumentCaptor.forClass(TroubleshootingTopologyProbeRunEntity.class);
        verify(runPersistence).insertIfDiagnosisOpen(
                eq(7L), eq(diagnosisId), captor.capture(), any(), anyInt());
        return captor.getValue();
    }

    private Diagnosis captureAdvanced(String diagnosisId) {
        ArgumentCaptor<Diagnosis> captor = ArgumentCaptor.forClass(Diagnosis.class);
        verify(runPersistence).insertIfDiagnosisOpen(
                eq(7L), eq(diagnosisId),
                any(TroubleshootingTopologyProbeRunEntity.class),
                captor.capture(), anyInt());
        assertThat(captor.getValue()).as("拨测结果必须写回 Diagnosis").isNotNull();
        return captor.getValue();
    }

    private void verifyNothingPersisted() {
        verify(runPersistence, never()).insertIfDiagnosisOpen(
                anyLong(), anyString(),
                any(TroubleshootingTopologyProbeRunEntity.class), any(), anyInt());
    }

    private Diagnosis awaitingDiagnosis() {
        return stateMachine.initializeScenarioAwaitingEvidence(new ScenarioDiagnosisDraft(
                "diag-topology", "case-topology", "run-topology",
                incident(), DeploymentTopologyScenarioPolicy.SCENARIO_KEY,
                topologyPlaybook(),
                new PlaybookVersionRef("playbook-topology", 1),
                "operator",
                NorthStarTimings.concluded(
                        NOW.minusSeconds(120), NOW.minusSeconds(110), NOW.minusSeconds(110)),
                false, false,
                List.of("取证尚未执行")));
    }

    private IncidentContext incident() {
        return new IncidentContext(
                "inc-topology", "CSDP", "network-path", null,
                "部署链路不通", "P1", "待确认", null,
                NOW.minusSeconds(120), null,
                "web:scenario", IncidentCompleteness.SYMPTOM, "部署链路不通");
    }

    private static EvidenceRequest probeRequest() {
        return new EvidenceRequest(
                "EV-TOPOLOGY", "synthetic_probe", "执行服务端授权的部署拓扑拨测",
                Map.of(
                        "assetType", DeploymentTopologyScenarioPolicy.ASSET_TYPE,
                        "toolKey", DeploymentTopologyScenarioPolicy.TOOL_KEY),
                "-15m", true);
    }

    private static SopEntry topologyPlaybook() {
        return new SopEntry(
                "playbook-topology",
                SopEntry.CURRENT_CONTRACT_VERSION,
                "CSDP", "scenario:" + DeploymentTopologyScenarioPolicy.SCENARIO_KEY,
                "network-path",
                "部署拓扑拨测分析", "部署网络路径存在失败拨测节点", "network", "网络平台组",
                "approved", true,
                List.of(probeRequest()),
                List.of(new AnomalyCriterion(
                        "failed_probe_present", "EV-TOPOLOGY", "至少存在一个失败拨测节点",
                        new Criterion.NumericGte(
                                TopologyProbeEvidence.FAILED_PROBE_COUNT, 1))),
                List.of(new DiagnosisRule(
                        "RULE-TOPOLOGY-FAILURE",
                        List.of("failed_probe_present"),
                        "部署拓扑存在失败拨测节点",
                        "沿失败节点的相邻拓扑链路继续核查；相邻不等于已证明根因。",
                        Confidence.MEDIUM, false)),
                List.of());
    }

    private static ApprovedPlaybookVersion approvedVersion() {
        return new ApprovedPlaybookVersion(
                "playbook-topology", 1, SELECTOR, "approved",
                "MANUAL_WRITE", "record-topology", null, null,
                "reviewer", "内网核实通过", null,
                null, null, null,
                topologyPlaybook(), NOW.minusSeconds(600), NOW.minusSeconds(600));
    }

    private DeploymentTopologySopResult result(
            DeploymentTopologySopResult.AnalysisStatus status,
            DeploymentTopologySopResult.Summary summary) {
        return new DeploymentTopologySopResult(
                "1.0",
                "csp-deployment",
                "CSP 部署架构",
                NOW.minusSeconds(60),
                "synthetic_probe",
                status,
                summary,
                List.of(new DeploymentTopologySopResult.NodeObservation(
                        "entry", "公网入口", "client", "https://example.test",
                        "首页拨测", "-5m",
                        DeploymentTopologySopResult.ObservationStatus.FAILED,
                        503, "https://example.test", "首页拨测",
                        "guance:synthetic_probe", "拨测异常", NOW)),
                List.of(new DeploymentTopologySopResult.SuspectLink(
                        "entry", "gateway", "失败节点相邻链路，仅供核查")),
                List.of("gateway"),
                List.of("未覆盖节点不声称健康"),
                NOW,
                false,
                true);
    }

    private DeploymentTopologySopResult failing() {
        return result(
                DeploymentTopologySopResult.AnalysisStatus.NETWORK_PROBLEM_DETECTED,
                new DeploymentTopologySopResult.Summary(2, 1, 1, 1, 0, 1, 0));
    }

    private DeploymentTopologySopResult healthy() {
        return result(
                DeploymentTopologySopResult.AnalysisStatus.NO_PROBLEM_OBSERVED,
                new DeploymentTopologySopResult.Summary(2, 1, 2, 2, 2, 0, 0));
    }

    private DeploymentTopologySopResult partial() {
        return result(
                DeploymentTopologySopResult.AnalysisStatus.PARTIAL_OBSERVATION,
                new DeploymentTopologySopResult.Summary(3, 2, 2, 1, 1, 0, 1));
    }

    private DeploymentTopologySopResult unobserved() {
        return result(
                DeploymentTopologySopResult.AnalysisStatus.INSUFFICIENT_EVIDENCE,
                new DeploymentTopologySopResult.Summary(2, 1, 1, 0, 0, 0, 1));
    }
}
