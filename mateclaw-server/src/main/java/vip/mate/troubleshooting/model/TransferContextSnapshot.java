package vip.mate.troubleshooting.model;

import java.util.List;

/** Immutable context handed to a human owner; never a write-execution request. */
public record TransferContextSnapshot(
        String caseId,
        String runId,
        String traceId,
        List<String> evidenceIds,
        String rootCause,
        Confidence confidence) {

    public TransferContextSnapshot {
        caseId = required(caseId, "caseId");
        runId = required(runId, "runId");
        evidenceIds = List.copyOf(evidenceIds == null ? List.of() : evidenceIds);
        rootCause = required(rootCause, "rootCause");
        confidence = confidence == null ? Confidence.LOW : confidence;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
