package vip.mate.troubleshooting.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.troubleshooting.model.Confidence;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.DiagnosisStatus;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.model.NorthStarTimings;
import vip.mate.troubleshooting.service.StoredDiagnosis;
import vip.mate.troubleshooting.service.TroubleshootingPersistenceService;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

    private OpenDiscoveryDiagnosisPersistenceService service;

    @BeforeEach
    void setUp() {
        service = new OpenDiscoveryDiagnosisPersistenceService(
                diagnoses, runAudits, claims);
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
}
