package vip.mate.troubleshooting.intake;

import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.model.BlastRadius;
import vip.mate.troubleshooting.model.Confidence;
import vip.mate.troubleshooting.model.ConclusionType;
import vip.mate.troubleshooting.model.DiagnosisStatus;
import vip.mate.troubleshooting.model.NorthStarTimings;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.BusinessSummary;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.ImpactView;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.NextStep;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TroubleshootingChannelSummaryRendererTest {

    @Test
    void rendersTypedBusinessSummaryFixtureBoundaryAndFormalWorkbenchDeepLink() {
        TroubleshootingChannelSummaryRenderer renderer =
                new TroubleshootingChannelSummaryRenderer("http://127.0.0.1:5173/");

        String text = renderer.render(summary());

        assertThat(text)
                .startsWith("[LOCATED · MEDIUM]")
                .contains("能力边界：仅完成只读取证，未执行任何生产变更。")
                .contains("Recorded Replay · 非真实观测云")
                .contains("http://127.0.0.1:5173/troubleshooting?diagnosisId=diag-1")
                .doesNotContain("DeveloperEvidenceView");
    }

    private BusinessSummary summary() {
        Instant reportedAt = Instant.parse("2026-07-29T02:00:00Z");
        return new BusinessSummary(
                "diag-1",
                ConclusionType.LOCATED,
                "已定位会话消息发送失败",
                "日志证据指向会话服务异常。",
                Confidence.MEDIUM,
                "会话消息发送失败",
                new ImpactView(
                        "csdp-wechat",
                        null,
                        null,
                        BlastRadius.UNKNOWN,
                        List.of(),
                        null,
                        "影响人数待确认"),
                new NextStep(
                        "人工复核",
                        "请值班开发核对证据并决定后续处置。",
                        "仅完成只读取证，未执行任何生产变更。"),
                DiagnosisStatus.READY_FOR_HUMAN,
                NorthStarTimings.concluded(
                        reportedAt,
                        reportedAt.plusSeconds(30),
                        reportedAt.plusSeconds(90)),
                true);
    }
}
