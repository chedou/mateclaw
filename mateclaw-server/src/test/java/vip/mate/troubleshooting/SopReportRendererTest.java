package vip.mate.troubleshooting;

import org.junit.jupiter.api.Test;
import vip.mate.skill.manifest.SkillManifest;
import vip.mate.troubleshooting.dto.SopValidationResult;
import vip.mate.troubleshooting.model.SopDefinition;
import vip.mate.troubleshooting.service.SopReportRenderer;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SopReportRendererTest {

    private final SopReportRenderer renderer = new SopReportRenderer();

    @Test
    void groupReportRedactsSensitiveFields() {
        String report = renderer.renderGroupReport(
                sop(),
                new SopValidationResult(true, List.of(), List.of()),
                Map.of(
                        "conclusion", "authorization: Bearer abc token=secret user=a@example.com phone=13800138000",
                        "confidence", "medium",
                        "nextAction", "cookie=sessionid api_key=sk-test"
                )
        );

        assertTrue(report.contains("authorization=<redacted>"));
        assertTrue(report.contains("token=<redacted>"));
        assertTrue(report.contains("cookie=<redacted>"));
        assertTrue(report.contains("api_key=<redacted>"));
        assertFalse(report.contains("a@example.com"));
        assertFalse(report.contains("13800138000"));
        assertFalse(report.contains("sk-test"));
    }

    @Test
    void invalidValidationMarksEvidenceInsufficient() {
        String report = renderer.renderGroupReport(
                sop(),
                new SopValidationResult(false, List.of("release"), List.of("required_evidence_missing")),
                Map.of("conclusion", "发布窗口未确认")
        );

        assertTrue(report.contains("状态：证据不足"));
        assertTrue(report.contains("缺少 release"));
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
