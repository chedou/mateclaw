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
import vip.mate.troubleshooting.model.InvestigationMode;
import vip.mate.troubleshooting.model.SopEntry;
import vip.mate.troubleshooting.intake.IntakeSession;
import vip.mate.troubleshooting.intake.IntakeSessionStatus;
import vip.mate.troubleshooting.intake.NormalizedIncidentFactKind;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
 * <p>Rehearsals may use complete caller-supplied evidence or the configured
 * read-only router. Formal intake is stricter: it first freezes the pilot plan,
 * active-approved Playbook and exact Guance owner acceptance, accepts only
 * server-collected Guance evidence, then revalidates mutable authority before
 * persistence. A genuine Guance {@code MISSING} result remains an honest
 * abstention; replay, fallback and caller evidence cannot masquerade as formal.</p>
 *
 * <p>If a matched Playbook cannot pass that initial formal admission, Intake
 * preserves the reported incident and enters the separately bounded generic
 * read-only investigation. Only the admission service's explicit conflict is
 * eligible: source, persistence and arbitrary runtime failures still surface
 * unchanged.</p>
 */
@Service
public class TroubleshootingIntakeService {

    /** The Agent's own key for "I am switched off / misconfigured", not "I failed". */
    private static final String AGENT_MISCONFIGURED =
            "err.troubleshooting.agent_misconfigured";
    private static final String FORMAL_ADMISSION_CONFLICT =
            "err.troubleshooting.formal_admission_conflict";
    private static final String FORMAL_ADMISSION_FALLBACK_REASON =
            "标准排障方法未通过正式准入，已转入通用只读调查";
    private static final Duration FORMAL_WEB_CLAIM_LEASE = Duration.ofMinutes(5);

    private final TroubleshootingSopPersistenceService sopPersistence;
    private final DeterministicDiagnosisService diagnosisService;
    private final EvidenceSourceRouter evidenceRouter;
    private final EvidenceSpineOrchestrator evidenceSpineOrchestrator;
    private final TroubleshootingAgentTriageService agentTriageService;
    private final FormalDiagnosisAdmissionService formalAdmissions;
    private final TroubleshootingPersistenceService existingDiagnoses;
    private final FormalDiagnosisClaimService formalClaims;
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
            TroubleshootingAgentTriageService agentTriageService,
            FormalDiagnosisAdmissionService formalAdmissions,
            TroubleshootingPersistenceService existingDiagnoses,
            FormalDiagnosisClaimService formalClaims) {
        this(sopPersistence, diagnosisService, evidenceRouter, evidenceSpineOrchestrator,
                agentTriageService, formalAdmissions, existingDiagnoses, formalClaims,
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
        this(
                sopPersistence,
                diagnosisService,
                evidenceRouter,
                evidenceSpineOrchestrator,
                agentTriageService,
                null,
                clock);
    }

    TroubleshootingIntakeService(
            TroubleshootingSopPersistenceService sopPersistence,
            DeterministicDiagnosisService diagnosisService,
            EvidenceSourceRouter evidenceRouter,
            EvidenceSpineOrchestrator evidenceSpineOrchestrator,
            TroubleshootingAgentTriageService agentTriageService,
            FormalDiagnosisAdmissionService formalAdmissions,
            Clock clock) {
        this(
                sopPersistence,
                diagnosisService,
                evidenceRouter,
                evidenceSpineOrchestrator,
                agentTriageService,
                formalAdmissions,
                null,
                null,
                clock);
    }

    TroubleshootingIntakeService(
            TroubleshootingSopPersistenceService sopPersistence,
            DeterministicDiagnosisService diagnosisService,
            EvidenceSourceRouter evidenceRouter,
            EvidenceSpineOrchestrator evidenceSpineOrchestrator,
            TroubleshootingAgentTriageService agentTriageService,
            FormalDiagnosisAdmissionService formalAdmissions,
            TroubleshootingPersistenceService existingDiagnoses,
            Clock clock) {
        this(
                sopPersistence,
                diagnosisService,
                evidenceRouter,
                evidenceSpineOrchestrator,
                agentTriageService,
                formalAdmissions,
                existingDiagnoses,
                null,
                clock);
    }

    TroubleshootingIntakeService(
            TroubleshootingSopPersistenceService sopPersistence,
            DeterministicDiagnosisService diagnosisService,
            EvidenceSourceRouter evidenceRouter,
            EvidenceSpineOrchestrator evidenceSpineOrchestrator,
            TroubleshootingAgentTriageService agentTriageService,
            FormalDiagnosisAdmissionService formalAdmissions,
            TroubleshootingPersistenceService existingDiagnoses,
            FormalDiagnosisClaimService formalClaims,
            Clock clock) {
        this.sopPersistence = sopPersistence;
        this.diagnosisService = diagnosisService;
        this.evidenceRouter = evidenceRouter;
        this.evidenceSpineOrchestrator = evidenceSpineOrchestrator;
        this.agentTriageService = agentTriageService;
        this.formalAdmissions = formalAdmissions;
        this.existingDiagnoses = existingDiagnoses;
        this.formalClaims = formalClaims;
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
        return report(session, true);
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
        IncidentContext originalSanitizedIncident = sanitizedIncident;
        requireSafeIncidentText(sanitizedIncident);
        List<EvidenceResult> sanitizedSuppliedEvidence =
                TroubleshootingEvidenceSanitizer.sanitizeSupplied(evidence);
        if (intakeSessionId != null
                && (existingDiagnoses == null || formalClaims == null)) {
            throw conflict("the IntakeSession claim runtime is not available");
        }
        if (!rehearsal
                && (formalAdmissions == null
                        || existingDiagnoses == null
                        || formalClaims == null)) {
            throw conflict("the formal admission runtime is not available");
        }
        if (!rehearsal && !sanitizedSuppliedEvidence.isEmpty()) {
            throw conflict(
                    "formal diagnosis accepts only server-collected Guance evidence");
        }
        FormalDiagnosisClaim formalClaim = null;
        if (intakeSessionId != null) {
            FormalDiagnosisClaimService.ClaimResult claimed = formalClaims.claim(
                    workspaceId,
                    FormalDiagnosisClaimKey.forIntake(workspaceId, intakeSessionId),
                    clock.instant(),
                    formalIntakeClaimLease());
            switch (claimed.state()) {
                case COMPLETED -> {
                    StoredDiagnosis completed = existingDiagnoses.get(
                            workspaceId, claimed.diagnosisId());
                    if (!rehearsal
                            && completed != null
                            && completed.diagnosis() != null
                            && completed.diagnosis().investigationMode()
                                    == InvestigationMode.OPEN_DISCOVERY) {
                        // The completed claim is the immutable idempotency
                        // authority for this IntakeSession. Re-admitting against
                        // today's mutable asset configuration would turn a safe
                        // retry into a different investigation (or reject a
                        // result that was valid when atomically committed).
                        return requireCompletedGenericIntake(completed);
                    }
                    return requireIntakeMode(completed, rehearsal);
                }
                case IN_PROGRESS -> throw conflict(
                        "the same intake session is already in progress");
                case ACQUIRED -> formalClaim = claimed.claim();
            }
            Optional<StoredDiagnosis> existing;
            try {
                existing = existingDiagnoses
                        .findByIntakeSessionId(workspaceId, intakeSessionId);
            } catch (RuntimeException failure) {
                formalClaims.release(workspaceId, formalClaim);
                throw failure;
            }
            if (existing.isPresent()) {
                try {
                    StoredDiagnosis stored = existing.orElseThrow();
                    if (!rehearsal) {
                        throw conflict(
                                "an existing Diagnosis without a completed claim cannot satisfy a formal intake");
                    }
                    StoredDiagnosis reusable = requireIntakeMode(stored, true);
                    formalClaims.complete(
                            workspaceId,
                            formalClaim,
                            reusable.diagnosis().diagnosisId(),
                            clock.instant());
                    return reusable;
                } catch (RuntimeException failure) {
                    formalClaims.release(workspaceId, formalClaim);
                    throw failure;
                }
            }
        }
        try {
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
                    if (!rehearsal && sop.scenarioScoped()) {
                        // A reviewed scenario may help rehearsal routing, but
                        // without scenario-scoped formal authority it must not
                        // stamp its selector onto the real alert. Investigate
                        // the original incident through the generic read-only
                        // lane instead.
                        return triageFormalRouteMiss(
                                workspaceId,
                                sanitizedIncident,
                                routeMissReason,
                                reportedAt,
                                intakeReadyAt == null ? clock.instant() : intakeReadyAt,
                                intakeSessionId,
                                formalClaim);
                    }
                    // The alert named no code; the matched Playbook names the route.
                    // Stamping it here is what lets the scenario lane reuse the one
                    // deterministic engine instead of growing a parallel one.
                    sanitizedIncident = sanitizedIncident.withResolvedRoute(sop.errorCode());
                } else {
                    if (!rehearsal) {
                        return triageFormalRouteMiss(
                                workspaceId,
                                sanitizedIncident,
                                routeMissReason,
                                reportedAt,
                                intakeReadyAt == null ? clock.instant() : intakeReadyAt,
                                intakeSessionId,
                                formalClaim);
                    }
                    StoredDiagnosis triaged = triageRouteMiss(
                            workspaceId,
                            sanitizedIncident,
                            sanitizedSuppliedEvidence,
                            true,
                            scenarioRoute == null
                                    ? routeMissReason
                                    : routeMissReason + "; " + scenarioRoute.missReason(),
                            reportedAt,
                            intakeReadyAt == null ? clock.instant() : intakeReadyAt,
                            intakeSessionId,
                            normalizedFactKind);
                    return completeClaimedMissPath(
                            workspaceId, triaged, formalClaim, intakeSessionId);
                }
            }

            if (sop == null) {
                sop = sopPersistence.find(
                        workspaceId, sanitizedIncident.system(), sanitizedIncident.errorCode());
            }
            if (sop == null) {
                if (!rehearsal) {
                    String reason = "no SOP registered for "
                            + sanitizedIncident.system() + ":"
                            + sanitizedIncident.errorCode();
                    return triageFormalRouteMiss(
                            workspaceId,
                            sanitizedIncident,
                            reason,
                            reportedAt,
                            intakeReadyAt == null ? clock.instant() : intakeReadyAt,
                            intakeSessionId,
                            formalClaim);
                }
                StoredDiagnosis triaged = triageRouteMiss(
                        workspaceId,
                        sanitizedIncident,
                        sanitizedSuppliedEvidence,
                        true,
                        "no SOP registered for " + sanitizedIncident.system()
                                + ":" + sanitizedIncident.errorCode(),
                        reportedAt,
                        intakeReadyAt == null ? clock.instant() : intakeReadyAt,
                        intakeSessionId,
                        normalizedFactKind);
                return completeClaimedMissPath(
                        workspaceId, triaged, formalClaim, intakeSessionId);
            }

            if (!rehearsal && sop.scenarioScoped()) {
                // Scenario Playbooks currently have no scenario-scoped formal
                // authority. A caller-supplied scenario selector must not make
                // that Playbook's selector or reviewed conclusion look like
                // evidence. Preserve the reported facts, remove only the
                // deterministic selector, and enter the generic read-only lane.
                return triageFormalRouteMiss(
                        workspaceId,
                        withoutDeterministicSelector(sanitizedIncident),
                        "场景专用排障能力尚未完成验收，已转入通用只读调查",
                        reportedAt,
                        intakeReadyAt == null ? clock.instant() : intakeReadyAt,
                        intakeSessionId,
                        formalClaim);
            }

            if (!rehearsal && intakeSessionId == null) {
                String ownerKey = IncidentDeduplicationKey.create(
                                sanitizedIncident, false, reportedAt)
                        .orElseThrow(() -> conflict(
                                "formal diagnosis requires a stable five-minute incident identity"));
                FormalDiagnosisClaimService.ClaimResult claimed = formalClaims.claim(
                        workspaceId,
                        ownerKey,
                        clock.instant(),
                        FORMAL_WEB_CLAIM_LEASE);
                switch (claimed.state()) {
                    case COMPLETED -> {
                        return requireFormalExisting(existingDiagnoses.get(
                                workspaceId, claimed.diagnosisId()));
                    }
                    case IN_PROGRESS -> throw conflict(
                            "the same formal diagnosis is already in progress");
                    case ACQUIRED -> formalClaim = claimed.claim();
                }
            }

            FormalDiagnosisAdmission formalAdmission = null;
            if (!rehearsal) {
                try {
                    formalAdmission = formalAdmissions.admit(
                            workspaceId, sanitizedIncident, sop);
                } catch (MateClawException rejected) {
                    if (!isFormalAdmissionConflict(rejected)) {
                        throw rejected;
                    }
                    // A direct deterministic run and the generic run use
                    // different durable idempotency claims. Retire the former
                    // before handing work over; an IntakeSession instead keeps
                    // its single live claim and delegates it to generic
                    // persistence for atomic completion.
                    if (intakeSessionId == null && formalClaim != null) {
                        FormalDiagnosisClaim deterministicClaim = formalClaim;
                        formalClaim = null;
                        formalClaims.release(workspaceId, deterministicClaim);
                    }
                    return triageFormalRouteMiss(
                            workspaceId,
                            originalSanitizedIncident,
                            FORMAL_ADMISSION_FALLBACK_REASON,
                            reportedAt,
                            intakeReadyAt == null ? clock.instant() : intakeReadyAt,
                            intakeSessionId,
                            formalClaim);
                }
            }

            return diagnoseWithPlaybook(
                    workspaceId,
                    sanitizedIncident,
                    sanitizedSuppliedEvidence,
                    rehearsal,
                    reportedAt,
                    intakeReadyAt,
                    intakeSessionId,
                    sop,
                    formalAdmission,
                    formalClaim);
        } catch (RuntimeException failure) {
            if (formalClaim != null) {
                try {
                    formalClaims.release(workspaceId, formalClaim);
                } catch (RuntimeException releaseFailure) {
                    failure.addSuppressed(releaseFailure);
                }
            }
            throw failure;
        }
    }

    private StoredDiagnosis diagnoseWithPlaybook(
            long workspaceId,
            IncidentContext sanitizedIncident,
            List<EvidenceResult> sanitizedSuppliedEvidence,
            boolean rehearsal,
            Instant reportedAt,
            Instant intakeReadyAt,
            String intakeSessionId,
            SopEntry sop,
            FormalDiagnosisAdmission formalAdmission,
            FormalDiagnosisClaim formalClaim) {
        Instant readyAt = intakeReadyAt == null ? clock.instant() : intakeReadyAt;
        List<EvidenceResult> collectedEvidence = TroubleshootingEvidenceSanitizer.sanitize(
                collectMissingEvidence(
                        workspaceId,
                        sop,
                        sanitizedIncident,
                        sanitizedSuppliedEvidence,
                        formalAdmission));
        // 证据成色从**这批证据自己**身上读，不再问一个全局开关：翻那个开关会让
        // 每一条诊断同时改口，包括同一时刻仍走录制回放的那些。
        boolean fixtureMode = formalAdmission == null
                ? EvidenceProvenance.fixtureMode(
                        collectedEvidence, sanitizedSuppliedEvidence)
                : EvidenceProvenance.fixtureModeForAcceptedGuanceRun(
                        collectedEvidence);
        if (formalAdmission != null) {
            if (fixtureMode) {
                throw conflict(
                        "formal diagnosis rejected fixture or unavailable-source evidence");
            }
            formalAdmissions.revalidate(
                    workspaceId, sanitizedIncident, formalAdmission);
            if (intakeSessionId == null) {
                return diagnosisService.diagnoseAndPersist(
                        workspaceId,
                        sanitizedIncident,
                        formalAdmission,
                        collectedEvidence,
                        fixtureMode,
                        reportedAt,
                        readyAt,
                        formalClaim,
                        clock.instant());
            }
            return diagnosisService.diagnoseAndPersistForIntake(
                    workspaceId,
                    sanitizedIncident,
                    formalAdmission,
                    collectedEvidence,
                    fixtureMode,
                    reportedAt,
                    readyAt,
                    intakeSessionId,
                    formalClaim,
                    clock.instant());
        }
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
                intakeSessionId,
                formalClaim,
                clock.instant());
    }

    private StoredDiagnosis requireFormalExisting(StoredDiagnosis stored) {
        if (stored == null
                || stored.diagnosis() == null
                || stored.diagnosis().rehearsal()) {
            throw conflict(
                    "a rehearsal Diagnosis cannot satisfy a formal intake session");
        }
        if (stored.pilotPlanVersion() == null
                || stored.diagnosis().sourcePlaybookVersionRef() == null) {
            throw conflict(
                    "the existing intake Diagnosis has no admitted pilot identity");
        }
        return stored;
    }

    private StoredDiagnosis requireCompletedGenericIntake(StoredDiagnosis stored) {
        if (stored == null
                || stored.diagnosis() == null
                || stored.diagnosis().rehearsal()
                || stored.diagnosis().investigationMode()
                        != InvestigationMode.OPEN_DISCOVERY
                || stored.pilotPlanVersion() == null) {
            throw conflict(
                    "the completed intake has no committed formal generic identity");
        }
        return stored;
    }

    private StoredDiagnosis requireIntakeMode(
            StoredDiagnosis stored,
            boolean rehearsal) {
        if (stored == null
                || stored.diagnosis() == null
                || stored.diagnosis().rehearsal() != rehearsal) {
            throw conflict(rehearsal
                    ? "a formal Diagnosis cannot satisfy a rehearsal intake session"
                    : "a rehearsal Diagnosis cannot satisfy a formal intake session");
        }
        return rehearsal ? stored : requireFormalExisting(stored);
    }

    private StoredDiagnosis completeClaimedMissPath(
            long workspaceId,
            StoredDiagnosis stored,
            FormalDiagnosisClaim claim,
            String intakeSessionId) {
        if (intakeSessionId == null) {
            return stored;
        }
        if (claim == null || formalClaims == null) {
            throw conflict("the IntakeSession claim runtime is not available");
        }
        StoredDiagnosis rehearsal = requireIntakeMode(stored, true);
        if (!rehearsal.created()) {
            throw conflict(
                    "an IntakeSession Diagnosis was created outside its active claim");
        }
        formalClaims.complete(
                workspaceId,
                claim,
                rehearsal.diagnosis().diagnosisId(),
                clock.instant());
        return rehearsal;
    }

    private List<EvidenceResult> collectMissingEvidence(
            long workspaceId,
            SopEntry sop,
            IncidentContext incident,
            List<EvidenceResult> supplied,
            FormalDiagnosisAdmission formalAdmission) {
        if (formalAdmission != null) {
            if (!supplied.isEmpty()) {
                throw conflict(
                        "formal diagnosis accepts only server-collected Guance evidence");
            }
            if (!sop.equals(formalAdmission.playbook())) {
                throw conflict("formal admission does not match the routed Playbook");
            }
            if (evidenceSpineOrchestrator == null) {
                throw conflict("the Evidence Spine runtime is not available");
            }
            return evidenceSpineOrchestrator.collect(
                    workspaceId,
                    incident,
                    formalAdmission.evidenceSpinePlan(),
                    Set.of("guance"))
                    .evidence();
        }
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

    private IncidentContext withoutDeterministicSelector(IncidentContext incident) {
        return new IncidentContext(
                incident.incidentId(),
                incident.system(),
                incident.service(),
                null,
                incident.title(),
                incident.severity(),
                incident.impact(),
                incident.traceId(),
                incident.occurredAt(),
                incident.slaRemaining(),
                incident.intakeSource(),
                incident.completeness(),
                incident.rawInput());
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

    private StoredDiagnosis triageFormalRouteMiss(
            long workspaceId,
            IncidentContext incident,
            String reason,
            Instant reportedAt,
            Instant readyAt,
            String intakeSessionId,
            FormalDiagnosisClaim intakeClaim) {
        if (agentTriageService == null) {
            throw routeMiss(
                    reason + "; bounded read-only investigation is disabled or unavailable");
        }
        if (intakeSessionId != null) {
            return agentTriageService.triageFormalForIntake(
                    workspaceId,
                    incident,
                    List.of(),
                    reason,
                    reportedAt,
                    readyAt,
                    intakeSessionId,
                    intakeClaim);
        }
        return agentTriageService.triageFormal(
                workspaceId,
                incident,
                List.of(),
                reason,
                reportedAt,
                readyAt);
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

    private boolean isFormalAdmissionConflict(MateClawException rejected) {
        return rejected.getCode() == 409
                && FORMAL_ADMISSION_CONFLICT.equals(rejected.getMsgKey());
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

    /**
     * Keeps the durable Intake owner alive for the whole bounded investigation.
     *
     * <p>The five-minute floor preserves the original web retry window. A
     * configured planner may legitimately run longer, so its own lease policy
     * must win instead of allowing a second request to take over the same
     * IntakeSession while the first one still owns live tool work.</p>
     */
    private Duration formalIntakeClaimLease() {
        Duration boundedLease = agentTriageService == null
                ? null
                : agentTriageService.formalOpenDiscoveryClaimLease();
        if (boundedLease == null
                || boundedLease.isZero()
                || boundedLease.isNegative()
                || boundedLease.compareTo(FORMAL_WEB_CLAIM_LEASE) < 0) {
            return FORMAL_WEB_CLAIM_LEASE;
        }
        return boundedLease;
    }
}
