package vip.mate.troubleshooting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import vip.mate.skill.manifest.SkillManifest;
import vip.mate.troubleshooting.dto.SopRouteRequest;
import vip.mate.troubleshooting.evidence.CollectedEvidence;
import vip.mate.troubleshooting.evidence.DataFluxMetricsConnector;
import vip.mate.troubleshooting.evidence.EvidenceCollectionRequest;
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

class DataFluxMetricsConnectorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void disabledByDefaultSoMockConnectorCanFallback() {
        DataFluxMetricsConnector connector = new DataFluxMetricsConnector(
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
            DataFluxMetricsConnector connector = new DataFluxMetricsConnector(props, objectMapper);

            List<CollectedEvidence> evidence = connector.collect(request());

            assertTrue(connector.supports("metrics"));
            assertEquals("Bearer dataflux-secret", auth.get());
            assertTrue(body.get().contains("order-api"));
            assertEquals(1, evidence.size());
            assertEquals("collected", evidence.get(0).status());
            assertEquals("dataflux-metrics", evidence.get(0).source());
            String preview = evidence.get(0).content().get("responsePreview").toString();
            assertTrue(preview.contains("ok"));
            assertFalse(preview.contains("raw-secret"));
            assertFalse(preview.contains("a@example.com"));
            assertFalse(preview.contains("13800138000"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failedRequestReturnsUnavailableEvidenceInsteadOfThrowing() {
        TroubleshootingEvidenceProperties props = new TroubleshootingEvidenceProperties();
        props.getDataflux().setEnabled(true);
        props.getDataflux().setBaseUrl("http://127.0.0.1:9");
        props.getDataflux().setConnectTimeout(Duration.ofMillis(100));
        props.getDataflux().setReadTimeout(Duration.ofMillis(100));
        DataFluxMetricsConnector connector = new DataFluxMetricsConnector(props, objectMapper);

        List<CollectedEvidence> evidence = connector.collect(request());

        assertEquals(1, evidence.size());
        assertEquals("unavailable", evidence.get(0).status());
        assertTrue(evidence.get(0).summary().contains("DataFlux metrics connector unavailable"));
    }

    private static HttpServer startServer(AtomicReference<String> auth,
                                          AtomicReference<String> body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/metrics/query", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {"status":"ok","token":"raw-secret","owner":"a@example.com","phone":"13800138000"}
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
        props.getDataflux().setEnabled(true);
        props.getDataflux().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        props.getDataflux().setQueryPath("/metrics/query");
        props.getDataflux().setToken("dataflux-secret");
        props.getDataflux().setConnectTimeout(Duration.ofSeconds(1));
        props.getDataflux().setReadTimeout(Duration.ofSeconds(1));
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
                "metrics"
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
                null,
                null,
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
