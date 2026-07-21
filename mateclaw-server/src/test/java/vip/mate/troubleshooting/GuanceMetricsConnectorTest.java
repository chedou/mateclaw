package vip.mate.troubleshooting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import vip.mate.skill.manifest.SkillManifest;
import vip.mate.troubleshooting.dto.SopRouteRequest;
import vip.mate.troubleshooting.evidence.CollectedEvidence;
import vip.mate.troubleshooting.evidence.EvidenceCollectionRequest;
import vip.mate.troubleshooting.evidence.GuanceMetricsConnector;
import vip.mate.troubleshooting.evidence.TroubleshootingEvidenceProperties;
import vip.mate.troubleshooting.model.SopDefinition;
import vip.mate.troubleshooting.model.TroubleshootingQueryTemplateEntity;
import vip.mate.troubleshooting.model.TroubleshootingSopRunEntity;
import vip.mate.troubleshooting.service.TroubleshootingQueryTemplateService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GuanceMetricsConnectorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void disabledByDefaultSoMockConnectorCanFallback() {
        GuanceMetricsConnector connector = new GuanceMetricsConnector(
                new TroubleshootingEvidenceProperties(),
                objectMapper
        );

        assertFalse(connector.supports("metrics"));
    }

    @Test
    void collectsMetricsFromConfiguredEndpointAndRedactsPreview() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = startServer(auth, body);
        try {
            TroubleshootingEvidenceProperties props = enabledProps(server);
            GuanceMetricsConnector connector = new GuanceMetricsConnector(props, objectMapper);

            List<CollectedEvidence> evidence = connector.collect(request());

            assertTrue(connector.supports("metrics"));
            assertEquals("Bearer guance-secret", auth.get());
            assertTrue(body.get().contains("order-api"));
            assertTrue(body.get().contains("includeSeries"));
            assertTrue(body.get().contains("http_5xx_rate"));
            assertEquals(1, evidence.size());
            assertEquals("metrics", evidence.get(0).evidenceType());
            assertEquals("collected", evidence.get(0).status());
            assertEquals("guance-metrics", evidence.get(0).source());
            String preview = evidence.get(0).content().get("responsePreview").toString();
            assertTrue(preview.contains("http_5xx_rate"));
            assertFalse(preview.contains("metric-secret"));
            assertFalse(preview.contains("ops@example.com"));
            assertFalse(preview.contains("13800138000"));
            @SuppressWarnings("unchecked")
            Map<String, Object> normalized = (Map<String, Object>) evidence.get(0).content().get("normalized");
            assertEquals(3, normalized.get("seriesCount"));
            assertTrue(normalized.get("metricNames").toString().contains("http_5xx_rate"));
            assertTrue(normalized.get("anomalyHints").toString().contains("p95_latency"));
            assertFalse(normalized.toString().contains("metric-secret"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void canRenderConfiguredPayloadTemplateForGuanceMetricsApi() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = startServer(auth, body);
        try {
            TroubleshootingEvidenceProperties props = enabledProps(server);
            props.getGuance().setMetricsPayloadTemplate("""
                    {
                      "query":"service=${serviceName} metric=${metricName} ${keywords}",
                      "workspace":"${env}/${cluster}/${namespace}",
                      "limit":"${limit}",
                      "event":"${eventId}",
                      "endpoint":"${label.endpoint}"
                    }
                    """);
            GuanceMetricsConnector connector = new GuanceMetricsConnector(props, objectMapper);

            List<CollectedEvidence> evidence = connector.collect(request());

            assertEquals(1, evidence.size());
            assertEquals("collected", evidence.get(0).status());
            assertTrue(body.get().contains("\"workspace\":\"prod/bwx-prod-k8s/default\""));
            assertTrue(body.get().contains("service=order-api"));
            assertTrue(body.get().contains("metric=http_5xx_rate"));
            assertTrue(body.get().contains("\"limit\":\"50\""));
            assertFalse(body.get().contains("\"includeSeries\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void usesQueryDataDqlPayloadByDefaultWhenPathTargetsDfQueryData() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = startServer(auth, body);
        try {
            TroubleshootingEvidenceProperties props = enabledProps(server);
            props.getGuance().setMetricsPath("/api/v1/df/query_data_v1");
            GuanceMetricsConnector connector = new GuanceMetricsConnector(props, objectMapper);

            List<CollectedEvidence> evidence = connector.collect(request());

            assertEquals(1, evidence.size());
            assertEquals("collected", evidence.get(0).status());
            assertEquals("Bearer guance-secret", auth.get());
            assertTrue(body.get().contains("\"qtype\":\"dql\""));
            assertTrue(body.get().contains("M::`http_5xx_rate`"));
            assertTrue(body.get().contains("`service` = 'order-api'"));
            assertTrue(body.get().contains("\"slimit\":50"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void canLoadMetricsPayloadAndDqlTemplateFromDatabase() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = startServer(auth, body);
        try {
            TroubleshootingEvidenceProperties props = enabledProps(server);
            props.getGuance().setMetricsPath("/guance/metrics");
            TroubleshootingQueryTemplateEntity template = new TroubleshootingQueryTemplateEntity();
            template.setId(903L);
            template.setTemplateKey("guance-metrics-service");
            template.setPayloadTemplate("""
                    {
                      "queries": [
                        {
                          "qtype": "dql",
                          "query": {
                            "q": "${dqlQuery}",
                            "templateKey": "${payloadTemplateName}"
                          }
                        }
                      ]
                    }
                    """);
            template.setDqlTemplate("M::`${metricNameIdentifier}`:(*) { `service` = '${serviceNameDql}' }");
            TroubleshootingQueryTemplateService templateService = mock(TroubleshootingQueryTemplateService.class);
            when(templateService.resolveForAlert(eq(1L), eq("guance"), eq("metrics"), eq("guance-metrics-service"), any(SopRouteRequest.class)))
                    .thenReturn(Optional.of(template));
            GuanceMetricsConnector connector = new GuanceMetricsConnector(props, objectMapper, templateService);

            List<CollectedEvidence> evidence = connector.collect(request(Map.of(
                    "metricsPayloadTemplateName", "guance-metrics-service"
            )));

            assertEquals(1, evidence.size());
            assertEquals("collected", evidence.get(0).status());
            assertTrue(body.get().contains("\"qtype\":\"dql\""));
            assertTrue(body.get().contains("M::`http_5xx_rate`"));
            assertTrue(body.get().contains("`service` = 'order-api'"));
            assertTrue(body.get().contains("\"templateKey\":\"guance-metrics-service\""));
            assertEquals("Bearer guance-secret", auth.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failedRequestReturnsUnavailableEvidenceInsteadOfThrowing() {
        TroubleshootingEvidenceProperties props = new TroubleshootingEvidenceProperties();
        props.getGuance().setEnabled(true);
        props.getGuance().setBaseUrl("http://127.0.0.1:9");
        props.getGuance().setConnectTimeout(Duration.ofMillis(100));
        props.getGuance().setReadTimeout(Duration.ofMillis(100));
        GuanceMetricsConnector connector = new GuanceMetricsConnector(props, objectMapper);

        List<CollectedEvidence> evidence = connector.collect(request());

        assertEquals(1, evidence.size());
        assertEquals("unavailable", evidence.get(0).status());
        assertTrue(evidence.get(0).summary().contains("Guance metrics connector unavailable"));
    }

    @Test
    void invalidPayloadTemplateReturnsUnavailableEvidence() {
        TroubleshootingEvidenceProperties props = new TroubleshootingEvidenceProperties();
        props.getGuance().setEnabled(true);
        props.getGuance().setBaseUrl("http://127.0.0.1:9");
        props.getGuance().setMetricsPayloadTemplate("{\"query\":\"${serviceName}\"");
        GuanceMetricsConnector connector = new GuanceMetricsConnector(props, objectMapper);

        List<CollectedEvidence> evidence = connector.collect(request());

        assertEquals(1, evidence.size());
        assertEquals("unavailable", evidence.get(0).status());
        assertTrue(evidence.get(0).content().get("error").toString().contains("Invalid Guance metrics payload template JSON"));
    }

    private static HttpServer startServer(AtomicReference<String> auth,
                                          AtomicReference<String> body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/guance/metrics", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {
                      "status":"ok",
                      "total":3,
                      "series":[
                        {"time":"2026-05-21T12:00:00+08:00","metric":"http_5xx_rate","value":6.8,"status":"critical","serviceName":"order-api","owner":"ops@example.com","phone":"13800138000","token":"metric-secret"},
                        {"time":"2026-05-21T12:00:00+08:00","metric":"p95_latency","value":1900,"unit":"ms","status":"warning"},
                        {"time":"2026-05-21T12:00:00+08:00","metric":"request_rate","value":120,"unit":"rps","status":"ok"}
                      ]
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/api/v1/df/query_data_v1", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {
                      "status":"ok",
                      "content":[
                        {"time":"2026-05-21T12:00:00+08:00","metric":"http_5xx_rate","value":6.8,"status":"critical"},
                        {"time":"2026-05-21T12:00:10+08:00","metric":"p95_latency","value":1900,"status":"warning"}
                      ]
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static TroubleshootingEvidenceProperties enabledProps(HttpServer server) {
        TroubleshootingEvidenceProperties props = new TroubleshootingEvidenceProperties();
        props.getGuance().setEnabled(true);
        props.getGuance().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        props.getGuance().setMetricsPath("/guance/metrics");
        props.getGuance().setToken("guance-secret");
        props.getGuance().setConnectTimeout(Duration.ofSeconds(1));
        props.getGuance().setReadTimeout(Duration.ofSeconds(1));
        return props;
    }

    private static EvidenceCollectionRequest request() {
        return request(Map.of("endpoint", "/api/orders"));
    }

    private static EvidenceCollectionRequest request(Map<String, Object> labels) {
        TroubleshootingSopRunEntity run = new TroubleshootingSopRunEntity();
        run.setId(123L);
        run.setCaseId("case-1");
        return new EvidenceCollectionRequest(
                1L,
                "case-1",
                run,
                sop(),
                alert(labels),
                "metrics"
        );
    }

    private static SopRouteRequest alert(Map<String, Object> labels) {
        return new SopRouteRequest(
                "evt-1",
                "wecom",
                "P1",
                "API 5xx",
                "firing",
                "order-api",
                "prod",
                "bwx-prod-k8s",
                "default",
                "order-api-7d6c",
                "10.0.0.12",
                "/api/orders",
                "http_5xx_rate",
                "HTTP 503 timeout",
                null,
                labels,
                3
        );
    }

    private static SopDefinition sop() {
        return new SopDefinition(
                1L,
                "api-service-5xx",
                null,
                "1.0.0",
                true,
                0L,
                "api_service",
                "http_5xx_timeout",
                SkillManifest.TroubleshootingMatch.builder().build(),
                List.of("metrics", "logs", "release"),
                List.of("synthetics"),
                "sop-checklist-v1",
                "platform-sre",
                90,
                null,
                false,
                "",
                ""
        );
    }
}
