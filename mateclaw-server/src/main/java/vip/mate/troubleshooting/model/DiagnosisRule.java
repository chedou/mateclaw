package vip.mate.troubleshooting.model;

import java.util.List;

public record DiagnosisRule(
        String ruleId,
        List<String> requiredSignals,
        String rootCause,
        String summary,
        Confidence confidence,
        ConclusionType conclusionType,
        boolean abstained) {

    /**
     * Backward-compatible shape for existing rules. A non-abstaining rule used
     * to mean LOCATED; callers that need to state only a hypothesis must now do
     * so explicitly through the canonical constructor.
     */
    public DiagnosisRule(
            String ruleId,
            List<String> requiredSignals,
            String rootCause,
            String summary,
            Confidence confidence,
            boolean abstained) {
        this(ruleId, requiredSignals, rootCause, summary, confidence,
                abstained ? ConclusionType.INSUFFICIENT_EVIDENCE : ConclusionType.LOCATED,
                abstained);
    }

    public DiagnosisRule {
        ruleId = required(ruleId, "ruleId");
        requiredSignals = List.copyOf(requiredSignals == null ? List.of() : requiredSignals);
        rootCause = required(rootCause, "rootCause");
        summary = summary == null ? "" : summary;
        confidence = confidence == null ? Confidence.LOW : confidence;
        conclusionType = conclusionType == null
                ? (abstained
                        ? ConclusionType.INSUFFICIENT_EVIDENCE
                        : ConclusionType.LOCATED)
                : conclusionType;
        if (abstained && conclusionType != ConclusionType.INSUFFICIENT_EVIDENCE) {
            throw new IllegalArgumentException(
                    "an abstaining rule must conclude INSUFFICIENT_EVIDENCE");
        }
        if (!abstained
                && conclusionType != ConclusionType.LOCATED
                && conclusionType != ConclusionType.HYPOTHESIS) {
            throw new IllegalArgumentException(
                    "a non-abstaining rule must conclude LOCATED or HYPOTHESIS");
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
