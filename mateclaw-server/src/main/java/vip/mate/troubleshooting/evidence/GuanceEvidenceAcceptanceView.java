package vip.mate.troubleshooting.evidence;

import java.util.List;

/** Current/stale projection for one workspace-owned T7 acceptance. */
public record GuanceEvidenceAcceptanceView(
        Status status,
        String system,
        String service,
        String currentBindingFingerprint,
        GuanceEvidenceAcceptance acceptance,
        List<String> blockers) {

    public GuanceEvidenceAcceptanceView {
        status = status == null ? Status.BLOCKED : status;
        system = system == null ? "" : system.trim();
        service = service == null ? "" : service.trim();
        currentBindingFingerprint = currentBindingFingerprint == null
                ? null
                : currentBindingFingerprint.trim();
        blockers = List.copyOf(blockers == null ? List.of() : blockers);
    }

    public boolean acceptedForCurrentBinding() {
        return status == Status.ACCEPTED
                && acceptance != null
                && acceptance.bindingFingerprint().equals(currentBindingFingerprint);
    }

    public enum Status {
        BLOCKED,
        NOT_ACCEPTED,
        STALE,
        ACCEPTED
    }
}
