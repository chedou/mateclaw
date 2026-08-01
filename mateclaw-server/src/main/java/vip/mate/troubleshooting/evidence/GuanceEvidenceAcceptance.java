package vip.mate.troubleshooting.evidence;

import java.time.Instant;
import java.util.regex.Pattern;

/** Immutable owner attestation for one exact T7 Guance binding fingerprint. */
public record GuanceEvidenceAcceptance(
        String acceptanceId,
        String system,
        String service,
        String bindingFingerprint,
        Checklist checklist,
        ValidationFacts validation,
        String acceptedBy,
        Instant acceptedAt) {

    private static final Pattern ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{2,127}");
    private static final Pattern SHA256 = Pattern.compile("[a-f0-9]{64}");

    public GuanceEvidenceAcceptance {
        acceptanceId = required(acceptanceId, "acceptanceId");
        system = required(system, "system");
        service = required(service, "service");
        bindingFingerprint = required(bindingFingerprint, "bindingFingerprint");
        acceptedBy = required(acceptedBy, "acceptedBy");
        if (!ID.matcher(acceptanceId).matches()) {
            throw new IllegalArgumentException("acceptanceId is invalid");
        }
        if (!SHA256.matcher(bindingFingerprint).matches()) {
            throw new IllegalArgumentException(
                    "bindingFingerprint must be SHA-256 hex");
        }
        if (checklist == null || !checklist.complete()) {
            throw new IllegalArgumentException(
                    "all T7 owner confirmations are required");
        }
        if (validation == null) {
            throw new IllegalArgumentException("validation facts are required");
        }
        if (acceptedAt == null) {
            throw new IllegalArgumentException("acceptedAt is required");
        }
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    /**
     * Each item is an explicit owner assertion. The server can prove the
     * canonical chain, but only the owner can attest the Guance-side schema and
     * legacy route review.
     */
    public record Checklist(
            boolean measurementAndFieldsVerified,
            boolean indexVerified,
            boolean psIdJoinVerified,
            boolean timestampUnitVerified,
            boolean timeWindowVerified,
            boolean dqlLatencyReviewed,
            boolean legacyRouteConflictReviewed) {

        public boolean complete() {
            return measurementAndFieldsVerified
                    && indexVerified
                    && psIdJoinVerified
                    && timestampUnitVerified
                    && timeWindowVerified
                    && dqlLatencyReviewed
                    && legacyRouteConflictReviewed;
        }
    }

    /**
     * Secret-free proof material. The PS ID itself is not persisted; only its
     * hash demonstrates that the search and trace stages joined on one value.
     */
    public record ValidationFacts(
            long matchCount,
            int traceEntries,
            String psIdFingerprint,
            long logSearchDurationMs,
            long logTraceDurationMs,
            long totalDurationMs,
            Instant observedAt) {

        public ValidationFacts {
            if (matchCount <= 0 || traceEntries <= 0) {
                throw new IllegalArgumentException(
                        "positive match and trace counts are required");
            }
            if (psIdFingerprint == null
                    || !SHA256.matcher(psIdFingerprint).matches()) {
                throw new IllegalArgumentException(
                        "psIdFingerprint must be SHA-256 hex");
            }
            if (logSearchDurationMs < 0
                    || logTraceDurationMs < 0
                    || totalDurationMs < logSearchDurationMs + logTraceDurationMs) {
                throw new IllegalArgumentException(
                        "validation durations are inconsistent");
            }
            if (observedAt == null) {
                throw new IllegalArgumentException("observedAt is required");
            }
        }
    }
}
