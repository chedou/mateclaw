package vip.mate.troubleshooting.service;

import org.springframework.stereotype.Service;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.model.SopEntry;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

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
 * <p>Evidence arrives from the caller for now. Read-only source adapters land
 * later, so nothing here may claim the evidence was collected and verified by
 * MateClaw — every diagnosis produced through this seam is marked
 * {@code fixtureMode}.</p>
 */
@Service
public class TroubleshootingIntakeService {

    /** Until read-only source adapters exist, no evidence is MateClaw-verified. */
    private static final boolean EVIDENCE_IS_FIXTURE = true;

    private final TroubleshootingSopPersistenceService sopPersistence;
    private final DeterministicDiagnosisService diagnosisService;
    private final Clock clock;

    public TroubleshootingIntakeService(
            TroubleshootingSopPersistenceService sopPersistence,
            DeterministicDiagnosisService diagnosisService) {
        this(sopPersistence, diagnosisService, Clock.systemUTC());
    }

    TroubleshootingIntakeService(
            TroubleshootingSopPersistenceService sopPersistence,
            DeterministicDiagnosisService diagnosisService,
            Clock clock) {
        this.sopPersistence = sopPersistence;
        this.diagnosisService = diagnosisService;
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

        return diagnosisService.diagnoseAndPersist(
                workspaceId,
                incident,
                sop,
                evidence == null ? List.of() : evidence,
                rehearsal,
                EVIDENCE_IS_FIXTURE,
                Instant.now(clock));
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
