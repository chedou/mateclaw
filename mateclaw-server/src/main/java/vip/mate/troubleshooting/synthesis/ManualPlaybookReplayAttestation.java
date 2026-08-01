package vip.mate.troubleshooting.synthesis;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/** Immutable, candidate-and-suite-scoped proof used by the MANUAL approval Gate. */
public record ManualPlaybookReplayAttestation(
        String attestationId,
        String sourceRecordId,
        String selectorKey,
        String candidateFingerprint,
        String suiteId,
        int suiteVersion,
        String suiteFingerprint,
        Status status,
        int positiveTotal,
        int positivePassed,
        int negativeOrAbstainTotal,
        int negativeOrAbstainPassed,
        List<String> failureCodes,
        boolean fixtureMode,
        String executedBy,
        Instant executedAt) {

    private static final Pattern HASH = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern FAILURE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,95}");

    public ManualPlaybookReplayAttestation {
        attestationId = required(attestationId, "attestationId");
        sourceRecordId = required(sourceRecordId, "sourceRecordId");
        selectorKey = required(selectorKey, "selectorKey");
        candidateFingerprint = hash(candidateFingerprint, "candidateFingerprint");
        suiteId = required(suiteId, "suiteId");
        if (suiteVersion < 1) {
            throw new IllegalArgumentException("suiteVersion must be positive");
        }
        suiteFingerprint = hash(suiteFingerprint, "suiteFingerprint");
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        failureCodes = List.copyOf(failureCodes == null ? List.of() : failureCodes);
        if (failureCodes.stream().anyMatch(
                code -> code == null || !FAILURE_CODE.matcher(code).matches())) {
            throw new IllegalArgumentException("failure codes must be bounded structured codes");
        }
        if (positiveTotal < 1
                || negativeOrAbstainTotal < 1
                || positivePassed < 0
                || positivePassed > positiveTotal
                || negativeOrAbstainPassed < 0
                || negativeOrAbstainPassed > negativeOrAbstainTotal) {
            throw new IllegalArgumentException("manual replay counters are invalid");
        }
        boolean complete = positivePassed == positiveTotal
                && negativeOrAbstainPassed == negativeOrAbstainTotal;
        if ((status == Status.PASSED) != (complete && failureCodes.isEmpty())) {
            throw new IllegalArgumentException(
                    "manual replay status must match its counters and failure codes");
        }
        if (!fixtureMode) {
            throw new IllegalArgumentException(
                    "manual replay attestations currently require a fixed fixture suite");
        }
        executedBy = required(executedBy, "executedBy");
        if (executedAt == null) {
            throw new IllegalArgumentException("executedAt is required");
        }
    }

    public enum Status {
        PASSED,
        FAILED
    }

    private static String hash(String value, String field) {
        String normalized = required(value, field);
        if (!HASH.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " must be a SHA-256 value");
        }
        return normalized;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
