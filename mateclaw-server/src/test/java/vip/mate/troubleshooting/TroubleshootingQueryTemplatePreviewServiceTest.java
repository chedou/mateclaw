package vip.mate.troubleshooting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.dto.SopRouteRequest;
import vip.mate.troubleshooting.dto.TroubleshootingQueryTemplatePreviewRequest;
import vip.mate.troubleshooting.dto.TroubleshootingQueryTemplatePreviewResponse;
import vip.mate.troubleshooting.dto.TroubleshootingQueryTemplateRequest;
import vip.mate.troubleshooting.evidence.GuanceSyntheticsConnector;
import vip.mate.troubleshooting.evidence.TroubleshootingEvidenceProperties;
import vip.mate.troubleshooting.service.TroubleshootingQueryTemplatePreviewService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TroubleshootingQueryTemplatePreviewServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void previewsGuanceSyntheticsTemplateWithCurrentAlertLabels() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = startServer(auth, body);
        try {
            TroubleshootingEvidenceProperties props = enabledGuanceProps(server);
            GuanceSyntheticsConnector connector = new GuanceSyntheticsConnector(props, objectMapper);
            TroubleshootingQueryTemplatePreviewService service = new TroubleshootingQueryTemplatePreviewService(List.of(connector));

            TroubleshootingQueryTemplatePreviewResponse response = service.preview(
                    1L,
                    new TroubleshootingQueryTemplatePreviewRequest(template(), alert())
            );

            assertEquals("collected", response.status());
            assertEquals("guance", response.provider());
            assertEquals("synthetics", response.evidenceType());
            assertEquals("guance-http-dial-by-name", response.templateKey());
            assertEquals("df-api-key-for-test", auth.get());
            assertTrue(body.get().contains("\"qtype\":\"dql\""));
            assertTrue(body.get().contains("D::http_dial_testing"));
            assertTrue(body.get().contains("`name` = '马来-国际CPQ-首页'"));
            assertTrue(body.get().contains("\"slimit\":20"));
            assertTrue(response.endpoint().endsWith("/api/v1/df/query_data_v1"));
            assertNotNull(response.request());
            assertEquals(2, response.normalized().get("checkCount"));
            assertEquals(1L, response.normalized().get("failedCount"));
            assertEquals(50.0, response.normalized().get("successRate"));
            assertTrue(response.normalized().get("failedStatusCodes").toString().contains("503"));
            assertTrue(response.normalized().get("availabilityConclusion").toString().contains("观测云拨测发现 1 条失败"));
            assertTrue(String.valueOf(response.responsePreview()).contains("timeout"));
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

    private static TroubleshootingEvidenceProperties enabledGuanceProps(HttpServer server) {
        TroubleshootingEvidenceProperties props = new TroubleshootingEvidenceProperties();
        props.getGuance().setEnabled(true);
        props.getGuance().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        props.getGuance().setSyntheticsPath("/api/v1/df/query_data_v1");
        props.getGuance().setTokenHeader("DF-API-KEY");
        props.getGuance().setTokenPrefix("");
        props.getGuance().setToken("df-api-key-for-test");
        props.getGuance().setConnectTimeout(Duration.ofSeconds(1));
        props.getGuance().setReadTimeout(Duration.ofSeconds(1));
        return props;
    }

    private static TroubleshootingQueryTemplateRequest template() {
        return new TroubleshootingQueryTemplateRequest(
                "guance",
                "synthetics",
                "guance-http-dial-by-name",
                "观测云 HTTP 拨测 - 按任务名",
                "适用于观测云「可用性检测 > 任务」的 http_dial_testing 查询。",
                """
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
                        """,
                "D::http_dial_testing:(`status_code`, `url`, `name`) { `name` = '${syntheticsTaskNameDql}' }",
                "",
                true,
                true,
                100
        );
    }

    private static SopRouteRequest alert() {
        return new SopRouteRequest(
                "evt-1",
                "wecom",
                "P1",
                "马来-国际CPQ-首页",
                "firing",
                "cpq-homepage",
                "prod",
                "bwx-prod-k8s",
                "default",
                null,
                null,
                "/",
                "synthetics_status_code",
                "观测云可用性检测任务失败",
                null,
                Map.of("syntheticsTaskName", "马来-国际CPQ-首页"),
                3
        );
    }
}
