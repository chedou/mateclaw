package vip.mate.channel;

import vip.mate.channel.model.ChannelEntity;

/**
 * Opt-in handler for domain intake that must run before the generic Agent path.
 *
 * <p>A handler first declares whether it owns a message. Once claimed, failure
 * is fail-closed: the router does not send the same report through an unrelated
 * Agent or Trigger pipeline. This is the extension seam used by troubleshooting
 * while preserving the existing channel adapters and webhook/signature path.</p>
 */
public interface ChannelMessagePreRouteHandler {

    /** True only for messages and explicitly configured channels owned by this handler. */
    boolean supports(ChannelMessage message, ChannelAdapter adapter, ChannelEntity channelEntity);

    /** Persist/acknowledge the claimed message. Implementations must not call an LLM here. */
    void handle(ChannelMessage message, ChannelAdapter adapter, ChannelEntity channelEntity);

    /** Lower values run first when more than one independent domain handler is installed. */
    default int order() {
        return 0;
    }
}
