package vip.mate.troubleshooting.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.synthesis.DeterministicLogTraceCompressor;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class GuanceEvidenceSpinePreviewFlowTest {

    private static final Instant NOW = Instant.parse("2026-07-29T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void runsTheSharedThreeStageSpineAgainstGuanceWithoutReplayFallback() {
        CapturingTransport transport = new CapturingTransport(
                response(
                        "[4,\"ps-message-001\",\"message failed\"]",
                        "[\"count\",\"ps_id\",\"message\"]"),
                response(
                        "[\"ps-message-001\",1000,\"gateway\",\"INFO\",\"accepted\",12],"
                                + "[\"ps-message-001\",1042,\"session-svc\",\"ERROR\",\"send failed\",42]",
                        "[\"ps_id\",\"time\",\"service\",\"status\",\"message\",\"duration_ms\"]"),
                response(
                        "[\"session_state_conflict\",100,92,100,3]",
                        "[\"discriminating_feature\",\"failure_sample_count\","
                                + "\"failure_match_count\",\"success_sample_count\","
                                + "\"success_match_count\"]"));
        EvidenceProperties properties = properties();
        GuanceEvidenceAdapter guance = new GuanceEvidenceAdapter(
                properties.getGuance(), new ObjectMapper(), transport, CLOCK);
        CountingReplayAdapter replay = new CountingReplayAdapter();
        EvidenceSourceRouter router = new EvidenceSourceRouter(
                List.of(guance, replay), properties, CLOCK);
        GuanceEvidenceReadinessService readiness =
                new GuanceEvidenceReadinessService(properties, guance);
        EvidenceSpineOrchestrator orchestrator = new EvidenceSpineOrchestrator(
                router, new DeterministicLogTraceCompressor(), CLOCK);
        GuanceEvidenceSpinePreviewService service = new GuanceEvidenceSpinePreviewService(
                orchestrator, readiness, CLOCK, new SequenceTicker(0L, 75_000_000L));

        GuanceEvidenceSpinePreview preview = service.preview(
                7L, "CSDP", "session-svc", "message_send_failed", "-15m", NOW);

        assertThat(preview.stage())
                .isEqualTo(GuanceEvidenceSpinePreview.Stage.FULL_SPINE_OBSERVED);
        assertThat(preview.serviceSequence()).containsExactly("gateway", "session-svc");
        assertThat(preview.contrast().failureMatchCount()).isEqualTo(92L);
        assertThat(preview.contrast().successMatchCount()).isEqualTo(3L);
        assertThat(preview.totalDurationMs()).isEqualTo(75L);
        assertThat(transport.calls.get()).isEqualTo(3);
        assertThat(replay.calls.get()).isZero();
        assertThat(transport.bodies.get(1)).contains("ps-message-001");
        assertThat(transport.bodies.get(2))
                .contains("message_send_failed", "ps-message-001");
        assertThat(preview.toString()).doesNotContain(
                "message failed", "send failed", "L::logs", "runtime-secret");
    }

    private EvidenceProperties properties() {
        EvidenceProperties properties = new EvidenceProperties();
        properties.setRoutes(Map.of(
                "CSDP", Map.of(
                        "log_search", List.of("guance", "recorded-replay"),
                        "log_trace_bundle", List.of("guance", "recorded-replay"),
                        "contrast_sample", List.of("guance", "recorded-replay"))));

        EvidenceProperties.Guance config = new EvidenceProperties.Guance();
        config.setEnabled(true);
        config.setBaseUrl("https://guance.example");
        config.setApiKey("runtime-secret");
        config.setQueryPath("/api/v1/df/query_data_v1");
        config.setBindings(Map.of(
                "search-binding", binding(
                        "L::logs:(count,ps_id,message) {service='{{service}}',message=~'{{search_term}}'} [{{window}}]",
                        Map.of("count", "match_count", "message", "sample_message"),
                        1),
                "trace-binding", binding(
                        "L::logs:(ps_id,time,service,status,message,duration_ms) {ps_id='{{ps_id}}'} [{{window}}]",
                        Map.of("time", "timestamp", "status", "level"),
                        200),
                "contrast-binding", binding(
                        "L::logs:(discriminating_feature,failure_sample_count,failure_match_count,"
                                + "success_sample_count,success_match_count) "
                                + "{scenario='{{scenario_key}}',ps_id!='{{exclude_ps_id}}'} [{{window}}]",
                        Map.of(),
                        1)));
        EvidenceProperties.AssetBinding asset = new EvidenceProperties.AssetBinding();
        asset.setWorkspaceId(7L);
        asset.setSystem("CSDP");
        asset.setService("session-svc");
        asset.setSignalBindings(Map.of(
                "log_search", "search-binding",
                "log_trace_bundle", "trace-binding",
                "contrast_sample", "contrast-binding"));
        config.setAssetBindings(List.of(asset));
        properties.setGuance(config);
        return properties;
    }

    private EvidenceProperties.Binding binding(
            String query,
            Map<String, String> aliases,
            int maxRows) {
        EvidenceProperties.Binding binding = new EvidenceProperties.Binding();
        binding.setNamespace("L");
        binding.setSummary("configured binding");
        binding.setQueryTemplate(query);
        binding.setFieldAliases(aliases);
        binding.setMaxRows(maxRows);
        return binding;
    }

    private String response(String values, String columns) {
        return "{\"code\":200,\"success\":true,\"content\":{\"data\":[{\"series\":[{"
                + "\"columns\":" + columns + ",\"values\":[" + values + "]}]}]}}";
    }

    private static final class CapturingTransport implements EvidenceHttpTransport {
        private final Deque<String> responses = new ArrayDeque<>();
        private final AtomicInteger calls = new AtomicInteger();
        private final List<String> bodies = new ArrayList<>();

        private CapturingTransport(String... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public Response postJson(
                URI uri,
                Map<String, String> headers,
                String body,
                Duration timeout) {
            calls.incrementAndGet();
            bodies.add(body);
            return new Response(200, responses.removeFirst());
        }
    }

    private static final class CountingReplayAdapter implements EvidenceSourceAdapter {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String platform() {
            return "recorded-replay";
        }

        @Override
        public boolean supports(String signalKind) {
            return true;
        }

        @Override
        public EvidenceResult collect(
                long workspaceId,
                vip.mate.troubleshooting.model.EvidenceRequest request,
                IncidentContext incident) {
            calls.incrementAndGet();
            throw new AssertionError("Guance-only preview must not call Replay");
        }

        @Override
        public EvidenceSourceHealth health() {
            return new EvidenceSourceHealth(
                    "recorded-replay", EvidenceSourceHealth.Status.READY, true, "fixture");
        }
    }

    private static final class SequenceTicker implements java.util.function.LongSupplier {
        private final Deque<Long> values = new ArrayDeque<>();

        private SequenceTicker(Long... values) {
            this.values.addAll(List.of(values));
        }

        @Override
        public long getAsLong() {
            return values.removeFirst();
        }
    }
}
