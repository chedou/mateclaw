package vip.mate.troubleshooting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import vip.mate.skill.manifest.SkillManifest;
import vip.mate.troubleshooting.dto.SopRouteRequest;
import vip.mate.troubleshooting.evidence.CollectedEvidence;
import vip.mate.troubleshooting.evidence.EvidenceCollectionRequest;
import vip.mate.troubleshooting.evidence.ReleasePlatformConnector;
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

class ReleasePlatformConnectorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void disabledByDefaultSoMockConnectorCanFallback() {
        ReleasePlatformConnector connector = new ReleasePlatformConnector(new TroubleshootingEvidenceProperties(), objectMapper);

        assertFalse(connector.supports("release"));
    }

    @Test
    void collectsReleaseChangesFromConfiguredEndpointAndRedactsPreview() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = startServer(auth, body);
        try {
            TroubleshootingEvidenceProperties props = enabledProps(server);
            ReleasePlatformConnector connector = new ReleasePlatformConnector(props, objectMapper);

            List<CollectedEvidence> evidence = connector.collect(request());

            assertTrue(connector.supports("release"));
            assertEquals("Bearer release-secret", auth.get());
            assertTrue(body.get().contains("order-api"));
            assertTrue(body.get().contains("includeRollbackState"));
            assertEquals(1, evidence.size());
            assertEquals("collected", evidence.get(0).status());
            assertEquals("release-platform", evidence.get(0).source());
            String preview = evidence.get(0).content().get("responsePreview").toString();
            assertTrue(preview.contains("deploy"));
            assertFalse(preview.contains("deploy-secret"));
            assertFalse(preview.contains("b@example.com"));
            assertFalse(preview.contains("13900139000"));
            @SuppressWarnings("unchecked")
            Map<String, Object> normalized = (Map<String, Object>) evidence.get(0).content().get("normalized");
            assertEquals(2, normalized.get("changeCount"));
            assertEquals(true, normalized.get("rollbackAvailable"));
            assertTrue(normalized.get("changes").toString().contains("v20260520"));
            assertFalse(normalized.toString().contains("deploy-secret"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void canRenderConfiguredPayloadTemplateForInternalReleasePlatform() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = startServer(auth, body);
        try {
            TroubleshootingEvidenceProperties props = enabledProps(server);
            props.getReleasePlatform().setPayloadTemplate("""
                    {
                      "service":"${serviceName}",
                      "scope":"${env}/${cluster}/${namespace}",
                      "changeWindow":"${window}",
                      "maxResults":"${limit}",
                      "endpoint":"${label.endpoint}",
                      "event":"${eventId}"
                    }
                    """);
            ReleasePlatformConnector connector = new ReleasePlatformConnector(props, objectMapper);

            List<CollectedEvidence> evidence = connector.collect(request());

            assertEquals(1, evidence.size());
            assertEquals("collected", evidence.get(0).status());
            assertTrue(body.get().contains("\"service\":\"order-api\""));
            assertTrue(body.get().contains("\"scope\":\"prod/bwx-prod-k8s/default\""));
            assertTrue(body.get().contains("\"maxResults\":\"20\""));
            assertTrue(body.get().contains("\"endpoint\":\"/api/orders\""));
            assertFalse(body.get().contains("\"includeRollbackState\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failedRequestReturnsUnavailableEvidenceInsteadOfThrowing() {
        TroubleshootingEvidenceProperties props = new TroubleshootingEvidenceProperties();
        props.getReleasePlatform().setEnabled(true);
        props.getReleasePlatform().setBaseUrl("http://127.0.0.1:9");
        props.getReleasePlatform().setConnectTimeout(Duration.ofMillis(100));
        props.getReleasePlatform().setReadTimeout(Duration.ofMillis(100));
        ReleasePlatformConnector connector = new ReleasePlatformConnector(props, objectMapper);

        List<CollectedEvidence> evidence = connector.collect(request());

        assertEquals(1, evidence.size());
        assertEquals("unavailable", evidence.get(0).status());
        assertTrue(evidence.get(0).summary().contains("Release platform connector unavailable"));
    }

    @Test
    void invalidPayloadTemplateReturnsUnavailableEvidence() {
        TroubleshootingEvidenceProperties props = new TroubleshootingEvidenceProperties();
        props.getReleasePlatform().setEnabled(true);
        props.getReleasePlatform().setBaseUrl("http://127.0.0.1:9");
        props.getReleasePlatform().setPayloadTemplate("{\"service\":\"${serviceName}\"");
        ReleasePlatformConnector connector = new ReleasePlatformConnector(props, objectMapper);

        List<CollectedEvidence> evidence = connector.collect(request());

        assertEquals(1, evidence.size());
        assertEquals("unavailable", evidence.get(0).status());
        assertTrue(evidence.get(0).content().get("error").toString().contains("Invalid ReleasePlatform payload template JSON"));
    }

    private static HttpServer startServer(AtomicReference<String> auth,
                                          AtomicReference<String> body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/release/search", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {
                      "status":"ok",
                      "data":{
                        "total":2,
                        "changes":[
                          {"changeType":"deploy","serviceName":"order-api","version":"v20260520","operator":"b@example.com","rollbackAvailable":true,"secret":"deploy-secret","phone":"13900139000"},
                          {"changeType":"config","serviceName":"order-api","version":"feature-timeout-3000","operator":"platform-sre","rollbackAvailable":false}
                        ]
                      }
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
        props.getReleasePlatform().setEnabled(true);
        props.getReleasePlatform().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        props.getReleasePlatform().setQueryPath("/release/search");
        props.getReleasePlatform().setToken("release-secret");
        props.getReleasePlatform().setConnectTimeout(Duration.ofSeconds(1));
        props.getReleasePlatform().setReadTimeout(Duration.ofSeconds(1));
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
                "release"
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
