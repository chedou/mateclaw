package vip.mate.troubleshooting.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.engine.CriterionEvaluator;
import vip.mate.troubleshooting.engine.DiagnosisRuleEvaluator;
import vip.mate.troubleshooting.engine.PlaybookEvidenceAssessment;
import vip.mate.troubleshooting.evidence.EvidenceProvenance;
import vip.mate.troubleshooting.evidence.EvidenceSourceRouter;
import vip.mate.troubleshooting.evidence.EvidenceSpineOrchestrator;
import vip.mate.troubleshooting.evidence.EvidenceSpinePlan;
import vip.mate.troubleshooting.evidence.PlaybookEvidenceCollector;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.DiagnosisStatus;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.InvestigationMode;
import vip.mate.troubleshooting.model.PlaybookVersionRef;
import vip.mate.troubleshooting.model.SopEntry;
import vip.mate.troubleshooting.statemachine.DiagnosisStateMachine;
import vip.mate.troubleshooting.synthesis.ApprovedPlaybookVersion;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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

    private static final Set<String> EVIDENCE_SPINE_KINDS = Set.of(
            "log_search", "log_trace_bundle", "contrast_sample");

    private final TroubleshootingPersistenceService persistence;
    private final TroubleshootingPlaybookVersionService versions;
    private final DiagnosisStateMachine stateMachine;
    private final CriterionEvaluator criteria;
    private final DiagnosisRuleEvaluator rules;
    private final PlaybookEvidenceCollector collector;
    private final EvidenceSpineOrchestrator spineOrchestrator;

    @Autowired
    public ScenarioEvidenceRunService(
            TroubleshootingPersistenceService persistence,
            TroubleshootingPlaybookVersionService versions,
            DiagnosisStateMachine stateMachine,
            CriterionEvaluator criteria,
            DiagnosisRuleEvaluator rules,
            EvidenceSourceRouter router,
            EvidenceSpineOrchestrator spineOrchestrator) {
        this(persistence, versions, stateMachine, criteria, rules,
                new PlaybookEvidenceCollector(router), spineOrchestrator);
    }

    ScenarioEvidenceRunService(
            TroubleshootingPersistenceService persistence,
            TroubleshootingPlaybookVersionService versions,
            DiagnosisStateMachine stateMachine,
            CriterionEvaluator criteria,
            DiagnosisRuleEvaluator rules,
            PlaybookEvidenceCollector collector) {
        this(persistence, versions, stateMachine, criteria, rules, collector, null);
    }

    ScenarioEvidenceRunService(
            TroubleshootingPersistenceService persistence,
            TroubleshootingPlaybookVersionService versions,
            DiagnosisStateMachine stateMachine,
            CriterionEvaluator criteria,
            DiagnosisRuleEvaluator rules,
            PlaybookEvidenceCollector collector,
            EvidenceSpineOrchestrator spineOrchestrator) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.versions = Objects.requireNonNull(versions, "versions");
        this.stateMachine = Objects.requireNonNull(stateMachine, "stateMachine");
        this.criteria = Objects.requireNonNull(criteria, "criteria");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.collector = Objects.requireNonNull(collector, "collector");
        this.spineOrchestrator = spineOrchestrator;
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

        ApprovedPlaybookVersion frozen = frozenPlaybook(workspaceId, diagnosis);
        SopEntry playbook = frozen.playbook();
        if (!PlaybookEvidenceCollector.servesEveryRequiredRequest(playbook)) {
            // Its evidence comes from a Workspace asset's own read-only tool
            // (D18). Running the Router here would return MISSING and file
            // "we looked and found nothing" about a source never consulted.
            throw conflict(
                    "this scenario's required evidence is collected by its own asset tool, "
                            + "not by the evidence router");
        }

        List<EvidenceResult> evidence = collectEvidence(workspaceId, diagnosis, playbook);
        boolean fixtureMode = EvidenceProvenance.fixtureMode(evidence);
        PlaybookEvidenceAssessment assessment = PlaybookEvidenceAssessment.assess(
                playbook, evidence, criteria, rules, fixtureMode,
                // 成色取自**冻结的那一版**，和判据规则同源：否则会拿今天的成色去评判
                // 当时那套规则得出的结论。
                frozen.knowledgeEvidenceGrade());
        Diagnosis advanced = stateMachine.recordScenarioEvidence(
                diagnosis, playbook, evidence, assessment, safeActor);
        return persistence.update(workspaceId, advanced, stored.version());
    }

    private List<EvidenceResult> collectEvidence(
            long workspaceId,
            Diagnosis diagnosis,
            SopEntry playbook) {
        EvidenceSpinePlan spinePlan = evidenceSpinePlan(playbook);
        if (spinePlan == null) {
            return collector.collect(
                    workspaceId, playbook, diagnosis.incident(), diagnosis.evidence());
        }
        if (spineOrchestrator == null) {
            throw conflict("the Evidence Spine runtime is not available");
        }

        // The PS ID stored in a frozen Playbook is only an illustrative contract
        // value. Runtime dependency is server-owned: log_search must answer first,
        // then its observed PS ID is passed to trace and contrast. Passing null for
        // permittedPlatforms deliberately leaves source choice to Workspace route
        // configuration; the browser cannot force Guance or Replay.
        return spineOrchestrator.collect(
                workspaceId, diagnosis.incident(), spinePlan, null).evidence();
    }

    /**
     * Recognizes the fixed three-step evidence protocol without coupling it to
     * one adapter. Other Playbooks keep the generic collector path.
     */
    private EvidenceSpinePlan evidenceSpinePlan(SopEntry playbook) {
        if (playbook.evidenceRequests().size() != EVIDENCE_SPINE_KINDS.size()) {
            return null;
        }
        Map<String, EvidenceRequest> byKind = new HashMap<>();
        for (EvidenceRequest request : playbook.evidenceRequests()) {
            if (!EVIDENCE_SPINE_KINDS.contains(request.signalKind())
                    || byKind.putIfAbsent(request.signalKind(), request) != null) {
                return null;
            }
        }
        if (!byKind.keySet().equals(EVIDENCE_SPINE_KINDS)) {
            return null;
        }

        EvidenceRequest search = byKind.get("log_search");
        EvidenceRequest trace = byKind.get("log_trace_bundle");
        EvidenceRequest contrast = byKind.get("contrast_sample");
        String searchTerm = targetString(search, "search_term");
        Object contrastScenario = contrast.target().get("scenario_key");
        if (contrastScenario != null
                && (!(contrastScenario instanceof String value)
                || !searchTerm.equals(value.trim()))) {
            throw conflict("Evidence Spine search and contrast targets must match");
        }

        String window = normalizedWindow(search.window());
        if (!window.equals(normalizedWindow(trace.window()))
                || !window.equals(normalizedWindow(contrast.window()))) {
            throw conflict("Evidence Spine requests must use one bounded time window");
        }
        try {
            return new EvidenceSpinePlan(
                    search.requestId(),
                    trace.requestId(),
                    contrast.requestId(),
                    searchTerm,
                    window);
        } catch (IllegalArgumentException invalidContract) {
            throw conflict("the frozen Evidence Spine contract is invalid");
        }
    }

    private String targetString(EvidenceRequest request, String field) {
        Object raw = request.target().get(field);
        if (!(raw instanceof String value) || value.isBlank()) {
            throw conflict("Evidence Spine " + field + " is required");
        }
        return value.trim();
    }

    private String normalizedWindow(String value) {
        if (value == null || value.isBlank()) {
            return "-15m";
        }
        String normalized = value.trim();
        return normalized.startsWith("-") ? normalized : "-" + normalized;
    }

    /**
     * The exact version the Diagnosis was frozen against, never the currently
     * active one. A Playbook approved after this investigation started is a
     * different Playbook, and judging old evidence by new rules is how a
     * conclusion stops matching the reasoning recorded beside it.
     */
    private ApprovedPlaybookVersion frozenPlaybook(long workspaceId, Diagnosis diagnosis) {
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
        // 返回整个版本而不是只返回 SopEntry：知识成色也冻结在这一版上，而它决定结论
        // 最高能声称到什么程度。只取 playbook() 会把那件事丢在这里。
        return version;
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
