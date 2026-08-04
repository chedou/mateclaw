package vip.mate.troubleshooting.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.engine.CriterionEvaluator;
import vip.mate.troubleshooting.engine.DiagnosisRuleEvaluator;
import vip.mate.troubleshooting.engine.PlaybookEvidenceAssessment;
import vip.mate.troubleshooting.model.ActionType;
import vip.mate.troubleshooting.model.ConclusionType;
import vip.mate.troubleshooting.model.DeterministicDiagnosisDraft;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.model.KnowledgeEvidenceGrade;
import vip.mate.troubleshooting.model.NorthStarTimings;
import vip.mate.troubleshooting.model.PlaybookVersionRef;
import vip.mate.troubleshooting.model.RecommendedAction;
import vip.mate.troubleshooting.model.SopEntry;
import vip.mate.troubleshooting.statemachine.DiagnosisStateMachine;
import vip.mate.troubleshooting.synthesis.ApprovedPlaybookVersion;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Zero-LLM orchestration for a known {@code (system,error_code)} route.
 * Evidence is already normalized at this boundary; the P3 router and adapters
 * stay upstream so this engine remains platform-independent and easy to test.
 */
@Service
public class DeterministicDiagnosisService {

    private final CriterionEvaluator evaluator;
    private final DiagnosisRuleEvaluator ruleEvaluator;
    private final DiagnosisStateMachine stateMachine;
    private final TroubleshootingPersistenceService persistence;
    private final TroubleshootingPlaybookVersionService playbookVersions;
    private final Clock clock;

    @Autowired
    public DeterministicDiagnosisService(
            CriterionEvaluator evaluator,
            DiagnosisRuleEvaluator ruleEvaluator,
            DiagnosisStateMachine stateMachine,
            TroubleshootingPersistenceService persistence,
            TroubleshootingPlaybookVersionService playbookVersions) {
        this(
                evaluator,
                ruleEvaluator,
                stateMachine,
                persistence,
                playbookVersions,
                Clock.systemUTC());
    }

    DeterministicDiagnosisService(
            CriterionEvaluator evaluator,
            DiagnosisRuleEvaluator ruleEvaluator,
            DiagnosisStateMachine stateMachine,
            TroubleshootingPersistenceService persistence,
            TroubleshootingPlaybookVersionService playbookVersions,
            Clock clock) {
        this.evaluator = evaluator;
        this.ruleEvaluator = ruleEvaluator;
        this.stateMachine = stateMachine;
        this.persistence = persistence;
        this.playbookVersions = playbookVersions;
        this.clock = clock;
    }

    @Transactional
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

    @Transactional
    public StoredDiagnosis diagnoseAndPersist(
            long workspaceId,
            IncidentContext incident,
            SopEntry sop,
            List<EvidenceResult> evidence,
            boolean rehearsal,
            boolean fixtureMode,
            Instant reportedAt,
            Instant readyAt) {
        Diagnosis diagnosis = diagnoseAgainstLockedPlaybook(
                workspaceId,
                incident,
                sop,
                evidence,
                rehearsal,
                fixtureMode,
                reportedAt,
                readyAt);
        return persistence.createOrGet(workspaceId, diagnosis, reportedAt);
    }

    /** Same deterministic engine, with IntakeSession as the durable owner. */
    @Transactional
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
        Diagnosis diagnosis = diagnoseAgainstLockedPlaybook(
                workspaceId,
                incident,
                sop,
                evidence,
                rehearsal,
                fixtureMode,
                reportedAt,
                readyAt);
        return persistence.createOrGetForIntake(
                workspaceId, diagnosis, intakeSessionId);
    }

    /** Pure evaluation entry point; callers must supply the exact authority under test. */
    public Diagnosis diagnose(
            IncidentContext incident,
            SopEntry sop,
            PlaybookVersionRef sourcePlaybookVersionRef,
            List<EvidenceResult> evidence,
            boolean rehearsal,
            boolean fixtureMode) {
        return diagnose(
                incident,
                sop,
                sourcePlaybookVersionRef,
                // 调用方自带权威时，成色未知——落在保守那一侧。
                KnowledgeEvidenceGrade.UNVERIFIED,
                evidence,
                rehearsal,
                fixtureMode,
                null,
                null);
    }

    private Diagnosis diagnoseAgainstLockedPlaybook(
            long workspaceId,
            IncidentContext incident,
            SopEntry sop,
            List<EvidenceResult> evidence,
            boolean rehearsal,
            boolean fixtureMode,
            Instant reportedAt,
            Instant readyAt) {
        ApprovedPlaybookVersion locked = lockExactPlaybook(workspaceId, sop);
        return diagnose(
                incident,
                sop,
                new PlaybookVersionRef(locked.playbookId(), locked.playbookVersion()),
                locked.knowledgeEvidenceGrade(),
                evidence,
                rehearsal,
                fixtureMode,
                reportedAt,
                readyAt);
    }

    private Diagnosis diagnose(
            IncidentContext incident,
            SopEntry sop,
            PlaybookVersionRef sourcePlaybookVersionRef,
            KnowledgeEvidenceGrade knowledgeGrade,
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
        // The same judgement the scenario lane reaches when evidence arrives
        // later. Two evaluators would eventually give two answers to one set of
        // evidence, which is the drift A9 forbids.
        PlaybookEvidenceAssessment assessment = PlaybookEvidenceAssessment.assess(
                sop, normalizedEvidence, evaluator, ruleEvaluator, fixtureMode,
                // 未标定的阈值不得声称 HIGH。成色与被锁定的那一版同源。
                knowledgeGrade);

        List<String> signals = assessment.activeSignals();
        List<String> warnings = new ArrayList<>(assessment.warnings());

        List<RecommendedAction> actions =
                assessment.conclusionType() == ConclusionType.LOCATED
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
                sourcePlaybookVersionRef,
                normalizedEvidence,
                signals,
                actions,
                assessment.summary(),
                assessment.rootCause(),
                assessment.confidence(),
                assessment.conclusionType(),
                assessment.conclusionType() == ConclusionType.INSUFFICIENT_EVIDENCE,
                timings,
                routeToTeam,
                rehearsal,
                fixtureMode,
                warnings);
        return stateMachine.initializeDeterministic(draft);
    }

    private ApprovedPlaybookVersion lockExactPlaybook(long workspaceId, SopEntry sop) {
        ApprovedPlaybookVersion version = playbookVersions.lockActiveApprovedByPlaybookId(
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
        // 返回整个版本：知识成色也冻结在这一版上，而它决定结论最高能声称到什么程度。
        return version;
    }

}
