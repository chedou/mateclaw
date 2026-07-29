package vip.mate.troubleshooting.evidence;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.synthesis.LogTraceSkeleton;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GuanceEvidenceSpinePreviewServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-29T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void projectsOneGuanceOnlyFullSpineWithoutRawEvidenceOrQueryText() {
        GuanceEvidenceReadinessService readinessService =
                mock(GuanceEvidenceReadinessService.class);
        EvidenceSpineOrchestrator orchestrator = mock(EvidenceSpineOrchestrator.class);
        when(readinessService.inspect(7L, "CSDP", "session-svc"))
                .thenReturn(readiness(GuanceEvidenceReadiness.Status.READY_FOR_VALIDATION))
                .thenReturn(readiness(GuanceEvidenceReadiness.Status.CANONICAL_SIGNALS_OBSERVED));
        when(orchestrator.collect(
                eq(7L), any(IncidentContext.class), any(EvidenceSpinePlan.class),
                eq(Set.of("guance"))))
                .thenReturn(fullSpine());
        GuanceEvidenceSpinePreviewService service = new GuanceEvidenceSpinePreviewService(
                orchestrator,
                readinessService,
                CLOCK,
                new SequenceTicker(0L, 47_000_000L));

        GuanceEvidenceSpinePreview preview = service.preview(
                7L, "CSDP", "session-svc", "message_send_failed", "-15m", NOW);

        assertThat(preview.stage())
                .isEqualTo(GuanceEvidenceSpinePreview.Stage.FULL_SPINE_OBSERVED);
        assertThat(preview.matchCount()).isEqualTo(4L);
        assertThat(preview.psId()).isEqualTo("ps-message-001");
        assertThat(preview.traceEntries()).isEqualTo(3);
        assertThat(preview.serviceSequence())
                .containsExactly("gateway", "session-svc", "openim");
        assertThat(preview.anomalyCount()).isEqualTo(2);
        assertThat(preview.traceElapsedMs()).isEqualTo(42L);
        assertThat(preview.contrast().available()).isTrue();
        assertThat(preview.contrast().failureRate()).isEqualTo(0.92);
        assertThat(preview.contrast().successRate()).isEqualTo(0.03);
        assertThat(preview.contrast().rateDelta()).isEqualTo(0.89);
        assertThat(preview.sourceRequestCount()).isEqualTo(3);
        assertThat(preview.totalDurationMs()).isEqualTo(47L);
        assertThat(preview.steps())
                .extracting(GuanceEvidenceSpinePreview.Step::status)
                .containsExactly(
                        GuanceEvidenceSpinePreview.StepStatus.CANONICAL_RESULT_OBSERVED,
                        GuanceEvidenceSpinePreview.StepStatus.CANONICAL_RESULT_OBSERVED,
                        GuanceEvidenceSpinePreview.StepStatus.CANONICAL_RESULT_OBSERVED);
        assertThat(preview.toString()).doesNotContain(
                "L::logs", "message_send_failed", "raw secret-bearing message", "runtime-secret");
        assertThat(preview.warnings())
                .anyMatch(warning -> warning.contains("20–30")
                        && warning.contains("fixtureMode"));

        ArgumentCaptor<IncidentContext> incident = ArgumentCaptor.forClass(IncidentContext.class);
        ArgumentCaptor<EvidenceSpinePlan> plan = ArgumentCaptor.forClass(EvidenceSpinePlan.class);
        verify(orchestrator).collect(
                eq(7L), incident.capture(), plan.capture(), eq(Set.of("guance")));
        assertThat(incident.getValue().intakeSource()).isEqualTo("guance_spine_preview");
        assertThat(plan.getValue().searchTerm()).isEqualTo("message_send_failed");
        assertThat(plan.getValue().window()).isEqualTo("-15m");
    }

    @Test
    void keepsMissingContrastVisibleWithoutDiscardingTheCoreChain() {
        GuanceEvidenceReadinessService readinessService =
                mock(GuanceEvidenceReadinessService.class);
        EvidenceSpineOrchestrator orchestrator = mock(EvidenceSpineOrchestrator.class);
        when(readinessService.inspect(7L, "CSDP", "session-svc"))
                .thenReturn(readiness(GuanceEvidenceReadiness.Status.READY_FOR_VALIDATION));
        EvidenceSpineResult coreOnly = coreOnlySpine();
        when(orchestrator.collect(anyLong(), any(), any(), eq(Set.of("guance"))))
                .thenReturn(coreOnly);
        GuanceEvidenceSpinePreviewService service = new GuanceEvidenceSpinePreviewService(
                orchestrator,
                readinessService,
                CLOCK,
                new SequenceTicker(0L, 18_000_000L));

        GuanceEvidenceSpinePreview preview = service.preview(
                7L, "CSDP", "session-svc", "message_send_failed", "-15m", NOW);

        assertThat(preview.stage())
                .isEqualTo(GuanceEvidenceSpinePreview.Stage.CORE_CHAIN_OBSERVED);
        assertThat(preview.contrast().available()).isFalse();
        assertThat(preview.steps()).last().satisfies(step -> {
            assertThat(step.signalKind()).isEqualTo("contrast_sample");
            assertThat(step.status()).isEqualTo(GuanceEvidenceSpinePreview.StepStatus.MISSING);
        });
        assertThat(preview.warnings())
                .anyMatch(warning -> warning.contains("对照") && warning.contains("校准期"));
    }

    @Test
    void blocksBeforeTheEvidenceSpineWhenTheRealSourceGateIsClosed() {
        GuanceEvidenceReadinessService readinessService =
                mock(GuanceEvidenceReadinessService.class);
        EvidenceSpineOrchestrator orchestrator = mock(EvidenceSpineOrchestrator.class);
        when(readinessService.inspect(7L, "CSDP", "session-svc"))
                .thenReturn(readiness(GuanceEvidenceReadiness.Status.UNAUTHORIZED));
        GuanceEvidenceSpinePreviewService service = new GuanceEvidenceSpinePreviewService(
                orchestrator,
                readinessService,
                CLOCK,
                new SequenceTicker(0L, 1_000_000L));

        GuanceEvidenceSpinePreview preview = service.preview(
                7L, "CSDP", "session-svc", "message_send_failed", "-15m", NOW);

        assertThat(preview.stage()).isEqualTo(GuanceEvidenceSpinePreview.Stage.BLOCKED);
        assertThat(preview.sourceRequestCount()).isZero();
        assertThat(preview.steps())
                .allMatch(step -> step.status() == GuanceEvidenceSpinePreview.StepStatus.NOT_RUN);
        verify(orchestrator, never()).collect(anyLong(), any(), any(), any());
    }

    private GuanceEvidenceReadiness readiness(GuanceEvidenceReadiness.Status status) {
        boolean authorized = status != GuanceEvidenceReadiness.Status.UNAUTHORIZED;
        GuanceEvidenceReadiness.SignalStatus signalStatus = authorized
                ? GuanceEvidenceReadiness.SignalStatus.READY_FOR_VALIDATION
                : GuanceEvidenceReadiness.SignalStatus.UNAUTHORIZED;
        return new GuanceEvidenceReadiness(
                "CSDP",
                "session-svc",
                status,
                true,
                true,
                authorized
                        ? GuanceEvidenceReadiness.CredentialState.CONFIGURED
                        : GuanceEvidenceReadiness.CredentialState.NOT_INSPECTED,
                authorized,
                List.of(
                        signal("log_search", signalStatus),
                        signal("log_trace_bundle", signalStatus),
                        signal("contrast_sample", signalStatus)),
                authorized ? List.of() : List.of("asset authorization missing"));
    }

    private GuanceEvidenceReadiness.SignalReadiness signal(
            String signalKind,
            GuanceEvidenceReadiness.SignalStatus status) {
        return new GuanceEvidenceReadiness.SignalReadiness(
                signalKind,
                true,
                status,
                signalKind + "-binding",
                null,
                "safe readiness detail");
    }

    private EvidenceSpineResult fullSpine() {
        EvidenceResult search = evidence(
                "T8-GUANCE-LOG-SEARCH",
                "log_search",
                Map.of(
                        "match_count", 4,
                        "ps_id", "ps-message-001",
                        "sample_message", "raw secret-bearing message"));
        EvidenceResult trace = evidence(
                "T8-GUANCE-TRACE-BUNDLE",
                "log_trace_bundle",
                Map.of(
                        "ps_id", "ps-message-001",
                        "entries", List.of(
                                Map.of("timestamp", 1_000L, "service", "gateway",
                                        "level", "INFO", "message", "accepted"),
                                Map.of("timestamp", 1_020L, "service", "session-svc",
                                        "level", "ERROR", "message", "state conflict"),
                                Map.of("timestamp", 1_042L, "service", "openim",
                                        "level", "ERROR", "message", "send rejected"))));
        EvidenceResult contrast = evidence(
                "T8-GUANCE-CONTRAST-SAMPLE",
                "contrast_sample",
                Map.of(
                        "discriminating_feature", "session_state_conflict",
                        "failure_sample_count", 100,
                        "failure_match_count", 92,
                        "success_sample_count", 100,
                        "success_match_count", 3));
        return new EvidenceSpineResult(
                search,
                trace,
                contrast,
                skeleton(true),
                3,
                null);
    }

    private EvidenceSpineResult coreOnlySpine() {
        EvidenceSpineResult full = fullSpine();
        EvidenceResult missingContrast = new EvidenceResult(
                "T8-GUANCE-CONTRAST-SAMPLE",
                "UNKNOWN",
                "",
                EvidenceStatus.MISSING,
                "contrast unavailable",
                Map.of(),
                "router:unavailable",
                NOW);
        return new EvidenceSpineResult(
                full.searchEvidence(),
                full.traceEvidence(),
                missingContrast,
                skeleton(false),
                3,
                null);
    }

    private EvidenceResult evidence(
            String requestId,
            String signalKind,
            Map<String, Object> observed) {
        return new EvidenceResult(
                requestId,
                "L",
                "L::logs contains runtime-secret",
                EvidenceStatus.ANOMALY,
                "canonical " + signalKind,
                observed,
                "guance:" + signalKind,
                NOW);
    }

    private LogTraceSkeleton skeleton(boolean contrastAvailable) {
        LogTraceSkeleton.ContrastSummary contrast = contrastAvailable
                ? new LogTraceSkeleton.ContrastSummary(
                        true, "session_state_conflict", 100, 92, 100, 3,
                        0.92, 0.03, 0.89)
                : LogTraceSkeleton.ContrastSummary.unavailable();
        return new LogTraceSkeleton(
                "ps-message-001",
                1_000L,
                1_042L,
                42L,
                List.of("gateway", "session-svc", "openim"),
                List.of(
                        new LogTraceSkeleton.TimelineEvent(
                                0, 0, "gateway", "INFO", "accepted", null, false),
                        new LogTraceSkeleton.TimelineEvent(
                                1, 20, "session-svc", "ERROR", "state conflict", null, true),
                        new LogTraceSkeleton.TimelineEvent(
                                2, 42, "openim", "ERROR", "send rejected", null, true)),
                List.of(1, 2),
                Map.of(),
                3,
                0,
                contrast);
    }

    private static final class SequenceTicker implements LongSupplier {
        private final Deque<Long> values = new ArrayDeque<>();

        private SequenceTicker(Long... values) {
            this.values.addAll(List.of(values));
        }

        @Override
        public long getAsLong() {
            if (values.isEmpty()) {
                throw new AssertionError("unexpected timing read");
            }
            return values.removeFirst();
        }
    }
}
