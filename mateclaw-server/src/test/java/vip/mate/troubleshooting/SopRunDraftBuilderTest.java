package vip.mate.troubleshooting;

import org.junit.jupiter.api.Test;
import vip.mate.skill.manifest.SkillManifest;
import vip.mate.troubleshooting.dto.SopRouteCandidate;
import vip.mate.troubleshooting.dto.SopRouteRequest;
import vip.mate.troubleshooting.dto.SopRouteResult;
import vip.mate.troubleshooting.dto.SopStepResult;
import vip.mate.troubleshooting.model.SopDefinition;
import vip.mate.troubleshooting.service.SopRunDraftBuilder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SopRunDraftBuilderTest {

    private final SopRunDraftBuilder builder = new SopRunDraftBuilder();

    @Test
    void buildsAlertAwareChecklistDrafts() {
        SopDefinition sop = sop();
        SopRouteRequest alert = new SopRouteRequest(
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
        SopRouteResult route = new SopRouteResult(
                SopRouteCandidate.of(sop, 98, "severity matched", List.of(), false),
                List.of(),
                false,
                false,
                List.of(),
                "P1 / order-api / API 5xx"
        );

        List<SopStepResult> steps = builder.buildStepDrafts(sop, alert, route);
        Map<String, Object> report = builder.buildFinalReportDraft(sop, alert, route);

        assertEquals(3, steps.size());
        assertEquals("collect-metrics", steps.get(0).stepId());
        assertEquals(List.of("metrics"), steps.get(0).evidenceTypes());
        assertTrue(steps.get(0).observation().contains("order-api"));
        assertTrue(steps.get(0).observation().contains("告警触发前后各 15 分钟"));
        assertEquals("low", report.get("confidence"));
        assertTrue(report.get("conclusion").toString().contains("证据不足"));
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
                List.of("k8s", "gateway"),
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
