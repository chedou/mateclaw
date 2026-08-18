package vip.mate.troubleshooting.service;

import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

/** Atomic ownership token for one non-rehearsal incident or IntakeSession. */
public record FormalDiagnosisClaim(
        String dedupKey,
        String claimToken,
        Instant claimedAt,
        Instant expiresAt) {

    private static final Pattern SHA_256 = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern SAFE_TOKEN = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    public FormalDiagnosisClaim {
        dedupKey = dedupKey == null
                ? "" : dedupKey.trim().toLowerCase(Locale.ROOT);
        claimToken = claimToken == null ? "" : claimToken.trim();
        if (!SHA_256.matcher(dedupKey).matches()
                || !SAFE_TOKEN.matcher(claimToken).matches()) {
            throw new IllegalArgumentException(
                    "a safe formal diagnosis claim identity is required");
        }
        if (claimedAt == null || expiresAt == null || !expiresAt.isAfter(claimedAt)) {
            throw new IllegalArgumentException(
                    "a positive formal diagnosis claim lease is required");
        }
    }
}
