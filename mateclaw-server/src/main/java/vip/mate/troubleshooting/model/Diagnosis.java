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
        List<RecommendedAction> pendingWrites,
        String routeToTeam,
        List<TransferRecord> transfers,
        List<ActionOutcomeRecord> actionOutcomes,
        ClosureRecord closure,
        List<KnowledgeCandidate> knowledgeCandidates,
        List<TimelineEvent> timeline,
        boolean rehearsal,
        boolean fixtureMode,
        boolean writeExecutionEnabled,
        List<String> warnings) {

    public static final String CURRENT_CONTRACT_VERSION = "1.3";
    private static final Set<String> SUPPORTED_CONTRACT_VERSIONS =
            Set.of(CURRENT_CONTRACT_VERSION);

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
        summary = summary == null ? "" : summary;
        rootCause = rootCause == null ? "" : rootCause;
        evidence = immutable(evidence);
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
        return initial(
                diagnosisId,
                caseId,
                runId,
                incident,
                routeMode,
                status,
                summary,
                rootCause,
                confidence,
                abstained,
                sopKey,
                sopTitle,
                evidence,
                triggeredSignals,
                recommendedActions,
                routeToTeam,
                rehearsal,
                fixtureMode,
                warnings,
                List.of());
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
                status,
                summary,
                rootCause,
                confidence,
                abstained,
                sopKey,
                sopTitle,
                evidence,
                triggeredSignals,
                recommendedActions,
                pendingWrites,
                routeToTeam,
                List.of(),
                List.of(),
                null,
                List.of(),
                timeline,
                rehearsal,
                fixtureMode,
                false,
                warnings);
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
        return new Diagnosis(
                diagnosisId, contractVersion, caseId, runId, incident, routeMode,
                newStatus, summary, rootCause, confidence, abstained,
                sopKey, sopTitle, evidence, triggeredSignals, newActions,
                newPendingWrites, newRouteToTeam, newTransfers, newActionOutcomes,
                newClosure, newKnowledgeCandidates, newTimeline, rehearsal,
                fixtureMode, false, warnings);
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
