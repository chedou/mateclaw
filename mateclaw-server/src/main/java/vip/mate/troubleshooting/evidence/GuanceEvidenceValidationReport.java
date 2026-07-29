package vip.mate.troubleshooting.evidence;

import java.time.Instant;
import java.util.List;

/** Secret-free outcome of one admin-triggered read-only Guance acceptance run. */
public record GuanceEvidenceValidationReport(
        Stage stage,
        GuanceEvidenceReadiness readiness,
        Long matchCount,
        String psId,
        Integer traceEntries,
        List<Step> steps,
        Instant completedAt,
        List<String> warnings) {

    public GuanceEvidenceValidationReport {
        stage = stage == null ? Stage.BLOCKED : stage;
        psId = psId == null ? null : psId.trim();
        steps = List.copyOf(steps == null ? List.of() : steps);
        completedAt = completedAt == null ? Instant.EPOCH : completedAt;
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
    }

    public enum Stage {
        BLOCKED,
        CANONICAL_CHAIN_OBSERVED
    }

    public enum StepStatus {
        NOT_RUN,
        BLOCKED,
        CANONICAL_RESULT_OBSERVED
    }

    public record Step(
            String signalKind,
            StepStatus status,
            String evidenceRef,
            String detail,
            Instant collectedAt) {

        public Step {
            signalKind = signalKind == null ? "" : signalKind.trim();
            status = status == null ? StepStatus.BLOCKED : status;
            evidenceRef = evidenceRef == null ? "" : evidenceRef.trim();
            detail = detail == null ? "" : detail.trim();
        }
    }
}
