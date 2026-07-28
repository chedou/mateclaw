package vip.mate.troubleshooting.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class GuanceEvidenceAdapterTest {

    private static final long WORKSPACE_ID = 1L;
    private static final Instant NOW = Instant.parse("2026-07-25T09:12:03Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void refusesGlobalCredentialsWithoutAnExplicitWorkspaceAssetBinding() {
        CapturingTransport transport = new CapturingTransport(200, "{}");
        EvidenceProperties.Guance config = guanceConfig();
        config.setAssetBindings(List.of());
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                config, objectMapper, transport, CLOCK);

        EvidenceResult result = adapter.collect(WORKSPACE_ID, request("-15m"), incident());

        assertThat(result.status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(result.observed()).isEmpty();
        assertThat(transport.calls.get()).isZero();
        assertThat(adapter.health().status()).isEqualTo(EvidenceSourceHealth.Status.DEGRADED);
        assertThat(adapter.health().detail()).contains("authorization");
    }

    @Test
    void doesNotReadTheCredentialBeforeExactAssetAuthorization() {
        EvidenceProperties.Guance template = guanceConfig();
        CredentialGuardedGuance config = new CredentialGuardedGuance();
        config.setEnabled(true);
        config.setBaseUrl("https://guance.example");
        config.setBindings(template.getBindings());
        config.setAssetBindings(List.of());
        CapturingTransport transport = new CapturingTransport(200, "{}");
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                config, objectMapper, transport, CLOCK);

        assertThat(adapter.supports("log_count")).isFalse();
        EvidenceResult result = adapter.collect(WORKSPACE_ID, request("-15m"), incident());

        assertThat(result.status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(transport.calls.get()).isZero();
        assertThat(adapter.health().status()).isEqualTo(EvidenceSourceHealth.Status.DEGRADED);
        assertThat(adapter.health().detail()).contains("authorization");
    }

    @Test
    void resolvesAConcreteBindingOnlyForTheExactWorkspaceSystemServiceAndSignal() {
        CapturingTransport transport = new CapturingTransport(200, """
                {
                  "code": 200,
                  "success": true,
                  "content": {"data": [{"series": [{
                    "columns": ["time", "total", "trace"],
                    "values": [[1753434723000, 148, "7f3a91c"]]
                  }]}]}
                }
                """);
        EvidenceProperties.Guance config = guanceConfig();
        config.setBindings(Map.of(
                "csdp-order-log-count", config.getBindings().get("log_count")));
        config.setAssetBindings(List.of(assetBinding(
                WORKSPACE_ID,
                "CSDP",
                "order-svc",
                Map.of("log_count", "csdp-order-log-count"))));
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                config, objectMapper, transport, CLOCK);

        EvidenceResult result = adapter.collect(WORKSPACE_ID, request("-15m"), incident());

        assertThat(result.status()).isEqualTo(EvidenceStatus.NORMAL);
        assertThat(result.observed()).containsEntry("count", 148);
        assertThat(transport.calls.get()).isEqualTo(1);
    }

    @Test
    void failsClosedBeforeTransportForEveryUnauthorizedAssetScope() {
        CapturingTransport transport = new CapturingTransport(200, "{}");
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                guanceConfig(), objectMapper, transport, CLOCK);

        List<EvidenceResult> results = List.of(
                adapter.collect(2L, request("-15m"), incident()),
                adapter.collect(WORKSPACE_ID, request("-15m"), incident("ERP", "order-svc")),
                adapter.collect(WORKSPACE_ID, request("-15m"), incident("CSDP", "other-svc")),
                adapter.collect(WORKSPACE_ID, new EvidenceRequest(
                                "EV-2", "metric", "confirm", Map.of(), "-15m", true),
                        incident()));

        assertThat(results).allMatch(result -> result.status() == EvidenceStatus.MISSING);
        assertThat(results).allMatch(result -> result.observed().isEmpty());
        assertThat(transport.calls.get()).isZero();
    }

    @Test
    void failsClosedWhenNormalizedAssetBindingsAreAmbiguous() {
        CapturingTransport transport = new CapturingTransport(200, "{}");
        EvidenceProperties.Guance config = guanceConfig();
        config.setAssetBindings(List.of(
                assetBinding(WORKSPACE_ID, "CSDP", "order-svc",
                        Map.of("log_count", "log_count")),
                assetBinding(WORKSPACE_ID, " csdp ", " ORDER-SVC ",
                        Map.of("LOG_COUNT", "log_count"))));
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                config, objectMapper, transport, CLOCK);

        EvidenceResult result = adapter.collect(WORKSPACE_ID, request("-15m"), incident());

        assertThat(result.status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(transport.calls.get()).isZero();
    }

    @Test
    void failsClosedWhenSignalOrConcreteBindingNamesAreAmbiguous() {
        CapturingTransport transport = new CapturingTransport(200, "{}");
        EvidenceProperties.Guance ambiguousSignal = guanceConfig();
        ambiguousSignal.setAssetBindings(List.of(assetBinding(
                WORKSPACE_ID,
                "CSDP",
                "order-svc",
                Map.of("log_count", "log_count", " LOG_COUNT ", "log_count"))));

        EvidenceProperties.Guance ambiguousBinding = guanceConfig();
        EvidenceProperties.Binding binding = ambiguousBinding.getBindings().get("log_count");
        ambiguousBinding.setBindings(Map.of(
                "log_count", binding,
                " LOG_COUNT ", binding));

        EvidenceResult signalResult = new GuanceEvidenceAdapter(
                ambiguousSignal, objectMapper, transport, CLOCK)
                .collect(WORKSPACE_ID, request("-15m"), incident());
        EvidenceResult bindingResult = new GuanceEvidenceAdapter(
                ambiguousBinding, objectMapper, transport, CLOCK)
                .collect(WORKSPACE_ID, request("-15m"), incident());

        assertThat(signalResult.status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(bindingResult.status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(transport.calls.get()).isZero();
    }

    @Test
    void postsOfficialDqlShapeAndNormalizesTheLatestSeriesRow() throws Exception {
        CapturingTransport transport = new CapturingTransport(200, """
                {
                  "code": 200,
                  "success": true,
                  "content": {
                    "data": [{
                      "series": [{
                        "columns": ["time", "total", "trace"],
                        "values": [[1753434723000, 148, "7f3a91c"], [1753433823000, 12, "old"]]
                      }]
                    }]
                  }
                }
                """);
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                guanceConfig(), objectMapper, transport, CLOCK);

        EvidenceResult result = adapter.collect(WORKSPACE_ID, request("-15m"), incident());

        assertThat(result.status()).isEqualTo(EvidenceStatus.NORMAL);
        assertThat(result.namespace()).isEqualTo("L");
        assertThat(result.source()).isEqualTo("guance:log_count");
        assertThat(result.observed())
                .containsEntry("count", 148)
                .containsEntry("trace_id", "7f3a91c")
                .doesNotContainKey("time");
        assertThat(transport.uri).isEqualTo(
                URI.create("https://guance.example/api/v1/df/query_data_v1"));
        assertThat(transport.headers)
                .containsEntry("Content-Type", "application/json")
                .containsEntry("DF-API-KEY", "secret-key");
        assertThat(transport.timeout).isEqualTo(Duration.ofSeconds(3));

        JsonNode body = objectMapper.readTree(transport.body);
        JsonNode query = body.path("queries").path(0);
        assertThat(query.path("qtype").asText()).isEqualTo("dql");
        assertThat(query.path("query").path("q").asText())
                .isEqualTo("L::order-svc:(count,trace) {error_code='903001'} [-15m]");
        assertThat(query.path("query").path("timeRange").path(0).asLong())
                .isEqualTo(NOW.minus(Duration.ofMinutes(15)).toEpochMilli());
        assertThat(query.path("query").path("timeRange").path(1).asLong())
                .isEqualTo(NOW.toEpochMilli());
    }

    @Test
    void normalizesALogSearchSampleWithoutRequiringAnErrorCode() throws Exception {
        CapturingTransport transport = new CapturingTransport(200, """
                {
                  "code": 200,
                  "success": true,
                  "content": {
                    "data": [{
                      "series": [{
                        "columns": ["time", "total", "trace", "sample"],
                        "values": [[1753434723000, 4, "synthetic-ps-001", "message send failed"]]
                      }]
                    }]
                  }
                }
                """);
        EvidenceProperties.Binding binding = binding(
                "L",
                "场景关键词日志取样",
                "L::session-log:(count,ps_id,message) {service='{{service}}' AND "
                        + "(error_code='{{search_term}}' OR message=~'{{search_term}}')} [{{window}}]",
                Map.of(
                        "total", "match_count",
                        "trace", "ps_id",
                        "sample", "sample_message"),
                1);
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                guanceConfig("log_search", binding), objectMapper, transport, CLOCK);
        EvidenceRequest request = new EvidenceRequest(
                "EV-P6-1", "log_search", "sample logs",
                Map.of("search_term", "message_send_failed"), "-15m", true);

        EvidenceResult result = adapter.collect(WORKSPACE_ID, request, incidentWithoutErrorCode());

        assertThat(result.status()).isEqualTo(EvidenceStatus.NORMAL);
        assertThat(result.source()).isEqualTo("guance:log_search");
        assertThat(result.query()).isEmpty();
        assertThat(result.observed()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "match_count", 4,
                "ps_id", "synthetic-ps-001",
                "sample_message", "message send failed"));
        JsonNode query = objectMapper.readTree(transport.body)
                .path("queries").path(0).path("query");
        assertThat(query.path("limit").asInt()).isEqualTo(2);
        assertThat(query.path("q").asText())
                .contains("error_code='message_send_failed'")
                .contains("message=~'message_send_failed'");
    }

    @Test
    void normalizesAndChronologicallyOrdersABoundedLogTraceBundle() throws Exception {
        CapturingTransport transport = new CapturingTransport(200, """
                {
                  "code": 200,
                  "success": true,
                  "content": {
                    "data": [{
                      "series": [{
                        "columns": ["time", "trace", "svc", "severity", "content", "elapsed"],
                        "values": [
                          [1753434723042, "synthetic-ps-001", "session-state", "ERROR", "concurrent write rejected", 42],
                          [1753434723000, "synthetic-ps-001", "session-api", "INFO", "message accepted", null]
                        ]
                      }]
                    }]
                  }
                }
                """);
        EvidenceProperties.Binding binding = binding(
                "L",
                "PS ID 全链路日志包",
                "L::session-log:(ps_id,service,status,message,duration_ms) {ps_id='{{ps_id}}'} [{{window}}]",
                Map.of(
                        "time", "timestamp",
                        "trace", "ps_id",
                        "svc", "service",
                        "severity", "level",
                        "content", "message",
                        "elapsed", "duration_ms"),
                2);
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                guanceConfig("log_trace_bundle", binding), objectMapper, transport, CLOCK);
        EvidenceRequest request = new EvidenceRequest(
                "EV-P6-2", "log_trace_bundle", "collect trace logs",
                Map.of("ps_id", "synthetic-ps-001"), "-15m", true);

        EvidenceResult result = adapter.collect(WORKSPACE_ID, request, incidentWithoutErrorCode());

        assertThat(result.status()).isEqualTo(EvidenceStatus.NORMAL);
        assertThat(result.source()).isEqualTo("guance:log_trace_bundle");
        assertThat(result.query()).isEmpty();
        assertThat(result.observed()).containsEntry("ps_id", "synthetic-ps-001");
        assertThat(result.observed().get("entries")).isEqualTo(List.of(
                Map.of(
                        "timestamp", 1753434723000L,
                        "service", "session-api",
                        "level", "INFO",
                        "message", "message accepted"),
                Map.of(
                        "timestamp", 1753434723042L,
                        "service", "session-state",
                        "level", "ERROR",
                        "message", "concurrent write rejected",
                        "duration_ms", 42)));
        JsonNode query = objectMapper.readTree(transport.body)
                .path("queries").path(0).path("query");
        assertThat(query.path("limit").asInt()).isEqualTo(3);
    }

    @Test
    void rejectsALogTraceBundleForADifferentPsId() {
        CapturingTransport transport = new CapturingTransport(200, """
                {
                  "code": 200,
                  "success": true,
                  "content": {"data": [{"series": [{
                    "columns": ["time", "trace", "svc", "severity", "content"],
                    "values": [[1, "different-ps", "session-api", "ERROR", "wrong request"]]
                  }]}]}
                }
                """);
        EvidenceProperties.Binding binding = binding(
                "L",
                "PS ID 全链路日志包",
                "L::session-log:(ps_id,service,status,message) {ps_id='{{ps_id}}'} [{{window}}]",
                Map.of(
                        "time", "timestamp",
                        "trace", "ps_id",
                        "svc", "service",
                        "severity", "level",
                        "content", "message"),
                2);
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                guanceConfig("log_trace_bundle", binding), objectMapper, transport, CLOCK);
        EvidenceRequest request = new EvidenceRequest(
                "EV-P6-2", "log_trace_bundle", "collect trace logs",
                Map.of("ps_id", "synthetic-ps-001"), "-15m", true);

        EvidenceResult result = adapter.collect(WORKSPACE_ID, request, incidentWithoutErrorCode());

        assertThat(result.status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(result.observed()).isEmpty();
    }

    @Test
    void failsClosedWhenALogTraceBundleExceedsItsConfiguredBound() {
        CapturingTransport transport = new CapturingTransport(200, """
                {
                  "code": 200,
                  "success": true,
                  "content": {"data": [{"series": [{
                    "columns": ["time", "trace", "svc", "severity", "content"],
                    "values": [
                      [1, "synthetic-ps-001", "one", "INFO", "one"],
                      [2, "synthetic-ps-001", "two", "INFO", "two"],
                      [3, "synthetic-ps-001", "three", "ERROR", "three"]
                    ]
                  }]}]}
                }
                """);
        EvidenceProperties.Binding binding = binding(
                "L",
                "PS ID 全链路日志包",
                "L::session-log:(ps_id,service,status,message) {ps_id='{{ps_id}}'} [{{window}}]",
                Map.of(
                        "time", "timestamp",
                        "trace", "ps_id",
                        "svc", "service",
                        "severity", "level",
                        "content", "message"),
                2);
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                guanceConfig("log_trace_bundle", binding), objectMapper, transport, CLOCK);
        EvidenceRequest request = new EvidenceRequest(
                "EV-P6-2", "log_trace_bundle", "collect trace logs",
                Map.of("ps_id", "synthetic-ps-001"), "-15m", true);

        EvidenceResult result = adapter.collect(WORKSPACE_ID, request, incidentWithoutErrorCode());

        assertThat(result.status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(result.observed()).isEmpty();
    }

    @Test
    void failsClosedOnAnHttpError() {
        CapturingTransport transport = new CapturingTransport(503, "upstream unavailable");
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                guanceConfig(), objectMapper, transport, CLOCK);

        EvidenceResult result = adapter.collect(WORKSPACE_ID, request("-15m"), incident());

        assertThat(result.status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(result.source()).isEqualTo("guance:unavailable");
        assertThat(result.observed()).isEmpty();
    }

    @Test
    void rejectsUnsafeTemplateValuesWithoutSendingARequest() {
        CapturingTransport transport = new CapturingTransport(200, "{}");
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                guanceConfig(), objectMapper, transport, CLOCK);

        EvidenceResult result = adapter.collect(
                WORKSPACE_ID, request("-15m"), incident("order-svc' OR true"));

        assertThat(result.status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(transport.calls.get()).isZero();
    }

    @Test
    void refusesToSendTheApiKeyOverPlainHttpByDefault() {
        CapturingTransport transport = new CapturingTransport(200, "{}");
        EvidenceProperties.Guance config = guanceConfig();
        config.setBaseUrl("http://guance.example");
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                config, objectMapper, transport, CLOCK);

        EvidenceResult result = adapter.collect(WORKSPACE_ID, request("-15m"), incident());

        assertThat(result.status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(transport.calls.get()).isZero();

        config.setAllowInsecureHttp(true);
        CapturingTransport explicitlyAllowed = new CapturingTransport(200, "{}");
        new GuanceEvidenceAdapter(config, objectMapper, explicitlyAllowed, CLOCK)
                .collect(WORKSPACE_ID, request("-15m"), incident());
        assertThat(explicitlyAllowed.calls.get()).isEqualTo(1);
    }

    @Test
    void failsClosedOnMissingWrongTypeOrAmbiguousCanonicalRows() {
        List<String> malformedResponses = List.of(
                """
                {"code":200,"success":true,"content":{"data":[{"series":[{
                  "columns":["time","total"],"values":[[1753434723000,148]]
                }]}]}}
                """,
                """
                {"code":200,"success":true,"content":{"data":[{"series":[{
                  "columns":["time","total","trace"],"values":[[1753434723000,"148","7f3a91c"]]
                }]}]}}
                """,
                """
                {"code":200,"success":true,"content":{"data":[{"series":[
                  {"columns":["time","total","trace"],"values":[[1753434723000,148,"7f3a91c"]]},
                  {"columns":["time","total","trace"],"values":[[1753434723000,149,"other"]]}
                ]}]}}
                """,
                """
                {"code":200,"success":true,"content":{"data":[{"series":[{
                  "columns":["time","total","trace"],
                  "values":[[1753434723000,148,"7f3a91c"],[1753434723000,149,"other"]]
                }]}]}}
                """);

        for (String response : malformedResponses) {
            GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                    guanceConfig(), objectMapper, new CapturingTransport(200, response), CLOCK);

            EvidenceResult result = adapter.collect(WORKSPACE_ID, request("-15m"), incident());

            assertThat(result.status()).isEqualTo(EvidenceStatus.MISSING);
            assertThat(result.observed()).isEmpty();
        }
    }

    @Test
    void reportsConfiguredButUnverifiedHealthHonestly() {
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                guanceConfig(), objectMapper, new CapturingTransport(200, "{}"), CLOCK);

        assertThat(adapter.health().status()).isEqualTo(EvidenceSourceHealth.Status.DEGRADED);
        assertThat(adapter.health().verified()).isFalse();
        assertThat(adapter.health().detail()).contains("not live-verified");
    }

    private EvidenceProperties.Guance guanceConfig() {
        EvidenceProperties.Guance config = new EvidenceProperties.Guance();
        config.setEnabled(true);
        config.setBaseUrl("https://guance.example");
        config.setApiKey("secret-key");
        config.setQueryPath("/api/v1/df/query_data_v1");
        config.setTimeout(Duration.ofSeconds(3));

        EvidenceProperties.Binding binding = new EvidenceProperties.Binding();
        binding.setNamespace("L");
        binding.setSummary("错误码日志计数");
        binding.setQueryTemplate(
                "L::{{service}}:(count,trace) {error_code='{{error_code}}'} [{{window}}]");
        binding.setFieldAliases(Map.of("total", "count", "trace", "trace_id"));
        config.setBindings(Map.of("log_count", binding));
        config.setAssetBindings(List.of(assetBinding(
                WORKSPACE_ID,
                "CSDP",
                "order-svc",
                Map.of("log_count", "log_count"))));
        return config;
    }

    private EvidenceProperties.Guance guanceConfig(
            String signalKind,
            EvidenceProperties.Binding binding) {
        EvidenceProperties.Guance config = new EvidenceProperties.Guance();
        config.setEnabled(true);
        config.setBaseUrl("https://guance.example");
        config.setApiKey("secret-key");
        config.setQueryPath("/api/v1/df/query_data_v1");
        config.setTimeout(Duration.ofSeconds(3));
        config.setBindings(Map.of(signalKind, binding));
        config.setAssetBindings(List.of(assetBinding(
                WORKSPACE_ID,
                "CSDP",
                "csdp-session-service",
                Map.of(signalKind, signalKind))));
        return config;
    }

    private EvidenceProperties.Binding binding(
            String namespace,
            String summary,
            String queryTemplate,
            Map<String, String> aliases,
            int maxRows) {
        EvidenceProperties.Binding binding = new EvidenceProperties.Binding();
        binding.setNamespace(namespace);
        binding.setSummary(summary);
        binding.setQueryTemplate(queryTemplate);
        binding.setFieldAliases(aliases);
        binding.setMaxRows(maxRows);
        return binding;
    }

    private EvidenceProperties.AssetBinding assetBinding(
            long workspaceId,
            String system,
            String service,
            Map<String, String> signalBindings) {
        EvidenceProperties.AssetBinding binding = new EvidenceProperties.AssetBinding();
        binding.setWorkspaceId(workspaceId);
        binding.setSystem(system);
        binding.setService(service);
        binding.setSignalBindings(signalBindings);
        return binding;
    }

    private EvidenceRequest request(String window) {
        return new EvidenceRequest(
                "EV-1", "log_count", "confirm",
                Map.of("service", "override-attempt", "error_code", "999999"), window, true);
    }

    private IncidentContext incident() {
        return incident("order-svc");
    }

    private IncidentContext incident(String service) {
        return incident("CSDP", service);
    }

    private IncidentContext incident(String system, String service) {
        return new IncidentContext(
                "inc-1", system, service, "903001", "订单创建超时",
                "P0", "订单创建成功率下降", "7f3a91c", NOW, "21:18",
                "alert_webhook", IncidentCompleteness.STRUCTURED, "code=903001");
    }

    private IncidentContext incidentWithoutErrorCode() {
        return new IncidentContext(
                "inc-p6", "CSDP", "csdp-session-service", null, "会话消息发送失败",
                "P1", "会话消息发送受阻", null, NOW, "18:00",
                "manual", IncidentCompleteness.SYMPTOM, "客户发送消息失败");
    }

    private static final class CapturingTransport implements EvidenceHttpTransport {
        private final int statusCode;
        private final String responseBody;
        private final AtomicInteger calls = new AtomicInteger();
        private URI uri;
        private Map<String, String> headers;
        private String body;
        private Duration timeout;

        private CapturingTransport(int statusCode, String responseBody) {
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }

        @Override
        public Response postJson(
                URI uri,
                Map<String, String> headers,
                String body,
                Duration timeout) {
            calls.incrementAndGet();
            this.uri = uri;
            this.headers = headers;
            this.body = body;
            this.timeout = timeout;
            return new Response(statusCode, responseBody);
        }
    }

    private static final class CredentialGuardedGuance extends EvidenceProperties.Guance {
        @Override
        public String getApiKey() {
            throw new AssertionError("credential must not be read before asset authorization");
        }
    }
}
