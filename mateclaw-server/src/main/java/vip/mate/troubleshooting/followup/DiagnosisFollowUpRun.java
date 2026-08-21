package vip.mate.troubleshooting.followup;

import vip.mate.troubleshooting.model.ConclusionType;

import java.time.Instant;

/**
 * Immutable receipt for operator-supplied material after a Diagnosis concluded.
 *
 * <p>No submitted body is stored.  The ledger proves that a new investigation
 * lead was recorded against an exact Diagnosis version while keeping raw logs,
 * identifiers and credentials outside the database.</p>
 */
public record DiagnosisFollowUpRun(
        String runId,
        String diagnosisId,
        int diagnosisVersion,
        ConclusionType conclusionType,
        DiagnosisFollowUpIntent turnKind,
        int contentLength,
        DiagnosisFollowUpDisposition disposition,
        String actorRef,
        Instant recordedAt) {

    public DiagnosisFollowUpRun {
        runId = safe(runId, "runId", 128);
        diagnosisId = safe(diagnosisId, "diagnosisId", 128);
        if (diagnosisVersion < 0) {
            throw new IllegalArgumentException("diagnosisVersion must not be negative");
        }
        if (conclusionType == null
                || turnKind != DiagnosisFollowUpIntent.SUPPLEMENTAL_EVIDENCE
                || disposition != DiagnosisFollowUpDisposition.RECORDED_NOT_VERIFIED) {
            throw new IllegalArgumentException(
                    "supplemental follow-up identity and disposition are required");
        }
        if (contentLength <= 0 || contentLength > 4000) {
            throw new IllegalArgumentException("contentLength must be between 1 and 4000");
        }
        actorRef = safe(actorRef, "actorRef", 192);
        if (recordedAt == null) {
            throw new IllegalArgumentException("recordedAt is required");
        }
    }

    private static String safe(String value, String field, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()
                || normalized.length() > maxLength
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must contain safe text");
        }
        return normalized;
    }
}
