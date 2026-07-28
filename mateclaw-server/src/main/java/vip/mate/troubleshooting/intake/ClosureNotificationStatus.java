package vip.mate.troubleshooting.intake;

/** Durable delivery state attached to a closed channel-origin Diagnosis. */
public enum ClosureNotificationStatus {
    NOT_APPLICABLE,
    PENDING,
    PROCESSING,
    FAILED,
    COMPLETED
}
