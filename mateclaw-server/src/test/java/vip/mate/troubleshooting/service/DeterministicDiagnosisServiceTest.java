package vip.mate.troubleshooting.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.troubleshooting.engine.Criterion;
import vip.mate.troubleshooting.engine.CriterionEvaluator;
import vip.mate.troubleshooting.model.ActionType;
import vip.mate.troubleshooting.model.AnomalyCriterion;
import vip.mate.troubleshooting.model.ApprovalStatus;
import vip.mate.troubleshooting.model.Confidence;
import vip.mate.troubleshooting.model.ConclusionType;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.DiagnosisRule;
import vip.mate.troubleshooting.model.DiagnosisStatus;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.ExecutionStatus;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.model.NorthStarTimings;
import vip.mate.troubleshooting.model.RecommendedAction;
import vip.mate.troubleshooting.model.SopEntry;
import vip.mate.troubleshooting.statemachine.DiagnosisStateMachine;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeterministicDiagnosisServiceTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-07-25T01:04:59Z");
    private static final Instant READY_AT = Instant.parse("2026-07-25T01:04:59.250Z");
    private static final Instant CONCLUSION_AT = Instant.parse("2026-07-25T01:05:00Z");

    @Mock private TroubleshootingPersistenceService persistence;

    private DeterministicDiagnosisService service;

    @BeforeEach
    void setUp() {
        service = new DeterministicDiagnosisService(
                new CriterionEvaluator(),
                new DiagnosisStateMachine(
                        Clock.fixed(CONCLUSION_AT, ZoneOffset.UTC),
                        prefix -> prefix + "-1"),
                persistence,
                Clock.fixed(CONCLUSION_AT, ZoneOffset.UTC));
    }

    @Test
    void hitPathEvaluatesRulesInitializesStateAndPersistsWithoutLlm() {
        when(persistence.createOrGet(eq(7L), any(Diagnosis.class), eq(RECEIVED_AT)))
                .thenAnswer(invocation -> new StoredDiagnosis(invocation.getArgument(1), 0, true));

        StoredDiagnosis stored = service.diagnoseAndPersist(
                7L,
                incident(),
                sop(true, "approved"),
                List.of(evidence(EvidenceStatus.ANOMALY, Map.of("reachable", false))),
                false,
                true,
                RECEIVED_AT,
                READY_AT);

        Diagnosis diagnosis = stored.diagnosis();
        assertEquals(DiagnosisStatus.READY_FOR_HUMAN, diagnosis.status());
        assertEquals("MongoDB 不可达", diagnosis.rootCause());
        assertEquals(List.of("mongo_unreachable"), diagnosis.triggeredSignals());
        assertEquals(2, diagnosis.recommendedActions().size());
        assertEquals(1, diagnosis.pendingWrites().size());
        assertEquals(ApprovalStatus.PENDING,
                diagnosis.pendingWrites().getFirst().approvalStatus());
        assertEquals(ExecutionStatus.BLOCKED,
                diagnosis.pendingWrites().getFirst().executionStatus());
        assertEquals(3, diagnosis.timeline().size());
        assertEquals(ConclusionType.LOCATED, diagnosis.conclusionType());
        assertEquals(
                NorthStarTimings.concluded(RECEIVED_AT, READY_AT, CONCLUSION_AT),
                diagnosis.timings());
        assertFalse(diagnosis.writeExecutionEnabled());
        Diagnosis confirmed = new DiagnosisStateMachine(
                Clock.fixed(Instant.parse("2026-07-25T01:06:00Z"), ZoneOffset.UTC),
                prefix -> prefix + "-2")
                .confirm(diagnosis, "on-call");
        assertEquals(DiagnosisStatus.CONFIRMED, confirmed.status());
        verify(persistence).createOrGet(7L, diagnosis, RECEIVED_AT);
    }

    @Test
    void requiredEvidenceFailureAbstainsAndSuppressesAllActions() {
        Diagnosis diagnosis = service.diagnose(
                incident(),
                sop(true, "approved"),
                List.of(evidence(EvidenceStatus.MISSING, Map.of())),
                false,
                false);

        assertEquals(DiagnosisStatus.NEEDS_INVESTIGATION, diagnosis.status());
        assertTrue(diagnosis.abstained());
        assertTrue(diagnosis.recommendedActions().isEmpty());
        assertEquals(ConclusionType.INSUFFICIENT_EVIDENCE, diagnosis.conclusionType());
        assertTrue(diagnosis.warnings().getFirst().contains("mongo-reachability"));
    }

    @Test
    void completeEvidenceThatDefinitivelyRefutesEveryRuleProducesExcludedConclusion() {
        Diagnosis diagnosis = service.diagnose(
                incident(),
                sop(true, "approved"),
                List.of(evidence(EvidenceStatus.NORMAL, Map.of("reachable", true))),
                false,
                false);

        assertEquals(DiagnosisStatus.READY_FOR_HUMAN, diagnosis.status());
        assertFalse(diagnosis.abstained());
        assertEquals(ConclusionType.EXCLUDED, diagnosis.conclusionType());
        assertEquals(Confidence.MEDIUM, diagnosis.confidence());
        assertTrue(diagnosis.recommendedActions().isEmpty());
    }

    @Test
    void presentEvidenceWithoutTheCriterionFieldCannotProduceExcludedConclusion() {
        Diagnosis diagnosis = service.diagnose(
                incident(),
                sop(true, "approved"),
                List.of(evidence(EvidenceStatus.NORMAL, Map.of("unrelated", true))),
                false,
                false);

        assertEquals(DiagnosisStatus.NEEDS_INVESTIGATION, diagnosis.status());
        assertTrue(diagnosis.abstained());
        assertEquals(ConclusionType.INSUFFICIENT_EVIDENCE, diagnosis.conclusionType());
        assertEquals(Confidence.LOW, diagnosis.confidence());
    }

    @Test
    void repeatedRehearsalsReceiveDistinctPersistenceIdentities() {
        List<EvidenceResult> evidence = List.of(
                evidence(EvidenceStatus.ANOMALY, Map.of("reachable", false)));

        Diagnosis first = service.diagnose(incident(), sop(true, "approved"), evidence, true, true);
        Diagnosis second = service.diagnose(incident(), sop(true, "approved"), evidence, true, true);

        assertNotEquals(first.diagnosisId(), second.diagnosisId());
        assertNotEquals(first.caseId(), second.caseId());
        assertNotEquals(first.runId(), second.runId());
    }

    private IncidentContext incident() {
        return new IncidentContext(
                "inc-903001",
                "CSDP",
                "csdp-wechat",
                "903001",
                "数据库访问异常",
                "P0",
                "所有客户",
                "trace-903001",
                Instant.parse("2026-07-25T01:02:00Z"),
                null,
                "fixture",
                IncidentCompleteness.STRUCTURED,
                null);
    }

    private SopEntry sop(boolean verified, String status) {
        return new SopEntry(
                "sop-903001",
                SopEntry.CURRENT_CONTRACT_VERSION,
                "CSDP",
                "903001",
                "csdp-wechat",
                "MongoDB 连接异常",
                "MongoDB 不可达",
                "database",
                "DBA 值班",
                status,
                verified,
                List.of(new EvidenceRequest(
                        "mongo-reachability",
                        "metric",
                        "检查 MongoDB 可达性",
                        Map.of("service", "mongodb"),
                        "5m",
                        true)),
                List.of(new AnomalyCriterion(
                        "mongo_unreachable",
                        "mongo-reachability",
                        "MongoDB 不可达",
                        new Criterion.BooleanEquals("reachable", false))),
                List.of(new DiagnosisRule(
                        "mongo-unreachable",
                        List.of("mongo_unreachable"),
                        "MongoDB 不可达",
                        "可达性检查失败",
                        Confidence.HIGH,
                        false)),
                List.of(
                        new RecommendedAction(
                                "contact-dba",
                                ActionType.HUMAN_CONTACT,
                                "联系 DBA",
                                "携带取证结果",
                                false,
                                ApprovalStatus.NOT_REQUIRED,
                                ExecutionStatus.PENDING),
                        RecommendedAction.manualWrite(
                                "restart-mongodb",
                                "重启 MongoDB",
                                "仅允许外部人工执行")));
    }

    private EvidenceResult evidence(EvidenceStatus status, Map<String, Object> observed) {
        return new EvidenceResult(
                "mongo-reachability",
                "fixture",
                "fixture://mongo/reachability",
                status,
                "reachability",
                observed,
                "fixture",
                Instant.parse("2026-07-25T01:03:00Z"));
    }
}
