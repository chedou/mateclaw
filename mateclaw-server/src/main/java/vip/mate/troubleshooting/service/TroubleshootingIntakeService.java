package vip.mate.troubleshooting.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.TroubleshootingBusinessTextPolicy;
import vip.mate.troubleshooting.TroubleshootingEvidenceSanitizer;
import vip.mate.troubleshooting.TroubleshootingSecretRedactor;
import vip.mate.troubleshooting.agent.TroubleshootingAgentTriageService;
import vip.mate.troubleshooting.evidence.EvidenceProvenance;
import vip.mate.troubleshooting.evidence.EvidenceSourceRouter;
import vip.mate.troubleshooting.evidence.EvidenceSpineOrchestrator;
import vip.mate.troubleshooting.evidence.EvidenceSpinePlan;
import vip.mate.troubleshooting.evidence.EvidenceSpinePlanResolver;
import vip.mate.troubleshooting.evidence.PlaybookEvidenceCollector;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.model.IncidentImpact;
import vip.mate.troubleshooting.model.SopEntry;
import vip.mate.troubleshooting.intake.IntakeSession;
import vip.mate.troubleshooting.intake.IntakeSessionStatus;
import vip.mate.troubleshooting.intake.NormalizedIncidentFactKind;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;

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

    /** The Agent's own key for "I am switched off / misconfigured", not "I failed". */
    private static final String AGENT_MISCONFIGURED =
            "err.troubleshooting.agent_misconfigured";

    /** Remains true until the read-only bindings and thresholds are live-verified. */
    private final TroubleshootingSopPersistenceService sopPersistence;
    private final DeterministicDiagnosisService diagnosisService;
    private final EvidenceSourceRouter evidenceRouter;
    private final EvidenceSpineOrchestrator evidenceSpineOrchestrator;
    private final TroubleshootingAgentTriageService agentTriageService;
    private final ScenarioSymptomRouter scenarioRouter;
    private final Clock clock;

    public TroubleshootingIntakeService(
            TroubleshootingSopPersistenceService sopPersistence,
            DeterministicDiagnosisService diagnosisService) {
        this(sopPersistence, diagnosisService, null, null, null, Clock.systemUTC());
    }

    public TroubleshootingIntakeService(
            TroubleshootingSopPersistenceService sopPersistence,
            DeterministicDiagnosisService diagnosisService,
            EvidenceSourceRouter evidenceRouter) {
        this(sopPersistence, diagnosisService, evidenceRouter, null, null, Clock.systemUTC());
    }

    public TroubleshootingIntakeService(
            TroubleshootingSopPersistenceService sopPersistence,
            DeterministicDiagnosisService diagnosisService,
            EvidenceSourceRouter evidenceRouter,
            TroubleshootingAgentTriageService agentTriageService) {
        this(sopPersistence, diagnosisService, evidenceRouter, null,
                agentTriageService, Clock.systemUTC());
    }

    @Autowired
    public TroubleshootingIntakeService(
            TroubleshootingSopPersistenceService sopPersistence,
            DeterministicDiagnosisService diagnosisService,
            EvidenceSourceRouter evidenceRouter,
            EvidenceSpineOrchestrator evidenceSpineOrchestrator,
            TroubleshootingAgentTriageService agentTriageService) {
        this(sopPersistence, diagnosisService, evidenceRouter, evidenceSpineOrchestrator,
                agentTriageService,
                Clock.systemUTC());
    }

    TroubleshootingIntakeService(
            TroubleshootingSopPersistenceService sopPersistence,
            DeterministicDiagnosisService diagnosisService,
            Clock clock) {
        this(sopPersistence, diagnosisService, null, null, null, clock);
    }

    TroubleshootingIntakeService(
            TroubleshootingSopPersistenceService sopPersistence,
            DeterministicDiagnosisService diagnosisService,
            EvidenceSourceRouter evidenceRouter,
            Clock clock) {
        this(sopPersistence, diagnosisService, evidenceRouter, null, null, clock);
    }

    TroubleshootingIntakeService(
            TroubleshootingSopPersistenceService sopPersistence,
            DeterministicDiagnosisService diagnosisService,
            EvidenceSourceRouter evidenceRouter,
            TroubleshootingAgentTriageService agentTriageService,
            Clock clock) {
        this(sopPersistence, diagnosisService, evidenceRouter, null,
                agentTriageService, clock);
    }

    TroubleshootingIntakeService(
            TroubleshootingSopPersistenceService sopPersistence,
            DeterministicDiagnosisService diagnosisService,
            EvidenceSourceRouter evidenceRouter,
            EvidenceSpineOrchestrator evidenceSpineOrchestrator,
            TroubleshootingAgentTriageService agentTriageService,
            Clock clock) {
        this.sopPersistence = sopPersistence;
        this.diagnosisService = diagnosisService;
        this.evidenceRouter = evidenceRouter;
        this.evidenceSpineOrchestrator = evidenceSpineOrchestrator;
        this.agentTriageService = agentTriageService;
        // Derived rather than injected: it reads the same registry and holds no
        // state of its own, so every existing constructor keeps its arity.
        this.scenarioRouter = sopPersistence == null
                ? null
                : new ScenarioSymptomRouter(sopPersistence);
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
                null,
                null);
    }

    /** Starts investigation from a complete, durably persisted channel intake. */
    public StoredDiagnosis report(IntakeSession session) {
        return report(session, false);
    }

    /** Starts a Web conversation intake with an explicit rehearsal boundary. */
    public StoredDiagnosis report(IntakeSession session, boolean rehearsal) {
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
                rehearsal,
                session.reportedAt(),
                session.readyAt(),
                session.intakeSessionId(),
                session.normalizedFactKind());
    }

    private StoredDiagnosis reportInternal(
            long workspaceId,
            IncidentContext incident,
            List<EvidenceResult> evidence,
            boolean rehearsal,
            Instant reportedAt,
            Instant intakeReadyAt,
            String intakeSessionId,
            NormalizedIncidentFactKind normalizedFactKind) {
        if (incident == null) {
            throw badRequest("incident is required");
        }
        if (reportedAt == null) {
            throw badRequest("reportedAt is required");
        }
        IncidentContext sanitizedIncident = TroubleshootingSecretRedactor.redact(incident);
        requireSafeIncidentText(sanitizedIncident);
        List<EvidenceResult> sanitizedSuppliedEvidence =
                TroubleshootingEvidenceSanitizer.sanitizeSupplied(evidence);
        SopEntry sop = null;
        String routeMissReason = deterministicRouteMissReason(sanitizedIncident);
        if (routeMissReason != null) {
            // An alert raised by symptom rather than by error code can still be
            // owned by a reviewed Playbook. Only a unique declared match counts;
            // anything else keeps the miss reason it already had.
            ScenarioSymptomRouter.ScenarioRoute scenarioRoute =
                    scenarioRouter == null || !symptomRoutable(sanitizedIncident)
                            ? null
                            : scenarioRouter.route(workspaceId, sanitizedIncident);
            if (scenarioRoute != null && scenarioRoute.matched()) {
                sop = scenarioRoute.playbook();
                // The alert named no code; the matched Playbook names the route.
                // Stamping it here is what lets the scenario lane reuse the one
                // deterministic engine instead of growing a parallel one.
                sanitizedIncident = sanitizedIncident.withResolvedRoute(sop.errorCode());
            } else {
                return triageRouteMiss(
                        workspaceId,
                        sanitizedIncident,
                        sanitizedSuppliedEvidence,
                        rehearsal,
                        scenarioRoute == null
                                ? routeMissReason
                                : routeMissReason + "; " + scenarioRoute.missReason(),
                        reportedAt,
                        intakeReadyAt == null ? clock.instant() : intakeReadyAt,
                        intakeSessionId,
                        normalizedFactKind);
            }
        }

        if (sop == null) {
            sop = sopPersistence.find(
                    workspaceId, sanitizedIncident.system(), sanitizedIncident.errorCode());
        }
        if (sop == null) {
            return triageRouteMiss(
                    workspaceId,
                    sanitizedIncident,
                    sanitizedSuppliedEvidence,
                    rehearsal,
                    "no SOP registered for " + sanitizedIncident.system()
                            + ":" + sanitizedIncident.errorCode(),
                    reportedAt,
                    intakeReadyAt == null ? clock.instant() : intakeReadyAt,
                    intakeSessionId,
                    normalizedFactKind);
        }

        Instant readyAt = intakeReadyAt == null ? clock.instant() : intakeReadyAt;
        List<EvidenceResult> collectedEvidence = TroubleshootingEvidenceSanitizer.sanitize(
                collectMissingEvidence(
                        workspaceId,
                        sop,
                        sanitizedIncident,
                        sanitizedSuppliedEvidence));
        // 证据成色从**这批证据自己**身上读，不再问一个全局开关：翻那个开关会让
        // 每一条诊断同时改口，包括同一时刻仍走录制回放的那些。
        boolean fixtureMode = EvidenceProvenance.fixtureMode(
                collectedEvidence, sanitizedSuppliedEvidence);
        if (intakeSessionId == null) {
            return diagnosisService.diagnoseAndPersist(
                    workspaceId,
                    sanitizedIncident,
                    sop,
                    collectedEvidence,
                    rehearsal,
                    fixtureMode,
                    reportedAt,
                    readyAt);
        }
        return diagnosisService.diagnoseAndPersistForIntake(
                workspaceId,
                sanitizedIncident,
                sop,
                collectedEvidence,
                rehearsal,
                fixtureMode,
                reportedAt,
                readyAt,
                intakeSessionId);
    }

    private List<EvidenceResult> collectMissingEvidence(
            long workspaceId,
            SopEntry sop,
            IncidentContext incident,
            List<EvidenceResult> supplied) {
        EvidenceSpinePlan spinePlan;
        try {
            spinePlan = EvidenceSpinePlanResolver.resolve(sop);
        } catch (IllegalArgumentException invalidContract) {
            throw conflict("the frozen Evidence Spine contract is invalid");
        }
        if (spinePlan != null && supplied.isEmpty()) {
            if (evidenceSpineOrchestrator == null) {
                throw conflict("the Evidence Spine runtime is not available");
            }
            return evidenceSpineOrchestrator.collect(
                    workspaceId, incident, spinePlan, null).evidence();
        }
        if (spinePlan != null && !supplied.isEmpty()) {
            Set<String> expectedIds = sop.evidenceRequests().stream()
                    .map(request -> request.requestId())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            Set<String> suppliedIds = supplied.stream()
                    .map(EvidenceResult::queryId)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (supplied.size() != expectedIds.size()
                    || suppliedIds.size() != supplied.size()
                    || !suppliedIds.equals(expectedIds)) {
                throw conflict(
                        "a partial caller-supplied Evidence Spine cannot be completed safely");
            }
        }
        // Generic Playbooks and complete caller-supplied replay evidence keep the
        // existing path. A dependent spine is always executed by the shared
        // orchestrator above so trace/contrast receive the observed correlation ID.
        return new PlaybookEvidenceCollector(evidenceRouter)
                .collect(workspaceId, sop, incident, supplied);
    }

    /**
     * Whether a symptom may stand in for the missing error code.
     *
     * <p>Only the absent code is substitutable. An unstructured report is a
     * different failure: its system and service were never confirmed, so
     * matching its free text against a Playbook would attach reviewed authority
     * to fields nobody has verified. Those keep going to the miss path, where
     * Intake can still ask for the structured fields.
     */
    private boolean symptomRoutable(IncidentContext incident) {
        return incident.completeness() != IncidentCompleteness.SYMPTOM;
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

    private void requireSafeIncidentText(IncidentContext incident) {
        try {
            TroubleshootingBusinessTextPolicy.requireNoDeveloperEvidence(incident);
        } catch (IllegalArgumentException unsafeText) {
            throw badRequest(unsafeText.getMessage());
        }
    }

    private StoredDiagnosis triageRouteMiss(
            long workspaceId,
            IncidentContext incident,
            List<EvidenceResult> evidence,
            boolean rehearsal,
            String reason,
            Instant reportedAt,
            Instant readyAt,
            String intakeSessionId,
            NormalizedIncidentFactKind normalizedFactKind) {
        if (agentTriageService == null) {
            throw routeMiss(reason + "; read-only Agent miss path is disabled or unavailable");
        }
        try {
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
                    intakeSessionId,
                    normalizedFactKind);
        } catch (MateClawException refused) {
            throw agentUnavailable(reason, refused);
        }
    }

    /**
     * 兜底路自己走不了时，把**先发生的那件事**还给调用方。
     *
     * <p>此前这里什么都不做：确定性路没命中的原因（比如「这个系统的这个错误码
     * 没有已注册的 Playbook」）算出来、传进 Agent、然后被 Agent 自己的
     * 「miss-path Agent is disabled」覆盖掉。新租户看到的是一句像基础设施故障的
     * 话，而真正的事实是他还没有登记过这条知识——两件事的下一步完全不同。</p>
     *
     * <p>只接管 Agent 的配置类拒绝。Agent 真的跑了但失败，是另一回事，原样抛出。</p>
     */
    private MateClawException agentUnavailable(
            String routeMissReason,
            MateClawException refused) {
        if (!AGENT_MISCONFIGURED.equals(refused.getMsgKey())) {
            return refused;
        }
        return routeMiss(routeMissReason
                + "; and the read-only miss-path Agent cannot run ("
                + refused.getMessage()
                + "). Either register a Playbook for this route via"
                + " POST /api/v1/troubleshooting/sops and approve it, or enable"
                + " mateclaw.troubleshooting.agent.enabled");
    }

    private MateClawException routeMiss(String message) {
        return new MateClawException("err.troubleshooting.route_miss", 409, message);
    }

    private MateClawException conflict(String message) {
        return new MateClawException("err.troubleshooting.conflict", 409, message);
    }

    private MateClawException badRequest(String message) {
        return new MateClawException("err.troubleshooting.invalid_request", 400, message);
    }
}
