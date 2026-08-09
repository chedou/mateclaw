package vip.mate.troubleshooting.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.troubleshooting.model.ConclusionType;
import vip.mate.troubleshooting.model.DiagnosisStatus;
import vip.mate.troubleshooting.model.PlaybookVersionRef;
import vip.mate.troubleshooting.model.TroubleshootingScenarioEvidenceRunEntity;
import vip.mate.troubleshooting.repository.TroubleshootingScenarioEvidenceRunMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScenarioEvidenceRunAuditServiceTest {

    private static final Instant STARTED_AT = Instant.parse("2026-08-09T09:25:07Z");
    private static final Instant COMPLETED_AT = Instant.parse("2026-08-09T09:25:13Z");

    @Mock
    private TroubleshootingScenarioEvidenceRunMapper mapper;

    private ScenarioEvidenceRunAuditService service;

    @BeforeEach
    void setUp() {
        service = new ScenarioEvidenceRunAuditService(mapper, new ObjectMapper());
    }

    @Test
    void insertsOnlySafeRunIdentityTimingAndEvidenceReferences() {
        when(mapper.insert(org.mockito.ArgumentMatchers.any(
                TroubleshootingScenarioEvidenceRunEntity.class))).thenReturn(1);
        ScenarioEvidenceRunAudit audit = audit();

        service.insert(7L, audit);

        ArgumentCaptor<TroubleshootingScenarioEvidenceRunEntity> row =
                ArgumentCaptor.forClass(TroubleshootingScenarioEvidenceRunEntity.class);
        verify(mapper).insert(row.capture());
        assertThat(row.getValue().getWorkspaceId()).isEqualTo(7L);
        assertThat(row.getValue().getDiagnosisId()).isEqualTo("diag-cti");
        assertThat(row.getValue().getEvidenceRefs())
                .isEqualTo("[\"CTI-LOG-SEARCH\",\"CTI-TRACE-BUNDLE\",\"CTI-CONTRAST\"]");
        assertThat(row.getValue().getStartedAt())
                .isEqualTo(LocalDateTime.ofInstant(STARTED_AT, ZoneOffset.UTC));
        assertThat(row.getValue().getCompletedAt())
                .isEqualTo(LocalDateTime.ofInstant(COMPLETED_AT, ZoneOffset.UTC));
    }

    @Test
    void readsTheLatestRunWithoutReplayingEvidence() {
        TroubleshootingScenarioEvidenceRunEntity row =
                new TroubleshootingScenarioEvidenceRunEntity();
        row.setRunId("scenario-evidence-run-1");
        row.setDiagnosisId("diag-cti");
        row.setPlaybookId("playbook-cti");
        row.setPlaybookVersion(2);
        row.setDiagnosisStatus("READY_FOR_HUMAN");
        row.setConclusionType("HYPOTHESIS");
        row.setEvidenceRefs("[\"CTI-LOG-SEARCH\"]");
        row.setActorRef("alice");
        row.setStartedAt(LocalDateTime.ofInstant(STARTED_AT, ZoneOffset.UTC));
        row.setCompletedAt(LocalDateTime.ofInstant(COMPLETED_AT, ZoneOffset.UTC));
        when(mapper.latestByDiagnosis(7L, "diag-cti")).thenReturn(row);

        ScenarioEvidenceRunAudit latest = service.latest(7L, "diag-cti").orElseThrow();

        assertThat(latest.duration()).isEqualTo(java.time.Duration.ofSeconds(6));
        assertThat(latest.evidenceRefs()).containsExactly("CTI-LOG-SEARCH");
        assertThat(latest.conclusionType()).isEqualTo(ConclusionType.HYPOTHESIS);
    }

    @Test
    void rejectsCredentialsFromTheImmutableAuditSurface() {
        assertThatThrownBy(() -> new ScenarioEvidenceRunAudit(
                "scenario-evidence-run-1",
                "diag-cti",
                new PlaybookVersionRef("playbook-cti", 2),
                DiagnosisStatus.READY_FOR_HUMAN,
                ConclusionType.HYPOTHESIS,
                List.of("DF-API-KEY: secret-value"),
                STARTED_AT,
                COMPLETED_AT,
                "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credentials");
    }

    private ScenarioEvidenceRunAudit audit() {
        return new ScenarioEvidenceRunAudit(
                "scenario-evidence-run-1",
                "diag-cti",
                new PlaybookVersionRef("playbook-cti", 2),
                DiagnosisStatus.READY_FOR_HUMAN,
                ConclusionType.HYPOTHESIS,
                List.of("CTI-LOG-SEARCH", "CTI-TRACE-BUNDLE", "CTI-CONTRAST"),
                STARTED_AT,
                COMPLETED_AT,
                "alice");
    }
}
