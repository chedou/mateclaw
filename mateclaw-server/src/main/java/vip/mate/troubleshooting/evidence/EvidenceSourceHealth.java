package vip.mate.troubleshooting.evidence;

/** Operator-facing source readiness without overstating live verification. */
public record EvidenceSourceHealth(
        String platform,
        Status status,
        boolean verified,
        String detail) {

    public EvidenceSourceHealth {
        if (platform == null || platform.isBlank()) {
            throw new IllegalArgumentException("platform must not be blank");
        }
        platform = platform.trim();
        status = status == null ? Status.DEGRADED : status;
        detail = detail == null ? "" : detail;
    }

    public enum Status {
        READY,
        DEGRADED,
        DISABLED
    }
}
