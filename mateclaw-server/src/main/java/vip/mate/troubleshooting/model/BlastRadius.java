package vip.mate.troubleshooting.model;

/** Evidence-backed scope of an incident; UNKNOWN is different from zero impact. */
public enum BlastRadius {
    SINGLE_CUSTOMER,
    MULTI_CUSTOMER,
    SYSTEM_WIDE,
    UNKNOWN
}
