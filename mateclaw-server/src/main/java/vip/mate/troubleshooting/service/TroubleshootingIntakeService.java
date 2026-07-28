package vip.mate.troubleshooting.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.TroubleshootingEvidenceSanitizer;
import vip.mate.troubleshooting.TroubleshootingSafetyPolicy;
import vip.mate.troubleshooting.TroubleshootingSecretRedactor;
import vip.mate.troubleshooting.agent.TroubleshootingAgentTriageService;
import vip.mate.troubleshooting.evidence.EvidenceSourceRouter;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.model.IncidentImpact;
import vip.mate.troubleshooting.model.SopEntry;
import vip.mate.troubleshooting.intake.IntakeSession;
import vip.mate.troubleshooting.intake.IntakeSessionStatus;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Intake seam for the deterministic hit path.
 *
 * <p>Routing is a plain {@code (system, error_code)} lookup in the domain
 * tables, so a hit costs zero LLM calls. Three situations are delegated to
 * the separately caged miss path:</p>
 * <ul>
 *   <li><b>No error code / symptom-only report.</b> Deterministic routing has
 *       nothing trustworthy to key on.</li>
 *   <li><b>No SOP for the route.</b> Same reasoning: an unknown error code is
 *       a knowledge gap, not a deterministic diagnosis.</li>
 * </ul>
 * The miss path remains fail-closed: its rollout switch is off by default and
 * unsafe or missing Agent configuration yields 409 before any model call.
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
    private final TroubleshootingSopPersistenceService sopPersistence;
    private final DeterministicDiagnosisService diagnosisService;
    private final EvidenceSourceRouter evidenceRouter;
    private final TroubleshootingAgentTriageService agentTriageService;
    private final Clock clock;

    public TroubleshootingIntakeService(
            TroubleshootingSopPersistenceService sopPersistence,
            DeterministicDiagnosisService diagnosisService) {
        this(sopPersistence, diagnosisService, null, null, Clock.systemUTC());
    }

    public TroubleshootingIntakeService(
            TroubleshootingSopPersistenceService sopPersistence,
            DeterministicDiagnosisService diagnosisService,
            EvidenceSourceRouter evidenceRouter) {
        this(sopPersistence, diagnosisService, evidenceRouter, null, Clock.systemUTC());
    }

    @Autowired
    public TroubleshootingIntakeService(
            TroubleshootingSopPersistenceService sopPersistence,
            DeterministicDiagnosisService diagnosisService,
            EvidenceSourceRouter evidenceRouter,
            TroubleshootingAgentTriageService agentTriageService) {
        this(sopPersistence, diagnosisService, evidenceRouter, agentTriageService,
                Clock.systemUTC());
    }

    TroubleshootingIntakeService(
            TroubleshootingSopPersistenceService sopPersistence,
            DeterministicDiagnosisService diagnosisService,
            Clock clock) {
        this(sopPersistence, diagnosisService, null, null, clock);
    }

    TroubleshootingIntakeService(
            TroubleshootingSopPersistenceService sopPersistence,
            DeterministicDiagnosisService diagnosisService,
            EvidenceSourceRouter evidenceRouter,
            Clock clock) {
        this(sopPersistence, diagnosisService, evidenceRouter, null, clock);
    }

    TroubleshootingIntakeService(
            TroubleshootingSopPersistenceService sopPersistence,
            DeterministicDiagnosisService diagnosisService,
            EvidenceSourceRouter evidenceRouter,
            TroubleshootingAgentTriageService agentTriageService,
            Clock clock) {
        this.sopPersistence = sopPersistence;
        this.diagnosisService = diagnosisService;
        this.evidenceRouter = evidenceRouter;
        this.agentTriageService = agentTriageService;
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
        return report(workspaceId, incident, evidence, rehearsal, clock.instant());
    }

    /** Preserves the protocol arrival timestamp captured before request mapping. */
    public StoredDiagnosis report(
            long workspaceId,
            IncidentContext incident,
            List<EvidenceResult> evidence,
            boolean rehearsal,
            Instant reportedAt) {
        return reportInternal(
                workspaceId,
                incident,
                evidence,
                rehearsal,
                reportedAt,
                null,
                null);
    }

    /** Starts investigation from a complete, durably persisted channel intake. */
    public StoredDiagnosis report(IntakeSession session) {
        if (session == null || session.status() != IntakeSessionStatus.READY
                || session.readyAt() == null) {
            throw badRequest("READY intake session is required");
        }
        IncidentContext incident = new IncidentContext(
                "incident-" + session.intakeSessionId(),
                session.system(),
                session.service(),
                session.errorCode(),
                session.symptom(),
                "P2",
                IncidentImpact.unknown("客户/影响对象: " + session.customerRef()),
                session.traceId(),
                session.occurredAt(),
                null,
                "channel:" + session.source(),
                IncidentCompleteness.STRUCTURED,
                session.symptom());
        return reportInternal(
                session.workspaceId(),
                incident,
                List.of(),
                false,
                session.reportedAt(),
                session.readyAt(),
                session.intakeSessionId());
    }

    private StoredDiagnosis reportInternal(
            long workspaceId,
            IncidentContext incident,
            List<EvidenceResult> evidence,
            boolean rehearsal,
            Instant reportedAt,
            Instant intakeReadyAt,
            String intakeSessionId) {
        if (incident == null) {
            throw badRequest("incident is required");
        }
        if (reportedAt == null) {
            throw badRequest("reportedAt is required");
        }
        IncidentContext sanitizedIncident = TroubleshootingSecretRedactor.redact(incident);
        String routeMissReason = deterministicRouteMissReason(sanitizedIncident);
        if (routeMissReason != null) {
            return triageRouteMiss(
                    workspaceId,
                    sanitizedIncident,
                    evidence,
                    rehearsal,
                    routeMissReason,
                    reportedAt,
                    intakeReadyAt == null ? clock.instant() : intakeReadyAt,
                    intakeSessionId);
        }

        SopEntry sop = sopPersistence.find(
                workspaceId, sanitizedIncident.system(), sanitizedIncident.errorCode());
        if (sop == null) {
            return triageRouteMiss(
                    workspaceId,
                    sanitizedIncident,
                    evidence,
                    rehearsal,
                    "no SOP registered for " + sanitizedIncident.system()
                            + ":" + sanitizedIncident.errorCode(),
                    reportedAt,
                    intakeReadyAt == null ? clock.instant() : intakeReadyAt,
                    intakeSessionId);
        }

        Instant readyAt = intakeReadyAt == null ? clock.instant() : intakeReadyAt;
        List<EvidenceResult> collectedEvidence = TroubleshootingEvidenceSanitizer.sanitize(
                collectMissingEvidence(
                        workspaceId,
                        sop,
                        sanitizedIncident,
                        evidence == null ? List.of() : evidence));
        if (intakeSessionId == null) {
            return diagnosisService.diagnoseAndPersist(
                    workspaceId,
                    sanitizedIncident,
                    sop,
                    collectedEvidence,
                    rehearsal,
                    TroubleshootingSafetyPolicy.EVIDENCE_IS_FIXTURE,
                    reportedAt,
                    readyAt);
        }
        return diagnosisService.diagnoseAndPersistForIntake(
                workspaceId,
                sanitizedIncident,
                sop,
                collectedEvidence,
                rehearsal,
                TroubleshootingSafetyPolicy.EVIDENCE_IS_FIXTURE,
                reportedAt,
                readyAt,
                intakeSessionId);
    }

    private List<EvidenceResult> collectMissingEvidence(
            long workspaceId,
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
            EvidenceResult collected = evidenceRouter.collect(workspaceId, request, incident);
            if (current == null || collected.status() != EvidenceStatus.MISSING) {
                merged.put(request.requestId(), collected);
            }
        }
        return List.copyOf(merged.values());
    }

    private String deterministicRouteMissReason(IncidentContext incident) {
        if (incident.errorCode() == null || incident.errorCode().isBlank()) {
            return "incident carries no errorCode; deterministic routing needs one";
        }
        if (incident.completeness() == IncidentCompleteness.SYMPTOM) {
            return "incident completeness is SYMPTOM; deterministic routing needs a structured report";
        }
        return null;
    }

    private StoredDiagnosis triageRouteMiss(
            long workspaceId,
            IncidentContext incident,
            List<EvidenceResult> evidence,
            boolean rehearsal,
            String reason,
            Instant reportedAt,
            Instant readyAt,
            String intakeSessionId) {
        if (agentTriageService == null) {
            throw routeMiss(reason + "; read-only Agent miss path is disabled or unavailable");
        }
        if (intakeSessionId == null) {
            return agentTriageService.triage(
                    workspaceId,
                    incident,
                    evidence == null ? List.of() : evidence,
                    rehearsal,
                    reason,
                    reportedAt,
                    readyAt);
        }
        return agentTriageService.triageForIntake(
                workspaceId,
                incident,
                evidence == null ? List.of() : evidence,
                rehearsal,
                reason,
                reportedAt,
                readyAt,
                intakeSessionId);
    }

    private MateClawException routeMiss(String message) {
        return new MateClawException("err.troubleshooting.route_miss", 409, message);
    }

    private MateClawException badRequest(String message) {
        return new MateClawException("err.troubleshooting.invalid_request", 400, message);
    }
}
