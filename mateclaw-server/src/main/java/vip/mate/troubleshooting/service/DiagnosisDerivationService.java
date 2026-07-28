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
import vip.mate.troubleshooting.model.SopEntry;

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
 * <p>Reconstruction has a trap worth naming: the diagnosis kept its evidence and
 * its signals, but the criteria that connected them live in the SOP, which
 * evolves. Recomputing against today's SOP could therefore describe a decision
 * that never happened. So the service recomputes and then checks itself against
 * the signals recorded at the time; a mismatch means the knowledge changed, and
 * it says so rather than presenting a tidy fiction.</p>
 */
@Service
public class DiagnosisDerivationService {

    private final TroubleshootingPersistenceService persistence;
    private final TroubleshootingSopPersistenceService sopPersistence;
    private final CriterionEvaluator evaluator;
    private final CriterionRenderer renderer;

    public DiagnosisDerivationService(
            TroubleshootingPersistenceService persistence,
            TroubleshootingSopPersistenceService sopPersistence,
            CriterionEvaluator evaluator,
            CriterionRenderer renderer) {
        this.persistence = persistence;
        this.sopPersistence = sopPersistence;
        this.evaluator = evaluator;
        this.renderer = renderer;
    }

    public DiagnosisDerivation explain(long workspaceId, String diagnosisId) {
        Diagnosis diagnosis = persistence.get(workspaceId, diagnosisId).diagnosis();
        SopEntry sop = sopPersistence.find(
                workspaceId, diagnosis.incident().system(), diagnosis.incident().errorCode());
        if (sop == null) {
            throw new MateClawException(
                    "err.troubleshooting.sop_not_found",
                    409,
                    "the SOP behind this diagnosis is no longer registered, "
                            + "so its derivation cannot be rebuilt");
        }

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
        return "SOP 自本次诊断后已变更：当时命中 " + describe(recorded)
                + "，按当前 SOP 重算得到 " + describe(recomputed)
                + "。以下推导反映的是当前知识，不是当时的判定过程。";
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
