package vip.mate.troubleshooting.dto;

import java.util.List;
import java.util.Map;

public record SopRunCompleteRequest(
        List<SopStepResult> stepResults,
        Map<String, Object> finalReport
) {
}
