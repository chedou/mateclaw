package vip.mate.troubleshooting.intake;

import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.model.BlastRadius;
import vip.mate.troubleshooting.model.ClosureOutcome;
import vip.mate.troubleshooting.model.ClosureRecord;
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

class TroubleshootingClosureNotificationRendererTest {

    @Test
    void rendersFinalOutcomeFixtureBoundaryAndFormalWorkbenchLinkWithoutDeveloperEvidence() {
        TroubleshootingClosureNotificationRenderer renderer =
                new TroubleshootingClosureNotificationRenderer("http://127.0.0.1:5173/");

        String text = renderer.render(summary(), closure());

        assertThat(text)
                .startsWith("排障闭环 · 已恢复")
                .contains("原诊断：已定位 · 结论依据有限 · ")
                .contains("处理结果：连接池扩容后恢复")
                .contains("恢复验证：已验证")
                .contains("能力边界：仅完成只读取证，未执行任何生产变更。")
                .contains("Recorded Replay · 非真实观测云")
                .contains("http://127.0.0.1:5173/troubleshooting?diagnosisId=diag-1")
                .doesNotContain("DeveloperEvidenceView")
                .doesNotContain("DQL");
    }

    @Test
    void defensivelyBoundsAndSanitizesLegacyClosureTextBeforeChannelDelivery() {
        TroubleshootingClosureNotificationRenderer renderer =
                new TroubleshootingClosureNotificationRenderer("http://127.0.0.1:5173/");
        ClosureRecord unsafeLegacyClosure = new ClosureRecord(
                ClosureOutcome.FALSE_POSITIVE,
                "DQL L::service:(*) token=top-secret <@all> " + "超长内容".repeat(900),
                false,
                null,
                null,
                "operator@example.com",
                Instant.parse("2026-07-29T03:00:00Z"));

        String text = renderer.render(summary(), unsafeLegacyClosure);

        assertThat(text)
                .hasSizeLessThanOrEqualTo(1_800)
                .contains("处理结果：")
                .contains("正式工作台：")
                .doesNotContain("DQL")
                .doesNotContain("top-secret")
                .doesNotContain("<@all>");
    }

    private BusinessSummary summary() {
        Instant reportedAt = Instant.parse("2026-07-29T02:00:00Z");
        return new BusinessSummary(
                "diag-1",
                ConclusionType.LOCATED,
                "已定位会话消息发送失败",
                "会话服务异常",
                "日志证据指向会话服务异常。",
                null,
                Confidence.MEDIUM,
                "会话消息发送失败",
                new ImpactView(
                        "csdp-wechat", null, null, BlastRadius.UNKNOWN,
                        List.of(), null, "影响人数待确认"),
                new NextStep(
                        "人工复核",
                        "请值班开发核对证据并决定后续处置。",
                        "仅完成只读取证，未执行任何生产变更。"),
                DiagnosisStatus.CLOSED,
                NorthStarTimings.concluded(
                        reportedAt, reportedAt.plusSeconds(30), reportedAt.plusSeconds(90)),
                true,
                vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.EvidenceBasis.RECORDED_REPLAY);
    }

    private ClosureRecord closure() {
        return new ClosureRecord(
                ClosureOutcome.RECOVERED,
                "连接池扩容后恢复",
                true,
                null,
                null,
                "operator@example.com",
                Instant.parse("2026-07-29T03:00:00Z"));
    }
}
