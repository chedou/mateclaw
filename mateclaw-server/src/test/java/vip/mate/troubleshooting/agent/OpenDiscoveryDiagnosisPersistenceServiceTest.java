package vip.mate.troubleshooting.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.model.Confidence;
import vip.mate.troubleshooting.model.BoundedInvestigationDraft;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.DiagnosisStatus;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.model.NorthStarTimings;
import vip.mate.troubleshooting.service.StoredDiagnosis;
import vip.mate.troubleshooting.service.FormalDiagnosisClaimService;
import vip.mate.troubleshooting.service.FormalDiagnosisClaim;
import vip.mate.troubleshooting.service.FormalDiagnosisClaimKey;
import vip.mate.troubleshooting.service.FormalOpenDiscoveryAdmission;
import vip.mate.troubleshooting.service.FormalOpenDiscoveryPlan;
import vip.mate.troubleshooting.service.IncidentDeduplicationKey;
import vip.mate.troubleshooting.service.TroubleshootingPersistenceService;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenDiscoveryDiagnosisPersistenceServiceTest {

    private static final long WORKSPACE_ID = 7L;
    private static final Instant NOW = Instant.parse("2026-08-11T10:00:00Z");

    @Mock
    private TroubleshootingPersistenceService diagnoses;
    @Mock
    private OpenDiscoveryRunAuditService runAudits;
    @Mock
    private OpenDiscoveryRunClaimService claims;
    @Mock
    private FormalDiagnosisClaimService formalClaims;

    private OpenDiscoveryDiagnosisPersistenceService service;

    @BeforeEach
    void setUp() {
        service = new OpenDiscoveryDiagnosisPersistenceService(
                diagnoses, runAudits, claims, formalClaims);
    }

    @Test
    void writesTheAuditOnlyWhenThisCallCreatedTheDiagnosis() {
        Diagnosis diagnosis = diagnosis();
        OpenDiscoveryRunAudit audit = audit();
        when(diagnoses.createOrGet(WORKSPACE_ID, diagnosis, NOW))
                .thenReturn(new StoredDiagnosis(diagnosis, 0, true));

        StoredDiagnosis stored = service.persist(
                WORKSPACE_ID, diagnosis, NOW, null, null, audit);

        assertThat(stored.created()).isTrue();
        verify(runAudits).insert(WORKSPACE_ID, audit);
    }

    @Test
    void doesNotAttachANewRunToADeduplicatedHistoricalDiagnosis() {
        Diagnosis diagnosis = diagnosis();
        OpenDiscoveryRunAudit audit = audit();
        when(diagnoses.createOrGet(WORKSPACE_ID, diagnosis, NOW))
                .thenReturn(new StoredDiagnosis(diagnosis, 3, false));

        StoredDiagnosis stored = service.persist(
                WORKSPACE_ID, diagnosis, NOW, null, null, audit);

        assertThat(stored.created()).isFalse();
        verify(runAudits, never()).insert(WORKSPACE_ID, audit);
    }

    @Test
    void rejectsAnAuditForAnotherDiagnosisBeforeWritingEitherRow() {
        OpenDiscoveryRunAudit wrong = new OpenDiscoveryRunAudit(
                "run-other", "diag-other", List.of(), null, List.of(),
                6, 6, 0, Duration.ofSeconds(20),
                OpenDiscoveryRunAudit.StopReason.AGENT_ABSTAINED,
                List.of(), NOW, NOW, "agent:88");

        assertThatThrownBy(() -> service.persist(
                WORKSPACE_ID, diagnosis(), NOW, null, null, wrong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must belong");
        verify(diagnoses, never()).createOrGet(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void completesTheAtomicClaimInTheSameShortTransactionAsDiagnosisAndAudit() {
        Diagnosis diagnosis = diagnosis();
        OpenDiscoveryRunAudit audit = audit();
        OpenDiscoveryRunClaim claim = new OpenDiscoveryRunClaim(
                "a".repeat(64), "claim-1", NOW, NOW.plusSeconds(80));
        when(diagnoses.createOrGet(WORKSPACE_ID, diagnosis, NOW))
                .thenReturn(new StoredDiagnosis(diagnosis, 0, true));

        service.persist(WORKSPACE_ID, diagnosis, NOW, null, claim, audit);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(
                diagnoses, runAudits, claims);
        order.verify(diagnoses).createOrGet(WORKSPACE_ID, diagnosis, NOW);
        order.verify(runAudits).insert(WORKSPACE_ID, audit);
        order.verify(claims).complete(
                WORKSPACE_ID, claim, diagnosis.diagnosisId(), audit.completedAt());
    }

    @Test
    void reservesAStableWebIncidentBeforeExternalInvestigation() {
        IncidentContext incident = diagnosis().incident();
        OpenDiscoveryRunClaim claim = new OpenDiscoveryRunClaim(
                "a".repeat(64), "claim-1", NOW, NOW.plusSeconds(80));
        when(diagnoses.findByIncident(WORKSPACE_ID, incident, false, NOW))
                .thenReturn(Optional.empty());
        when(claims.claim(eq(WORKSPACE_ID), any(), eq(NOW), eq(Duration.ofSeconds(80))))
                .thenReturn(OpenDiscoveryRunClaimService.ClaimResult.acquired(claim));

        OpenDiscoveryRunReservation reservation = service.reserve(
                WORKSPACE_ID, incident, false, NOW, null, Duration.ofSeconds(80));

        assertThat(reservation.alreadyCompleted()).isFalse();
        assertThat(reservation.claim()).isSameAs(claim);
        verify(claims).claim(
                eq(WORKSPACE_ID), any(), eq(NOW), eq(Duration.ofSeconds(80)));
    }

    @Test
    void mapsACompletedClaimBackToTheStoredDiagnosis() {
        IncidentContext incident = diagnosis().incident();
        StoredDiagnosis stored = new StoredDiagnosis(diagnosis(), 3, false);
        when(diagnoses.findByIncident(WORKSPACE_ID, incident, false, NOW))
                .thenReturn(Optional.empty());
        when(claims.claim(eq(WORKSPACE_ID), any(), eq(NOW), any()))
                .thenReturn(OpenDiscoveryRunClaimService.ClaimResult.completed("diag-agent-1"));
        when(diagnoses.get(WORKSPACE_ID, "diag-agent-1")).thenReturn(stored);

        OpenDiscoveryRunReservation reservation = service.reserve(
                WORKSPACE_ID, incident, false, NOW, null, Duration.ofSeconds(80));

        assertThat(reservation.completedDiagnosis()).isSameAs(stored);
        assertThat(reservation.claim()).isNull();
    }

    @Test
    void rejectsAConcurrentDuplicateWithoutStartingAnotherRun() {
        IncidentContext incident = diagnosis().incident();
        when(diagnoses.findByIncident(WORKSPACE_ID, incident, false, NOW))
                .thenReturn(Optional.empty());
        when(claims.claim(eq(WORKSPACE_ID), any(), eq(NOW), any()))
                .thenReturn(OpenDiscoveryRunClaimService.ClaimResult.inProgress());

        assertThatThrownBy(() -> service.reserve(
                WORKSPACE_ID, incident, false, NOW, null, Duration.ofSeconds(80)))
                .isInstanceOf(vip.mate.exception.MateClawException.class)
                .satisfies(error -> assertThat(
                        ((vip.mate.exception.MateClawException) error).getCode())
                        .isEqualTo(409))
                .hasMessageContaining("already in progress");

        verify(diagnoses, never()).get(eq(WORKSPACE_ID), any());
    }

    @Test
    void persistsABoundedAbstentionWithAnExplicitAbstainedStopReason() {
        Diagnosis diagnosis = boundedDiagnosis(true);
        OpenDiscoveryRunAudit audit = boundedAudit(true);
        when(diagnoses.createOrGet(WORKSPACE_ID, diagnosis, NOW))
                .thenReturn(new StoredDiagnosis(diagnosis, 0, true));

        StoredDiagnosis stored = service.persist(
                WORKSPACE_ID, diagnosis, NOW, null, null, audit);

        assertThat(stored.created()).isTrue();
        verify(runAudits).insert(WORKSPACE_ID, audit);
    }

    @Test
    void persistsABoundedHypothesisWithANonAbstainedStopReason() {
        Diagnosis diagnosis = boundedDiagnosis(false);
        OpenDiscoveryRunAudit audit = boundedAudit(false);
        when(diagnoses.createOrGet(WORKSPACE_ID, diagnosis, NOW))
                .thenReturn(new StoredDiagnosis(diagnosis, 0, true));

        service.persist(WORKSPACE_ID, diagnosis, NOW, null, null, audit);

        verify(runAudits).insert(WORKSPACE_ID, audit);
    }

    @Test
    void rejectsCrossedBoundedFindingAndStopReasonCombinations() {
        assertThatThrownBy(() -> service.persist(
                WORKSPACE_ID, boundedDiagnosis(true), NOW, null, null,
                boundedAudit(false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stopReason must agree");
        assertThatThrownBy(() -> service.persist(
                WORKSPACE_ID, boundedDiagnosis(false), NOW, null, null,
                boundedAudit(true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stopReason must agree");
    }

    @Test
    void persistsFormalDiagnosisAuditAndClaimAsOneTransactionBoundary() {
        Diagnosis diagnosis = boundedDiagnosis(false);
        FormalOpenDiscoveryAdmission admission = new FormalOpenDiscoveryAdmission(
                4, "t7-accepted-generic-000001", "a".repeat(64));
        OpenDiscoveryRunAudit audit = formalBoundedAudit(admission);
        OpenDiscoveryRunClaim claim = new OpenDiscoveryRunClaim(
                IncidentDeduplicationKey.create(
                                diagnosis.incident(), false, NOW)
                        .orElseThrow(),
                "claim-formal-1",
                NOW,
                NOW.plusSeconds(80));
        when(diagnoses.createOrGet(
                WORKSPACE_ID, diagnosis, NOW, 4, claim))
                .thenReturn(new StoredDiagnosis(diagnosis, 0, true, 4));

        StoredDiagnosis stored = service.persistFormal(
                WORKSPACE_ID, diagnosis, NOW, null, claim, null, audit, admission,
                NOW.plusSeconds(12));

        assertThat(stored.pilotPlanVersion()).isEqualTo(4);
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(
                diagnoses, runAudits, claims);
        order.verify(claims).complete(
                WORKSPACE_ID, claim, diagnosis.diagnosisId(), NOW.plusSeconds(12));
        order.verify(diagnoses).createOrGet(
                WORKSPACE_ID, diagnosis, NOW, 4, claim);
        order.verify(runAudits).insert(WORKSPACE_ID, audit);
    }

    @Test
    void rejectsAFormalIntakeClaimForAnotherSessionBeforeLockingIt() {
        Diagnosis diagnosis = boundedDiagnosis(false);
        FormalOpenDiscoveryAdmission admission = new FormalOpenDiscoveryAdmission(
                4, "t7-accepted-generic-000001", "a".repeat(64));
        OpenDiscoveryRunAudit audit = formalBoundedAudit(admission);
        FormalDiagnosisClaim wrongClaim = new FormalDiagnosisClaim(
                FormalDiagnosisClaimKey.forIntake(WORKSPACE_ID, "another-session"),
                "claim-intake-wrong",
                NOW,
                NOW.plusSeconds(80));

        assertThatThrownBy(() -> service.persistFormal(
                WORKSPACE_ID,
                diagnosis,
                NOW,
                "intake-formal-1",
                null,
                wrongClaim,
                audit,
                admission,
                NOW.plusSeconds(12)))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("claim identity");

        verify(formalClaims, never()).lockForCommit(anyLong(), any());
        verify(diagnoses, never()).createOrGetForIntake(
                anyLong(), any(), anyString(), anyInt(), any());
    }

    @Test
    void persistsAFormalIntakeDiagnosisAuditAndSessionClaimAtomically() {
        Diagnosis diagnosis = boundedDiagnosis(false);
        FormalOpenDiscoveryAdmission admission = new FormalOpenDiscoveryAdmission(
                4, "t7-accepted-generic-000001", "a".repeat(64));
        OpenDiscoveryRunAudit audit = formalBoundedAudit(admission);
        FormalDiagnosisClaim intakeClaim = new FormalDiagnosisClaim(
                FormalDiagnosisClaimKey.forIntake(WORKSPACE_ID, "intake-formal-1"),
                "claim-intake-1",
                NOW,
                NOW.plusSeconds(80));
        when(diagnoses.createOrGetForIntake(
                WORKSPACE_ID,
                diagnosis,
                "intake-formal-1",
                4,
                intakeClaim))
                .thenReturn(new StoredDiagnosis(diagnosis, 0, true, 4));

        StoredDiagnosis stored = service.persistFormal(
                WORKSPACE_ID,
                diagnosis,
                NOW,
                "intake-formal-1",
                null,
                intakeClaim,
                audit,
                admission,
                NOW.plusSeconds(12));

        assertThat(stored.created()).isTrue();
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(
                formalClaims, diagnoses, runAudits);
        order.verify(formalClaims).lockForCommit(WORKSPACE_ID, intakeClaim);
        order.verify(diagnoses).createOrGetForIntake(
                WORKSPACE_ID, diagnosis, "intake-formal-1", 4, intakeClaim);
        order.verify(runAudits).insert(WORKSPACE_ID, audit);
        order.verify(formalClaims).complete(
                WORKSPACE_ID,
                intakeClaim,
                diagnosis.diagnosisId(),
                NOW.plusSeconds(12));
        verify(claims, never()).complete(anyLong(), any(), anyString(), any());
    }

    @Test
    void rejectsFormalAuditThatDoesNotMatchTheFrozenAdmission() {
        Diagnosis diagnosis = boundedDiagnosis(false);
        FormalOpenDiscoveryAdmission admission = new FormalOpenDiscoveryAdmission(
                4, "t7-accepted-generic-000001", "a".repeat(64));
        OpenDiscoveryRunAudit mismatched = new OpenDiscoveryRunAudit(
                "run-bounded-1", "diag-bounded-1",
                List.of("bounded-open-discovery-v1"),
                "bounded-open-discovery-v1", "0".repeat(64),
                List.of("error_log_scan", "k8s_workload_health"),
                2, 2, 2, Duration.ofSeconds(10),
                OpenDiscoveryRunAudit.StopReason.BOUNDED_EVIDENCE_EXHAUSTED,
                List.of("open-discovery-error-log-scan"), NOW, NOW,
                "planner:bounded-open-discovery-v1", 4,
                "t7-other-acceptance-000001", "b".repeat(64));

        assertThatThrownBy(() -> service.persistFormal(
                WORKSPACE_ID, diagnosis, NOW, null, null, null, mismatched, admission,
                NOW.plusSeconds(12)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must match its admission");

        verify(diagnoses, never()).createOrGet(
                eq(WORKSPACE_ID), eq(diagnosis), eq(NOW), eq(4), any());
    }

    @Test
    void reusesACompletedFormalDiagnosisOnlyWhenItsAuditMatchesCurrentAuthority() {
        Diagnosis diagnosis = boundedDiagnosis(false);
        FormalOpenDiscoveryAdmission admission = new FormalOpenDiscoveryAdmission(
                4, "t7-accepted-generic-000001", "a".repeat(64));
        StoredDiagnosis stored = new StoredDiagnosis(diagnosis, 2, false, 4);
        when(runAudits.latest(WORKSPACE_ID, diagnosis.diagnosisId()))
                .thenReturn(Optional.of(formalBoundedAudit(admission)));

        assertThat(service.requireCompletedFormal(
                WORKSPACE_ID, stored, admission)).isSameAs(stored);
    }

    @Test
    void rejectsACompletedFormalDiagnosisWhenItsFrozenCapabilityPlanChanged() {
        Diagnosis diagnosis = boundedDiagnosis(false);
        FormalOpenDiscoveryAdmission admission = new FormalOpenDiscoveryAdmission(
                4,
                "t7-accepted-generic-000001",
                "a".repeat(64),
                FormalOpenDiscoveryPlan.fromAcceptedCapabilities(
                        Set.of("error_log_scan")));
        StoredDiagnosis stored = new StoredDiagnosis(diagnosis, 2, false, 4);
        OpenDiscoveryRunAudit staleTwoSignalAudit = new OpenDiscoveryRunAudit(
                "run-bounded-1",
                "diag-bounded-1",
                List.of("bounded-open-discovery-v1"),
                "bounded-open-discovery-v1",
                "0".repeat(64),
                List.of("error_log_scan", "k8s_workload_health"),
                2,
                2,
                2,
                Duration.ofSeconds(10),
                OpenDiscoveryRunAudit.StopReason.BOUNDED_EVIDENCE_EXHAUSTED,
                List.of("open-discovery-error-log-scan"),
                NOW,
                NOW,
                "planner:bounded-open-discovery-v1",
                admission.pilotPlanVersion(),
                admission.guanceAcceptanceId(),
                admission.guanceBindingFingerprint());
        when(runAudits.latest(WORKSPACE_ID, diagnosis.diagnosisId()))
                .thenReturn(Optional.of(staleTwoSignalAudit));

        assertThatThrownBy(() -> service.requireCompletedFormal(
                WORKSPACE_ID, stored, admission))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("frozen capability plan");
    }

    @Test
    void rejectsLegacyCompletedDiagnosisWithoutMatchingFormalAuditAuthority() {
        Diagnosis diagnosis = boundedDiagnosis(false);
        FormalOpenDiscoveryAdmission admission = new FormalOpenDiscoveryAdmission(
                4, "t7-accepted-generic-000001", "a".repeat(64));
        StoredDiagnosis stored = new StoredDiagnosis(diagnosis, 2, false, 4);
        when(runAudits.latest(WORKSPACE_ID, diagnosis.diagnosisId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireCompletedFormal(
                WORKSPACE_ID, stored, admission))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("frozen formal authority");
    }

    private Diagnosis diagnosis() {
        IncidentContext incident = new IncidentContext(
                "incident-agent-1", "CSDP", "csdp-task", null,
                "CTI创建会话失败", "P1", "影响待确认", null,
                NOW, null, "web", IncidentCompleteness.SYMPTOM, null);
        return Diagnosis.initialAgentFallback(
                new vip.mate.troubleshooting.model.AgentTriageDraft(
                        "diag-agent-1", "case-agent-1", "run-agent-1", incident,
                        List.<EvidenceResult>of(), List.of(), "证据不足",
                        "待人工深查", Confidence.LOW, true,
                        NorthStarTimings.concluded(NOW, NOW, NOW),
                        false, false, List.of()),
                DiagnosisStatus.NEEDS_INVESTIGATION,
                List.of());
    }

    private OpenDiscoveryRunAudit audit() {
        return new OpenDiscoveryRunAudit(
                "run-agent-1", "diag-agent-1",
                List.of("message_send_failed"), "message_send_failed",
                List.of("log_search", "log_trace_bundle", "contrast_sample"),
                6, 6, 3, Duration.ofSeconds(20),
                OpenDiscoveryRunAudit.StopReason.AGENT_ABSTAINED,
                List.of(), NOW, NOW, "agent:88");
    }

    private Diagnosis boundedDiagnosis(boolean abstained) {
        IncidentContext incident = new IncidentContext(
                "incident-bounded-1", "CSDP", "csdp-wechat", null,
                "未知服务异常", "P1", "影响待确认", null,
                NOW, null, "web", IncidentCompleteness.SYMPTOM, null);
        EvidenceResult evidence = new EvidenceResult(
                "open-discovery-error-log-scan", "logs", "", EvidenceStatus.ANOMALY,
                "service errors", java.util.Map.of("error_count", 3), "guance", NOW);
        BoundedInvestigationDraft draft = new BoundedInvestigationDraft(
                "diag-bounded-1",
                "case-bounded-1",
                "run-bounded-1",
                incident,
                abstained ? List.of() : List.of(evidence),
                abstained ? List.of() : List.of(evidence.queryId()),
                abstained ? "证据不足，系统已弃权" : "应用错误方向有证据支持",
                abstained ? "" : "应用服务自身出现集中错误",
                abstained ? Confidence.LOW : Confidence.MEDIUM,
                abstained,
                NorthStarTimings.concluded(NOW, NOW, NOW),
                false,
                false,
                List.of());
        return Diagnosis.initialBoundedInvestigation(
                draft,
                abstained ? DiagnosisStatus.NEEDS_INVESTIGATION
                        : DiagnosisStatus.READY_FOR_HUMAN,
                List.of());
    }

    private OpenDiscoveryRunAudit boundedAudit(boolean abstained) {
        return new OpenDiscoveryRunAudit(
                "run-bounded-1",
                "diag-bounded-1",
                List.of("bounded-open-discovery-v1"),
                "bounded-open-discovery-v1",
                "0".repeat(64),
                List.of("error_log_scan", "k8s_workload_health"),
                2,
                2,
                2,
                Duration.ofSeconds(10),
                abstained
                        ? OpenDiscoveryRunAudit.StopReason.BOUNDED_EVIDENCE_EXHAUSTED_ABSTAINED
                        : OpenDiscoveryRunAudit.StopReason.BOUNDED_EVIDENCE_EXHAUSTED,
                abstained ? List.of() : List.of("open-discovery-error-log-scan"),
                NOW,
                NOW,
                "planner:bounded-open-discovery-v1");
    }

    private OpenDiscoveryRunAudit formalBoundedAudit(
            FormalOpenDiscoveryAdmission admission) {
        return new OpenDiscoveryRunAudit(
                "run-bounded-1",
                "diag-bounded-1",
                List.of("bounded-open-discovery-v1"),
                "bounded-open-discovery-v1",
                admission.plan().fingerprint(),
                admission.plan().allowedSignalKinds().stream().sorted().toList(),
                2,
                2,
                2,
                Duration.ofSeconds(10),
                OpenDiscoveryRunAudit.StopReason.BOUNDED_EVIDENCE_EXHAUSTED,
                List.of("open-discovery-error-log-scan"),
                NOW,
                NOW,
                "planner:bounded-open-discovery-v1",
                admission.pilotPlanVersion(),
                admission.guanceAcceptanceId(),
                admission.guanceBindingFingerprint());
    }
}
