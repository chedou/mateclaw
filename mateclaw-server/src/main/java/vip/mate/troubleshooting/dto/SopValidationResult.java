package vip.mate.troubleshooting.dto;

import java.util.List;

public record SopValidationResult(
        boolean valid,
        List<String> missingEvidence,
        List<String> errors
) {
}
