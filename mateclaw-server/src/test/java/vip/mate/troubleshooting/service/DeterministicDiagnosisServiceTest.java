package vip.mate.troubleshooting.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.engine.Criterion;
import vip.mate.troubleshooting.engine.CriterionEvaluator;
import vip.mate.troubleshooting.engine.DiagnosisRuleEvaluator;
import vip.mate.troubleshooting.evidence.EvidenceSpinePlan;
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
import vip.mate.troubleshooting.model.PlaybookVersionRef;
import vip.mate.troubleshooting.model.RecommendedAction;
import vip.mate.troubleshooting.model.SopEntry;
import vip.mate.troubleshooting.statemachine.DiagnosisStateMachine;
import vip.mate.troubleshooting.synthesis.ApprovedPlaybookVersion;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeterministicDiagnosisServiceTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-07-25T01:04:59Z");
    private static final Instant READY_AT = Instant.parse("2026-07-25T01:04:59.250Z");
    private static final Instant CONCLUSION_AT = Instant.parse("2026-07-25T01:05:00Z");
    private static final PlaybookVersionRef SOURCE_PLAYBOOK =
            new PlaybookVersionRef("sop-903001", 3);

    @Mock private TroubleshootingPersistenceService persistence;
    @Mock private TroubleshootingPlaybookVersionService playbookVersions;
    @Mock private FormalDiagnosisClaimService formalClaims;

    private DeterministicDiagnosisService service;

    @BeforeEach
    void setUp() {
        service = new DeterministicDiagnosisService(
                new CriterionEvaluator(),
                new DiagnosisRuleEvaluator(),
                new DiagnosisStateMachine(
                        Clock.fixed(CONCLUSION_AT, ZoneOffset.UTC),
                        prefix -> prefix + "-1"),
                persistence,
                playbookVersions,
                formalClaims,
                Clock.fixed(CONCLUSION_AT, ZoneOffset.UTC));
    }

    @Test
    void hitPathEvaluatesRulesInitializesStateAndPersistsWithoutLlm() {
        when(persistence.createOrGet(eq(7L), any(Diagnosis.class), eq(RECEIVED_AT)))
                .thenAnswer(invocation -> new StoredDiagnosis(invocation.getArgument(1), 0, true));
        when(playbookVersions.lockActiveApprovedByPlaybookId(7L, "sop-903001"))
                .thenReturn(Optional.of(approvedVersion()));

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
        assertEquals(Diagnosis.CURRENT_CONTRACT_VERSION, diagnosis.contractVersion());
        assertEquals("DBA 值班", diagnosis.sourcePlaybookOwner());
        assertEquals(
                new PlaybookVersionRef("sop-903001", 3),
                diagnosis.sourcePlaybookVersionRef());
        assertEquals(
                NorthStarTimings.concluded(RECEIVED_AT, READY_AT, CONCLUSION_AT),
                diagnosis.timings());
        assertFalse(diagnosis.writeExecutionEnabled());
        Diagnosis confirmed = new DiagnosisStateMachine(
                Clock.fixed(Instant.parse("2026-07-25T01:06:00Z"), ZoneOffset.UTC),
                prefix -> prefix + "-2")
                .confirm(diagnosis, "on-call");
        assertEquals(DiagnosisStatus.CONFIRMED, confirmed.status());
        assertEquals(
                diagnosis.sourcePlaybookVersionRef(),
                confirmed.sourcePlaybookVersionRef());
        verify(persistence).createOrGet(7L, diagnosis, RECEIVED_AT);
    }

    @Test
    void formalHitPersistsTheAdmissionPilotAndExactPlaybookIdentity() {
        FormalDiagnosisAdmission admission = formalAdmission();
        FormalDiagnosisClaim claim = formalClaim();
        when(playbookVersions.lockActiveApprovedByPlaybookId(7L, "sop-903001"))
                .thenReturn(Optional.of(approvedVersion()));
        when(persistence.createOrGet(
                eq(7L), any(Diagnosis.class), eq(RECEIVED_AT), eq(9), eq(claim)))
                .thenAnswer(invocation -> new StoredDiagnosis(
                        invocation.getArgument(1), 0, true, 9));

        StoredDiagnosis stored = service.diagnoseAndPersist(
                7L,
                incident(),
                admission,
                List.of(evidence(EvidenceStatus.ANOMALY, Map.of("reachable", false))),
                false,
                RECEIVED_AT,
                READY_AT,
                claim,
                CONCLUSION_AT);

        assertEquals(SOURCE_PLAYBOOK, stored.diagnosis().sourcePlaybookVersionRef());
        assertEquals(9, stored.pilotPlanVersion());
        var order = inOrder(persistence, formalClaims);
        order.verify(formalClaims).lockForCommit(7L, claim);
        order.verify(persistence).createOrGet(
                7L, stored.diagnosis(), RECEIVED_AT, 9, claim);
        order.verify(formalClaims).complete(
                7L, claim, stored.diagnosis().diagnosisId(), CONCLUSION_AT);
    }

    @Test
    void acquiredDirectFormalClaimCannotCompleteAgainstAnExistingDiagnosis() {
        FormalDiagnosisAdmission admission = formalAdmission();
        FormalDiagnosisClaim claim = formalClaim();
        when(playbookVersions.lockActiveApprovedByPlaybookId(7L, "sop-903001"))
                .thenReturn(Optional.of(approvedVersion()));
        when(persistence.createOrGet(
                eq(7L), any(Diagnosis.class), eq(RECEIVED_AT), eq(9), eq(claim)))
                .thenAnswer(invocation -> new StoredDiagnosis(
                        invocation.getArgument(1), 1, false, 9));

        MateClawException error = assertThrows(
                MateClawException.class,
                () -> service.diagnoseAndPersist(
                        7L, incident(), admission, List.of(), false,
                        RECEIVED_AT, READY_AT, claim, CONCLUSION_AT));

        assertEquals(409, error.getCode());
        verify(formalClaims, never()).complete(anyLong(), any(), any(), any());
    }

    @Test
    void formalHitRechecksTheActivePlaybookInsideTheInsertTransaction() {
        FormalDiagnosisAdmission admission = formalAdmission();
        ApprovedPlaybookVersion replacement = new ApprovedPlaybookVersion(
                "sop-903001", 4, "csdp:903001", "APPROVED",
                "MANUAL", "manual-903001", "review-903001", 3,
                "reviewer", "replacement", null,
                sop(true, "approved"), RECEIVED_AT, RECEIVED_AT);
        when(playbookVersions.lockActiveApprovedByPlaybookId(7L, "sop-903001"))
                .thenReturn(Optional.of(replacement));

        assertThrows(
                MateClawException.class,
                () -> service.diagnoseAndPersist(
                        7L, incident(), admission, List.of(), true,
                        RECEIVED_AT, READY_AT, formalClaim(), CONCLUSION_AT));

        verify(persistence, never()).createOrGet(
                eq(7L), any(Diagnosis.class), eq(RECEIVED_AT), eq(9), any());
    }

    @Test
    void formalHitHoldsThePlaybookLockDiagnosisInsertAndClaimCompletionInOneTransaction()
            throws NoSuchMethodException {
        var method = DeterministicDiagnosisService.class.getDeclaredMethod(
                "diagnoseAndPersist",
                long.class,
                IncidentContext.class,
                FormalDiagnosisAdmission.class,
                List.class,
                boolean.class,
                Instant.class,
                Instant.class,
                FormalDiagnosisClaim.class,
                Instant.class);

        assertNotNull(
                method.getAnnotation(Transactional.class),
                "the formal Playbook lock, Diagnosis insert and claim completion "
                        + "must share one transaction");
    }

    @Test
    void formalIntakePersistsAndCompletesItsClaimInOrder() {
        FormalDiagnosisAdmission admission = formalAdmission();
        FormalDiagnosisClaim claim = formalIntakeClaim("intake-7");
        when(playbookVersions.lockActiveApprovedByPlaybookId(7L, "sop-903001"))
                .thenReturn(Optional.of(approvedVersion()));
        when(persistence.createOrGetForIntake(
                eq(7L), any(Diagnosis.class), eq("intake-7"), eq(9), eq(claim)))
                .thenAnswer(invocation -> new StoredDiagnosis(
                        invocation.getArgument(1), 0, true, 9));

        StoredDiagnosis stored = service.diagnoseAndPersistForIntake(
                7L,
                incident(),
                admission,
                List.of(evidence(EvidenceStatus.ANOMALY, Map.of("reachable", false))),
                false,
                RECEIVED_AT,
                READY_AT,
                "intake-7",
                claim,
                CONCLUSION_AT);

        var order = inOrder(persistence, formalClaims);
        order.verify(formalClaims).lockForCommit(7L, claim);
        order.verify(persistence).createOrGetForIntake(
                7L, stored.diagnosis(), "intake-7", 9, claim);
        order.verify(formalClaims).complete(
                7L, claim, stored.diagnosis().diagnosisId(), CONCLUSION_AT);
    }

    @Test
    void rehearsalIntakePersistsAndCompletesTheSharedSessionClaimInOneTransaction()
            throws NoSuchMethodException {
        FormalDiagnosisClaim claim = formalIntakeClaim("intake-7");
        when(playbookVersions.lockActiveApprovedByPlaybookId(7L, "sop-903001"))
                .thenReturn(Optional.of(approvedVersion()));
        when(persistence.createOrGetForIntake(
                eq(7L), any(Diagnosis.class), eq("intake-7"), eq(claim)))
                .thenAnswer(invocation -> new StoredDiagnosis(
                        invocation.getArgument(1), 0, true, null));

        StoredDiagnosis stored = service.diagnoseAndPersistForIntake(
                7L, incident(), sop(true, "approved"), List.of(), true, true,
                RECEIVED_AT, READY_AT, "intake-7", claim, CONCLUSION_AT);

        var order = inOrder(persistence, formalClaims);
        order.verify(formalClaims).lockForCommit(7L, claim);
        order.verify(persistence).createOrGetForIntake(
                7L, stored.diagnosis(), "intake-7", claim);
        order.verify(formalClaims).complete(
                7L, claim, stored.diagnosis().diagnosisId(), CONCLUSION_AT);
        var method = DeterministicDiagnosisService.class.getDeclaredMethod(
                "diagnoseAndPersistForIntake",
                long.class, IncidentContext.class, SopEntry.class, List.class,
                boolean.class, boolean.class, Instant.class, Instant.class,
                String.class, FormalDiagnosisClaim.class, Instant.class);
        assertNotNull(method.getAnnotation(Transactional.class));
    }

    @Test
    void acquiredRehearsalIntakeClaimCannotCompleteAgainstAnExistingDiagnosis() {
        FormalDiagnosisClaim claim = formalIntakeClaim("intake-7");
        when(playbookVersions.lockActiveApprovedByPlaybookId(7L, "sop-903001"))
                .thenReturn(Optional.of(approvedVersion()));
        when(persistence.createOrGetForIntake(
                eq(7L), any(Diagnosis.class), eq("intake-7"), eq(claim)))
                .thenAnswer(invocation -> new StoredDiagnosis(
                        invocation.getArgument(1), 1, false, null));

        MateClawException error = assertThrows(
                MateClawException.class,
                () -> service.diagnoseAndPersistForIntake(
                        7L, incident(), sop(true, "approved"), List.of(), true, true,
                        RECEIVED_AT, READY_AT, "intake-7", claim, CONCLUSION_AT));

        assertEquals(409, error.getCode());
        verify(formalClaims, never()).complete(anyLong(), any(), any(), any());
    }

    @Test
    void formalIntakeInsertAndClaimCompletionShareOneTransaction()
            throws NoSuchMethodException {
        var method = DeterministicDiagnosisService.class.getDeclaredMethod(
                "diagnoseAndPersistForIntake",
                long.class,
                IncidentContext.class,
                FormalDiagnosisAdmission.class,
                List.class,
                boolean.class,
                Instant.class,
                Instant.class,
                String.class,
                FormalDiagnosisClaim.class,
                Instant.class);

        assertNotNull(method.getAnnotation(Transactional.class));
    }

    @Test
    void rehearsalRowWinningTheIntakeUniqueKeyCannotCompleteAFormalClaim() {
        FormalDiagnosisAdmission admission = formalAdmission();
        FormalDiagnosisClaim claim = formalIntakeClaim("intake-7");
        Diagnosis rehearsal = org.mockito.Mockito.mock(Diagnosis.class);
        when(playbookVersions.lockActiveApprovedByPlaybookId(7L, "sop-903001"))
                .thenReturn(Optional.of(approvedVersion()));
        when(persistence.createOrGetForIntake(
                eq(7L), any(Diagnosis.class), eq("intake-7"), eq(9), eq(claim)))
                .thenReturn(new StoredDiagnosis(rehearsal, 1, false, null));

        MateClawException error = assertThrows(
                MateClawException.class,
                () -> service.diagnoseAndPersistForIntake(
                        7L, incident(), admission, List.of(), false,
                        RECEIVED_AT, READY_AT, "intake-7", claim, CONCLUSION_AT));

        assertEquals(409, error.getCode());
        verify(formalClaims, never()).complete(anyLong(), any(), any(), any());
    }

    @Test
    void persistedHitPathFailsClosedWithoutAnImmutablePlaybookVersion() {
        MateClawException error = assertThrows(
                MateClawException.class,
                () -> service.diagnoseAndPersist(
                        7L,
                        incident(),
                        sop(true, "approved"),
                        List.of(evidence(
                                EvidenceStatus.ANOMALY,
                                Map.of("reachable", false))),
                        false,
                        true,
                        RECEIVED_AT,
                        READY_AT));

        assertEquals(409, error.getCode());
        verify(persistence, never()).createOrGet(
                eq(7L), any(Diagnosis.class), eq(RECEIVED_AT));
    }

    @Test
    void requiredEvidenceFailureAbstainsAndSuppressesAllActions() {
        Diagnosis diagnosis = service.diagnose(
                incident(),
                sop(true, "approved"),
                SOURCE_PLAYBOOK,
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
                SOURCE_PLAYBOOK,
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
                SOURCE_PLAYBOOK,
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

        Diagnosis first = service.diagnose(
                incident(), sop(true, "approved"), SOURCE_PLAYBOOK,
                evidence, true, true);
        Diagnosis second = service.diagnose(
                incident(), sop(true, "approved"), SOURCE_PLAYBOOK,
                evidence, true, true);

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

    private ApprovedPlaybookVersion approvedVersion() {
        return new ApprovedPlaybookVersion(
                "sop-903001",
                3,
                "csdp:903001",
                "APPROVED",
                "MANUAL",
                "manual-903001",
                "review-903001",
                2,
                "reviewer",
                "fixed replay passed",
                null,
                sop(true, "approved"),
                RECEIVED_AT,
                RECEIVED_AT);
    }

    private FormalDiagnosisAdmission formalAdmission() {
        return new FormalDiagnosisAdmission(
                9,
                SOURCE_PLAYBOOK,
                sop(true, "approved"),
                approvedVersion().knowledgeEvidenceGrade(),
                new EvidenceSpinePlan(
                        "FORMAL-SEARCH", "FORMAL-TRACE", "FORMAL-CONTRAST",
                        "message_send_failed", "-15m"),
                "t7-012345678901234567890123",
                "a".repeat(64));
    }

    private FormalDiagnosisClaim formalClaim() {
        return new FormalDiagnosisClaim(
                "a".repeat(64),
                "claim-formal-1",
                RECEIVED_AT,
                RECEIVED_AT.plusSeconds(300));
    }

    private FormalDiagnosisClaim formalIntakeClaim(String intakeSessionId) {
        return new FormalDiagnosisClaim(
                FormalDiagnosisClaimKey.forIntake(7L, intakeSessionId),
                "claim-formal-intake-1",
                RECEIVED_AT,
                RECEIVED_AT.plusSeconds(300));
    }
}
