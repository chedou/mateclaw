package vip.mate.troubleshooting.intake;

/** State owned by intake only; it must never be folded into DiagnosisStatus. */
public enum IntakeSessionStatus {
    RECEIVED,
    AWAITING_INPUT,
    READY
}
