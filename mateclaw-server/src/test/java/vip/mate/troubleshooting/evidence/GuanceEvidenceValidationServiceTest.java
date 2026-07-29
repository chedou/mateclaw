package vip.mate.troubleshooting.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.mate.exception.MateClawException;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GuanceEvidenceValidationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-29T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void runsOneReadOnlyGuanceChainWithoutReplayFallbackOrRawEvidence() {
        SequenceTransport transport = new SequenceTransport(
                response("[4,\"ps-message-001\",\"message failed\"]",
                        "[\"count\",\"ps_id\",\"message\"]"),
                response("[\"ps-message-001\",1753775940000,\"gateway\",\"INFO\",\"accepted\",12],"
                                + "[\"ps-message-001\",1753775941000,\"session-svc\",\"ERROR\",\"send failed\",3010]",
                        "[\"ps_id\",\"time\",\"service\",\"status\",\"message\",\"duration_ms\"]"));
        Fixture fixture = fixture(transport, true);

        GuanceEvidenceValidationReport result = fixture.validation().validate(
                7L, "CSDP", "session-svc", "message_send_failed", "-15m", NOW);

        assertThat(result.stage())
                .isEqualTo(GuanceEvidenceValidationReport.Stage.CANONICAL_CHAIN_OBSERVED);
        assertThat(result.matchCount()).isEqualTo(4L);
        assertThat(result.psId()).isEqualTo("ps-message-001");
        assertThat(result.traceEntries()).isEqualTo(2);
        assertThat(result.steps())
                .extracting(GuanceEvidenceValidationReport.Step::status)
                .containsExactly(
                        GuanceEvidenceValidationReport.StepStatus.CANONICAL_RESULT_OBSERVED,
                        GuanceEvidenceValidationReport.StepStatus.CANONICAL_RESULT_OBSERVED);
        assertThat(result.readiness().status())
                .isEqualTo(GuanceEvidenceReadiness.Status.CANONICAL_SIGNALS_OBSERVED);
        assertThat(transport.calls.get()).isEqualTo(2);
        assertThat(result.toString())
                .doesNotContain(
                        "message failed",
                        "send failed",
                        "message_send_failed",
                        "-15m",
                        "runtime-secret",
                        "L::");
        assertThat(result.warnings())
                .anyMatch(value -> value.contains("不代表 T7 已验收"));
    }

    @Test
    void returnsATypedBlockedReportBeforeTransportWhenTheAssetIsUnauthorized() {
        SequenceTransport transport = new SequenceTransport();
        Fixture fixture = fixture(transport, false);

        GuanceEvidenceValidationReport result = fixture.validation().validate(
                7L, "CSDP", "session-svc", "message_send_failed", "-15m", NOW);

        assertThat(result.stage())
                .isEqualTo(GuanceEvidenceValidationReport.Stage.BLOCKED);
        assertThat(result.matchCount()).isNull();
        assertThat(result.psId()).isNull();
        assertThat(result.steps())
                .allMatch(step -> step.status()
                        == GuanceEvidenceValidationReport.StepStatus.NOT_RUN);
        assertThat(transport.calls.get()).isZero();
    }

    @Test
    void stopsAfterSearchWhenTraceDoesNotReturnTheSamePsId() {
        SequenceTransport transport = new SequenceTransport(
                response("[4,\"ps-message-001\",\"message failed\"]",
                        "[\"count\",\"ps_id\",\"message\"]"),
                response("[\"ps-other\",1753775941000,\"session-svc\",\"ERROR\",\"send failed\",3010]",
                        "[\"ps_id\",\"time\",\"service\",\"status\",\"message\",\"duration_ms\"]"));
        Fixture fixture = fixture(transport, true);

        GuanceEvidenceValidationReport result = fixture.validation().validate(
                7L, "CSDP", "session-svc", "message_send_failed", "-15m", NOW);

        assertThat(result.stage())
                .isEqualTo(GuanceEvidenceValidationReport.Stage.BLOCKED);
        assertThat(result.steps()).last()
                .satisfies(step -> {
                    assertThat(step.signalKind()).isEqualTo("log_trace_bundle");
                    assertThat(step.status())
                            .isEqualTo(GuanceEvidenceValidationReport.StepStatus.BLOCKED);
                });
        assertThat(result.toString()).doesNotContain("send failed");
    }

    @Test
    void rejectsAParseableButOverflowingWindowAsAnInvalidRequest() {
        Fixture fixture = fixture(new SequenceTransport(), true);

        assertThatThrownBy(() -> fixture.validation().validate(
                7L,
                "CSDP",
                "session-svc",
                "message_send_failed",
                "-9223372036854775807d",
                NOW))
                .isInstanceOfSatisfying(MateClawException.class, failure -> {
                    assertThat(failure.getCode()).isEqualTo(400);
                    assertThat(failure.getMessage()).contains("too large");
                });
    }

    @Test
    void rejectsAnOccurredAtOutsideTheSupportedEpochRange() {
        Fixture fixture = fixture(new SequenceTransport(), true);

        assertThatThrownBy(() -> fixture.validation().validate(
                7L,
                "CSDP",
                "session-svc",
                "message_send_failed",
                "-15m",
                Instant.MAX))
                .isInstanceOfSatisfying(MateClawException.class, failure -> {
                    assertThat(failure.getCode()).isEqualTo(400);
                    assertThat(failure.getMessage()).contains("occurredAt");
                });
    }

    @Test
    void neverFallsBackToRecordedReplayWhenGuanceReturnsNoCanonicalRows() {
        EvidenceSourceAdapter replay = mock(EvidenceSourceAdapter.class);
        when(replay.platform()).thenReturn("recorded-replay");
        when(replay.supports(any())).thenReturn(true);
        SequenceTransport transport = new SequenceTransport(
                response("", "[\"count\",\"ps_id\",\"message\"]"));
        Fixture fixture = fixture(transport, true, replay);

        GuanceEvidenceValidationReport result = fixture.validation().validate(
                7L, "CSDP", "session-svc", "message_send_failed", "-15m", NOW);

        assertThat(result.stage())
                .isEqualTo(GuanceEvidenceValidationReport.Stage.BLOCKED);
        assertThat(transport.calls.get()).isEqualTo(1);
        verify(replay, never()).collect(anyLong(), any(), any());
    }

    private Fixture fixture(
            SequenceTransport transport,
            boolean authorize,
            EvidenceSourceAdapter... additionalAdapters) {
        EvidenceProperties properties = new EvidenceProperties();
        properties.setRoutes(Map.of(
                "CSDP", Map.of(
                        "log_search", List.of("guance", "recorded-replay"),
                        "log_trace_bundle", List.of("guance", "recorded-replay"),
                        "contrast_sample", List.of("recorded-replay"),
                        "incident_impact", List.of("guance", "recorded-replay"))));
        EvidenceProperties.Guance config = new EvidenceProperties.Guance();
        config.setEnabled(true);
        config.setBaseUrl("https://guance.example");
        config.setApiKey("runtime-secret");
        config.setQueryPath("/api/v1/df/query_data_v1");
        config.setTimeout(Duration.ofSeconds(3));
        config.setBindings(Map.of(
                "search-binding", binding(
                        "L::logs:(count,ps_id,message) {service='{{service}}',message=~'{{search_term}}'} [{{window}}]",
                        Map.of("count", "match_count", "message", "sample_message"), 1),
                "trace-binding", binding(
                        "L::logs:(ps_id,time,service,status,message,duration_ms) {ps_id='{{ps_id}}'} [{{window}}]",
                        Map.of("time", "timestamp", "status", "level"), 200)));
        config.setAssetBindings(authorize ? List.of(assetBinding()) : List.of());
        properties.setGuance(config);

        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                config, new ObjectMapper(), transport, CLOCK);
        List<EvidenceSourceAdapter> adapters = new ArrayList<>();
        adapters.add(adapter);
        adapters.addAll(List.of(additionalAdapters));
        EvidenceSourceRouter router = new EvidenceSourceRouter(
                adapters, properties, CLOCK);
        GuanceEvidenceReadinessService readiness =
                new GuanceEvidenceReadinessService(properties, adapter);
        return new Fixture(
                new GuanceEvidenceValidationService(router, readiness, CLOCK), readiness);
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

    private EvidenceProperties.AssetBinding assetBinding() {
        EvidenceProperties.AssetBinding binding = new EvidenceProperties.AssetBinding();
        binding.setWorkspaceId(7L);
        binding.setSystem("CSDP");
        binding.setService("session-svc");
        binding.setSignalBindings(Map.of(
                "log_search", "search-binding",
                "log_trace_bundle", "trace-binding"));
        return binding;
    }

    private String response(String values, String columns) {
        return "{\"code\":200,\"success\":true,\"content\":{\"data\":[{\"series\":[{"
                + "\"columns\":" + columns + ",\"values\":[" + values + "]}]}]}}";
    }

    private record Fixture(
            GuanceEvidenceValidationService validation,
            GuanceEvidenceReadinessService readiness) {
    }

    private static final class SequenceTransport implements EvidenceHttpTransport {
        private final Deque<String> responses = new ArrayDeque<>();
        private final AtomicInteger calls = new AtomicInteger();

        private SequenceTransport(String... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public Response postJson(
                URI uri,
                Map<String, String> headers,
                String body,
                Duration timeout) {
            calls.incrementAndGet();
            if (responses.isEmpty()) {
                throw new AssertionError("unexpected Guance request");
            }
            return new Response(200, responses.removeFirst());
        }
    }
}
