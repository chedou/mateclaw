package vip.mate.troubleshooting.evidence;

/** Called immediately before each read-only source request in one Evidence Spine run. */
@FunctionalInterface
public interface EvidenceSpineRunControl {

    void beforeSourceRequest(String requestId);

    static EvidenceSpineRunControl unbounded() {
        return requestId -> { };
    }
}
