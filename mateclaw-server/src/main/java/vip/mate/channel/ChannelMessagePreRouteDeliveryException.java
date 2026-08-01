package vip.mate.channel;

/**
 * Signals that a pre-route handler durably accepted the message but could not
 * deliver its acknowledgement.
 *
 * <p>The router must keep the message claimed without sending its generic
 * persistence-failure reply, because the intake has already been committed.</p>
 */
public final class ChannelMessagePreRouteDeliveryException extends RuntimeException {

    public ChannelMessagePreRouteDeliveryException(String message) {
        super(message);
    }

    public ChannelMessagePreRouteDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
