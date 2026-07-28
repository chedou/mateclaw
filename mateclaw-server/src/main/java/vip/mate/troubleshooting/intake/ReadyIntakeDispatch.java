package vip.mate.troubleshooting.intake;

import java.util.Objects;

/** READY aggregate plus its optional transport-specific delivery route. */
public record ReadyIntakeDispatch(
        IntakeSession session,
        String deliveryConversationId) {

    public ReadyIntakeDispatch {
        session = Objects.requireNonNull(session, "session");
        deliveryConversationId = deliveryConversationId == null
                || deliveryConversationId.isBlank()
                ? null
                : deliveryConversationId.trim();
    }

    /** New sessions use the exact ChannelSessionStore key; legacy rows fall back safely. */
    public String routeRef() {
        return deliveryConversationId == null
                ? session.conversationRef()
                : deliveryConversationId;
    }
}
