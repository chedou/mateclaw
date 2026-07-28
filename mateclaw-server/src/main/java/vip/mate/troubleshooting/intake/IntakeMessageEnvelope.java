package vip.mate.troubleshooting.intake;

import java.time.Instant;
import java.util.List;

/** Normalized channel message handed to the troubleshooting intake domain. */
public record IntakeMessageEnvelope(
        long workspaceId,
        String source,
        String sourceMessageId,
        String conversationRef,
        String reporterRef,
        String text,
        List<IntakeAttachmentRef> attachments,
        Instant receivedAt) {

    public IntakeMessageEnvelope {
        if (workspaceId <= 0) {
            throw new IllegalArgumentException("workspaceId must be positive");
        }
        source = required(source, "source");
        sourceMessageId = required(sourceMessageId, "sourceMessageId");
        conversationRef = required(conversationRef, "conversationRef");
        reporterRef = required(reporterRef, "reporterRef");
        text = text == null ? "" : text.trim();
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        if (receivedAt == null) {
            throw new IllegalArgumentException("receivedAt must not be null");
        }
        if (text.isBlank() && attachments.isEmpty()) {
            throw new IllegalArgumentException("intake message must contain text or attachments");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
