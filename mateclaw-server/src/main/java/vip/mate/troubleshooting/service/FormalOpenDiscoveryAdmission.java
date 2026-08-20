package vip.mate.troubleshooting.service;

/**
 * Immutable authority frozen before a formal generic investigation can touch
 * an external evidence source.
 */
public record FormalOpenDiscoveryAdmission(
        int pilotPlanVersion,
        String guanceAcceptanceId,
        String guanceBindingFingerprint) {

    public FormalOpenDiscoveryAdmission {
        if (pilotPlanVersion < 1
                || guanceAcceptanceId == null
                || guanceAcceptanceId.isBlank()
                || guanceBindingFingerprint == null
                || !guanceBindingFingerprint.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException(
                    "formal open-discovery admission identity is incomplete");
        }
        guanceAcceptanceId = guanceAcceptanceId.trim();
        guanceBindingFingerprint = guanceBindingFingerprint.trim();
    }
}
