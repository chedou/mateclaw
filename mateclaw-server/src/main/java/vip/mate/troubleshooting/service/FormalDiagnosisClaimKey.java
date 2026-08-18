package vip.mate.troubleshooting.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Domain-separated owner keys for the shared formal-diagnosis claim table. */
public final class FormalDiagnosisClaimKey {

    private FormalDiagnosisClaimKey() {
    }

    public static String forIntake(long workspaceId, String intakeSessionId) {
        if (workspaceId <= 0
                || intakeSessionId == null
                || intakeSessionId.isBlank()) {
            throw new IllegalArgumentException(
                    "workspaceId and intakeSessionId are required for a formal intake claim");
        }
        return sha256("intake-session\u001f" + workspaceId + "\u001f"
                + intakeSessionId.trim());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
