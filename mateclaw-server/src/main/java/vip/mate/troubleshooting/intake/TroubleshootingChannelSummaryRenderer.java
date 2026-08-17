package vip.mate.troubleshooting.intake;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import vip.mate.troubleshooting.model.ConclusionType;
import vip.mate.troubleshooting.model.Confidence;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.BusinessSummary;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Pure-text channel projection until the platform card renderer is generalized. */
@Component
public class TroubleshootingChannelSummaryRenderer {

    private static final String FIXTURE_NOTICE = "Recorded Replay · 非真实观测云";
    private static final String REPORTED_NOTICE = "告警事实 · 未经真实观测数据验证";

    private final String workbenchBaseUrl;

    public TroubleshootingChannelSummaryRenderer(
            @Value("${mateclaw.troubleshooting.workbench-base-url:${mateclaw.server.public-base-url:}}")
            String workbenchBaseUrl) {
        this.workbenchBaseUrl = normalizeBase(workbenchBaseUrl);
    }

    public String render(BusinessSummary summary) {
        if (summary == null) {
            throw new IllegalArgumentException("business summary is required");
        }
        // Scan-first: verdict, the cause, the counts, the next human move.
        // The workbench still has the Playbook explanation and impact notes.
        StringBuilder text = new StringBuilder()
                .append(conclusionLabel(summary.conclusionType()))
                .append(" · ")
                .append(confidenceLabel(summary.confidence()));
        if (summary.rootCause() != null) {
            text.append('\n').append(causeLine(summary));
        } else {
            text.append('\n').append(summary.headline());
            if (!summary.narrative().isBlank()) {
                text.append('\n').append(summary.narrative());
            }
        }
        if (summary.keyEvidence() != null) {
            text.append("\n关键数字：").append(summary.keyEvidence());
        }
        text.append("\n下一步：").append(summary.nextStep().text());
        if (summary.nextStep().capabilityBoundary() != null) {
            text.append('\n').append(summary.nextStep().capabilityBoundary());
        }
        if (summary.evidenceBasis()
                != vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.EvidenceBasis.OBSERVED) {
            text.append('\n').append(fixtureNotice(summary));
        }
        text.append("\n详情：")
                .append(workbenchLink(summary.diagnosisId()));
        return text.toString();
    }

    private String causeLine(BusinessSummary summary) {
        return switch (summary.conclusionType()) {
            case LOCATED -> "根因：" + summary.rootCause();
            case HYPOTHESIS -> summary.evidenceBasis()
                    == vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.EvidenceBasis.REPORTED
                    ? summary.rootCause()
                    : "候选方向：" + summary.rootCause();
            case EXCLUDED -> "已排除方向：" + summary.rootCause();
            case INSUFFICIENT_EVIDENCE -> "尚未形成根因：" + summary.rootCause();
        };
    }

    String conclusionLabel(ConclusionType conclusionType) {
        return switch (conclusionType) {
            case LOCATED -> "已定位";
            case EXCLUDED -> "已排除";
            case HYPOTHESIS -> "待确认假设";
            case INSUFFICIENT_EVIDENCE -> "证据不足";
        };
    }

    String confidenceLabel(Confidence confidence) {
        return switch (confidence) {
            case HIGH -> "结论依据充分";
            case MEDIUM -> "结论依据有限";
            case LOW -> "仅供人工核查";
        };
    }

    String fixtureNotice(BusinessSummary summary) {
        return switch (summary.evidenceBasis()) {
            case REPORTED -> REPORTED_NOTICE;
            case RECORDED_REPLAY -> FIXTURE_NOTICE;
            case OBSERVED -> "";
        };
    }

    public String workbenchLink(String diagnosisId) {
        if (diagnosisId == null || diagnosisId.isBlank()) {
            throw new IllegalArgumentException("diagnosisId must not be blank");
        }
        String encoded = URLEncoder.encode(diagnosisId, StandardCharsets.UTF_8);
        return workbenchBaseUrl + "/troubleshooting?diagnosisId=" + encoded;
    }

    private String normalizeBase(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().replaceAll("/+$", "");
    }
}
