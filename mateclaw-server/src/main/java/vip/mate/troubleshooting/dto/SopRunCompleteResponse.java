package vip.mate.troubleshooting.dto;

import vip.mate.troubleshooting.model.TroubleshootingSopRunEntity;

public record SopRunCompleteResponse(
        TroubleshootingSopRunEntity run,
        SopValidationResult validation,
        String groupReport
) {
}
