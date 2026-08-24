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
                .contains("仅完成只读取证，未执行任何生产变更。")
                .contains("Recorded Replay · 非真实观测云")
                .contains("http://127.0.0.1:5173/troubleshooting?diagnosisId=diag-1")
                .doesNotContain("DeveloperEvidenceView");
    }

    /**
     * The reader is a service manager, not an operator of this system. Leading
     * with `LOCATED · MEDIUM` asked them to learn our enum names before they
     * could tell whether the line underneath was worth acting on.
     */
    @Test
    void leadsWithTheVerdictAndItsStrengthInWordsRatherThanEnumNames() {
        TroubleshootingChannelSummaryRenderer renderer =
                new TroubleshootingChannelSummaryRenderer("http://127.0.0.1:5173");

        String text = renderer.render(summary());

        assertThat(text)
                .startsWith("已定位 · 结论依据有限")
                .doesNotContain("LOCATED")
                .doesNotContain("MEDIUM");
    }

    /** The cause and the counts behind it are the two facts worth acting on. */
    @Test
    void showsTheRootCauseAndTheCountsThatSupportIt() {
        TroubleshootingChannelSummaryRenderer renderer =
                new TroubleshootingChannelSummaryRenderer("http://127.0.0.1:5173");

        String text = renderer.render(summary());

        assertThat(text)
                .contains("根因：会话服务异常")
                .contains("关键数字：71 个异常请求中有 28 个出现同一异常特征")
                .doesNotContain("问题：")
                .doesNotContain("说明：")
                .doesNotContain("影响：");
    }

    @Test
    void labelsAReviewedAlertFactAsUnverifiedInsteadOfCallingItRecordedReplay() {
        TroubleshootingChannelSummaryRenderer renderer =
                new TroubleshootingChannelSummaryRenderer("http://127.0.0.1:5173");
        BusinessSummary base = summary();
        BusinessSummary reported = new BusinessSummary(
                base.diagnosisId(), ConclusionType.HYPOTHESIS, base.headline(),
                "直接失败点：iCare 接口返回 HTTP 502",
                "上游原因尚未定位。",
                "告警已经明确：iCare 产品映射接口调用返回 HTTP 502。",
                Confidence.LOW, base.problem(), base.impact(), base.nextStep(),
                base.status(), base.timings(), true,
                vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.EvidenceBasis.REPORTED);

        assertThat(renderer.render(reported))
                .startsWith("直接失败点已明确 ·")
                .contains("告警事实 · 未经真实观测数据验证")
                .contains("直接失败点：iCare 接口返回 HTTP 502")
                .doesNotContain("根因：直接失败点")
                .doesNotContain("Recorded Replay");
    }

    @Test
    void labelsAnUnconfirmedHypothesisAsACandidateRatherThanARootCause() {
        BusinessSummary base = summary();
        BusinessSummary hypothesis = new BusinessSummary(
                base.diagnosisId(), ConclusionType.HYPOTHESIS, base.headline(),
                "网关连接异常", base.narrative(), base.keyEvidence(),
                Confidence.LOW, base.problem(), base.impact(), base.nextStep(),
                base.status(), base.timings(), false,
                vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.EvidenceBasis.OBSERVED);

        assertThat(new TroubleshootingChannelSummaryRenderer("").render(hypothesis))
                .startsWith("最可能方向 ·")
                .contains("候选方向：网关连接异常")
                .doesNotContain("根因：网关连接异常");
    }

    @Test
    void observedHypothesisCannotChangeItsLabelWithAChinesePrefix() {
        BusinessSummary base = summary();
        BusinessSummary hypothesis = new BusinessSummary(
                base.diagnosisId(), ConclusionType.HYPOTHESIS, base.headline(),
                "直接失败点：网关连接异常", base.narrative(), base.keyEvidence(),
                Confidence.LOW, base.problem(), base.impact(), base.nextStep(),
                base.status(), base.timings(), false,
                vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.EvidenceBasis.OBSERVED);

        assertThat(new TroubleshootingChannelSummaryRenderer("").render(hypothesis))
                .contains("候选方向：直接失败点：网关连接异常")
                .doesNotContain("\n直接失败点：网关连接异常");
    }

    private BusinessSummary summary() {
        Instant reportedAt = Instant.parse("2026-07-29T02:00:00Z");
        return new BusinessSummary(
                "diag-1",
                ConclusionType.LOCATED,
                "已定位会话消息发送失败",
                "会话服务异常",
                "日志证据指向会话服务异常。",
                "71 个异常请求中有 28 个出现同一异常特征；21412 个正常请求中只有 35 个出现。",
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
                true,
                vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.EvidenceBasis.RECORDED_REPLAY);
    }
}
