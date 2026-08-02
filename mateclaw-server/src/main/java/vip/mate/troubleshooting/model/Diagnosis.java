package vip.mate.troubleshooting.model;

import java.util.List;
import java.util.Set;

/**
 * Immutable troubleshooting aggregate contract.
 *
 * <p>The constructor rejects write execution by design. Human approval can
 * only change action metadata; an external outcome is recorded separately.</p>
 */
public record Diagnosis(
        String diagnosisId,
        String contractVersion,
        String caseId,
        String runId,
        IncidentContext incident,
        RouteMode routeMode,
        InvestigationMode investigationMode,
        RouteAuthority routeAuthority,
        ConclusionType conclusionType,
        DiagnosisStatus status,
        String summary,
        String rootCause,
        Confidence confidence,
        boolean abstained,
        String sopKey,
        String sopTitle,
        String sourcePlaybookOwner,
        PlaybookVersionRef sourcePlaybookVersionRef,
        List<EvidenceResult> evidence,
        List<String> evidenceCitations,
        List<String> triggeredSignals,
        List<RecommendedAction> recommendedActions,
        List<RecommendedAction> pendingWrites,
        String routeToTeam,
        List<TransferRecord> transfers,
        List<ActionOutcomeRecord> actionOutcomes,
        ClosureRecord closure,
        List<KnowledgeCandidate> knowledgeCandidates,
        List<TimelineEvent> timeline,
        NorthStarTimings timings,
        boolean rehearsal,
        boolean fixtureMode,
        boolean writeExecutionEnabled,
        List<String> warnings) {

    public static final String CURRENT_CONTRACT_VERSION = "1.8";
    private static final String FROZEN_OWNER_CONTRACT_VERSION = "1.7";
    private static final Set<String> SUPPORTED_CONTRACT_VERSIONS =
            Set.of(
                    "1.3", "1.4", "1.5", "1.6",
                    FROZEN_OWNER_CONTRACT_VERSION, CURRENT_CONTRACT_VERSION);

    public Diagnosis {
        diagnosisId = required(diagnosisId, "diagnosisId");
        contractVersion = required(contractVersion, "contractVersion");
        if (!SUPPORTED_CONTRACT_VERSIONS.contains(contractVersion)) {
            throw new IllegalArgumentException(
                    "unsupported diagnosis contractVersion: " + contractVersion);
        }
        caseId = required(caseId, "caseId");
        runId = required(runId, "runId");
        if (incident == null || routeMode == null || status == null || confidence == null) {
            throw new IllegalArgumentException("incident, routeMode, status and confidence are required");
        }
        boolean legacyContract = "1.3".equals(contractVersion) || "1.4".equals(contractVersion);
        if (investigationMode == null) {
            if (!legacyContract) {
                throw new IllegalArgumentException("investigationMode is required for diagnosis 1.5+");
            }
            investigationMode = defaultInvestigationMode(routeMode);
        }
        if (routeAuthority == null) {
            if (!legacyContract) {
                throw new IllegalArgumentException("routeAuthority is required for diagnosis 1.5+");
            }
            routeAuthority = defaultRouteAuthority(routeMode);
        }
        if (conclusionType == null) {
            if (!legacyContract) {
                throw new IllegalArgumentException("conclusionType is required for diagnosis 1.5+");
            }
            conclusionType = defaultConclusionType(routeMode, abstained);
        }
        if (timings == null) {
            if (!legacyContract) {
                throw new IllegalArgumentException("timings are required for diagnosis 1.5+");
            }
            timings = NorthStarTimings.unrecorded();
        }
        summary = summary == null ? "" : summary;
        rootCause = rootCause == null ? "" : rootCause;
        sourcePlaybookOwner = sourcePlaybookOwner == null
                || sourcePlaybookOwner.isBlank()
                ? null : sourcePlaybookOwner.trim();
        if (sourcePlaybookVersionRef != null
                && !CURRENT_CONTRACT_VERSION.equals(contractVersion)) {
            throw new IllegalArgumentException(
                    "only diagnosis 1.8 may carry an exact Playbook version");
        }
        if (sourcePlaybookVersionRef != null && (sopKey == null || sopKey.isBlank())) {
            throw new IllegalArgumentException(
                    "an exact Playbook version requires a diagnosis selector");
        }
        if (CURRENT_CONTRACT_VERSION.equals(contractVersion)
                && routeMode == RouteMode.DETERMINISTIC
                && sourcePlaybookVersionRef == null) {
            throw new IllegalArgumentException(
                    "diagnosis 1.8 deterministic routes require an exact Playbook version");
        }
        if (routeMode == RouteMode.LLM_FALLBACK && sourcePlaybookVersionRef != null) {
            throw new IllegalArgumentException(
                    "LLM fallback cannot claim an approved Playbook version");
        }
        evidence = immutable(evidence);
        evidenceCitations = immutable(evidenceCitations);
        triggeredSignals = immutable(triggeredSignals);
        recommendedActions = immutable(recommendedActions);
        pendingWrites = immutable(pendingWrites);
        transfers = immutable(transfers);
        actionOutcomes = immutable(actionOutcomes);
        knowledgeCandidates = immutable(knowledgeCandidates);
        timeline = immutable(timeline);
        warnings = immutable(warnings);
        if (writeExecutionEnabled) {
            throw new IllegalArgumentException("production write execution must remain disabled");
        }
        validateExperienceClassification(
                incident,
                routeMode,
                investigationMode,
                routeAuthority,
                conclusionType,
                confidence,
                abstained,
                legacyContract);
        validateEvidenceCitations(evidence, evidenceCitations);
        if (routeMode == RouteMode.LLM_FALLBACK) {
            if (!recommendedActions.isEmpty() || !pendingWrites.isEmpty()) {
                throw new IllegalArgumentException(
                        "LLM fallback must not recommend or execute actions");
            }
            if (!abstained && evidenceCitations.isEmpty()) {
                throw new IllegalArgumentException(
                        "non-abstained LLM fallback requires evidence citations");
            }
        }
        validateLifecycle(
                diagnosisId,
                status,
                abstained,
                recommendedActions,
                pendingWrites,
                routeToTeam,
                transfers,
                actionOutcomes,
                closure,
                knowledgeCandidates);
    }

    public static Diagnosis initial(
            String diagnosisId,
            String caseId,
            String runId,
            IncidentContext incident,
            RouteMode routeMode,
            DiagnosisStatus status,
            String summary,
            String rootCause,
            Confidence confidence,
            boolean abstained,
            String sopKey,
            String sopTitle,
            List<EvidenceResult> evidence,
            List<String> triggeredSignals,
            List<RecommendedAction> recommendedActions,
            String routeToTeam,
            boolean rehearsal,
            boolean fixtureMode,
            List<String> warnings) {
        if (routeMode != RouteMode.LLM_FALLBACK) {
            throw new IllegalArgumentException(
                    "deterministic diagnosis creation requires an exact Playbook version");
        }
        return initial(
                diagnosisId,
                caseId,
                runId,
                incident,
                routeMode,
                defaultInvestigationMode(routeMode),
                defaultRouteAuthority(routeMode),
                defaultConclusionType(routeMode, abstained),
                NorthStarTimings.unrecorded(),
                status, summary, rootCause, confidence, abstained,
                sopKey, sopTitle, null, null,
                evidence, triggeredSignals, recommendedActions, routeToTeam,
                rehearsal, fixtureMode, warnings, List.of());
    }

    public static Diagnosis initial(
            String diagnosisId,
            String caseId,
            String runId,
            IncidentContext incident,
            RouteMode routeMode,
            DiagnosisStatus status,
            String summary,
            String rootCause,
            Confidence confidence,
            boolean abstained,
            String sopKey,
            String sopTitle,
            PlaybookVersionRef sourcePlaybookVersionRef,
            List<EvidenceResult> evidence,
            List<String> triggeredSignals,
            List<RecommendedAction> recommendedActions,
            String routeToTeam,
            boolean rehearsal,
            boolean fixtureMode,
            List<String> warnings) {
        return initial(
                diagnosisId,
                caseId,
                runId,
                incident,
                routeMode,
                defaultInvestigationMode(routeMode),
                defaultRouteAuthority(routeMode),
                defaultConclusionType(routeMode, abstained),
                NorthStarTimings.unrecorded(),
                status, summary, rootCause, confidence, abstained,
                sopKey, sopTitle, null, sourcePlaybookVersionRef,
                evidence, triggeredSignals, recommendedActions, routeToTeam,
                rehearsal, fixtureMode, warnings, List.of());
    }

    /** Creates a current v4 diagnosis with an exact Playbook authority and no owner claim. */
    public static Diagnosis initial(
            String diagnosisId,
            String caseId,
            String runId,
            IncidentContext incident,
            RouteMode routeMode,
            InvestigationMode investigationMode,
            RouteAuthority routeAuthority,
            ConclusionType conclusionType,
            NorthStarTimings timings,
            DiagnosisStatus status,
            String summary,
            String rootCause,
            Confidence confidence,
            boolean abstained,
            String sopKey,
            String sopTitle,
            PlaybookVersionRef sourcePlaybookVersionRef,
            List<EvidenceResult> evidence,
            List<String> triggeredSignals,
            List<RecommendedAction> recommendedActions,
            String routeToTeam,
            boolean rehearsal,
            boolean fixtureMode,
            List<String> warnings,
            List<TimelineEvent> timeline) {
        return initial(
                diagnosisId,
                caseId,
                runId,
                incident,
                routeMode,
                investigationMode,
                routeAuthority,
                conclusionType,
                timings,
                status,
                summary,
                rootCause,
                confidence,
                abstained,
                sopKey,
                sopTitle,
                null,
                sourcePlaybookVersionRef,
                evidence,
                triggeredSignals,
                recommendedActions,
                routeToTeam,
                rehearsal,
                fixtureMode,
                warnings,
                timeline);
    }

    /**
     * Creates a current diagnosis and freezes the immutable Playbook authority
     * used for its deterministic decision.
     */
    public static Diagnosis initial(
            String diagnosisId,
            String caseId,
            String runId,
            IncidentContext incident,
            RouteMode routeMode,
            InvestigationMode investigationMode,
            RouteAuthority routeAuthority,
            ConclusionType conclusionType,
            NorthStarTimings timings,
            DiagnosisStatus status,
            String summary,
            String rootCause,
            Confidence confidence,
            boolean abstained,
            String sopKey,
            String sopTitle,
            String sourcePlaybookOwner,
            PlaybookVersionRef sourcePlaybookVersionRef,
            List<EvidenceResult> evidence,
            List<String> triggeredSignals,
            List<RecommendedAction> recommendedActions,
            String routeToTeam,
            boolean rehearsal,
            boolean fixtureMode,
            List<String> warnings,
            List<TimelineEvent> timeline) {
        if (status != DiagnosisStatus.READY_FOR_HUMAN
                && status != DiagnosisStatus.NEEDS_INVESTIGATION) {
            throw new IllegalArgumentException("initial diagnosis must start before human confirmation");
        }
        List<RecommendedAction> pendingWrites = immutable(recommendedActions).stream()
                .filter(action -> action.actionType() == ActionType.MANUAL_WRITE)
                .filter(action -> action.approvalStatus() == ApprovalStatus.PENDING)
                .toList();
        return new Diagnosis(
                diagnosisId,
                CURRENT_CONTRACT_VERSION,
                caseId,
                runId,
                incident,
                routeMode,
                investigationMode,
                routeAuthority,
                conclusionType,
                status,
                summary,
                rootCause,
                confidence,
                abstained,
                sopKey,
                sopTitle,
                sourcePlaybookOwner,
                sourcePlaybookVersionRef,
                evidence,
                List.of(),
                triggeredSignals,
                recommendedActions,
                pendingWrites,
                routeToTeam,
                List.of(),
                List.of(),
                null,
                List.of(),
                timeline,
                timings,
                rehearsal,
                fixtureMode,
                false,
                warnings);
    }

    /** Creates an initial miss-path aggregate after Agent output validation. */
    public static Diagnosis initialAgentFallback(
            AgentTriageDraft draft,
            DiagnosisStatus status,
            List<TimelineEvent> timeline) {
        if (draft == null) {
            throw new IllegalArgumentException("Agent triage draft is required");
        }
        if (status != DiagnosisStatus.READY_FOR_HUMAN
                && status != DiagnosisStatus.NEEDS_INVESTIGATION) {
            throw new IllegalArgumentException("Agent triage must start before human confirmation");
        }
        return new Diagnosis(
                draft.diagnosisId(),
                CURRENT_CONTRACT_VERSION,
                draft.caseId(),
                draft.runId(),
                draft.incident(),
                RouteMode.LLM_FALLBACK,
                InvestigationMode.OPEN_DISCOVERY,
                RouteAuthority.MODEL_PROPOSED,
                draft.abstained()
                        ? ConclusionType.INSUFFICIENT_EVIDENCE
                        : ConclusionType.HYPOTHESIS,
                status,
                draft.summary(),
                draft.hypothesis(),
                draft.confidence(),
                draft.abstained(),
                null,
                null,
                null,
                null,
                draft.evidence(),
                draft.evidenceCitations(),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of(),
                List.of(),
                null,
                List.of(),
                timeline,
                draft.timings(),
                draft.rehearsal(),
                draft.fixtureMode(),
                false,
                draft.warnings());
    }

    /**
     * Read-only evidence arrived for a scenario investigation; the conclusion is
     * revised from it.
     *
     * <p><b>Why this had to exist.</b> A scenario Diagnosis is created
     * {@code abstained} — naming a scenario picks an evidence plan, it does not
     * assert a cause — and {@code confirm} refuses an abstained Diagnosis until
     * new evidence arrives. Both halves were right and nothing supplied that
     * evidence, so every scenario Diagnosis in the system was permanently stuck
     * in {@code NEEDS_INVESTIGATION}: it could never be confirmed, handed off,
     * or closed. This is the transition that was missing.</p>
     *
     * <p><b>It cannot manufacture a conclusion.</b> The caller passes what the
     * deterministic evaluator concluded from this exact evidence. When that is
     * still {@code INSUFFICIENT_EVIDENCE} the Diagnosis stays abstained and
     * stays in {@code NEEDS_INVESTIGATION} — evidence arriving is not the same
     * as evidence answering, and a run that collected nothing useful must not
     * unlock human confirmation.</p>
     *
     * <p>Only an investigation may take this step. A Diagnosis a human has
     * already acted on is not re-decided underneath them.</p>
     */
    public Diagnosis evidenceRecorded(
            ConclusionType newConclusionType,
            String newRootCause,
            String newSummary,
            Confidence newConfidence,
            List<EvidenceResult> newEvidence,
            List<String> newTriggeredSignals,
            List<RecommendedAction> newActions,
            List<String> newWarnings,
            List<TimelineEvent> newTimeline) {
        requireStatus(DiagnosisStatus.NEEDS_INVESTIGATION, "record evidence");
        if (newConclusionType == null || newRootCause == null || newConfidence == null) {
            throw new IllegalArgumentException(
                    "recorded evidence must carry a conclusion type, root cause and confidence");
        }
        boolean stillAbstained = newConclusionType == ConclusionType.INSUFFICIENT_EVIDENCE;
        return new Diagnosis(
                diagnosisId, contractVersion, caseId, runId, incident,
                routeMode, investigationMode, routeAuthority,
                newConclusionType,
                stillAbstained
                        ? DiagnosisStatus.NEEDS_INVESTIGATION
                        : DiagnosisStatus.READY_FOR_HUMAN,
                newSummary == null ? summary : newSummary,
                newRootCause,
                newConfidence,
                stillAbstained,
                sopKey, sopTitle, sourcePlaybookOwner, sourcePlaybookVersionRef,
                immutable(newEvidence),
                // A1: only evidence that actually answered may be cited. Citing a
                // MISSING result would let "we tried to look" support a conclusion.
                immutable(newEvidence).stream()
                        .filter(result -> result.status() != EvidenceStatus.MISSING)
                        .map(EvidenceResult::queryId)
                        .toList(),
                immutable(newTriggeredSignals),
                immutable(newActions),
                pendingWrites,
                routeToTeam, transfers, actionOutcomes, closure, knowledgeCandidates,
                advancedTimeline(newTimeline),
                timings, rehearsal, fixtureMode, writeExecutionEnabled,
                immutable(newWarnings));
    }

    public Diagnosis confirmed(List<TimelineEvent> newTimeline) {
        requireStatus(DiagnosisStatus.READY_FOR_HUMAN, "confirm");
        return copyLifecycle(
                DiagnosisStatus.CONFIRMED,
                recommendedActions,
                pendingWrites,
                routeToTeam,
                transfers,
                actionOutcomes,
                null,
                knowledgeCandidates,
                advancedTimeline(newTimeline));
    }

    public Diagnosis transferred(
            String newRouteToTeam,
            List<TransferRecord> newTransfers,
            List<TimelineEvent> newTimeline) {
        requireConfirmed("transfer");
        List<TransferRecord> immutableTransfers = immutable(newTransfers);
        if (immutableTransfers.size() != transfers.size() + 1
                || !immutableTransfers.subList(0, transfers.size()).equals(transfers)) {
            throw new IllegalArgumentException("transfer must append exactly one transfer record");
        }
        return copyLifecycle(
                DiagnosisStatus.TRANSFERRED,
                recommendedActions,
                pendingWrites,
                required(newRouteToTeam, "routeToTeam"),
                immutableTransfers,
                actionOutcomes,
                null,
                knowledgeCandidates,
                advancedTimeline(newTimeline));
    }

    public Diagnosis actionsUpdated(
            List<RecommendedAction> newActions,
            List<RecommendedAction> newPendingWrites,
            List<TimelineEvent> newTimeline) {
        requireConfirmed("update actions");
        validateSingleApproval(newActions);
        return copyLifecycle(
                status,
                newActions,
                newPendingWrites,
                routeToTeam,
                transfers,
                actionOutcomes,
                null,
                knowledgeCandidates,
                advancedTimeline(newTimeline));
    }

    public Diagnosis outcomesUpdated(
            List<ActionOutcomeRecord> newActionOutcomes,
            List<TimelineEvent> newTimeline) {
        requireConfirmed("record action outcome");
        List<ActionOutcomeRecord> immutableOutcomes = immutable(newActionOutcomes);
        if (immutableOutcomes.size() != actionOutcomes.size() + 1
                || !immutableOutcomes.subList(0, actionOutcomes.size()).equals(actionOutcomes)) {
            throw new IllegalArgumentException("outcome update must append exactly one record");
        }
        return copyLifecycle(
                status,
                recommendedActions,
                pendingWrites,
                routeToTeam,
                transfers,
                immutableOutcomes,
                null,
                knowledgeCandidates,
                advancedTimeline(newTimeline));
    }

    public Diagnosis closed(
            ClosureRecord newClosure,
            List<KnowledgeCandidate> newKnowledgeCandidates,
            List<TimelineEvent> newTimeline) {
        requireConfirmed("close");
        validateClosureSafety(newClosure);
        List<KnowledgeCandidate> immutableCandidates = immutable(newKnowledgeCandidates);
        if (immutableCandidates.size() < knowledgeCandidates.size()
                || immutableCandidates.size() > knowledgeCandidates.size() + 1
                || !immutableCandidates.subList(0, knowledgeCandidates.size()).equals(knowledgeCandidates)) {
            throw new IllegalArgumentException("close may append at most one knowledge candidate");
        }
        String appendedCandidateId = immutableCandidates.size() == knowledgeCandidates.size() + 1
                ? immutableCandidates.getLast().candidateId()
                : null;
        if (!java.util.Objects.equals(newClosure.knowledgeCandidateId(), appendedCandidateId)) {
            throw new IllegalArgumentException(
                    "closure candidate reference must match the candidate appended by this transition");
        }
        return copyLifecycle(
                DiagnosisStatus.CLOSED,
                recommendedActions,
                pendingWrites,
                routeToTeam,
                transfers,
                actionOutcomes,
                newClosure,
                immutableCandidates,
                advancedTimeline(newTimeline));
    }

    private Diagnosis copyLifecycle(
            DiagnosisStatus newStatus,
            List<RecommendedAction> newActions,
            List<RecommendedAction> newPendingWrites,
            String newRouteToTeam,
            List<TransferRecord> newTransfers,
            List<ActionOutcomeRecord> newActionOutcomes,
            ClosureRecord newClosure,
            List<KnowledgeCandidate> newKnowledgeCandidates,
            List<TimelineEvent> newTimeline) {
        NorthStarTimings nextTimings = timings;
        if (newTimeline.size() > timeline.size()) {
            nextTimings = timings.withHandoff(newTimeline.get(timeline.size()).timestamp());
        }
        return new Diagnosis(
                diagnosisId, contractVersion, caseId, runId, incident, routeMode,
                investigationMode, routeAuthority, conclusionType,
                newStatus, summary, rootCause, confidence, abstained,
                sopKey, sopTitle, sourcePlaybookOwner, sourcePlaybookVersionRef,
                evidence, evidenceCitations,
                triggeredSignals, newActions,
                newPendingWrites, newRouteToTeam, newTransfers, newActionOutcomes,
                newClosure, newKnowledgeCandidates, newTimeline, nextTimings, rehearsal,
                fixtureMode, false, warnings);
    }

    private static void validateExperienceClassification(
            IncidentContext incident,
            RouteMode routeMode,
            InvestigationMode investigationMode,
            RouteAuthority routeAuthority,
            ConclusionType conclusionType,
            Confidence confidence,
            boolean abstained,
            boolean legacyContract) {
        // v1.3/v1.4 did not persist these fields and therefore cannot be held to
        // invariants that did not exist when the row was written. Their derived
        // values are projection-compatible, while v1.5 writes remain strict.
        if (legacyContract) {
            return;
        }
        if ((conclusionType == ConclusionType.INSUFFICIENT_EVIDENCE) != abstained) {
            throw new IllegalArgumentException(
                    "only INSUFFICIENT_EVIDENCE diagnoses may be abstained");
        }
        if (conclusionType == ConclusionType.INSUFFICIENT_EVIDENCE
                && confidence != Confidence.LOW) {
            throw new IllegalArgumentException(
                    "INSUFFICIENT_EVIDENCE confidence must be LOW");
        }
        if (conclusionType == ConclusionType.EXCLUDED && confidence == Confidence.HIGH) {
            throw new IllegalArgumentException("EXCLUDED confidence cannot be HIGH");
        }
        if (routeAuthority == RouteAuthority.MODEL_PROPOSED && confidence == Confidence.HIGH) {
            throw new IllegalArgumentException("MODEL_PROPOSED confidence cannot be HIGH");
        }
        if (routeMode == RouteMode.LLM_FALLBACK) {
            if (investigationMode != InvestigationMode.OPEN_DISCOVERY
                    || routeAuthority != RouteAuthority.MODEL_PROPOSED) {
                throw new IllegalArgumentException(
                        "LLM_FALLBACK requires OPEN_DISCOVERY + MODEL_PROPOSED");
            }
            if (conclusionType != ConclusionType.HYPOTHESIS
                    && conclusionType != ConclusionType.INSUFFICIENT_EVIDENCE) {
                throw new IllegalArgumentException(
                        "LLM_FALLBACK may only produce HYPOTHESIS or INSUFFICIENT_EVIDENCE");
            }
            return;
        }
        if (investigationMode == InvestigationMode.OPEN_DISCOVERY) {
            throw new IllegalArgumentException(
                    "DETERMINISTIC route cannot masquerade as OPEN_DISCOVERY");
        }
        if (investigationMode == InvestigationMode.ERROR_CODE_PLAYBOOK) {
            if (incident.errorCode() == null || incident.errorCode().isBlank()) {
                throw new IllegalArgumentException(
                        "ERROR_CODE_PLAYBOOK requires an explicit errorCode");
            }
            if (routeAuthority == RouteAuthority.MODEL_PROPOSED) {
                throw new IllegalArgumentException(
                        "a model proposal cannot select ERROR_CODE_PLAYBOOK");
            }
        }
    }

    private static InvestigationMode defaultInvestigationMode(RouteMode routeMode) {
        if (routeMode == null) {
            return null;
        }
        return routeMode == RouteMode.DETERMINISTIC
                ? InvestigationMode.ERROR_CODE_PLAYBOOK
                : InvestigationMode.OPEN_DISCOVERY;
    }

    private static RouteAuthority defaultRouteAuthority(RouteMode routeMode) {
        if (routeMode == null) {
            return null;
        }
        return routeMode == RouteMode.DETERMINISTIC
                ? RouteAuthority.EXPLICIT
                : RouteAuthority.MODEL_PROPOSED;
    }

    private static ConclusionType defaultConclusionType(RouteMode routeMode, boolean abstained) {
        if (abstained) {
            return ConclusionType.INSUFFICIENT_EVIDENCE;
        }
        return routeMode == RouteMode.LLM_FALLBACK
                ? ConclusionType.HYPOTHESIS
                : ConclusionType.LOCATED;
    }

    private List<TimelineEvent> advancedTimeline(List<TimelineEvent> candidate) {
        List<TimelineEvent> next = immutable(candidate);
        List<TimelineEvent> completedPrefix = timeline.stream()
                .map(TimelineEvent::done)
                .toList();
        if (next.size() <= timeline.size()
                || !next.subList(0, timeline.size()).equals(completedPrefix)) {
            throw new IllegalArgumentException("lifecycle transition must append timeline events");
        }
        return next;
    }

    private void validateSingleApproval(List<RecommendedAction> candidate) {
        List<RecommendedAction> next = immutable(candidate);
        if (next.size() != recommendedActions.size()) {
            throw new IllegalArgumentException("approval must preserve the recommendation set");
        }
        int approvals = 0;
        for (int index = 0; index < recommendedActions.size(); index++) {
            RecommendedAction existing = recommendedActions.get(index);
            RecommendedAction updated = next.get(index);
            if (existing.equals(updated)) {
                continue;
            }
            if (existing.actionType() != ActionType.MANUAL_WRITE
                    || existing.approvalStatus() != ApprovalStatus.PENDING
                    || !existing.approveWithoutExecution().equals(updated)) {
                throw new IllegalArgumentException(
                        "action update may only approve one pending manual write without execution");
            }
            approvals++;
        }
        if (approvals != 1) {
            throw new IllegalArgumentException("action update must approve exactly one manual write");
        }
    }

    private void validateClosureSafety(ClosureRecord candidateClosure) {
        if (candidateClosure == null) {
            throw new IllegalArgumentException("closure is required");
        }
        if (candidateClosure.outcome() != ClosureOutcome.RECOVERED) {
            return;
        }
        if (!candidateClosure.recoveryVerified()) {
            throw new IllegalArgumentException("recovered closure requires recovery verification");
        }
        if (!pendingWrites.isEmpty()) {
            throw new IllegalArgumentException(
                    "pending manual writes must be resolved before recovered closure");
        }
        List<String> approvedIds = recommendedActions.stream()
                .filter(action -> action.actionType() == ActionType.MANUAL_WRITE)
                .filter(action -> action.approvalStatus() == ApprovalStatus.APPROVED_NOT_EXECUTED)
                .map(RecommendedAction::actionId)
                .toList();
        for (String actionId : approvedIds) {
            ActionOutcomeRecord latest = actionOutcomes.stream()
                    .filter(outcome -> outcome.actionId().equals(actionId))
                    .reduce((first, second) -> second)
                    .orElse(null);
            if (latest == null
                    || latest.outcome() != ActionOutcomeStatus.SUCCEEDED
                    || !latest.recoveryVerified()) {
                throw new IllegalArgumentException(
                        "recovered closure requires verified successful external outcomes");
            }
        }
    }

    private void requireConfirmed(String operation) {
        if (status != DiagnosisStatus.CONFIRMED && status != DiagnosisStatus.TRANSFERRED) {
            throw new IllegalStateException(operation + " requires a confirmed diagnosis");
        }
    }

    private void requireStatus(DiagnosisStatus requiredStatus, String operation) {
        if (status != requiredStatus) {
            throw new IllegalStateException(operation + " is not legal from " + status);
        }
    }

    private static void validateLifecycle(
            String diagnosisId,
            DiagnosisStatus status,
            boolean abstained,
            List<RecommendedAction> actions,
            List<RecommendedAction> pendingWrites,
            String routeToTeam,
            List<TransferRecord> transfers,
            List<ActionOutcomeRecord> outcomes,
            ClosureRecord closure,
            List<KnowledgeCandidate> candidates) {
        if ((status == DiagnosisStatus.NEEDS_INVESTIGATION) != abstained) {
            throw new IllegalArgumentException(
                    "only NEEDS_INVESTIGATION diagnoses may be abstained");
        }
        if ((status == DiagnosisStatus.CLOSED) != (closure != null)) {
            throw new IllegalArgumentException("CLOSED status and closure record must appear together");
        }
        if (status == DiagnosisStatus.TRANSFERRED
                && (routeToTeam == null || routeToTeam.isBlank() || transfers.isEmpty())) {
            throw new IllegalArgumentException("TRANSFERRED diagnosis requires route and transfer record");
        }

        List<RecommendedAction> expectedPending = actions.stream()
                .filter(action -> action.actionType() == ActionType.MANUAL_WRITE)
                .filter(action -> action.approvalStatus() == ApprovalStatus.PENDING)
                .toList();
        if (!expectedPending.equals(pendingWrites)) {
            throw new IllegalArgumentException("pendingWrites must match pending manual actions");
        }

        Set<String> approvedManualActionIds = actions.stream()
                .filter(action -> action.actionType() == ActionType.MANUAL_WRITE)
                .filter(action -> action.approvalStatus() == ApprovalStatus.APPROVED_NOT_EXECUTED)
                .map(RecommendedAction::actionId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (outcomes.stream().anyMatch(outcome -> !approvedManualActionIds.contains(outcome.actionId()))) {
            throw new IllegalArgumentException("action outcomes require an approved manual action");
        }
        if (candidates.stream()
                .anyMatch(candidate -> !diagnosisId.equals(candidate.sourceDiagnosisId()))) {
            throw new IllegalArgumentException("knowledge candidate belongs to another diagnosis");
        }
    }

    private static void validateEvidenceCitations(
            List<EvidenceResult> evidence,
            List<String> evidenceCitations) {
        Set<String> usableEvidence = evidence.stream()
                .filter(item -> item.status() != EvidenceStatus.MISSING)
                .map(EvidenceResult::queryId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> uniqueCitations = new java.util.LinkedHashSet<>();
        for (String citation : evidenceCitations) {
            String queryId = required(citation, "evidenceCitation");
            if (!uniqueCitations.add(queryId)) {
                throw new IllegalArgumentException(
                        "duplicate evidence citation: " + queryId);
            }
            if (!usableEvidence.contains(queryId)) {
                throw new IllegalArgumentException(
                        "evidence citation is missing or unknown: " + queryId);
            }
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static <T> List<T> immutable(List<T> values) {
        return List.copyOf(values == null ? List.of() : values);
    }
}
