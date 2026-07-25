package vip.mate.troubleshooting.model;

import java.util.List;

public record DiagnosisRule(
        String ruleId,
        List<String> requiredSignals,
        String rootCause,
        String summary,
        Confidence confidence,
        boolean abstained) {

    public DiagnosisRule {
        ruleId = required(ruleId, "ruleId");
        requiredSignals = List.copyOf(requiredSignals == null ? List.of() : requiredSignals);
        rootCause = required(rootCause, "rootCause");
        summary = summary == null ? "" : summary;
        confidence = confidence == null ? Confidence.LOW : confidence;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
