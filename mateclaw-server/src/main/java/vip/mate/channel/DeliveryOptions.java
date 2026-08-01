package vip.mate.channel;

import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * RFC-063r §2.10: Parameter Object that bundles optional delivery hints
 * (Slack {@code thread_ts}, Telegram {@code message_thread_id}, multi-bot
 * {@code accountId}, etc.) so {@link ChannelManager#sendToChannel} doesn't
 * grow a 5-arg overload.
 *
 * <p>{@link #DEFAULTS} is the canonical "no hints" instance — adapters that
 * don't override the 4-arg {@code proactiveSend} keep their pre-RFC behavior.
 */
public record DeliveryOptions(
        @Nullable String threadId,
        @Nullable String accountId,
        Map<String, Object> ext
) {

    public static final String EXT_MENTION_USER_IDS = "mentionUserIds";
    public static final String EXT_REQUIRE_REPLY_CONTEXT = "requireReplyContext";
    public static final DeliveryOptions DEFAULTS = new DeliveryOptions(null, null, Map.of());

    public DeliveryOptions {
        // Defensive: never expose a null map — the receiver should be able to
        // call .get(...) without a null check.
        ext = ext == null ? Map.of() : Map.copyOf(ext);
    }

    /** Creates delivery hints that ask a supporting adapter to mention users. */
    public static DeliveryOptions mentioningUsers(List<String> userIds) {
        if (userIds == null) {
            return DEFAULTS;
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String userId : userIds) {
            if (userId != null && !userId.isBlank()) {
                normalized.add(userId.trim());
            }
        }
        if (normalized.isEmpty()) {
            return DEFAULTS;
        }
        return new DeliveryOptions(
                null,
                null,
                Map.of(EXT_MENTION_USER_IDS, List.copyOf(normalized)));
    }

    /**
     * Requires an adapter-owned reply slot instead of a generic proactive
     * fallback. WeCom group delivery uses this after resolving a durable
     * ChannelSession: the platform rejects {@code aibot_send_msg} for groups,
     * so a node may advertise the route only while it owns a current inbound
     * {@code req_id} that can carry {@code aibot_respond_msg}.
     */
    public static DeliveryOptions requiringReplyContext() {
        return DEFAULTS.withRequiredReplyContext();
    }

    /** Returns a copy that preserves existing hints and requires a reply slot. */
    public DeliveryOptions withRequiredReplyContext() {
        if (requiresReplyContext()) {
            return this;
        }
        java.util.LinkedHashMap<String, Object> merged = new java.util.LinkedHashMap<>(ext);
        merged.put(EXT_REQUIRE_REPLY_CONTEXT, Boolean.TRUE);
        return new DeliveryOptions(threadId, accountId, merged);
    }

    public boolean requiresReplyContext() {
        return Boolean.TRUE.equals(ext.get(EXT_REQUIRE_REPLY_CONTEXT));
    }

    /** Returns normalized mention recipients; malformed extension values are ignored. */
    public List<String> mentionUserIds() {
        Object value = ext.get(EXT_MENTION_USER_IDS);
        if (!(value instanceof Iterable<?> values)) {
            return List.of();
        }
        ArrayList<String> result = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Object item : values) {
            if (item instanceof String userId && !userId.isBlank()) {
                String normalized = userId.trim();
                if (seen.add(normalized)) {
                    result.add(normalized);
                }
            }
        }
        return List.copyOf(result);
    }
}
