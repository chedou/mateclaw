package vip.mate.troubleshooting.intake;

import vip.mate.troubleshooting.TroubleshootingSecretRedactor;
import vip.mate.workspace.conversation.model.MessageContentPart;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Safe attachment metadata retained by an intake session.
 *
 * <p>Local paths, signed URLs and provider media tokens are intentionally not
 * part of this contract. The session keeps the stable stored name when one is
 * available; otherwise it stores a deterministic message-scoped reference.
 * Video is reference-only in the current product boundary.</p>
 */
public record IntakeAttachmentRef(
        String kind,
        String reference,
        String fileName,
        String contentType,
        Long fileSize,
        boolean contentAnalyzed) {

    private static final Pattern MIME_TYPE = Pattern.compile(
            "^[A-Za-z0-9][A-Za-z0-9.+-]{0,63}/[A-Za-z0-9*][A-Za-z0-9.+-]{0,127}$");

    public IntakeAttachmentRef {
        kind = required(kind, "kind");
        reference = safeReference(required(reference, "reference"));
        if (reference == null) {
            throw new IllegalArgumentException("reference must be a safe opaque identifier");
        }
        fileName = safeDisplayName(fileName);
        contentType = safeContentType(contentType);
        if (fileSize != null && fileSize < 0) {
            throw new IllegalArgumentException("fileSize must not be negative");
        }
        if ("video".equalsIgnoreCase(kind) && contentAnalyzed) {
            throw new IllegalArgumentException("video content analysis is not enabled");
        }
    }

    public static List<IntakeAttachmentRef> fromContentParts(
            List<MessageContentPart> parts,
            String sourceMessageId) {
        if (parts == null || parts.isEmpty()) {
            return List.of();
        }
        String messageRef = sourceMessageId == null || sourceMessageId.isBlank()
                ? "unknown-message"
                : fingerprint(sourceMessageId);
        List<IntakeAttachmentRef> refs = new ArrayList<>();
        int attachmentIndex = 0;
        for (MessageContentPart part : parts) {
            if (part == null || !isAttachment(part.getType())) {
                continue;
            }
            attachmentIndex++;
            String stableReference = safeStableName(part.getStoredName());
            if (stableReference == null) {
                stableReference = "channel-message:" + messageRef + ":" + attachmentIndex;
            }
            refs.add(new IntakeAttachmentRef(
                    text(part.getType()),
                    stableReference,
                    safeDisplayName(part.getFileName()),
                    safeContentType(part.getContentType()),
                    part.getFileSize(),
                    false));
        }
        return List.copyOf(refs);
    }

    private static boolean isAttachment(String type) {
        return "image".equals(type)
                || "file".equals(type)
                || "audio".equals(type)
                || "video".equals(type);
    }

    private static String safeStableName(String value) {
        return safeReference(text(value));
    }

    private static String safeReference(String value) {
        if (value == null
                || value.contains("/")
                || value.contains("\\")
                || value.contains("://")
                || value.contains("?")) {
            return null;
        }
        return value;
    }

    private static String safeDisplayName(String value) {
        return safeReference(text(value));
    }

    private static String safeContentType(String value) {
        String normalized = text(value);
        if (normalized == null) {
            return null;
        }
        int parameters = normalized.indexOf(';');
        String baseType = (parameters < 0 ? normalized : normalized.substring(0, parameters)).trim();
        return MIME_TYPE.matcher(baseType).matches() ? baseType : null;
    }

    private static String required(String value, String field) {
        String normalized = text(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static String text(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String safe = TroubleshootingSecretRedactor.redact(value.trim());
        return safe.length() <= 512 ? safe : safe.substring(0, 512);
    }

    private static String fingerprint(String value) {
        try {
            String digest = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
            return digest.substring(0, 24);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
