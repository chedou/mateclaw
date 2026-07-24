package vip.mate.troubleshooting.service;

import vip.mate.troubleshooting.model.IncidentContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

/** Stable five-minute idempotency key for production incident ingestion. */
public final class IncidentDeduplicationKey {

    private static final long BUCKET_SECONDS = 5 * 60L;

    private IncidentDeduplicationKey() {
    }

    public static Optional<String> create(
            IncidentContext incident,
            boolean rehearsal,
            Instant receivedAt) {
        if (incident == null) {
            throw new IllegalArgumentException("incident must not be null");
        }
        if (rehearsal || incident.errorCode() == null || incident.errorCode().isBlank()) {
            return Optional.empty();
        }
        Instant basis = incident.occurredAt() == null
                ? requireReceivedAt(receivedAt)
                : incident.occurredAt();
        long bucket = Math.floorDiv(basis.getEpochSecond(), BUCKET_SECONDS);
        String raw = normalizeRouteField(incident.system())
                + "\u001f" + incident.errorCode().trim()
                + "\u001f" + normalizeRouteField(incident.service())
                + "\u001f" + bucket;
        return Optional.of(sha256(raw));
    }

    private static Instant requireReceivedAt(Instant receivedAt) {
        if (receivedAt == null) {
            throw new IllegalArgumentException("receivedAt is required when occurredAt is absent");
        }
        return receivedAt;
    }

    private static String normalizeRouteField(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
