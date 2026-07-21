package vip.mate.troubleshooting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import vip.mate.skill.manifest.SkillManifest;
import vip.mate.troubleshooting.dto.SopRouteRequest;
import vip.mate.troubleshooting.evidence.CollectedEvidence;
import vip.mate.troubleshooting.evidence.EvidenceCollectionRequest;
import vip.mate.troubleshooting.evidence.GuanceSyntheticsConnector;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GuanceSyntheticsConnectorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void disabledByDefaultSoMockConnectorCanFallback() {
        GuanceSyntheticsConnector connector = new GuanceSyntheticsConnector(
                new TroubleshootingEvidenceProperties(),
                objectMapper
        );

        assertFalse(connector.supports("synthetics"));
        assertFalse(connector.supports("拨测"));
    }

    @Test
    void collectsSyntheticsFromConfiguredEndpointAndRedactsPreview() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = startServer(auth, body);
        try {
            TroubleshootingEvidenceProperties props = enabledProps(server);
            GuanceSyntheticsConnector connector = new GuanceSyntheticsConnector(props, objectMapper);

            List<CollectedEvidence> evidence = connector.collect(request("synthetics"));

            assertTrue(connector.supports("synthetics"));
            assertTrue(connector.supports("拨测"));
            assertEquals("Bearer guance-secret", auth.get());
            assertTrue(body.get().contains("order-api"));
            assertTrue(body.get().contains("includeRegions"));
            assertEquals(1, evidence.size());
            assertEquals("synthetics", evidence.get(0).evidenceType());
            assertEquals("collected", evidence.get(0).status());
            assertEquals("guance-synthetics", evidence.get(0).source());
            String preview = evidence.get(0).content().get("responsePreview").toString();
            assertTrue(preview.contains("timeout"));
            assertFalse(preview.contains("synthetic-secret"));
            assertFalse(preview.contains("ops@example.com"));
            assertFalse(preview.contains("13800138000"));
            @SuppressWarnings("unchecked")
            Map<String, Object> normalized = (Map<String, Object>) evidence.get(0).content().get("normalized");
            assertEquals(3, normalized.get("checkCount"));
            assertEquals(2L, normalized.get("failedCount"));
            assertEquals(33.33, normalized.get("successRate"));
            assertEquals(66.67, normalized.get("failureRate"));
            assertTrue(normalized.get("failedStatusCodes").toString().contains("503"));
            assertTrue(normalized.get("affectedRegions").toString().contains("shanghai"));
            assertTrue(normalized.get("affectedNodes").toString().contains("beijing"));
            assertTrue(normalized.get("failureReasons").toString().contains("timeout"));
            assertTrue(normalized.get("diagnosisSignals").toString().contains("失败率=66.67%"));
            assertTrue(normalized.get("availabilityConclusion").toString().contains("观测云拨测发现 2 条失败"));
            assertTrue(normalized.get("failedChecks").toString().contains("statusCode=503"));
            assertFalse(normalized.toString().contains("synthetic-secret"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void canRenderConfiguredPayloadTemplateForGuanceDialTestApi() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = startServer(auth, body);
        try {
            TroubleshootingEvidenceProperties props = enabledProps(server);
            props.getGuance().setPayloadTemplate("""
                    {
                      "query":"service=${serviceName} endpoint=${endpoint} ${keywords}",
                      "workspace":"${env}/${cluster}/${namespace}",
                      "limit":"${limit}",
                      "limitNumber":${limit},
                      "event":"${eventId}",
                      "labelEndpoint":"${label.endpoint}"
                    }
                    """);
            GuanceSyntheticsConnector connector = new GuanceSyntheticsConnector(props, objectMapper);

            List<CollectedEvidence> evidence = connector.collect(request("dialtest"));

            assertEquals(1, evidence.size());
            assertEquals("collected", evidence.get(0).status());
            assertTrue(body.get().contains("\"workspace\":\"prod/bwx-prod-k8s/default\""));
            assertTrue(body.get().contains("service=order-api"));
            assertTrue(body.get().contains("endpoint=/api/orders"));
            assertTrue(body.get().contains("\"limit\":\"20\""));
            assertTrue(body.get().contains("\"limitNumber\":20"));
            assertFalse(body.get().contains("\"includeRegions\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void canRenderGuanceOpenApiDqlPayloadForAvailabilityTaskName() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = startServer(auth, body);
        try {
            TroubleshootingEvidenceProperties props = enabledProps(server);
            props.getGuance().setSyntheticsPath("/api/v1/df/query_data_v1");
            props.getGuance().setTokenHeader("DF-API-KEY");
            props.getGuance().setTokenPrefix("");
            props.getGuance().setToken("df-api-key-for-test");
            props.getGuance().setPayloadTemplate("""
                    {
                      "queries": [
                        {
                          "qtype": "dql",
                          "query": {
                            "q": "${dqlQuery}",
                            "_funcList": [],
                            "funcList": [],
                            "maxPointCount": 720,
                            "interval": 10,
                            "align_time": true,
                            "sorder_by": [],
                            "slimit": ${limit},
                            "disable_sampling": false,
                            "timeRange": [],
                            "tz": "Asia/Shanghai"
                          }
                        }
                      ]
                    }
                    """);
            GuanceSyntheticsConnector connector = new GuanceSyntheticsConnector(props, objectMapper);

            List<CollectedEvidence> evidence = connector.collect(request("拨测"));

            assertEquals(1, evidence.size());
            assertEquals("collected", evidence.get(0).status());
            assertEquals("df-api-key-for-test", auth.get());
            assertTrue(body.get().contains("\"qtype\":\"dql\""));
            assertTrue(body.get().contains("D::http_dial_testing"));
            assertTrue(body.get().contains("`status_code`"));
            assertTrue(body.get().contains("`name` = '马来-国际CPQ-首页'"));
            assertTrue(body.get().contains("\"tz\":\"Asia/Shanghai\""));
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
            props.getGuance().setSyntheticsPath("/api/v1/df/query_data_v1");
            props.getGuance().setTokenHeader("DF-API-KEY");
            props.getGuance().setTokenPrefix("");
            props.getGuance().setToken("df-api-key-for-test");
            GuanceSyntheticsConnector connector = new GuanceSyntheticsConnector(props, objectMapper);

            List<CollectedEvidence> evidence = connector.collect(request("synthetics"));

            assertEquals(1, evidence.size());
            assertEquals("collected", evidence.get(0).status());
            assertEquals("df-api-key-for-test", auth.get());
            assertTrue(body.get().contains("\"qtype\":\"dql\""));
            assertTrue(body.get().contains("D::http_dial_testing"));
            assertTrue(body.get().contains("`name` = '马来-国际CPQ-首页'"));
            assertTrue(body.get().contains("\"slimit\":20"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void canSelectNamedPayloadTemplateAndRenderCustomDqlTemplateFromLabels() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = startServer(auth, body);
        try {
            TroubleshootingEvidenceProperties props = enabledProps(server);
            props.getGuance().setSyntheticsPath("/api/v1/df/query_data_v1");
            props.getGuance().setTokenHeader("DF-API-KEY");
            props.getGuance().setTokenPrefix("");
            props.getGuance().setToken("df-api-key-for-test");
            props.getGuance().getSyntheticsPayloadTemplates().put("host-dial", """
                    {
                      "queries": [
                        {
                          "qtype": "dql",
                          "query": {
                            "q": "${dqlQuery}",
                            "slimit": ${limit},
                            "tz": "Asia/Shanghai",
                            "selectedBy": "${payloadTemplateName}"
                          }
                        }
                      ]
                    }
                    """);
            GuanceSyntheticsConnector connector = new GuanceSyntheticsConnector(props, objectMapper);

            List<CollectedEvidence> evidence = connector.collect(request("synthetics", Map.of(
                    "syntheticsPayloadTemplateName", "host-dial",
                    "dqlQueryTemplate", "D::tcp_dial_testing:(`status`, `host`, `name`) { `host` = '${label.host}' AND `name` = '${syntheticsTaskName}' }",
                    "host", "cpq-edge-01",
                    "syntheticsTaskName", "马来-国际CPQ-首页"
            )));

            assertEquals(1, evidence.size());
            assertEquals("collected", evidence.get(0).status());
            assertEquals("df-api-key-for-test", auth.get());
            assertTrue(body.get().contains("\"selectedBy\":\"host-dial\""));
            assertTrue(body.get().contains("D::tcp_dial_testing"));
            assertTrue(body.get().contains("`host` = 'cpq-edge-01'"));
            assertTrue(body.get().contains("`name` = '马来-国际CPQ-首页'"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void canLoadNamedPayloadAndDqlTemplateFromDatabase() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = startServer(auth, body);
        try {
            TroubleshootingEvidenceProperties props = enabledProps(server);
            props.getGuance().setSyntheticsPath("/api/v1/df/query_data_v1");
            props.getGuance().setTokenHeader("DF-API-KEY");
            props.getGuance().setTokenPrefix("");
            props.getGuance().setToken("df-api-key-for-test");

            TroubleshootingQueryTemplateEntity template = new TroubleshootingQueryTemplateEntity();
            template.setId(901L);
            template.setTemplateKey("db-host-dial");
            template.setPayloadTemplate("""
                    {
                      "queries": [
                        {
                          "qtype": "dql",
                          "query": {
                            "q": "${dqlQuery}",
                            "templateKey": "${payloadTemplateName}",
                            "templateId": "${queryTemplateId}",
                            "templateSource": "${queryTemplateSource}"
                          }
                        }
                      ]
                    }
                    """);
            template.setDqlTemplate("D::tcp_dial_testing:(`status`, `host`, `name`) { `host` = '${label.host}' AND `name` = '${syntheticsTaskName}' }");
            TroubleshootingQueryTemplateService templateService = mock(TroubleshootingQueryTemplateService.class);
            when(templateService.resolveForAlert(
                    org.mockito.ArgumentMatchers.eq(1L),
                    org.mockito.ArgumentMatchers.eq("guance"),
                    org.mockito.ArgumentMatchers.eq("synthetics"),
                    org.mockito.ArgumentMatchers.eq("db-host-dial"),
                    org.mockito.ArgumentMatchers.any(SopRouteRequest.class)))
                    .thenReturn(Optional.of(template));
            GuanceSyntheticsConnector connector = new GuanceSyntheticsConnector(props, objectMapper, templateService);

            List<CollectedEvidence> evidence = connector.collect(request("synthetics", Map.of(
                    "syntheticsPayloadTemplateName", "db-host-dial",
                    "host", "cpq-edge-01",
                    "syntheticsTaskName", "马来-国际CPQ-首页"
            )));

            assertEquals(1, evidence.size());
            assertEquals("collected", evidence.get(0).status());
            assertEquals("df-api-key-for-test", auth.get());
            assertTrue(body.get().contains("\"templateKey\":\"db-host-dial\""));
            assertTrue(body.get().contains("\"templateId\":\"901\""));
            assertTrue(body.get().contains("\"templateSource\":\"database\""));
            assertTrue(body.get().contains("D::tcp_dial_testing"));
            assertTrue(body.get().contains("`host` = 'cpq-edge-01'"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void inlinePayloadTemplateLabelOverridesGlobalTemplate() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = startServer(auth, body);
        try {
            TroubleshootingEvidenceProperties props = enabledProps(server);
            props.getGuance().setPayloadTemplate("""
                    {"global":"should-not-be-used","q":"${dqlQuery}"}
                    """);
            GuanceSyntheticsConnector connector = new GuanceSyntheticsConnector(props, objectMapper);

            List<CollectedEvidence> evidence = connector.collect(request("synthetics", Map.of(
                    "syntheticsPayloadTemplate", "{\"inline\":\"used\",\"q\":\"${dqlQuery}\",\"task\":\"${syntheticsTaskName}\"}",
                    "syntheticsTaskName", "马来-国际CPQ-首页"
            )));

            assertEquals(1, evidence.size());
            assertEquals("collected", evidence.get(0).status());
            assertTrue(body.get().contains("\"inline\":\"used\""));
            assertTrue(body.get().contains("\"task\":\"马来-国际CPQ-首页\""));
            assertFalse(body.get().contains("should-not-be-used"));
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
        GuanceSyntheticsConnector connector = new GuanceSyntheticsConnector(props, objectMapper);

        List<CollectedEvidence> evidence = connector.collect(request("synthetics"));

        assertEquals(1, evidence.size());
        assertEquals("unavailable", evidence.get(0).status());
        assertTrue(evidence.get(0).summary().contains("Guance synthetics connector unavailable"));
    }

    @Test
    void invalidPayloadTemplateReturnsUnavailableEvidence() {
        TroubleshootingEvidenceProperties props = new TroubleshootingEvidenceProperties();
        props.getGuance().setEnabled(true);
        props.getGuance().setBaseUrl("http://127.0.0.1:9");
        props.getGuance().setPayloadTemplate("{\"query\":\"${serviceName}\"");
        GuanceSyntheticsConnector connector = new GuanceSyntheticsConnector(props, objectMapper);

        List<CollectedEvidence> evidence = connector.collect(request("synthetics"));

        assertEquals(1, evidence.size());
        assertEquals("unavailable", evidence.get(0).status());
        assertTrue(evidence.get(0).content().get("error").toString().contains("Invalid Guance synthetics payload template JSON"));
    }

    private static HttpServer startServer(AtomicReference<String> auth,
                                          AtomicReference<String> body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/guance/synthetics", exchange -> {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            String dfApiKey = exchange.getRequestHeaders().getFirst("DF-API-KEY");
            auth.set(authorization == null ? dfApiKey : authorization);
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {
                      "status":"ok",
                      "total":3,
                      "results":[
                        {"time":"2026-05-21T12:00:00+08:00","checkName":"order-api","url":"/api/orders","region":"shanghai","status":"failed","statusCode":503,"responseTime":3100,"failureReason":"timeout token=synthetic-secret","owner":"ops@example.com","phone":"13800138000"},
                        {"time":"2026-05-21T12:00:10+08:00","checkName":"order-api","url":"/api/orders","region":"beijing","success":false,"message":"http 503 timeout"},
                        {"time":"2026-05-21T12:00:20+08:00","checkName":"order-api","url":"/api/orders","region":"guangzhou","success":true,"statusCode":200,"responseTime":120}
                      ]
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/api/v1/df/query_data_v1", exchange -> {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            String dfApiKey = exchange.getRequestHeaders().getFirst("DF-API-KEY");
            auth.set(authorization == null ? dfApiKey : authorization);
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {
                      "status":"ok",
                      "content":[
                        {"time":"2026-05-22T10:00:00+08:00","name":"马来-国际CPQ-首页","url":"https://example.internal","status_code":200},
                        {"time":"2026-05-22T10:00:10+08:00","name":"马来-国际CPQ-首页","url":"https://example.internal","status_code":503,"message":"timeout"}
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
        props.getGuance().setSyntheticsPath("/guance/synthetics");
        props.getGuance().setToken("guance-secret");
        props.getGuance().setConnectTimeout(Duration.ofSeconds(1));
        props.getGuance().setReadTimeout(Duration.ofSeconds(1));
        return props;
    }

    private static EvidenceCollectionRequest request(String evidenceType) {
        return request(evidenceType, Map.of());
    }

    private static EvidenceCollectionRequest request(String evidenceType, Map<String, Object> labelsOverride) {
        TroubleshootingSopRunEntity run = new TroubleshootingSopRunEntity();
        run.setId(123L);
        run.setCaseId("case-1");
        return new EvidenceCollectionRequest(
                1L,
                "case-1",
                run,
                sop(),
                alert(labelsOverride),
                evidenceType
        );
    }

    private static SopRouteRequest alert() {
        return alert(Map.of());
    }

    private static SopRouteRequest alert(Map<String, Object> labelsOverride) {
        Map<String, Object> labels = new java.util.LinkedHashMap<>();
        labels.put("endpoint", "/api/orders");
        labels.put("syntheticsTaskName", "马来-国际CPQ-首页");
        labels.putAll(labelsOverride);
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
