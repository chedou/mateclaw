package vip.mate.troubleshooting.evidence;

import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.model.SopEntry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a Playbook's evidence plan through the read-only Router, filling in only
 * what is not already answered.
 *
 * <p><b>Why it is its own type.</b> This was private to the error-code intake
 * path, where the Diagnosis is built after the evidence exists. A scenario
 * investigation runs the other way round — the Diagnosis is created first and
 * waits — so it needed the same collection and had nowhere to get it. Copying
 * the loop would have produced two answers to "what did we already have", which
 * is the drift A9 forbids.</p>
 *
 * <p><b>Not every evidence request belongs here.</b> A request that names an
 * {@code assetType} is served by that asset's own authorized read-only tool
 * (D18) — deployment topology is the shipped example — not by the observability
 * Router. Handing one to this collector would return {@code MISSING} and record
 * "we looked and found nothing" about a source that was never asked. Callers
 * check {@link #servesEveryRequiredRequest} before running a plan.</p>
 */
public final class PlaybookEvidenceCollector {

    /** Target key that marks a request as belonging to a Workspace asset's own tool. */
    public static final String ASSET_TYPE_TARGET = "assetType";

    private final EvidenceSourceRouter router;

    public PlaybookEvidenceCollector(EvidenceSourceRouter router) {
        this.router = router;
    }

    /**
     * @return false when a required request is served by a Workspace asset tool
     *     rather than the Router, so the caller can decline instead of
     *     manufacturing a MISSING result for a source it never consulted
     */
    public static boolean servesEveryRequiredRequest(SopEntry playbook) {
        if (playbook == null) {
            return false;
        }
        return playbook.evidenceRequests().stream()
                .filter(EvidenceRequest::required)
                .noneMatch(request -> request.target().containsKey(ASSET_TYPE_TARGET));
    }

    /**
     * @param supplied evidence already held; a non-MISSING entry is never
     *     re-collected, because re-running a query that already answered would
     *     let a later flaky read overwrite a good one
     */
    public List<EvidenceResult> collect(
            long workspaceId,
            SopEntry playbook,
            IncidentContext incident,
            List<EvidenceResult> supplied) {
        List<EvidenceResult> safeSupplied = List.copyOf(supplied == null ? List.of() : supplied);
        if (router == null || playbook == null) {
            return safeSupplied;
        }
        Map<String, EvidenceResult> merged = new LinkedHashMap<>();
        for (EvidenceResult result : safeSupplied) {
            if (merged.putIfAbsent(result.queryId(), result) != null) {
                throw new IllegalArgumentException(
                        "duplicate evidence queryId: " + result.queryId());
            }
        }
        for (EvidenceRequest request : playbook.evidenceRequests()) {
            EvidenceResult current = merged.get(request.requestId());
            if (current != null && current.status() != EvidenceStatus.MISSING) {
                continue;
            }
            EvidenceResult collected = router.collect(workspaceId, request, incident);
            if (current == null || collected.status() != EvidenceStatus.MISSING) {
                merged.put(request.requestId(), collected);
            }
        }
        return List.copyOf(merged.values());
    }
}
