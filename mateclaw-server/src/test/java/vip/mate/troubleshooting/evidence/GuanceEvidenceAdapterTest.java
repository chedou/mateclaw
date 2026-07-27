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

    private static final Instant NOW = Instant.parse("2026-07-25T09:12:03Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ObjectMapper objectMapper = new ObjectMapper();

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

        EvidenceResult result = adapter.collect(request("-15m"), incident());

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
    void failsClosedOnAnHttpError() {
        CapturingTransport transport = new CapturingTransport(503, "upstream unavailable");
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                guanceConfig(), objectMapper, transport, CLOCK);

        EvidenceResult result = adapter.collect(request("-15m"), incident());

        assertThat(result.status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(result.source()).isEqualTo("guance:unavailable");
        assertThat(result.observed()).isEmpty();
    }

    @Test
    void rejectsUnsafeTemplateValuesWithoutSendingARequest() {
        CapturingTransport transport = new CapturingTransport(200, "{}");
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                guanceConfig(), objectMapper, transport, CLOCK);

        EvidenceResult result = adapter.collect(request("-15m"), incident("order-svc' OR true"));

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

        EvidenceResult result = adapter.collect(request("-15m"), incident());

        assertThat(result.status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(transport.calls.get()).isZero();

        config.setAllowInsecureHttp(true);
        CapturingTransport explicitlyAllowed = new CapturingTransport(200, "{}");
        new GuanceEvidenceAdapter(config, objectMapper, explicitlyAllowed, CLOCK)
                .collect(request("-15m"), incident());
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

            EvidenceResult result = adapter.collect(request("-15m"), incident());

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
        return config;
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
        return new IncidentContext(
                "inc-1", "CSDP", service, "903001", "订单创建超时",
                "P0", "订单创建成功率下降", "7f3a91c", NOW, "21:18",
                "alert_webhook", IncidentCompleteness.STRUCTURED, "code=903001");
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
}
