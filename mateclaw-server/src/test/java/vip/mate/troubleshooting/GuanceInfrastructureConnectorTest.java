package vip.mate.troubleshooting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import vip.mate.skill.manifest.SkillManifest;
import vip.mate.troubleshooting.dto.SopRouteRequest;
import vip.mate.troubleshooting.evidence.CollectedEvidence;
import vip.mate.troubleshooting.evidence.EvidenceCollectionRequest;
import vip.mate.troubleshooting.evidence.GuanceInfrastructureConnector;
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

class GuanceInfrastructureConnectorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void disabledByDefaultSoMockConnectorCanFallback() {
        GuanceInfrastructureConnector connector = new GuanceInfrastructureConnector(
                new TroubleshootingEvidenceProperties(),
                objectMapper
        );

        assertFalse(connector.supports("host"));
        assertFalse(connector.supports("container"));
        assertFalse(connector.supports("k8s"));
    }

    @Test
    void supportsInfrastructureAliasesWhenEnabled() {
        TroubleshootingEvidenceProperties props = new TroubleshootingEvidenceProperties();
        props.getGuance().setEnabled(true);
        props.getGuance().setBaseUrl("http://127.0.0.1:9");
        GuanceInfrastructureConnector connector = new GuanceInfrastructureConnector(props, objectMapper);

        assertTrue(connector.supports("guance-host"));
        assertTrue(connector.supports("pods"));
        assertTrue(connector.supports("guance-container"));
        assertTrue(connector.supports("guance-k8s"));
    }

    @Test
    void collectsHostEvidenceWithDatabaseDqlTemplate() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = startServer(auth, body);
        try {
            TroubleshootingEvidenceProperties props = enabledProps(server);
            TroubleshootingQueryTemplateEntity template = template(
                    "guance-host-by-name",
                    "host",
                    "D::host:(`host`, `host_name`, `ip`, `cpu_usage`, `mem_used_percent`, `status`) { `host` = '${hostNameDql}' }"
            );
            TroubleshootingQueryTemplateService templateService = mock(TroubleshootingQueryTemplateService.class);
            when(templateService.resolveForAlert(eq(1L), eq("guance"), eq("host"), eq("guance-host-by-name"), any(SopRouteRequest.class)))
                    .thenReturn(Optional.of(template));
            GuanceInfrastructureConnector connector = new GuanceInfrastructureConnector(props, objectMapper, templateService);

            List<CollectedEvidence> evidence = connector.collect(request("host", Map.of(
                    "hostPayloadTemplateName", "guance-host-by-name",
                    "hostName", "cpq-node-01"
            )));

            assertEquals(1, evidence.size());
            assertEquals("host", evidence.get(0).evidenceType());
            assertEquals("collected", evidence.get(0).status());
            assertEquals("guance-infrastructure", evidence.get(0).source());
            assertEquals("df-api-key-for-test", auth.get());
            assertTrue(body.get().contains("\"qtype\":\"dql\""));
            assertTrue(body.get().contains("D::host"));
            assertTrue(body.get().contains("`host` = 'cpq-node-01'"));
            assertTrue(body.get().contains("\"templateKey\":\"guance-host-by-name\""));
            @SuppressWarnings("unchecked")
            Map<String, Object> normalized = (Map<String, Object>) evidence.get(0).content().get("normalized");
            assertEquals(2, normalized.get("recordCount"));
            assertTrue(normalized.get("objectNames").toString().contains("cpq-node-01"));
            assertEquals(1, normalized.get("abnormalCount"));
            assertTrue(normalized.get("abnormalStates").toString().contains("high_cpu"));
            assertTrue(normalized.get("resourcePressure").toString().contains("cpu_usage=91.2%"));
            assertTrue(normalized.get("infrastructureSignals").toString().contains("异常对象=1/2"));
            assertTrue(normalized.get("infrastructureConclusion").toString().contains("观测云主机发现 1 条异常记录"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void collectsContainerEvidenceWithInlineTemplate() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = startServer(auth, body);
        try {
            TroubleshootingEvidenceProperties props = enabledProps(server);
            GuanceInfrastructureConnector connector = new GuanceInfrastructureConnector(props, objectMapper);

            List<CollectedEvidence> evidence = connector.collect(request("container", Map.of(
                    "payloadTemplate", "{\"queries\":[{\"qtype\":\"dql\",\"query\":{\"q\":\"${dqlQuery}\",\"source\":\"inline\"}}]}",
                    "containerDqlTemplate", "D::container:(`container_name`, `pod_name`, `status`, `restart_count`) { `pod_name` = '${podNameDql}' }",
                    "pod", "cpq-web-7d6c"
            )));

            assertEquals(1, evidence.size());
            assertEquals("container", evidence.get(0).evidenceType());
            assertEquals("collected", evidence.get(0).status());
            assertEquals("df-api-key-for-test", auth.get());
            assertTrue(body.get().contains("\"source\":\"inline\""));
            assertTrue(body.get().contains("D::container"));
            assertTrue(body.get().contains("`pod_name` = 'cpq-web-7d6c'"));
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer startServer(AtomicReference<String> auth,
                                          AtomicReference<String> body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/df/query_data_v1", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("DF-API-KEY"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {
                      "status":"ok",
                      "content":[
                        {"time":"2026-05-26T10:00:00+08:00","host":"cpq-node-01","ip":"10.0.0.10","status":"high_cpu","cpu_usage":91.2},
                        {"time":"2026-05-26T10:00:10+08:00","host":"cpq-node-02","ip":"10.0.0.11","status":"running","cpu_usage":22.1}
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
        props.getGuance().setMetricsPath("/api/v1/df/query_data_v1");
        props.getGuance().setTokenHeader("DF-API-KEY");
        props.getGuance().setTokenPrefix("");
        props.getGuance().setToken("df-api-key-for-test");
        props.getGuance().setConnectTimeout(Duration.ofSeconds(1));
        props.getGuance().setReadTimeout(Duration.ofSeconds(1));
        return props;
    }

    private static TroubleshootingQueryTemplateEntity template(String key, String evidenceType, String dqlTemplate) {
        TroubleshootingQueryTemplateEntity template = new TroubleshootingQueryTemplateEntity();
        template.setId(902L);
        template.setProvider("guance");
        template.setEvidenceType(evidenceType);
        template.setTemplateKey(key);
        template.setPayloadTemplate("""
                {
                  "queries": [
                    {
                      "qtype": "dql",
                      "query": {
                        "q": "${dqlQuery}",
                        "templateKey": "${payloadTemplateName}",
                        "templateId": "${queryTemplateId}"
                      }
                    }
                  ]
                }
                """);
        template.setDqlTemplate(dqlTemplate);
        return template;
    }

    private static EvidenceCollectionRequest request(String evidenceType, Map<String, Object> labels) {
        TroubleshootingSopRunEntity run = new TroubleshootingSopRunEntity();
        run.setId(123L);
        run.setCaseId("case-1");
        return new EvidenceCollectionRequest(
                1L,
                "case-1",
                run,
                sop(),
                alert(labels),
                evidenceType
        );
    }

    private static SopRouteRequest alert(Map<String, Object> labels) {
        return new SopRouteRequest(
                "evt-1",
                "wecom",
                "P1",
                "Pod restart",
                "firing",
                "cpq-web",
                "prod",
                "bwx-prod-k8s",
                "default",
                "cpq-web-7d6c",
                "10.0.0.12",
                "/",
                "container_restart_count",
                "Pod restart and host cpu high",
                null,
                labels,
                3
        );
    }

    private static SopDefinition sop() {
        return new SopDefinition(
                1L,
                "release-k8s",
                null,
                "1.0.0",
                true,
                0L,
                "release_k8s",
                "post_deploy_pod_restart",
                SkillManifest.TroubleshootingMatch.builder().build(),
                List.of("release", "k8s"),
                List.of("metrics", "logs"),
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
