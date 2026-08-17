package vip.mate.troubleshooting.intake;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import vip.mate.troubleshooting.TroubleshootingBusinessTextPolicy;
import vip.mate.troubleshooting.model.ClosureOutcome;
import vip.mate.troubleshooting.model.ClosureRecord;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.BusinessSummary;

/** Pure-text final outcome projection for the original incident channel. */
@Component
public class TroubleshootingClosureNotificationRenderer {

    private static final int MAX_NOTIFICATION_CHARS = 1_800;

    private final TroubleshootingChannelSummaryRenderer linkRenderer;

    @Autowired
    public TroubleshootingClosureNotificationRenderer(
            TroubleshootingChannelSummaryRenderer linkRenderer) {
        this.linkRenderer = linkRenderer;
    }

    TroubleshootingClosureNotificationRenderer(String workbenchBaseUrl) {
        this(new TroubleshootingChannelSummaryRenderer(workbenchBaseUrl));
    }

    public String render(BusinessSummary summary, ClosureRecord closure) {
        if (summary == null || closure == null) {
            throw new IllegalArgumentException("business summary and closure are required");
        }
        StringBuilder body = new StringBuilder()
                .append("排障闭环 · ")
                .append(outcomeLabel(closure.outcome()))
                .append("\n原诊断：")
                .append(linkRenderer.conclusionLabel(summary.conclusionType()))
                .append(" · ")
                .append(linkRenderer.confidenceLabel(summary.confidence()))
                .append(" · ")
                .append(channelText(summary.headline(), 160))
                .append("\n问题：")
                .append(channelText(summary.problem(), 320))
                .append("\n处理结果：")
                .append(channelText(closure.summary(), 500))
                .append("\n恢复验证：")
                .append(closure.recoveryVerified() ? "已验证" : "未声明已验证");
        // Keep the same typed conclusion/confidence vocabulary as the first
        // notification, while making the final outcome the leading fact.
        if (summary.nextStep().capabilityBoundary() != null) {
            body.append("\n能力边界：")
                    .append(channelText(summary.nextStep().capabilityBoundary(), 280));
        }
        if (summary.evidenceBasis()
                != vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.EvidenceBasis.OBSERVED) {
            body.append('\n').append(linkRenderer.fixtureNotice(summary));
        }
        String suffix = "\n正式工作台："
                + channelText(linkRenderer.workbenchLink(summary.diagnosisId()), 360);
        int bodyBudget = Math.max(1, MAX_NOTIFICATION_CHARS - suffix.length());
        return TroubleshootingBusinessTextPolicy.truncate(body.toString(), bodyBudget)
                + suffix;
    }

    private String channelText(String value, int maxChars) {
        return TroubleshootingBusinessTextPolicy.forChannel(value, maxChars);
    }

    private String outcomeLabel(ClosureOutcome outcome) {
        return switch (outcome) {
            case RECOVERED -> "已恢复";
            case FALSE_POSITIVE -> "误报";
            case TRANSFERRED_OUT -> "已转出处置";
            case UNRESOLVED -> "未解决";
        };
    }
}
