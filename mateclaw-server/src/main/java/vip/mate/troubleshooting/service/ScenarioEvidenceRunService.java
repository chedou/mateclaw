package vip.mate.troubleshooting.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.engine.CriterionEvaluator;
import vip.mate.troubleshooting.engine.DiagnosisRuleEvaluator;
import vip.mate.troubleshooting.engine.PlaybookEvidenceAssessment;
import vip.mate.troubleshooting.evidence.EvidenceSourceRouter;
import vip.mate.troubleshooting.evidence.PlaybookEvidenceCollector;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.DiagnosisStatus;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.InvestigationMode;
import vip.mate.troubleshooting.model.PlaybookVersionRef;
import vip.mate.troubleshooting.model.SopEntry;
import vip.mate.troubleshooting.statemachine.DiagnosisStateMachine;
import vip.mate.troubleshooting.synthesis.ApprovedPlaybookVersion;

import java.util.List;
import java.util.Objects;

/**
 * Runs a waiting Scenario Playbook's evidence plan and lets its Diagnosis
 * re-decide.
 *
 * <p><b>The half that was missing.</b> Naming a scenario created a Diagnosis
 * that abstained and waited, and the aggregate learned how to accept evidence,
 * but nothing between the two actually went and collected any. Deployment
 * topology had its own probe endpoint, so it became the one scenario that could
 * finish; every other scenario stopped at "waiting for evidence" with no way to
 * supply it.</p>
 *
 * <p><b>It re-decides, it does not decide.</b> The conclusion comes from the
 * frozen Playbook's own criteria and rules via {@link PlaybookEvidenceAssessment}
 * — the same evaluation the error-code hit path uses. Nothing here can produce a
 * root cause the Playbook did not author, and evidence that fails to arrive
 * leaves the investigation exactly where it was.</p>
 *
 * <p><b>Why it refuses instead of retrying.</b> A Diagnosis past
 * {@code NEEDS_INVESTIGATION} has been read by a person. Re-running its plan and
 * overwriting the conclusion would rewrite something that person may have acted
 * on. That is a decision for a new investigation, not a silent update to an old
 * one.</p>
 */
@Service
public class ScenarioEvidenceRunService {

    private final TroubleshootingPersistenceService persistence;
    private final TroubleshootingPlaybookVersionService versions;
    private final DiagnosisStateMachine stateMachine;
    private final CriterionEvaluator criteria;
    private final DiagnosisRuleEvaluator rules;
    private final PlaybookEvidenceCollector collector;

    @Autowired
    public ScenarioEvidenceRunService(
            TroubleshootingPersistenceService persistence,
            TroubleshootingPlaybookVersionService versions,
            DiagnosisStateMachine stateMachine,
            CriterionEvaluator criteria,
            DiagnosisRuleEvaluator rules,
            EvidenceSourceRouter router) {
        this(persistence, versions, stateMachine, criteria, rules,
                new PlaybookEvidenceCollector(router));
    }

    ScenarioEvidenceRunService(
            TroubleshootingPersistenceService persistence,
            TroubleshootingPlaybookVersionService versions,
            DiagnosisStateMachine stateMachine,
            CriterionEvaluator criteria,
            DiagnosisRuleEvaluator rules,
            PlaybookEvidenceCollector collector) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.versions = Objects.requireNonNull(versions, "versions");
        this.stateMachine = Objects.requireNonNull(stateMachine, "stateMachine");
        this.criteria = Objects.requireNonNull(criteria, "criteria");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.collector = Objects.requireNonNull(collector, "collector");
    }

    @Transactional
    public StoredDiagnosis run(long workspaceId, String diagnosisId, String actor) {
        if (workspaceId <= 0) {
            throw invalid("workspaceId is required");
        }
        String safeActor = required(actor, "actor");
        StoredDiagnosis stored = persistence.get(
                workspaceId, required(diagnosisId, "diagnosisId"));
        Diagnosis diagnosis = stored.diagnosis();

        if (diagnosis.investigationMode() != InvestigationMode.SCENARIO_PLAYBOOK) {
            throw conflict("only a scenario investigation runs a scenario evidence plan");
        }
        if (diagnosis.status() != DiagnosisStatus.NEEDS_INVESTIGATION) {
            throw conflict(
                    "this investigation is no longer waiting for evidence; "
                            + "re-running its plan would overwrite a conclusion a human has seen");
        }

        SopEntry playbook = frozenPlaybook(workspaceId, diagnosis);
        if (!PlaybookEvidenceCollector.servesEveryRequiredRequest(playbook)) {
            // Its evidence comes from a Workspace asset's own read-only tool
            // (D18). Running the Router here would return MISSING and file
            // "we looked and found nothing" about a source never consulted.
            throw conflict(
                    "this scenario's required evidence is collected by its own asset tool, "
                            + "not by the evidence router");
        }

        List<EvidenceResult> evidence = collector.collect(
                workspaceId, playbook, diagnosis.incident(), diagnosis.evidence());
        PlaybookEvidenceAssessment assessment = PlaybookEvidenceAssessment.assess(
                playbook, evidence, criteria, rules, diagnosis.fixtureMode());
        Diagnosis advanced = stateMachine.recordScenarioEvidence(
                diagnosis, playbook, evidence, assessment, safeActor);
        return persistence.update(workspaceId, advanced, stored.version());
    }

    /**
     * The exact version the Diagnosis was frozen against, never the currently
     * active one. A Playbook approved after this investigation started is a
     * different Playbook, and judging old evidence by new rules is how a
     * conclusion stops matching the reasoning recorded beside it.
     */
    private SopEntry frozenPlaybook(long workspaceId, Diagnosis diagnosis) {
        PlaybookVersionRef ref = diagnosis.sourcePlaybookVersionRef();
        if (ref == null) {
            throw conflict("the investigation carries no frozen Playbook version");
        }
        ApprovedPlaybookVersion version = versions.findByRef(workspaceId, ref)
                .orElseThrow(() -> conflict(
                        "the frozen Playbook version is no longer readable: " + ref.playbookId()));
        if (!version.playbook().routingKey().equals(diagnosis.sopKey())) {
            throw conflict("the frozen Playbook no longer matches the investigation selector");
        }
        return version.playbook();
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(field + " must not be blank");
        }
        return value.trim();
    }

    private MateClawException invalid(String message) {
        return new MateClawException("err.troubleshooting.invalid_request", 400, message);
    }

    private MateClawException conflict(String message) {
        return new MateClawException(
                "err.troubleshooting.scenario_evidence_conflict", 409, message);
    }
}
