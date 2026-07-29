package vip.mate.troubleshooting.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.engine.CriterionEvaluator;
import vip.mate.troubleshooting.model.ActionType;
import vip.mate.troubleshooting.model.Confidence;
import vip.mate.troubleshooting.model.ConclusionType;
import vip.mate.troubleshooting.model.CriterionOutcome;
import vip.mate.troubleshooting.model.DeterministicDiagnosisDraft;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.DiagnosisRule;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.model.NorthStarTimings;
import vip.mate.troubleshooting.model.PlaybookVersionRef;
import vip.mate.troubleshooting.model.RecommendedAction;
import vip.mate.troubleshooting.model.SopEntry;
import vip.mate.troubleshooting.statemachine.DiagnosisStateMachine;
import vip.mate.troubleshooting.synthesis.ApprovedPlaybookVersion;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Zero-LLM orchestration for a known {@code (system,error_code)} route.
 * Evidence is already normalized at this boundary; the P3 router and adapters
 * stay upstream so this engine remains platform-independent and easy to test.
 */
@Service
public class DeterministicDiagnosisService {

    private final CriterionEvaluator evaluator;
    private final DiagnosisStateMachine stateMachine;
    private final TroubleshootingPersistenceService persistence;
    private final TroubleshootingPlaybookVersionService playbookVersions;
    private final Clock clock;

    @Autowired
    public DeterministicDiagnosisService(
            CriterionEvaluator evaluator,
            DiagnosisStateMachine stateMachine,
            TroubleshootingPersistenceService persistence,
            TroubleshootingPlaybookVersionService playbookVersions) {
        this(evaluator, stateMachine, persistence, playbookVersions, Clock.systemUTC());
    }

    DeterministicDiagnosisService(
            CriterionEvaluator evaluator,
            DiagnosisStateMachine stateMachine,
            TroubleshootingPersistenceService persistence,
            TroubleshootingPlaybookVersionService playbookVersions,
            Clock clock) {
        this.evaluator = evaluator;
        this.stateMachine = stateMachine;
        this.persistence = persistence;
        this.playbookVersions = playbookVersions;
        this.clock = clock;
    }

    public StoredDiagnosis diagnoseAndPersist(
            long workspaceId,
            IncidentContext incident,
            SopEntry sop,
            List<EvidenceResult> evidence,
            boolean rehearsal,
            boolean fixtureMode,
            Instant receivedAt) {
        return diagnoseAndPersist(
                workspaceId,
                incident,
                sop,
                evidence,
                rehearsal,
                fixtureMode,
                receivedAt,
                receivedAt);
    }

    public StoredDiagnosis diagnoseAndPersist(
            long workspaceId,
            IncidentContext incident,
            SopEntry sop,
            List<EvidenceResult> evidence,
            boolean rehearsal,
            boolean fixtureMode,
            Instant reportedAt,
            Instant readyAt) {
        Diagnosis diagnosis = diagnose(
                incident,
                sop,
                exactPlaybook(workspaceId, sop),
                evidence,
                rehearsal,
                fixtureMode,
                reportedAt,
                readyAt);
        return persistence.createOrGet(workspaceId, diagnosis, reportedAt);
    }

    /** Same deterministic engine, with IntakeSession as the durable owner. */
    public StoredDiagnosis diagnoseAndPersistForIntake(
            long workspaceId,
            IncidentContext incident,
            SopEntry sop,
            List<EvidenceResult> evidence,
            boolean rehearsal,
            boolean fixtureMode,
            Instant reportedAt,
            Instant readyAt,
            String intakeSessionId) {
        Diagnosis diagnosis = diagnose(
                incident,
                sop,
                exactPlaybook(workspaceId, sop),
                evidence,
                rehearsal,
                fixtureMode,
                reportedAt,
                readyAt);
        return persistence.createOrGetForIntake(
                workspaceId, diagnosis, intakeSessionId);
    }

    public Diagnosis diagnose(
            IncidentContext incident,
            SopEntry sop,
            List<EvidenceResult> evidence,
            boolean rehearsal,
            boolean fixtureMode) {
        return diagnose(
                incident, sop, null, evidence, rehearsal, fixtureMode, null, null);
    }

    private Diagnosis diagnose(
            IncidentContext incident,
            SopEntry sop,
            PlaybookVersionRef sourcePlaybook,
            List<EvidenceResult> evidence,
            boolean rehearsal,
            boolean fixtureMode,
            Instant reportedAt,
            Instant readyAt) {
        if (incident == null || sop == null) {
            throw new IllegalArgumentException("incident and sop are required");
        }
        if (incident.completeness() == IncidentCompleteness.SYMPTOM
                || incident.errorCode() == null) {
            throw new IllegalArgumentException(
                    "deterministic diagnosis requires structured incident and errorCode");
        }
        List<EvidenceResult> normalizedEvidence = List.copyOf(
                evidence == null ? List.of() : evidence);
        Map<String, EvidenceResult> evidenceByRequest = indexEvidence(normalizedEvidence);
        List<String> missing = missingRequests(sop, evidenceByRequest, false);
        List<String> requiredMissing = missingRequests(sop, evidenceByRequest, true);

        Map<String, CriterionOutcome> outcomes = evaluator.outcomesBySignal(
                sop.anomalyCriteria(), normalizedEvidence);
        List<String> signals = outcomes.entrySet().stream()
                .filter(entry -> entry.getValue() == CriterionOutcome.SATISFIED)
                .map(Map.Entry::getKey)
                .toList();
        Decision decision = synthesize(sop.diagnosisRules(), signals, outcomes);
        List<String> warnings = new ArrayList<>();

        if (!requiredMissing.isEmpty()) {
            decision = new Decision(
                    "自动取证不完整，当前不能确认根因。",
                    "观测工具不可用，已降级为 SOP 文本与人工取证指引。",
                    Confidence.LOW,
                    ConclusionType.INSUFFICIENT_EVIDENCE);
        }
        if (!sop.operational()) {
            decision = new Decision(
                    "SOP 尚未审核，当前仅完成影子取证，不输出正式根因。",
                    "命中草案 SOP；结果仅用于离线比对，不能作为处置建议。",
                    Confidence.LOW,
                    ConclusionType.INSUFFICIENT_EVIDENCE);
        }

        if (fixtureMode) {
            warnings.add("当前仅使用 fixture 证据；DQL 指标名与阈值尚未联调核实。");
        }
        if (!missing.isEmpty()) {
            warnings.add("自动取证失败（" + String.join(", ", missing)
                    + "）；请按 SOP 进行人工取证。");
        }
        if (!sop.operational()) {
            warnings.add("SOP 仍为草案，禁止越过影子模式或输出恢复动作。");
        }
        if (decision.conclusionType() == ConclusionType.EXCLUDED) {
            warnings.add("当前 SOP 的所有候选结论都被已取得证据反证；这是排除，不是定位。");
        }

        List<RecommendedAction> actions = decision.conclusionType() == ConclusionType.LOCATED
                ? List.copyOf(sop.actions())
                : List.of();
        String routeToTeam = actions.stream()
                .anyMatch(action -> action.actionType() == ActionType.HUMAN_CONTACT)
                ? sop.ownerTeam()
                : null;
        String correlationId = UUID.randomUUID().toString().replace("-", "");
        NorthStarTimings timings = reportedAt == null
                ? NorthStarTimings.unrecorded()
                : NorthStarTimings.concluded(reportedAt, readyAt, clock.instant());
        DeterministicDiagnosisDraft draft = new DeterministicDiagnosisDraft(
                "diag-" + correlationId,
                "case-" + correlationId,
                "run-" + correlationId,
                incident,
                sop,
                sourcePlaybook,
                normalizedEvidence,
                signals,
                actions,
                decision.summary(),
                decision.rootCause(),
                decision.confidence(),
                decision.conclusionType(),
                decision.conclusionType() == ConclusionType.INSUFFICIENT_EVIDENCE,
                timings,
                routeToTeam,
                rehearsal,
                fixtureMode,
                warnings);
        return stateMachine.initializeDeterministic(draft);
    }

    private PlaybookVersionRef exactPlaybook(long workspaceId, SopEntry sop) {
        ApprovedPlaybookVersion version = playbookVersions.findByPlaybookId(
                        workspaceId, sop.sopId())
                .orElseThrow(() -> new MateClawException(
                        "err.troubleshooting.playbook_version_not_frozen",
                        409,
                        "the routeable Playbook has no immutable version record; "
                                + "diagnosis stopped before persisting an unverifiable decision"));
        if (!version.selectorKey().equals(sop.routingKey())
                || !version.playbook().equals(sop)) {
            throw new MateClawException(
                    "err.troubleshooting.playbook_version_mismatch",
                    409,
                    "the routeable Playbook no longer matches its immutable version record");
        }
        return new PlaybookVersionRef(
                version.playbookId(), version.playbookVersion());
    }

    private Map<String, EvidenceResult> indexEvidence(List<EvidenceResult> evidence) {
        Map<String, EvidenceResult> indexed = new HashMap<>();
        for (EvidenceResult result : evidence) {
            if (indexed.putIfAbsent(result.queryId(), result) != null) {
                throw new IllegalArgumentException(
                        "duplicate evidence queryId: " + result.queryId());
            }
        }
        return indexed;
    }

    private List<String> missingRequests(
            SopEntry sop,
            Map<String, EvidenceResult> evidenceByRequest,
            boolean requiredOnly) {
        return sop.evidenceRequests().stream()
                .filter(request -> !requiredOnly || request.required())
                .filter(request -> isMissing(request, evidenceByRequest))
                .map(EvidenceRequest::requestId)
                .toList();
    }

    private boolean isMissing(
            EvidenceRequest request,
            Map<String, EvidenceResult> evidenceByRequest) {
        EvidenceResult result = evidenceByRequest.get(request.requestId());
        return result == null || result.status() == EvidenceStatus.MISSING;
    }

    private Decision synthesize(
            List<DiagnosisRule> rules,
            List<String> signals,
            Map<String, CriterionOutcome> outcomes) {
        Set<String> active = new HashSet<>(signals);
        for (DiagnosisRule rule : rules) {
            if (active.containsAll(rule.requiredSignals())) {
                return new Decision(
                        rule.rootCause(),
                        rule.summary(),
                        rule.confidence(),
                        rule.abstained()
                                ? ConclusionType.INSUFFICIENT_EVIDENCE
                                : ConclusionType.LOCATED);
            }
        }
        if (allRulesDefinitivelyExcluded(rules, outcomes)) {
            return new Decision(
                    "当前 SOP 候选根因均被反证。",
                    "现有证据已排除当前 SOP 中的候选结论。",
                    Confidence.MEDIUM,
                    ConclusionType.EXCLUDED);
        }
        return new Decision(
                "证据不足，暂不能确认根因。",
                "SOP 未提供与当前信号匹配的结论规则。",
                Confidence.LOW,
                ConclusionType.INSUFFICIENT_EVIDENCE);
    }

    private boolean allRulesDefinitivelyExcluded(
            List<DiagnosisRule> rules,
            Map<String, CriterionOutcome> outcomes) {
        return !rules.isEmpty() && rules.stream().allMatch(rule -> {
            boolean hasExclusion = false;
            for (String signal : rule.requiredSignals()) {
                CriterionOutcome outcome = outcomes.get(signal);
                if (outcome == null || outcome == CriterionOutcome.UNEVALUATED) {
                    return false;
                }
                hasExclusion |= outcome == CriterionOutcome.EXCLUDED;
            }
            return hasExclusion;
        });
    }

    private record Decision(
            String rootCause,
            String summary,
            Confidence confidence,
            ConclusionType conclusionType) {
    }
}
