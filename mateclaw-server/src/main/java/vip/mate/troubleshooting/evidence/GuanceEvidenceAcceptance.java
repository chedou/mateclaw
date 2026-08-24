package vip.mate.troubleshooting.evidence;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
            Instant observedAt,
            Map<String, Long> liveCapabilityDurationsMs) {

        /** Backward-compatible shape for acceptances persisted before generic validation. */
        public ValidationFacts(
                long matchCount,
                int traceEntries,
                String psIdFingerprint,
                long logSearchDurationMs,
                long logTraceDurationMs,
                long totalDurationMs,
                Instant observedAt) {
            this(
                    matchCount,
                    traceEntries,
                    psIdFingerprint,
                    logSearchDurationMs,
                    logTraceDurationMs,
                    totalDurationMs,
                    observedAt,
                    Map.of());
        }

        public ValidationFacts {
            Map<String, Long> normalizedCapabilities = new LinkedHashMap<>();
            for (Map.Entry<String, Long> entry : liveCapabilityDurationsMs == null
                    ? Map.<String, Long>of().entrySet()
                    : liveCapabilityDurationsMs.entrySet()) {
                String signalKind = entry.getKey() == null
                        ? ""
                        : entry.getKey().trim().toLowerCase(Locale.ROOT);
                Long durationMs = entry.getValue();
                if (signalKind.isBlank()
                        || !CanonicalEvidenceSchema.isExternallyRoutable(signalKind)
                        || durationMs == null
                        || durationMs < 0
                        || normalizedCapabilities.putIfAbsent(
                                signalKind, durationMs) != null) {
                    throw new IllegalArgumentException(
                            "live capability validation facts are invalid");
                }
            }
            liveCapabilityDurationsMs = Map.copyOf(normalizedCapabilities);

            boolean coreObserved = matchCount > 0
                    && traceEntries > 0
                    && psIdFingerprint != null
                    && SHA256.matcher(psIdFingerprint).matches();
            boolean coreAbsent = matchCount == 0
                    && traceEntries == 0
                    && (psIdFingerprint == null || psIdFingerprint.isBlank())
                    && logSearchDurationMs == 0
                    && logTraceDurationMs == 0;
            if (!coreObserved && !coreAbsent) {
                throw new IllegalArgumentException(
                        "canonical chain validation facts are inconsistent");
            }
            if (!coreObserved && liveCapabilityDurationsMs.isEmpty()) {
                throw new IllegalArgumentException(
                        "at least one live validation proof is required");
            }
            long measuredCapabilityDuration = liveCapabilityDurationsMs.values()
                    .stream()
                    .mapToLong(Long::longValue)
                    .sum();
            if (logSearchDurationMs < 0
                    || logTraceDurationMs < 0
                    || totalDurationMs < logSearchDurationMs
                            + logTraceDurationMs
                            + measuredCapabilityDuration) {
                throw new IllegalArgumentException(
                        "validation durations are inconsistent");
            }
            if (observedAt == null) {
                throw new IllegalArgumentException("observedAt is required");
            }
        }

        public boolean coreChainObserved() {
            return matchCount > 0
                    && traceEntries > 0
                    && psIdFingerprint != null
                    && SHA256.matcher(psIdFingerprint).matches();
        }

        public Set<String> liveAcceptedSignalKinds() {
            return liveCapabilityDurationsMs.keySet();
        }
    }
}
