package vip.mate.troubleshooting.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.troubleshooting.model.TroubleshootingOpenDiscoveryRunEntity;
import vip.mate.troubleshooting.repository.TroubleshootingOpenDiscoveryRunMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenDiscoveryRunAuditServiceTest {

    private static final Instant STARTED_AT = Instant.parse("2026-08-11T10:00:00Z");
    private static final Instant COMPLETED_AT = Instant.parse("2026-08-11T10:00:04Z");

    @Mock
    private TroubleshootingOpenDiscoveryRunMapper mapper;

    private OpenDiscoveryRunAuditService service;

    @BeforeEach
    void setUp() {
        service = new OpenDiscoveryRunAuditService(mapper, new ObjectMapper());
    }

    @Test
    void insertsOnlyPlanIdentityBudgetsStopReasonAndEvidenceReferences() {
        when(mapper.insert(any(TroubleshootingOpenDiscoveryRunEntity.class))).thenReturn(1);

        service.insert(7L, audit());

        ArgumentCaptor<TroubleshootingOpenDiscoveryRunEntity> row =
                ArgumentCaptor.forClass(TroubleshootingOpenDiscoveryRunEntity.class);
        verify(mapper).insert(row.capture());
        assertThat(row.getValue().getWorkspaceId()).isEqualTo(7L);
        assertThat(row.getValue().getVisibleScenarioKeys())
                .isEqualTo("[\"message_send_failed\"]");
        assertThat(row.getValue().getSelectedScenarioKey())
                .isEqualTo("message_send_failed");
        assertThat(row.getValue().getSelectedPlanFingerprint())
                .isEqualTo("a".repeat(64));
        assertThat(row.getValue().getPlannedSignalKinds())
                .isEqualTo("[\"log_search\",\"log_trace_bundle\",\"contrast_sample\"]");
        assertThat(row.getValue().getSourceRequestCount()).isEqualTo(3);
        assertThat(row.getValue().getTimeBudgetMs()).isEqualTo(20_000L);
        assertThat(row.getValue().getEvidenceRefs())
                .isEqualTo("[\"ONLINE-LOG-SEARCH\",\"ONLINE-TRACE-BUNDLE\"]");
        assertThat(row.getValue().getStartedAt())
                .isEqualTo(LocalDateTime.ofInstant(STARTED_AT, ZoneOffset.UTC));
    }

    @Test
    void readsTheLatestRunWithoutReplayingTheAgentOrEvidenceQueries() {
        TroubleshootingOpenDiscoveryRunEntity row =
                new TroubleshootingOpenDiscoveryRunEntity();
        row.setRunId("run-agent-1");
        row.setDiagnosisId("diag-agent-1");
        row.setVisibleScenarioKeys("[\"message_send_failed\"]");
        row.setSelectedScenarioKey("message_send_failed");
        row.setSelectedPlanFingerprint("a".repeat(64));
        row.setPlannedSignalKinds(
                "[\"log_search\",\"log_trace_bundle\",\"contrast_sample\"]");
        row.setMaxIterations(6);
        row.setMaxEvidenceRequests(6);
        row.setSourceRequestCount(3);
        row.setTimeBudgetMs(20_000L);
        row.setStopReason("VERIFIABLE_HYPOTHESIS");
        row.setEvidenceRefs("[\"ONLINE-LOG-SEARCH\"]");
        row.setActorRef("agent:88");
        row.setStartedAt(LocalDateTime.ofInstant(STARTED_AT, ZoneOffset.UTC));
        row.setCompletedAt(LocalDateTime.ofInstant(COMPLETED_AT, ZoneOffset.UTC));
        when(mapper.latestByDiagnosis(7L, "diag-agent-1")).thenReturn(row);

        OpenDiscoveryRunAudit latest = service.latest(7L, "diag-agent-1")
                .orElseThrow();

        assertThat(latest.duration()).isEqualTo(Duration.ofSeconds(4));
        assertThat(latest.stopReason())
                .isEqualTo(OpenDiscoveryRunAudit.StopReason.VERIFIABLE_HYPOTHESIS);
        assertThat(latest.evidenceRefs()).containsExactly("ONLINE-LOG-SEARCH");
    }

    @Test
    void rejectsCredentialsAndPlansOutsideTheVisibleServerAllowlist() {
        assertThatThrownBy(() -> new OpenDiscoveryRunAudit(
                "run-agent-1", "diag-agent-1",
                List.of("message_send_failed"), "unknown_scenario",
                List.of("log_search"), 6, 6, 1, Duration.ofSeconds(20),
                OpenDiscoveryRunAudit.StopReason.AGENT_ABSTAINED,
                List.of(), STARTED_AT, COMPLETED_AT, "agent:88"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("visible scenario keys");
        assertThatThrownBy(() -> new OpenDiscoveryRunAudit(
                "run-agent-1", "diag-agent-1",
                List.of("message_send_failed"), "message_send_failed",
                List.of("log_search"), 6, 6, 1, Duration.ofSeconds(20),
                OpenDiscoveryRunAudit.StopReason.AGENT_ABSTAINED,
                List.of("DF-API-KEY: secret-value"),
                STARTED_AT, COMPLETED_AT, "agent:88"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credentials");
    }

    private OpenDiscoveryRunAudit audit() {
        return new OpenDiscoveryRunAudit(
                "run-agent-1",
                "diag-agent-1",
                List.of("message_send_failed"),
                "message_send_failed",
                "a".repeat(64),
                List.of("log_search", "log_trace_bundle", "contrast_sample"),
                6,
                6,
                3,
                Duration.ofSeconds(20),
                OpenDiscoveryRunAudit.StopReason.VERIFIABLE_HYPOTHESIS,
                List.of("ONLINE-LOG-SEARCH", "ONLINE-TRACE-BUNDLE"),
                STARTED_AT,
                COMPLETED_AT,
                "agent:88");
    }
}
