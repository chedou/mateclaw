package vip.mate.troubleshooting.intake;

import vip.mate.troubleshooting.TroubleshootingSecretRedactor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Pure reducer for RECEIVED -> AWAITING_INPUT -> READY. */
public final class IntakeSessionReducer {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final List<DateTimeFormatter> LOCAL_TIME_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    private static final List<String> REQUIRED_FIELDS = List.of(
            "symptom", "system", "service", "customerRef", "occurredAt");

    public IntakeSession start(String intakeSessionId, IntakeMessageEnvelope envelope) {
        ParsedInput parsed = parse(envelope.text());
        List<IntakeSessionEvent> initialTimeline = List.of(
                new IntakeSessionEvent(
                        envelope.receivedAt(), IntakeSessionStatus.RECEIVED,
                        envelope.sourceMessageId()));
        return build(
                intakeSessionId,
                envelope,
                parsed.symptom(),
                parsed.system(),
                parsed.service(),
                parsed.customerRef(),
                parsed.errorCode(),
                parsed.traceId(),
                parsed.occurredAt(),
                envelope.attachments(),
                envelope.receivedAt(),
                null,
                initialTimeline);
    }

    public IntakeSession accept(IntakeSession current, IntakeMessageEnvelope envelope) {
        if (current == null) {
            throw new IllegalArgumentException("current intake session must not be null");
        }
        if (current.workspaceId() != envelope.workspaceId()
                || !current.source().equals(envelope.source())
                || !current.conversationRef().equals(envelope.conversationRef())
                || !current.reporterRef().equals(envelope.reporterRef())) {
            throw new IllegalArgumentException("intake message belongs to another session");
        }
        if (current.status() == IntakeSessionStatus.READY) {
            // READY is a hand-off checkpoint. Later chat frames may be stored as
            // receipts by the coordinator, but cannot silently rewrite fields
            // that were already declared ready for investigation.
            return current;
        }
        if (!envelope.receivedAt().isAfter(current.lastMessageAt())) {
            return current;
        }
        ParsedInput parsed = parse(envelope.text());
        return build(
                current.intakeSessionId(),
                envelope,
                first(parsed.symptom(), current.symptom()),
                first(parsed.system(), current.system()),
                first(parsed.service(), current.service()),
                first(parsed.customerRef(), current.customerRef()),
                first(parsed.errorCode(), current.errorCode()),
                first(parsed.traceId(), current.traceId()),
                parsed.occurredAt() == null ? current.occurredAt() : parsed.occurredAt(),
                mergeAttachments(current.attachments(), envelope.attachments()),
                current.reportedAt(),
                current.readyAt(),
                current.timeline());
    }

    private IntakeSession build(
            String intakeSessionId,
            IntakeMessageEnvelope envelope,
            String symptom,
            String system,
            String service,
            String customerRef,
            String errorCode,
            String traceId,
            Instant occurredAt,
            List<IntakeAttachmentRef> attachments,
            Instant reportedAt,
            Instant existingReadyAt,
            List<IntakeSessionEvent> timeline) {
        List<String> missing = missing(symptom, system, service, customerRef, occurredAt);
        IntakeSessionStatus status = missing.isEmpty()
                ? IntakeSessionStatus.READY
                : IntakeSessionStatus.AWAITING_INPUT;
        Instant readyAt = status == IntakeSessionStatus.READY
                ? existingReadyAt == null ? envelope.receivedAt() : existingReadyAt
                : null;
        List<IntakeSessionEvent> nextTimeline = new ArrayList<>(timeline);
        IntakeSessionStatus lastStatus = nextTimeline.isEmpty()
                ? null
                : nextTimeline.getLast().status();
        if (lastStatus != status) {
            nextTimeline.add(new IntakeSessionEvent(
                    envelope.receivedAt(), status, envelope.sourceMessageId()));
        }
        return new IntakeSession(
                intakeSessionId,
                IntakeSession.CURRENT_CONTRACT_VERSION,
                envelope.workspaceId(),
                envelope.source(),
                envelope.conversationRef(),
                envelope.reporterRef(),
                status,
                safe(symptom),
                safe(system),
                safe(service),
                safe(customerRef),
                safe(errorCode),
                safe(traceId),
                occurredAt,
                attachments,
                missing,
                reportedAt,
                readyAt,
                envelope.receivedAt(),
                nextTimeline);
    }

    private ParsedInput parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return ParsedInput.empty();
        }
        Map<String, String> fields = new LinkedHashMap<>();
        List<String> freeLines = new ArrayList<>();
        for (String rawLine : raw.lines().toList()) {
            String line = rawLine.trim();
            if (line.isBlank() || isMediaMarker(line)) {
                continue;
            }
            int separator = separator(line);
            if (separator > 0) {
                String key = canonicalKey(line.substring(0, separator));
                if (key != null) {
                    fields.put(key, safe(line.substring(separator + 1)));
                    continue;
                }
            }
            freeLines.add(line);
        }
        String symptom = fields.get("symptom");
        if (symptom == null && !freeLines.isEmpty()) {
            symptom = safe(String.join("\n", freeLines));
        }
        return new ParsedInput(
                symptom,
                fields.get("system"),
                fields.get("service"),
                fields.get("customerRef"),
                fields.get("errorCode"),
                fields.get("traceId"),
                parseOccurredAt(fields.get("occurredAt")));
    }

    private Instant parseOccurredAt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // Try an explicit offset next.
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            // Try the two documented local formats next.
        }
        for (DateTimeFormatter format : LOCAL_TIME_FORMATS) {
            try {
                return LocalDateTime.parse(value, format).atZone(BUSINESS_ZONE).toInstant();
            } catch (DateTimeParseException ignored) {
                // Continue through the small deterministic format set.
            }
        }
        return null;
    }

    private List<String> missing(
            String symptom,
            String system,
            String service,
            String customerRef,
            Instant occurredAt) {
        Map<String, Object> values = Map.of(
                "symptom", nullable(symptom),
                "system", nullable(system),
                "service", nullable(service),
                "customerRef", nullable(customerRef),
                "occurredAt", occurredAt == null ? "" : occurredAt);
        return REQUIRED_FIELDS.stream()
                .filter(field -> isMissing(values.get(field)))
                .toList();
    }

    private boolean isMissing(Object value) {
        return value == null || (value instanceof String text && text.isBlank());
    }

    private Object nullable(String value) {
        return value == null ? "" : value;
    }

    private List<IntakeAttachmentRef> mergeAttachments(
            List<IntakeAttachmentRef> current,
            List<IntakeAttachmentRef> incoming) {
        Map<String, IntakeAttachmentRef> merged = new LinkedHashMap<>();
        for (IntakeAttachmentRef ref : current) {
            merged.put(ref.kind() + "\u0000" + ref.reference(), ref);
        }
        for (IntakeAttachmentRef ref : incoming) {
            merged.putIfAbsent(ref.kind() + "\u0000" + ref.reference(), ref);
        }
        return List.copyOf(merged.values());
    }

    private String canonicalKey(String rawKey) {
        String key = rawKey.trim().toLowerCase(Locale.ROOT)
                .replace("_", "")
                .replace("-", "")
                .replace(" ", "");
        return switch (key) {
            case "现象", "问题", "问题现象", "symptom", "title" -> "symptom";
            case "系统", "system" -> "system";
            case "服务", "service" -> "service";
            case "客户id", "客户", "租户id", "customerid", "customerref" -> "customerRef";
            case "发生时间", "时间", "occurredat" -> "occurredAt";
            case "错误码", "errorcode" -> "errorCode";
            case "traceid", "psid", "psid/traceid" -> "traceId";
            default -> null;
        };
    }

    private int separator(String line) {
        int ascii = line.indexOf(':');
        int chinese = line.indexOf('：');
        if (ascii < 0) return chinese;
        if (chinese < 0) return ascii;
        return Math.min(ascii, chinese);
    }

    private boolean isMediaMarker(String line) {
        return line.matches("^【?(?:图片|视频|音频|语音|文件)(?::[^]]*)?】?$")
                || line.matches("^\\[(?:图片|视频|音频|语音|文件)(?::[^]]*)?]$");
    }

    private String first(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private String safe(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String redacted = TroubleshootingSecretRedactor.redact(value.trim());
        return redacted.length() <= 2000 ? redacted : redacted.substring(0, 2000);
    }

    private record ParsedInput(
            String symptom,
            String system,
            String service,
            String customerRef,
            String errorCode,
            String traceId,
            Instant occurredAt) {

        static ParsedInput empty() {
            return new ParsedInput(null, null, null, null, null, null, null);
        }
    }
}
