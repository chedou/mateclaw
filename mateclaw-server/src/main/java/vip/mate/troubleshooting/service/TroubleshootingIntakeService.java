package vip.mate.troubleshooting.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.evidence.EvidenceSourceRouter;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.model.SopEntry;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Intake seam for the deterministic hit path.
 *
 * <p>Routing is a plain {@code (system, error_code)} lookup in the domain
 * tables, so a hit costs zero LLM calls. Two situations deliberately fail
 * loudly instead of degrading into a guess:</p>
 * <ul>
 *   <li><b>No error code / symptom-only report.</b> Deterministic routing has
 *       nothing to key on. The miss path belongs to a caged read-only agent
 *       that is not wired yet, so we reject rather than invent a conclusion.</li>
 *   <li><b>No SOP for the route.</b> Same reasoning: an unknown error code is
 *       a knowledge gap, and reporting it as such is more useful than a
 *       fabricated diagnosis.</li>
 * </ul>
 *
 * <p>Caller-provided evidence wins. Any SOP request that is absent or explicitly
 * missing is offered to the read-only evidence router. Source failures become
 * canonical {@code MISSING} evidence, preserving the existing abstention
 * boundary. Until the 903001 bindings are live-verified, every diagnosis remains
 * marked {@code fixtureMode}.</p>
 */
@Service
public class TroubleshootingIntakeService {

    /** Remains true until the read-only bindings and thresholds are live-verified. */
    private static final boolean EVIDENCE_IS_FIXTURE = true;

    private final TroubleshootingSopPersistenceService sopPersistence;
    private final DeterministicDiagnosisService diagnosisService;
    private final EvidenceSourceRouter evidenceRouter;
    private final Clock clock;

    public TroubleshootingIntakeService(
            TroubleshootingSopPersistenceService sopPersistence,
            DeterministicDiagnosisService diagnosisService) {
        this(sopPersistence, diagnosisService, null, Clock.systemUTC());
    }

    @Autowired
    public TroubleshootingIntakeService(
            TroubleshootingSopPersistenceService sopPersistence,
            DeterministicDiagnosisService diagnosisService,
            EvidenceSourceRouter evidenceRouter) {
        this(sopPersistence, diagnosisService, evidenceRouter, Clock.systemUTC());
    }

    TroubleshootingIntakeService(
            TroubleshootingSopPersistenceService sopPersistence,
            DeterministicDiagnosisService diagnosisService,
            Clock clock) {
        this(sopPersistence, diagnosisService, null, clock);
    }

    TroubleshootingIntakeService(
            TroubleshootingSopPersistenceService sopPersistence,
            DeterministicDiagnosisService diagnosisService,
            EvidenceSourceRouter evidenceRouter,
            Clock clock) {
        this.sopPersistence = sopPersistence;
        this.diagnosisService = diagnosisService;
        this.evidenceRouter = evidenceRouter;
        this.clock = clock;
    }

    /**
     * Routes an incoming incident and stores the resulting diagnosis.
     *
     * <p>Replays inside the five-minute deduplication bucket return the stored
     * diagnosis with {@code created=false} rather than a second run, so an
     * alert source that retries cannot fan out duplicate cases.</p>
     */
    public StoredDiagnosis report(
            long workspaceId,
            IncidentContext incident,
            List<EvidenceResult> evidence,
            boolean rehearsal) {
        if (incident == null) {
            throw badRequest("incident is required");
        }
        requireDeterministicRouting(incident);

        SopEntry sop = sopPersistence.find(
                workspaceId, incident.system(), incident.errorCode());
        if (sop == null) {
            throw routeMiss(
                    "no SOP registered for " + incident.system() + ":" + incident.errorCode()
                            + "; the miss path (read-only agent triage) is not wired yet");
        }

        List<EvidenceResult> collectedEvidence = collectMissingEvidence(
                sop, incident, evidence == null ? List.of() : evidence);
        return diagnosisService.diagnoseAndPersist(
                workspaceId,
                incident,
                sop,
                collectedEvidence,
                rehearsal,
                EVIDENCE_IS_FIXTURE,
                Instant.now(clock));
    }

    private List<EvidenceResult> collectMissingEvidence(
            SopEntry sop,
            IncidentContext incident,
            List<EvidenceResult> supplied) {
        if (evidenceRouter == null) {
            return supplied;
        }

        Map<String, EvidenceResult> merged = new LinkedHashMap<>();
        for (EvidenceResult result : supplied) {
            if (merged.putIfAbsent(result.queryId(), result) != null) {
                throw new IllegalArgumentException(
                        "duplicate evidence queryId: " + result.queryId());
            }
        }
        for (EvidenceRequest request : sop.evidenceRequests()) {
            EvidenceResult current = merged.get(request.requestId());
            if (current != null && current.status() != EvidenceStatus.MISSING) {
                continue;
            }
            EvidenceResult collected = evidenceRouter.collect(request, incident);
            if (current == null || collected.status() != EvidenceStatus.MISSING) {
                merged.put(request.requestId(), collected);
            }
        }
        return List.copyOf(merged.values());
    }

    private void requireDeterministicRouting(IncidentContext incident) {
        if (incident.errorCode() == null || incident.errorCode().isBlank()) {
            throw routeMiss("incident carries no errorCode; deterministic routing needs one "
                    + "and the miss path is not wired yet");
        }
        if (incident.completeness() == IncidentCompleteness.SYMPTOM) {
            throw routeMiss("incident completeness is SYMPTOM; deterministic routing needs a "
                    + "structured report and the miss path is not wired yet");
        }
    }

    private MateClawException routeMiss(String message) {
        return new MateClawException("err.troubleshooting.route_miss", 409, message);
    }

    private MateClawException badRequest(String message) {
        return new MateClawException("err.troubleshooting.invalid_request", 400, message);
    }
}
