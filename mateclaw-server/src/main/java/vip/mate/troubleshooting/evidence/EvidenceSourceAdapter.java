package vip.mate.troubleshooting.evidence;

import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.IncidentContext;

/** Read-only boundary between troubleshooting semantics and an observability platform. */
public interface EvidenceSourceAdapter {

    /** Stable source name referenced by route configuration. */
    String platform();

    /** Whether this adapter has a binding for the semantic signal kind. */
    boolean supports(String signalKind);

    /** Collects one request as canonical evidence; implementations must fail closed. */
    EvidenceResult collect(EvidenceRequest request, IncidentContext incident);

    /** Current source readiness for diagnostics and capability reporting. */
    EvidenceSourceHealth health();
}
