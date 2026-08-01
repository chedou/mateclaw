package vip.mate.troubleshooting.intake;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.BusinessSummary;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Pure-text channel projection until the platform card renderer is generalized. */
@Component
public class TroubleshootingChannelSummaryRenderer {

    private static final String FIXTURE_NOTICE = "Recorded Replay · 非真实观测云";

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
        StringBuilder text = new StringBuilder()
                .append('[')
                .append(summary.conclusionType())
                .append(" · ")
                .append(summary.confidence())
                .append("] ")
                .append(summary.headline())
                .append("\n问题：")
                .append(summary.problem())
                .append("\n结论：")
                .append(summary.narrative())
                .append("\n影响：")
                .append(summary.impact().functionScope());
        if (!summary.impact().note().isBlank()) {
            text.append("（").append(summary.impact().note()).append('）');
        }
        text.append("\n下一步：")
                .append(summary.nextStep().label())
                .append(" — ")
                .append(summary.nextStep().text());
        if (summary.nextStep().capabilityBoundary() != null) {
            text.append("\n能力边界：")
                    .append(summary.nextStep().capabilityBoundary());
        }
        if (summary.fixtureMode()) {
            text.append('\n').append(FIXTURE_NOTICE);
        }
        text.append("\n正式工作台：")
                .append(workbenchLink(summary.diagnosisId()));
        return text.toString();
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
