package vip.mate.troubleshooting;

import org.junit.jupiter.api.Test;
import vip.mate.skill.manifest.SkillManifest;
import vip.mate.troubleshooting.dto.SopRouteRequest;
import vip.mate.troubleshooting.dto.SopRouteResult;
import vip.mate.troubleshooting.model.SopDefinition;
import vip.mate.troubleshooting.service.SopRouter;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SopRouterTest {

    private final SopRouter router = new SopRouter(null);

    @Test
    void routesApi5xxAlertToApiServiceSop() {
        List<SopDefinition> sops = List.of(
                sop(1L, "api-service-5xx", "api_service", "http_5xx_timeout",
                        List.of("P1", "P2"),
                        List.of("serviceName", "env", "cluster", "endpoint"),
                        List.of("500", "502", "503", "timeout"),
                        List.of("metrics", "logs", "release")),
                sop(2L, "redis-timeout", "cache", "redis_timeout",
                        List.of("P1", "P2"),
                        List.of("serviceName", "env", "cluster"),
                        List.of("redis", "timeout", "cache"),
                        List.of("metrics", "logs")),
                fallback()
        );
        SopRouteRequest request = request(
                "P2",
                "API 5xx error rate high",
                "sf-icare-exchange",
                "prod",
                "bwx-prod-k8s",
                "/api/order/list",
                "http_5xx_rate",
                "HTTP 500 timeout observed on endpoint",
                Map.of("namespace", "default")
        );

        SopRouteResult result = router.route(sops, request);

        assertNotNull(result.selected());
        assertEquals("api_service", result.selected().domain());
        assertEquals("http_5xx_timeout", result.selected().scenario());
        assertFalse(result.usedFallback());
        assertTrue(result.selected().confidence() > 0.5d);
    }

    @Test
    void lowConfidenceFallsBackToSystematicDebugging() {
        List<SopDefinition> sops = List.of(
                sop(1L, "api-service-5xx", "api_service", "http_5xx_timeout",
                        List.of("P1", "P2"),
                        List.of("serviceName", "env", "cluster", "endpoint"),
                        List.of("500", "502", "503", "timeout"),
                        List.of("metrics", "logs", "release")),
                fallback()
        );
        SopRouteRequest request = request(
                null,
                "unknown alert",
                null,
                null,
                null,
                null,
                null,
                "unstructured symptom without routing signals",
                Map.of()
        );

        SopRouteResult result = router.route(sops, request);

        assertNotNull(result.selected());
        assertEquals("generic", result.selected().domain());
        assertEquals("systematic_debugging", result.selected().scenario());
        assertTrue(result.lowConfidence());
        assertTrue(result.usedFallback());
        assertTrue(result.selected().fallback());
    }

    private static SopRouteRequest request(String severity,
                                           String alertName,
                                           String serviceName,
                                           String env,
                                           String cluster,
                                           String endpoint,
                                           String metricName,
                                           String message,
                                           Map<String, Object> labels) {
        return new SopRouteRequest(
                null,
                "test",
                severity,
                alertName,
                null,
                serviceName,
                env,
                cluster,
                null,
                null,
                null,
                endpoint,
                metricName,
                message,
                null,
                labels,
                5
        );
    }

    private static SopDefinition fallback() {
        return sop(99L, "systematic-debugging", "generic", "systematic_debugging",
                List.of(), List.of(), List.of("debug", "incident"), List.of());
    }

    private static SopDefinition sop(Long id,
                                     String name,
                                     String domain,
                                     String scenario,
                                     List<String> severities,
                                     List<String> labels,
                                     List<String> keywords,
                                     List<String> requiredEvidence) {
        SkillManifest.TroubleshootingMatch match = SkillManifest.TroubleshootingMatch.builder()
                .severities(severities)
                .labels(labels)
                .keywords(keywords)
                .build();
        return new SopDefinition(
                id,
                name,
                null,
                "1.0.0",
                true,
                0L,
                domain,
                scenario,
                match,
                requiredEvidence,
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
