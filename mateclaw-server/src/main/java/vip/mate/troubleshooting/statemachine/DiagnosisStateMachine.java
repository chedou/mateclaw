package vip.mate.troubleshooting.statemachine;

import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.model.ActionOutcomeRecord;
import vip.mate.troubleshooting.model.ActionOutcomeStatus;
import vip.mate.troubleshooting.model.ActionType;
import vip.mate.troubleshooting.model.AgentTriageDraft;
import vip.mate.troubleshooting.model.ApprovalStatus;
import vip.mate.troubleshooting.model.ClosureOutcome;
import vip.mate.troubleshooting.model.ClosureRecord;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.DeterministicDiagnosisDraft;
import vip.mate.troubleshooting.model.DiagnosisStatus;
import vip.mate.troubleshooting.model.KnowledgeCandidate;
import vip.mate.troubleshooting.model.RecommendedAction;
import vip.mate.troubleshooting.model.ScenarioDiagnosisDraft;
import vip.mate.troubleshooting.model.TimelineEvent;
import vip.mate.troubleshooting.model.TransferContextSnapshot;
import vip.mate.troubleshooting.model.TransferRecord;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Component;

/** Human-controlled lifecycle. Every transition is pure and returns a new aggregate. */
@Component
public final class DiagnosisStateMachine {

    private final Clock clock;
    private final IdentifierGenerator identifiers;

    public DiagnosisStateMachine() {
        this(Clock.systemUTC(), prefix -> prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
    }

    public DiagnosisStateMachine(Clock clock, IdentifierGenerator identifiers) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers");
    }

    /** Creates the only legal initial state for the deterministic hit path. */
    public Diagnosis initializeDeterministic(DeterministicDiagnosisDraft draft) {
        Objects.requireNonNull(draft, "draft");
        DiagnosisStatus initialStatus = draft.abstained()
                ? DiagnosisStatus.NEEDS_INVESTIGATION
                : DiagnosisStatus.READY_FOR_HUMAN;
        List<TimelineEvent> timeline = List.of(
                new TimelineEvent(
                        clock.instant(),
                        "故障上下文已接收",
                        draft.incident().intakeSource(),
                        "done"),
                new TimelineEvent(
                        clock.instant(),
                        "确定性路由命中 " + draft.sop().routingKey(),
                        "orchestrator",
                        "done"),
                new TimelineEvent(
                        clock.instant(),
                        draft.abstained()
                                ? "证据或审核条件不足，转人工深查"
                                : "诊断结论待人工确认",
                        "orchestrator",
                        "current"));
        return Diagnosis.initial(
                draft.diagnosisId(),
                draft.caseId(),
                draft.runId(),
                draft.incident(),
                vip.mate.troubleshooting.model.RouteMode.DETERMINISTIC,
                vip.mate.troubleshooting.model.InvestigationMode.ERROR_CODE_PLAYBOOK,
                vip.mate.troubleshooting.model.RouteAuthority.EXPLICIT,
                draft.conclusionType(),
                draft.timings(),
                initialStatus,
                draft.summary(),
                draft.rootCause(),
                draft.confidence(),
                draft.abstained(),
                draft.sop().routingKey(),
                draft.sop().title(),
                draft.sop().ownerTeam(),
                draft.sourcePlaybookVersionRef(),
                draft.evidence(),
                draft.triggeredSignals(),
                draft.recommendedActions(),
                draft.routeToTeam(),
                draft.rehearsal(),
                draft.fixtureMode(),
                draft.warnings(),
                timeline);
    }

    /** Creates the only legal initial state for the caged read-only Agent path. */
    public Diagnosis initializeAgentFallback(AgentTriageDraft draft) {
        Objects.requireNonNull(draft, "draft");
        DiagnosisStatus initialStatus = draft.abstained()
                ? DiagnosisStatus.NEEDS_INVESTIGATION
                : DiagnosisStatus.READY_FOR_HUMAN;
        List<TimelineEvent> timeline = List.of(
                new TimelineEvent(
                        clock.instant(),
                        "故障上下文已接收",
                        draft.incident().intakeSource(),
                        "done"),
                new TimelineEvent(
                        clock.instant(),
                        "确定性路由未命中，进入只读 Agent 取证",
                        "orchestrator",
                        "done"),
                new TimelineEvent(
                        clock.instant(),
                        draft.abstained()
                                ? "只读 Agent 已弃权，转人工深查"
                                : "只读 Agent 建议待人工确认",
                        "orchestrator",
                        "current"));
        return Diagnosis.initialAgentFallback(draft, initialStatus, timeline);
    }

    /** Starts an explicitly selected Scenario Playbook without inventing evidence or a root cause. */
    public Diagnosis initializeScenarioAwaitingEvidence(ScenarioDiagnosisDraft draft) {
        Objects.requireNonNull(draft, "draft");
        List<TimelineEvent> timeline = List.of(
                new TimelineEvent(
                        clock.instant(),
                        "故障上下文已接收",
                        draft.incident().intakeSource(),
                        "done"),
                new TimelineEvent(
                        clock.instant(),
                        "显式选择排障场景 " + draft.scenarioKey(),
                        draft.actor(),
                        "done"),
                new TimelineEvent(
                        clock.instant(),
                        "等待场景 Playbook 要求的只读证据",
                        "orchestrator",
                        "current"));
        return Diagnosis.initial(
                draft.diagnosisId(),
                draft.caseId(),
                draft.runId(),
                draft.incident(),
                vip.mate.troubleshooting.model.RouteMode.DETERMINISTIC,
                vip.mate.troubleshooting.model.InvestigationMode.SCENARIO_PLAYBOOK,
                vip.mate.troubleshooting.model.RouteAuthority.EXPLICIT,
                vip.mate.troubleshooting.model.ConclusionType.INSUFFICIENT_EVIDENCE,
                draft.timings(),
                DiagnosisStatus.NEEDS_INVESTIGATION,
                draft.playbook().title() + "场景已创建，等待执行只读取证。",
                "尚未取得场景要求的只读证据，当前不能判断根因。",
                vip.mate.troubleshooting.model.Confidence.LOW,
                true,
                draft.selectorKey(),
                draft.playbook().title(),
                draft.playbook().ownerTeam(),
                draft.sourcePlaybookVersionRef(),
                List.of(),
                List.of(),
                List.of(),
                null,
                draft.rehearsal(),
                draft.fixtureMode(),
                draft.warnings(),
                timeline);
    }

    public Diagnosis confirm(Diagnosis diagnosis, String actor) {
        requireDiagnosis(diagnosis);
        requireActor(actor);
        if (diagnosis.abstained()) {
            throw conflict("abstained diagnosis requires new evidence before confirmation");
        }
        if (diagnosis.status() != DiagnosisStatus.READY_FOR_HUMAN) {
            throw conflict("diagnosis is not waiting for confirmation");
        }
        return diagnosis.confirmed(
                event(diagnosis.timeline(), "人工确认诊断结论", actor));
    }

    public Diagnosis transfer(
            Diagnosis diagnosis,
            String targetTeam,
            String note,
            String actor) {
        requireConfirmed(diagnosis);
        targetTeam = required(targetTeam, "targetTeam");
        note = required(note, "note");
        actor = requireActor(actor);
        TransferRecord transfer = new TransferRecord(
                identifiers.next("transfer"),
                targetTeam,
                note,
                actor,
                clock.instant(),
                new TransferContextSnapshot(
                        diagnosis.caseId(),
                        diagnosis.runId(),
                        diagnosis.incident().traceId(),
                        diagnosis.evidence().stream().map(item -> item.queryId()).toList(),
                        diagnosis.rootCause(),
                        diagnosis.confidence()));
        List<TransferRecord> transfers = append(diagnosis.transfers(), transfer);
        return diagnosis.transferred(
                targetTeam,
                transfers,
                event(diagnosis.timeline(), "结构化转派至 " + targetTeam + "（携带完整上下文）", actor));
    }

    public Diagnosis approveAction(
            Diagnosis diagnosis,
            String actionId,
            String reason,
            String actor) {
        requireConfirmed(diagnosis);
        String normalizedActionId = required(actionId, "actionId");
        reason = required(reason, "reason");
        actor = requireActor(actor);
        RecommendedAction action = action(diagnosis, normalizedActionId);
        if (action.actionType() != ActionType.MANUAL_WRITE) {
            throw conflict("only manual writes use human approval");
        }
        if (action.approvalStatus() == ApprovalStatus.APPROVED_NOT_EXECUTED) {
            return diagnosis;
        }
        RecommendedAction approved = action.approveWithoutExecution();
        List<RecommendedAction> actions = replace(diagnosis.recommendedActions(), approved);
        List<RecommendedAction> pending = diagnosis.pendingWrites().stream()
                .filter(item -> !item.actionId().equals(normalizedActionId))
                .toList();
        return diagnosis.actionsUpdated(
                actions,
                pending,
                event(
                        diagnosis.timeline(),
                        "生产写操作已人工批准（" + reason + "，系统未执行）",
                        actor));
    }

    public Diagnosis recordActionOutcome(
            Diagnosis diagnosis,
            String actionId,
            ActionOutcomeStatus outcome,
            String notes,
            boolean recoveryVerified,
            String actor) {
        requireConfirmed(diagnosis);
        actionId = required(actionId, "actionId");
        notes = required(notes, "notes");
        actor = requireActor(actor);
        if (outcome == null) {
            throw conflict("outcome is required");
        }
        RecommendedAction action = action(diagnosis, actionId);
        if (action.actionType() != ActionType.MANUAL_WRITE
                || action.approvalStatus() != ApprovalStatus.APPROVED_NOT_EXECUTED) {
            throw conflict("manual write must be approved before recording an external outcome");
        }
        if (recoveryVerified && outcome != ActionOutcomeStatus.SUCCEEDED) {
            throw conflict("only a succeeded external outcome can pass recovery verification");
        }
        ActionOutcomeRecord record = new ActionOutcomeRecord(
                identifiers.next("outcome"),
                actionId,
                outcome,
                notes,
                recoveryVerified,
                actor,
                clock.instant());
        List<TimelineEvent> timeline = event(
                diagnosis.timeline(),
                "登记外部处置结果：" + outcome.name().toLowerCase() + "（MateClaw 未执行）",
                actor);
        if (recoveryVerified) {
            timeline = event(timeline, "恢复验证通过", actor);
        }
        return diagnosis.outcomesUpdated(
                append(diagnosis.actionOutcomes(), record),
                timeline);
    }

    public Diagnosis close(
            Diagnosis diagnosis,
            ClosureOutcome outcome,
            String summary,
            boolean recoveryVerified,
            String sopFeedback,
            boolean createKnowledgeCandidate,
            String actor) {
        requireConfirmed(diagnosis);
        summary = required(summary, "summary");
        actor = requireActor(actor);
        if (outcome == null) {
            throw conflict("closure outcome is required");
        }
        if (outcome == ClosureOutcome.RECOVERED && !recoveryVerified) {
            throw conflict("recovered closure requires recovery verification");
        }
        if (outcome != ClosureOutcome.RECOVERED && recoveryVerified) {
            throw conflict("only recovered closure can carry recovery verification");
        }
        if (outcome == ClosureOutcome.RECOVERED && !diagnosis.pendingWrites().isEmpty()) {
            throw conflict("pending manual writes must be resolved before recovered closure");
        }
        if (outcome == ClosureOutcome.RECOVERED) {
            requireSuccessfulVerifiedOutcomes(diagnosis);
        }

        String candidateId = createKnowledgeCandidate
                ? identifiers.next("candidate")
                : null;
        Instant closedAt = clock.instant();
        ClosureRecord closure = new ClosureRecord(
                outcome,
                summary,
                recoveryVerified,
                sopFeedback,
                candidateId,
                actor,
                closedAt);
        KnowledgeCandidate candidate = createKnowledgeCandidate
                ? candidate(diagnosis, summary, sopFeedback, actor, candidateId, closure)
                : null;
        List<KnowledgeCandidate> candidates = candidate == null
                ? diagnosis.knowledgeCandidates()
                : append(diagnosis.knowledgeCandidates(), candidate);
        List<TimelineEvent> timeline = event(
                diagnosis.timeline(),
                "关闭归档：" + outcome.name().toLowerCase() + "（" + summary + "）",
                actor);
        if (candidate != null) {
            timeline = event(
                    timeline,
                    "知识候选 " + candidate.candidateId()
                            + " 已记录（发布状态不等于审核）",
                    actor);
        }
        return diagnosis.closed(
                closure,
                candidates,
                timeline);
    }

    /** Deliberate compatibility seam for a future controller: always maps to HTTP 409. */
    public void executeAction(Diagnosis diagnosis, String actionId, String actor) {
        requireDiagnosis(diagnosis);
        required(actionId, "actionId");
        requireActor(actor);
        throw new MateClawException(
                "err.troubleshooting.production_write_disabled",
                409,
                "production write executor is not connected; execute externally and record the outcome");
    }

    private void requireSuccessfulVerifiedOutcomes(Diagnosis diagnosis) {
        List<String> approvedIds = diagnosis.recommendedActions().stream()
                .filter(action -> action.actionType() == ActionType.MANUAL_WRITE)
                .filter(action -> action.approvalStatus() == ApprovalStatus.APPROVED_NOT_EXECUTED)
                .map(RecommendedAction::actionId)
                .toList();
        for (String actionId : approvedIds) {
            ActionOutcomeRecord latest = diagnosis.actionOutcomes().stream()
                    .filter(outcome -> outcome.actionId().equals(actionId))
                    .reduce((first, second) -> second)
                    .orElse(null);
            if (latest == null
                    || latest.outcome() != ActionOutcomeStatus.SUCCEEDED
                    || !latest.recoveryVerified()) {
                throw conflict(
                        "approved manual writes require a succeeded external outcome "
                                + "and recovery verification before closure");
            }
        }
    }

    private KnowledgeCandidate candidate(
            Diagnosis diagnosis,
            String summary,
            String feedback,
            String actor,
            String candidateId,
            ClosureRecord closure) {
        return new KnowledgeCandidate(
                candidateId,
                KnowledgeCandidate.CURRENT_CONTRACT_VERSION,
                diagnosis.diagnosisId(),
                diagnosis.caseId(),
                diagnosis.runId(),
                diagnosis.incident().system(),
                diagnosis.incident().errorCode(),
                diagnosis.sopKey(),
                diagnosis.rootCause(),
                diagnosis.evidence().stream().map(item -> item.queryId()).toList(),
                diagnosis.recommendedActions(),
                diagnosis.actionOutcomes(),
                summary,
                feedback,
                actor,
                closure.closedAt(),
                KnowledgeCandidate.OutcomeProof.from(closure),
                diagnosis.sourcePlaybookOwner());
    }

    private RecommendedAction action(Diagnosis diagnosis, String actionId) {
        return diagnosis.recommendedActions().stream()
                .filter(item -> item.actionId().equals(actionId))
                .findFirst()
                .orElseThrow(() -> conflict("recommended action not found: " + actionId));
    }

    private List<RecommendedAction> replace(
            List<RecommendedAction> actions,
            RecommendedAction replacement) {
        return actions.stream()
                .map(item -> item.actionId().equals(replacement.actionId()) ? replacement : item)
                .toList();
    }

    private List<TimelineEvent> event(List<TimelineEvent> existing, String text, String actor) {
        List<TimelineEvent> timeline = new ArrayList<>(existing.size() + 1);
        existing.forEach(item -> timeline.add(item.done()));
        timeline.add(new TimelineEvent(clock.instant(), text, actor, "done"));
        return List.copyOf(timeline);
    }

    private <T> List<T> append(List<T> existing, T value) {
        List<T> result = new ArrayList<>(existing.size() + 1);
        result.addAll(existing);
        result.add(value);
        return List.copyOf(result);
    }

    private void requireConfirmed(Diagnosis diagnosis) {
        requireDiagnosis(diagnosis);
        if (diagnosis.status() != DiagnosisStatus.CONFIRMED
                && diagnosis.status() != DiagnosisStatus.TRANSFERRED) {
            throw conflict("diagnosis must be confirmed before this operation");
        }
    }

    private void requireDiagnosis(Diagnosis diagnosis) {
        if (diagnosis == null) {
            throw conflict("diagnosis is required");
        }
    }

    private String requireActor(String actor) {
        return required(actor, "actor");
    }

    private String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw conflict(name + " must not be blank");
        }
        return value.trim();
    }

    private MateClawException conflict(String message) {
        return new MateClawException("err.troubleshooting.workflow_conflict", 409, message);
    }

    @FunctionalInterface
    public interface IdentifierGenerator {
        String next(String prefix);
    }
}
