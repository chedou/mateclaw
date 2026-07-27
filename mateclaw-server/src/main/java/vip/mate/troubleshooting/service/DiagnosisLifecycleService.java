package vip.mate.troubleshooting.service;

import org.springframework.stereotype.Service;
import vip.mate.troubleshooting.model.ActionOutcomeStatus;
import vip.mate.troubleshooting.model.ClosureOutcome;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.KnowledgeCandidate;
import vip.mate.troubleshooting.statemachine.DiagnosisStateMachine;

/**
 * Drives one diagnosis through its human-controlled lifecycle.
 *
 * <p>Each method is the same three steps: load the stored aggregate, ask the
 * state machine for the next immutable value, write it back under the version
 * we read. Every legality question — may this transition happen, is this
 * approval allowed, is this closure safe — belongs to
 * {@link DiagnosisStateMachine}; nothing here decides anything, so a caller
 * cannot reach a state the state machine would refuse.</p>
 *
 * <p>Approval is the load-bearing case. It flips one action from
 * {@code PENDING} to {@code APPROVED_NOT_EXECUTED} and nothing else: no tool
 * runs, no production write is dispatched. The write itself is carried out by
 * an authorized human outside MateClaw, who then reports back through
 * {@link #recordOutcome}. That is why approval and outcome are two separate
 * calls rather than one — the platform cannot know the result of an action it
 * did not perform.</p>
 */
@Service
public class DiagnosisLifecycleService {

    private final TroubleshootingPersistenceService persistence;
    private final DiagnosisStateMachine stateMachine;

    public DiagnosisLifecycleService(
            TroubleshootingPersistenceService persistence,
            DiagnosisStateMachine stateMachine) {
        this.persistence = persistence;
        this.stateMachine = stateMachine;
    }

    /** Accepts the machine's conclusion so the case can move on to a team. */
    public StoredDiagnosis confirm(long workspaceId, String diagnosisId, String actor) {
        StoredDiagnosis current = persistence.get(workspaceId, diagnosisId);
        Diagnosis next = stateMachine.confirm(current.diagnosis(), actor);
        return persistence.update(workspaceId, next, current.version());
    }

    /** Hands the case to a team together with the full context snapshot. */
    public StoredDiagnosis transfer(
            long workspaceId, String diagnosisId, String targetTeam, String note, String actor) {
        StoredDiagnosis current = persistence.get(workspaceId, diagnosisId);
        Diagnosis next = stateMachine.transfer(current.diagnosis(), targetTeam, note, actor);
        return persistence.update(workspaceId, next, current.version());
    }

    /**
     * Authorizes a manual write without executing it.
     *
     * <p>The recorded reason is what an auditor reads later to judge whether
     * the authorization was sound, so the state machine requires it.</p>
     */
    public StoredDiagnosis approveAction(
            long workspaceId, String diagnosisId, String actionId, String reason, String actor) {
        StoredDiagnosis current = persistence.get(workspaceId, diagnosisId);
        Diagnosis next = stateMachine.approveAction(current.diagnosis(), actionId, reason, actor);
        return persistence.update(workspaceId, next, current.version());
    }

    /** Records what actually happened when a human ran the approved write elsewhere. */
    public StoredDiagnosis recordOutcome(
            long workspaceId,
            String diagnosisId,
            String actionId,
            ActionOutcomeStatus outcome,
            String notes,
            boolean recoveryVerified,
            String actor) {
        StoredDiagnosis current = persistence.get(workspaceId, diagnosisId);
        Diagnosis next = stateMachine.recordActionOutcome(
                current.diagnosis(), actionId, outcome, notes, recoveryVerified, actor);
        return persistence.update(workspaceId, next, current.version());
    }

    /**
     * Closes the case and, when asked, sediments what was learned.
     *
     * <p>A candidate is enqueued in the same transaction as the closure, so a
     * crash cannot leave a closed case whose lesson was silently dropped. The
     * candidate only enters a review queue — it never overwrites an approved
     * SOP, because a single incident is not evidence enough to rewrite the
     * knowledge the deterministic path depends on.</p>
     */
    public StoredDiagnosis close(
            long workspaceId,
            String diagnosisId,
            ClosureOutcome outcome,
            String summary,
            boolean recoveryVerified,
            String sopFeedback,
            boolean createKnowledgeCandidate,
            String actor) {
        StoredDiagnosis current = persistence.get(workspaceId, diagnosisId);
        Diagnosis next = stateMachine.close(
                current.diagnosis(), outcome, summary, recoveryVerified,
                sopFeedback, createKnowledgeCandidate, actor);

        KnowledgeCandidate appended = appendedCandidate(current.diagnosis(), next);
        return appended == null
                ? persistence.update(workspaceId, next, current.version())
                : persistence.updateAndEnqueue(workspaceId, next, current.version(), appended);
    }

    /**
     * Returns the candidate this closure added, or {@code null} when the
     * operator chose not to sediment anything.
     */
    private KnowledgeCandidate appendedCandidate(Diagnosis before, Diagnosis after) {
        return after.knowledgeCandidates().size() > before.knowledgeCandidates().size()
                ? after.knowledgeCandidates().getLast()
                : null;
    }
}
