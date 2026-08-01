package vip.mate.troubleshooting.evidence;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.synthesis.DeterministicLogTraceCompressor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvidenceSpineOrchestratorTest {

    private static final long WORKSPACE_ID = 7L;
    private static final Instant NOW = Instant.parse("2026-07-29T02:00:00Z");
    private static final IncidentContext INCIDENT = new IncidentContext(
            "incident-1", "CSDP", "csdp-session-service", null,
            "会话消息发送失败", "P2", "影响待核实", null, NOW,
            "-15m", "web", IncidentCompleteness.SYMPTOM, "message send failed");

    @Test
    void collectsOneSharedSearchTraceAndContrastSpineWithDependentTargets() {
        EvidenceSourceRouter router = mock(EvidenceSourceRouter.class);
        when(router.collect(
                eq(WORKSPACE_ID), any(EvidenceRequest.class), eq(INCIDENT),
                eq(Set.of("recorded-replay"))))
                .thenAnswer(invocation -> evidence(invocation.getArgument(1)));
        EvidenceSpineOrchestrator orchestrator = new EvidenceSpineOrchestrator(
                router,
                new DeterministicLogTraceCompressor(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        EvidenceSpineResult result = orchestrator.collect(
                WORKSPACE_ID,
                INCIDENT,
                plan(),
                Set.of("recorded-replay"));

        assertThat(result.coreComplete()).isTrue();
        assertThat(result.contrastAvailable()).isTrue();
        assertThat(result.sourceRequestCount()).isEqualTo(3);
        assertThat(result.evidence())
                .extracting(EvidenceResult::queryId)
                .containsExactly("ONLINE-LOG-SEARCH", "ONLINE-TRACE-BUNDLE",
                        "ONLINE-CONTRAST-SAMPLE");
        assertThat(result.skeleton()).isNotNull();
        assertThat(result.skeleton().psId()).isEqualTo("synthetic-ps-1");
        assertThat(result.skeleton().serviceSequence())
                .containsExactly("session-api", "session-domain", "openim");
        assertThat(result.skeleton().contrast().failureMatchCount()).isEqualTo(92);
        assertThat(result.skeleton().contrast().successMatchCount()).isEqualTo(3);

        ArgumentCaptor<EvidenceRequest> requests = ArgumentCaptor.forClass(EvidenceRequest.class);
        verify(router, times(3)).collect(
                eq(WORKSPACE_ID), requests.capture(), eq(INCIDENT),
                eq(Set.of("recorded-replay")));
        assertThat(requests.getAllValues().get(0).target())
                .containsExactlyEntriesOf(Map.of("search_term", "message_send_failed"));
        assertThat(requests.getAllValues().get(1).target())
                .containsExactlyEntriesOf(Map.of("ps_id", "synthetic-ps-1"));
        assertThat(requests.getAllValues().get(2).target())
                .containsExactlyEntriesOf(Map.of(
                        "scenario_key", "message_send_failed",
                        "exclude_ps_id", "synthetic-ps-1"));
    }

    @Test
    void keepsMissingContrastExplicitButDoesNotDiscardTheCoreTrace() {
        EvidenceSourceRouter router = mock(EvidenceSourceRouter.class);
        when(router.collect(
                eq(WORKSPACE_ID), any(EvidenceRequest.class), eq(INCIDENT), eq((Set<String>) null)))
                .thenAnswer(invocation -> {
                    EvidenceRequest request = invocation.getArgument(1);
                    if ("contrast_sample".equals(request.signalKind())) {
                        return new EvidenceResult(
                                request.requestId(), "UNKNOWN", "", EvidenceStatus.MISSING,
                                "success comparison unavailable", Map.of(),
                                "router:unavailable", NOW);
                    }
                    return evidence(request);
                });
        EvidenceSpineOrchestrator orchestrator = new EvidenceSpineOrchestrator(
                router,
                new DeterministicLogTraceCompressor(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        EvidenceSpineResult result = orchestrator.collect(
                WORKSPACE_ID, INCIDENT, plan(), null);

        assertThat(result.coreComplete()).isTrue();
        assertThat(result.contrastAvailable()).isFalse();
        assertThat(result.sourceRequestCount()).isEqualTo(3);
        assertThat(result.contrastEvidence().status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(result.evidence()).contains(result.contrastEvidence());
        assertThat(result.skeleton().contrast().available()).isFalse();
    }

    @Test
    void measuresSourceRoundTripsAndBothDeterministicCompressionPasses() {
        EvidenceSourceRouter router = mock(EvidenceSourceRouter.class);
        when(router.collect(
                eq(WORKSPACE_ID), any(EvidenceRequest.class), eq(INCIDENT), eq((Set<String>) null)))
                .thenAnswer(invocation -> evidence(invocation.getArgument(1)));
        EvidenceSpineOrchestrator orchestrator = new EvidenceSpineOrchestrator(
                router,
                new DeterministicLogTraceCompressor(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new SequenceTicker(
                        0L, 5_000_000L,
                        10_000_000L, 17_000_000L,
                        20_000_000L, 22_000_000L,
                        30_000_000L, 41_000_000L,
                        50_000_000L, 53_000_000L));

        EvidenceSpineResult result = orchestrator.collect(
                WORKSPACE_ID, INCIDENT, plan(), null);

        assertThat(result.timings())
                .isEqualTo(new EvidenceSpineTimings(5L, 7L, 11L, 5L));
        assertThat(result.timings().evidenceAcquisitionDurationMs()).isEqualTo(23L);
    }

    private EvidenceSpinePlan plan() {
        return new EvidenceSpinePlan(
                "ONLINE-LOG-SEARCH",
                "ONLINE-TRACE-BUNDLE",
                "ONLINE-CONTRAST-SAMPLE",
                "message_send_failed",
                "-15m");
    }

    private EvidenceResult evidence(EvidenceRequest request) {
        Map<String, Object> observed = switch (request.signalKind()) {
            case "log_search" -> Map.of(
                    "match_count", 4,
                    "ps_id", "synthetic-ps-1",
                    "sample_message", "message send failed");
            case "log_trace_bundle" -> Map.of(
                    "ps_id", "synthetic-ps-1",
                    "entries", List.of(
                            entry(1_000, "session-api", "INFO", "accepted", 3),
                            entry(1_020, "session-domain", "ERROR", "state conflict", 18),
                            entry(1_040, "openim", "ERROR", "send rejected", 20)));
            case "contrast_sample" -> Map.of(
                    "discriminating_feature", "session_state_conflict",
                    "failure_sample_count", 100,
                    "failure_match_count", 92,
                    "success_sample_count", 100,
                    "success_match_count", 3);
            default -> throw new IllegalArgumentException(request.signalKind());
        };
        return new EvidenceResult(
                request.requestId(), "L", "safe query", EvidenceStatus.ANOMALY,
                "canonical evidence", observed, "recorded-replay", NOW);
    }

    private Map<String, Object> entry(
            long timestamp,
            String service,
            String level,
            String message,
            double durationMs) {
        return Map.of(
                "timestamp", timestamp,
                "service", service,
                "level", level,
                "message", message,
                "duration_ms", durationMs);
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
