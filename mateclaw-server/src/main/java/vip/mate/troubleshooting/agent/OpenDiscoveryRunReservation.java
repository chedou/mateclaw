package vip.mate.troubleshooting.agent;

import vip.mate.troubleshooting.service.StoredDiagnosis;

/** Result of the pre-execution idempotency boundary. */
public record OpenDiscoveryRunReservation(
        StoredDiagnosis completedDiagnosis,
        OpenDiscoveryRunClaim claim) {

    public OpenDiscoveryRunReservation {
        if (completedDiagnosis != null && claim != null) {
            throw new IllegalArgumentException(
                    "a completed reservation cannot also own a claim");
        }
    }

    public static OpenDiscoveryRunReservation completed(StoredDiagnosis diagnosis) {
        return new OpenDiscoveryRunReservation(diagnosis, null);
    }

    public static OpenDiscoveryRunReservation acquired(OpenDiscoveryRunClaim claim) {
        if (claim == null) {
            throw new IllegalArgumentException("an acquired reservation requires a claim");
        }
        return new OpenDiscoveryRunReservation(null, claim);
    }

    /** Rehearsals and channel-owned intake leases intentionally have no web claim. */
    public static OpenDiscoveryRunReservation unclaimed() {
        return new OpenDiscoveryRunReservation(null, null);
    }

    public boolean alreadyCompleted() {
        return completedDiagnosis != null;
    }
}
