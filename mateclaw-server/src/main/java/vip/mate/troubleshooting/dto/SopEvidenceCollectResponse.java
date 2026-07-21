package vip.mate.troubleshooting.dto;

import vip.mate.troubleshooting.model.TroubleshootingSopRunEntity;

import java.util.List;
import java.util.Map;

public record SopEvidenceCollectResponse(
        TroubleshootingSopRunEntity run,
        List<SopEvidenceRecord> evidenceRecords,
        List<SopStepResult> stepResults,
        Map<String, Object> finalReportTemplate
) {
}
