package vip.mate.troubleshooting.dto;

import vip.mate.troubleshooting.model.TroubleshootingSopRunEntity;

import java.util.List;
import java.util.Map;

public record SopRunStartResponse(
        TroubleshootingSopRunEntity run,
        SopRouteResult route,
        SopSummary sop,
        String executionPrompt,
        List<SopStepResult> sampleStepResults,
        Map<String, Object> finalReportTemplate
) {
}
