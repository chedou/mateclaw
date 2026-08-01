package vip.mate.troubleshooting.synthesis;

import java.util.regex.Pattern;

/** Current exact fingerprints plus the matching persisted replay, when one exists. */
public record ManualPlaybookReplayQualification(
        String candidateFingerprint,
        String suiteFingerprint,
        ManualPlaybookReplayAttestation attestation) {

    private static final Pattern HASH = Pattern.compile("[a-f0-9]{64}");

    public ManualPlaybookReplayQualification {
        candidateFingerprint = hash(candidateFingerprint, "candidateFingerprint", false);
        suiteFingerprint = hash(suiteFingerprint, "suiteFingerprint", true);
    }

    public boolean suiteAvailable() {
        return suiteFingerprint != null;
    }

    private static String hash(String value, String field, boolean nullable) {
        if (value == null || value.isBlank()) {
            if (nullable) {
                return null;
            }
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String normalized = value.trim();
        if (!HASH.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " must be a SHA-256 value");
        }
        return normalized;
    }
}
