package vip.mate.troubleshooting;

import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.mate.skill.manifest.SkillManifest;
import vip.mate.troubleshooting.dto.SopRouteRequest;
import vip.mate.troubleshooting.evidence.CollectedEvidence;
import vip.mate.troubleshooting.evidence.EvidenceCollectionRequest;
import vip.mate.troubleshooting.evidence.LogSearchConnector;
import vip.mate.troubleshooting.evidence.TroubleshootingEvidenceProperties;
import vip.mate.troubleshooting.model.SopDefinition;
import vip.mate.troubleshooting.model.TroubleshootingSopRunEntity;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogSearchConnectorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void disabledByDefaultSoMockConnectorCanFallback() {
        LogSearchConnector connector = new LogSearchConnector(new TroubleshootingEvidenceProperties(), objectMapper);

        assertFalse(connector.supports("logs"));
    }

    @Test
    void collectsLogsFromConfiguredEndpointAndRedactsPreview() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = startServer(auth, body);
        try {
            TroubleshootingEvidenceProperties props = enabledProps(server);
            LogSearchConnector connector = new LogSearchConnector(props, objectMapper);

            List<CollectedEvidence> evidence = connector.collect(request());

            assertTrue(connector.supports("logs"));
            assertEquals("Bearer log-secret", auth.get());
            assertTrue(body.get().contains("order-api"));
            assertTrue(body.get().contains("timeout"));
            assertEquals(1, evidence.size());
            assertEquals("collected", evidence.get(0).status());
            assertEquals("logsearch-logs", evidence.get(0).source());
            String preview = evidence.get(0).content().get("responsePreview").toString();
            assertTrue(preview.contains("timeout"));
            assertFalse(preview.contains("raw-secret"));
            assertFalse(preview.contains("a@example.com"));
            assertFalse(preview.contains("13800138000"));
            @SuppressWarnings("unchecked")
            Map<String, Object> normalized = (Map<String, Object>) evidence.get(0).content().get("normalized");
            assertEquals(37, normalized.get("matchedCount"));
            assertTrue(normalized.get("topMessages").toString().contains("payment-service"));
            assertFalse(normalized.toString().contains("raw-secret"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void canRenderConfiguredPayloadTemplateForInternalLogPlatform() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = startServer(auth, body);
        try {
            TroubleshootingEvidenceProperties props = enabledProps(server);
            props.getLogSearch().setPayloadTemplate("""
                    {
                      "query":"service=${serviceName} endpoint=${endpoint} ${keywords}",
                      "scope":"${env}/${cluster}/${namespace}",
                      "limit":"${limit}",
                      "event":"${eventId}"
                    }
                    """);
            LogSearchConnector connector = new LogSearchConnector(props, objectMapper);

            List<CollectedEvidence> evidence = connector.collect(request());

            assertEquals(1, evidence.size());
            assertEquals("collected", evidence.get(0).status());
            assertTrue(body.get().contains("\"scope\":\"prod/bwx-prod-k8s/default\""));
            assertTrue(body.get().contains("service=order-api"));
            assertTrue(body.get().contains("endpoint=/api/orders"));
            assertTrue(body.get().contains("\"limit\":\"50\""));
            assertFalse(body.get().contains("\"fields\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failedRequestReturnsUnavailableEvidenceInsteadOfThrowing() {
        TroubleshootingEvidenceProperties props = new TroubleshootingEvidenceProperties();
        props.getLogSearch().setEnabled(true);
        props.getLogSearch().setBaseUrl("http://127.0.0.1:9");
        props.getLogSearch().setConnectTimeout(Duration.ofMillis(100));
        props.getLogSearch().setReadTimeout(Duration.ofMillis(100));
        LogSearchConnector connector = new LogSearchConnector(props, objectMapper);

        List<CollectedEvidence> evidence = connector.collect(request());

        assertEquals(1, evidence.size());
        assertEquals("unavailable", evidence.get(0).status());
        assertTrue(evidence.get(0).summary().contains("LogSearch connector unavailable"));
    }

    @Test
    void invalidPayloadTemplateReturnsUnavailableEvidence() {
        TroubleshootingEvidenceProperties props = new TroubleshootingEvidenceProperties();
        props.getLogSearch().setEnabled(true);
        props.getLogSearch().setBaseUrl("http://127.0.0.1:9");
        props.getLogSearch().setPayloadTemplate("{\"query\":\"${serviceName}\"");
        LogSearchConnector connector = new LogSearchConnector(props, objectMapper);

        List<CollectedEvidence> evidence = connector.collect(request());

        assertEquals(1, evidence.size());
        assertEquals("unavailable", evidence.get(0).status());
        assertTrue(evidence.get(0).content().get("error").toString().contains("Invalid LogSearch payload template JSON"));
    }

    private static HttpServer startServer(AtomicReference<String> auth,
                                          AtomicReference<String> body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/logs/search", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {
                      "status":"ok",
                      "total":37,
                      "logs":[
                        {"timestamp":"2026-05-20T15:00:00+08:00","level":"ERROR","message":"timeout calling payment-service token=raw-secret","traceId":"abc123","pod":"order-api-7d6c","owner":"a@example.com","phone":"13800138000"},
                        {"timestamp":"2026-05-20T15:00:03+08:00","level":"ERROR","message":"timeout calling payment-service after 3000 ms","traceId":"def456","pod":"order-api-7d6c"}
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
        props.getLogSearch().setEnabled(true);
        props.getLogSearch().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        props.getLogSearch().setQueryPath("/logs/search");
        props.getLogSearch().setToken("log-secret");
        props.getLogSearch().setConnectTimeout(Duration.ofSeconds(1));
        props.getLogSearch().setReadTimeout(Duration.ofSeconds(1));
        return props;
    }

    private static EvidenceCollectionRequest request() {
        TroubleshootingSopRunEntity run = new TroubleshootingSopRunEntity();
        run.setId(123L);
        run.setCaseId("case-1");
        return new EvidenceCollectionRequest(
                1L,
                "case-1",
                run,
                sop(),
                alert(),
                "logs"
        );
    }

    private static SopRouteRequest alert() {
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
                Map.of("endpoint", "/api/orders"),
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
                List.of(),
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
