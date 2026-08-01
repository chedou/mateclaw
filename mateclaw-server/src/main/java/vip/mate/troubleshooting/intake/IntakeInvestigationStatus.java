package vip.mate.troubleshooting.intake;

/** Delivery state for the durable READY -> Diagnosis hand-off. */
public enum IntakeInvestigationStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,
    TERMINAL_PENDING,
    TERMINAL_PROCESSING
}
