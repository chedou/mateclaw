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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    private OpenDiscoveryDiagnosisPersistenceService service;

    @BeforeEach
    void setUp() {
        service = new OpenDiscoveryDiagnosisPersistenceService(diagnoses, runAudits);
    }

    @Test
    void writesTheAuditOnlyWhenThisCallCreatedTheDiagnosis() {
        Diagnosis diagnosis = diagnosis();
        OpenDiscoveryRunAudit audit = audit();
        when(diagnoses.createOrGet(WORKSPACE_ID, diagnosis, NOW))
                .thenReturn(new StoredDiagnosis(diagnosis, 0, true));

        StoredDiagnosis stored = service.persist(
                WORKSPACE_ID, diagnosis, NOW, null, audit);

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
                WORKSPACE_ID, diagnosis, NOW, null, audit);

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
                WORKSPACE_ID, diagnosis(), NOW, null, wrong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must belong");
        verify(diagnoses, never()).createOrGet(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
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
