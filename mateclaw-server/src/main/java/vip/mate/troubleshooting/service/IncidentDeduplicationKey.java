package vip.mate.troubleshooting.service;

import vip.mate.troubleshooting.model.IncidentContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Stable five-minute idempotency key for production incident ingestion.
 * Error-code reports key on the deterministic route; symptom reports key on
 * their normalized symptom plus an optional trace so retry safety does not
 * depend on an error code being present.
 */
public final class IncidentDeduplicationKey {

    private static final long BUCKET_SECONDS = 5 * 60L;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private IncidentDeduplicationKey() {
    }

    public static Optional<String> create(
            IncidentContext incident,
            boolean rehearsal,
            Instant receivedAt) {
        if (incident == null) {
            throw new IllegalArgumentException("incident must not be null");
        }
        if (rehearsal) {
            return Optional.empty();
        }
        String errorCode = incident.errorCode() == null
                ? ""
                : incident.errorCode().trim();
        String symptomDiscriminator = errorCode.isEmpty()
                ? symptomDiscriminator(incident)
                : null;
        if (errorCode.isEmpty() && symptomDiscriminator == null) {
            return Optional.empty();
        }
        Instant basis = incident.occurredAt() == null
                ? requireReceivedAt(receivedAt)
                : incident.occurredAt();
        long bucket = Math.floorDiv(basis.getEpochSecond(), BUCKET_SECONDS);
        String system = normalizeRouteField(incident.system());
        String service = normalizeRouteField(incident.service());
        String raw;
        if (!errorCode.isEmpty()) {
            // Preserve the pre-symptom-ingestion byte format for rolling-deploy retries.
            raw = system
                    + "\u001f" + errorCode
                    + "\u001f" + service
                    + "\u001f" + bucket;
        } else {
            raw = system
                    + "\u001f" + service
                    + "\u001f" + symptomDiscriminator
                    + "\u001f" + bucket;
        }
        return Optional.of(sha256(raw));
    }

    /**
     * Stable idempotency key for an explicitly selected scenario intake.
     *
     * <p>The scenario identity is part of the key on purpose. A symptom-only
     * report and a user-selected Scenario Playbook are different routing
     * authorities and must never collapse into the same Diagnosis.</p>
     */
    public static Optional<String> createForScenario(
            IncidentContext incident,
            String scenarioKey,
            boolean rehearsal,
            Instant receivedAt) {
        if (incident == null) {
            throw new IllegalArgumentException("incident must not be null");
        }
        if (scenarioKey == null || scenarioKey.isBlank()) {
            throw new IllegalArgumentException("scenarioKey must not be blank");
        }
        if (rehearsal) {
            return Optional.empty();
        }
        Instant basis = incident.occurredAt() == null
                ? requireReceivedAt(receivedAt)
                : incident.occurredAt();
        long bucket = Math.floorDiv(basis.getEpochSecond(), BUCKET_SECONDS);
        String traceId = incident.traceId() == null ? "" : incident.traceId().trim();
        String raw = "scenario"
                + "\u001f" + normalizeRouteField(incident.system())
                + "\u001f" + normalizeRouteField(incident.service())
                + "\u001f" + normalizeRouteField(scenarioKey)
                + "\u001f" + normalizeSymptom(incident.title())
                + "\u001f" + traceId
                + "\u001f" + bucket;
        return Optional.of(sha256(raw));
    }

    private static String symptomDiscriminator(IncidentContext incident) {
        String symptom = normalizeSymptom(incident.title());
        String traceId = incident.traceId() == null ? "" : incident.traceId().trim();
        if (symptom.isEmpty() && traceId.isEmpty()) {
            return null;
        }
        return "symptom\u001f" + symptom + "\u001f" + traceId;
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

    private static String normalizeSymptom(String value) {
        if (value == null) {
            return "";
        }
        return WHITESPACE.matcher(value.trim()).replaceAll(" ").toLowerCase(Locale.ROOT);
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
