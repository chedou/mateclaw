package vip.mate.troubleshooting.model;

/** Approval records intent only; it never authorizes an in-process execution. */
public enum ApprovalStatus {
    NOT_REQUIRED,
    PENDING,
    APPROVED_NOT_EXECUTED
}
