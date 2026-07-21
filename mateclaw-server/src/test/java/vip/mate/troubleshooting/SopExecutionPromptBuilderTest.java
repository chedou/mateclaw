package vip.mate.troubleshooting;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.mate.skill.manifest.SkillManifest;
import vip.mate.troubleshooting.dto.SopRouteCandidate;
import vip.mate.troubleshooting.dto.SopRouteRequest;
import vip.mate.troubleshooting.dto.SopRouteResult;
import vip.mate.troubleshooting.model.SopDefinition;
import vip.mate.troubleshooting.model.TroubleshootingSopRunEntity;
import vip.mate.troubleshooting.service.SopExecutionPromptBuilder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SopExecutionPromptBuilderTest {

    private final SopExecutionPromptBuilder builder = new SopExecutionPromptBuilder(new ObjectMapper());

    @Test
    void promptContainsSopBodyAlertAndOutputContract() {
        TroubleshootingSopRunEntity run = new TroubleshootingSopRunEntity();
        run.setId(123L);
        run.setCaseId("case-api-5xx");
        SopDefinition sop = sop();
        SopRouteRequest alert = new SopRouteRequest(
                "evt-1",
                "wecom",
                "P2",
                "API 5xx",
                null,
                "sf-icare-exchange",
                "prod",
                "bwx-prod-k8s",
                null,
                null,
                null,
                "/api/order/list",
                "http_5xx_rate",
                "HTTP 500 timeout",
                null,
                Map.of("endpoint", "/api/order/list"),
                5
        );
        SopRouteResult route = new SopRouteResult(
                SopRouteCandidate.of(sop, 88, "test route", List.of(), false),
                List.of(),
                false,
                false,
                List.of(),
                "P2 / sf-icare-exchange / API 5xx"
        );

        String prompt = builder.build(run, sop, alert, route);

        assertTrue(prompt.contains("case-api-5xx"));
        assertTrue(prompt.contains("api_service/http_5xx_timeout"));
        assertTrue(prompt.contains("sf-icare-exchange"));
        assertTrue(prompt.contains("检查发布窗口"));
        assertTrue(prompt.contains("\"stepResults\""));
        assertTrue(prompt.contains("\"finalReport\""));
        assertTrue(prompt.contains("requiredEvidence"));
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
                SkillManifest.TroubleshootingMatch.builder()
                        .severities(List.of("P1", "P2"))
                        .keywords(List.of("500", "timeout"))
                        .build(),
                List.of("metrics", "logs", "release"),
                List.of("k8s", "gateway"),
                "sop-checklist-v1",
                "platform-sre",
                90,
                null,
                false,
                "",
                "## Checklist\n\n1. 检查发布窗口\n2. 查询错误日志"
        );
    }
}
