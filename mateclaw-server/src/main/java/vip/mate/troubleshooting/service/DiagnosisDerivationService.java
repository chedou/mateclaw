package vip.mate.troubleshooting.service;

import org.springframework.stereotype.Service;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.engine.CriterionEvaluator;
import vip.mate.troubleshooting.engine.CriterionRenderer;
import vip.mate.troubleshooting.model.AnomalyCriterion;
import vip.mate.troubleshooting.model.CriterionOutcome;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.DiagnosisDerivation;
import vip.mate.troubleshooting.model.DiagnosisRule;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.PlaybookVersionRef;
import vip.mate.troubleshooting.model.SopEntry;
import vip.mate.troubleshooting.synthesis.ApprovedPlaybookVersion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rebuilds the chain from evidence to conclusion so a human can check it.
 *
 * <p>An operator asked to confirm a root cause needs to see why the machine
 * believes it, and — just as important — what it ruled out and what it never
 * managed to test. This service reconstructs that from the stored evidence and
 * the SOP's criteria.</p>
 *
 * <p>Reconstruction uses the exact immutable Playbook version frozen by the
 * Diagnosis. Reading today's active Playbook would describe a decision that
 * never happened after a replacement. Older Diagnosis contracts without that
 * reference therefore fail closed instead of guessing.</p>
 */
@Service
public class DiagnosisDerivationService {

    private final TroubleshootingPersistenceService persistence;
    private final TroubleshootingPlaybookVersionService playbookVersions;
    private final CriterionEvaluator evaluator;
    private final CriterionRenderer renderer;

    public DiagnosisDerivationService(
            TroubleshootingPersistenceService persistence,
            TroubleshootingPlaybookVersionService playbookVersions,
            CriterionEvaluator evaluator,
            CriterionRenderer renderer) {
        this.persistence = persistence;
        this.playbookVersions = playbookVersions;
        this.evaluator = evaluator;
        this.renderer = renderer;
    }

    public DiagnosisDerivation explain(long workspaceId, String diagnosisId) {
        Diagnosis diagnosis = persistence.get(workspaceId, diagnosisId).diagnosis();
        PlaybookVersionRef sourcePlaybook = diagnosis.sourcePlaybook();
        if (sourcePlaybook == null) {
            throw new MateClawException(
                    "err.troubleshooting.diagnosis_playbook_version_not_frozen",
                    409,
                    "this historical diagnosis did not freeze an exact Playbook version; "
                            + "current knowledge will not be used to invent its derivation");
        }
        ApprovedPlaybookVersion version = playbookVersions.findByRef(
                        workspaceId, sourcePlaybook)
                .orElseThrow(() -> new MateClawException(
                        "err.troubleshooting.diagnosis_playbook_version_missing",
                        409,
                        "the exact Playbook version behind this diagnosis is unavailable; "
                                + "its derivation cannot be rebuilt"));
        if (!version.selectorKey().equals(diagnosis.sopKey())
                || !version.selectorKey().equals(version.playbook().routingKey())) {
            throw new MateClawException(
                    "err.troubleshooting.diagnosis_playbook_selector_mismatch",
                    409,
                    "the frozen Playbook version does not match the diagnosis selector");
        }
        SopEntry sop = version.playbook();

        Map<String, EvidenceResult> evidenceByRequest = new LinkedHashMap<>();
        for (EvidenceResult result : diagnosis.evidence()) {
            evidenceByRequest.putIfAbsent(result.queryId(), result);
        }

        List<DiagnosisDerivation.CriterionEvaluation> criteria = new ArrayList<>();
        Map<String, CriterionOutcome> outcomeBySignal =
                evaluator.outcomesBySignal(sop.anomalyCriteria(), diagnosis.evidence());
        for (AnomalyCriterion criterion : sop.anomalyCriteria()) {
            EvidenceResult source = evidenceByRequest.get(criterion.sourceRequestId());
            CriterionOutcome outcome = outcomeBySignal.get(criterion.signal());
            criteria.add(new DiagnosisDerivation.CriterionEvaluation(
                    criterion.signal(),
                    criterion.sourceRequestId(),
                    criterion.description(),
                    kindOf(criterion),
                    renderer.expression(criterion.rule()),
                    renderer.substitution(
                            criterion.rule(), source == null ? null : source.observed()),
                    outcome,
                    source == null ? EvidenceStatus.MISSING : source.status()));
        }

        Set<String> satisfied = new LinkedHashSet<>();
        outcomeBySignal.forEach((signal, outcome) -> {
            if (outcome == CriterionOutcome.SATISFIED) {
                satisfied.add(signal);
            }
        });

        List<DiagnosisDerivation.RuleEvaluation> rules = new ArrayList<>();
        boolean winnerFound = false;
        for (DiagnosisRule rule : sop.diagnosisRules()) {
            List<String> byExclusion = new ArrayList<>();
            List<String> byGap = new ArrayList<>();
            List<String> undefined = new ArrayList<>();
            for (String signal : rule.requiredSignals()) {
                CriterionOutcome outcome = outcomeBySignal.get(signal);
                if (outcome == null) {
                    // No criterion produces this signal, so the rule can never
                    // fire — a gap in the SOP rather than a fact about this incident.
                    undefined.add(signal);
                } else if (outcome == CriterionOutcome.EXCLUDED) {
                    byExclusion.add(signal);
                } else if (outcome == CriterionOutcome.UNEVALUATED) {
                    byGap.add(signal);
                }
            }
            boolean satisfiable = byExclusion.isEmpty() && byGap.isEmpty() && undefined.isEmpty();
            // Only the first satisfiable rule wins, mirroring the evaluator's
            // first-match-wins order.
            boolean fired = satisfiable && !winnerFound;
            winnerFound |= fired;
            rules.add(new DiagnosisDerivation.RuleEvaluation(
                    rule.ruleId(), rule.requiredSignals(), rule.rootCause(),
                    rule.confidence(), fired, byExclusion, byGap, undefined));
        }

        Set<String> recorded = new LinkedHashSet<>(diagnosis.triggeredSignals());
        boolean faithful = recorded.equals(satisfied);
        String note = faithful ? null : driftNote(recorded, satisfied);

        return new DiagnosisDerivation(
                diagnosis.diagnosisId(), sop.routingKey(), faithful, note, criteria, rules);
    }

    private String driftNote(Set<String> recorded, Set<String> recomputed) {
        return "诊断记录与冻结 Playbook 版本不一致：当时记录命中 "
                + describe(recorded) + "，按该不可变版本重算得到 "
                + describe(recomputed) + "。请按数据完整性故障处理，不展示为可信判定链。";
    }

    private String describe(Set<String> signals) {
        return signals.isEmpty() ? "（无信号）" : String.join(", ", signals);
    }

    /** The wire name the criterion serializes under, e.g. {@code ratio_of_sum_gt}. */
    private String kindOf(AnomalyCriterion criterion) {
        String simpleName = criterion.rule().getClass().getSimpleName();
        return simpleName.replaceAll("(?<!^)([A-Z])", "_$1").toLowerCase(java.util.Locale.ROOT);
    }
}
