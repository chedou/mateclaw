package vip.mate.troubleshooting.evidence;

import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.IncidentContext;

/** Read-only boundary between troubleshooting semantics and an observability platform. */
public interface EvidenceSourceAdapter {

    /** Stable source name referenced by route configuration. */
    String platform();

    /**
     * Capability prefilter: whether any static binding supports the semantic signal.
     * This is never a tenant authorization decision; {@link #collect(long,
     * EvidenceRequest, IncidentContext)} must enforce the exact workspace resource.
     */
    boolean supports(String signalKind);

    /**
     * Collects one request as canonical evidence for an authenticated workspace.
     * Implementations must validate tenant/resource authorization before using
     * credentials or contacting the source, and must fail closed.
     */
    EvidenceResult collect(long workspaceId, EvidenceRequest request, IncidentContext incident);

    /** Current source readiness for diagnostics and capability reporting. */
    EvidenceSourceHealth health();
}
