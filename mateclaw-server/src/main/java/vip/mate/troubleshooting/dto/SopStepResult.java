package vip.mate.troubleshooting.dto;

import java.util.List;

public record SopStepResult(
        String stepId,
        String status,
        List<String> evidenceIds,
        List<String> evidenceTypes,
        String observation,
        String interpretation,
        String nextDecision
) {
}
