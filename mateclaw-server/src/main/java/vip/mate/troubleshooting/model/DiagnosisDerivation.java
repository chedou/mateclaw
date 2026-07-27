package vip.mate.troubleshooting.model;

import java.util.List;

/**
 * How a diagnosis reached its conclusion, rebuilt for review.
 *
 * <p>Evidence, criteria, signals and rules already exist separately; what an
 * operator lacks is the chain between them. Without it, confirming a conclusion
 * means trusting the machine, which is exactly what the deterministic path was
 * built to avoid.</p>
 *
 * <p><b>On faithfulness.</b> A diagnosis records the evidence it saw and the
 * signals that fired, but not the criteria that produced them — those live in
 * the SOP, and SOPs evolve. So this projection recomputes the chain from the
 * SOP as it stands now and then checks its own work: if the recomputed signals
 * differ from the ones recorded at the time, the knowledge has changed since,
 * {@code faithful} is false and {@code note} says so. Showing a plausible but
 * wrong account of a past decision would be worse than showing none.</p>
 *
 * @param diagnosisId  the diagnosis this explains
 * @param sopKey       routing key of the SOP used
 * @param faithful     whether the recomputed chain matches what was recorded
 * @param note         set when the chain could not be rebuilt faithfully
 * @param criteria     every criterion the SOP defines, with its outcome
 * @param rules        every candidate conclusion, including the ones that lost
 */
public record DiagnosisDerivation(
        String diagnosisId,
        String sopKey,
        boolean faithful,
        String note,
        List<CriterionEvaluation> criteria,
        List<RuleEvaluation> rules) {

    public DiagnosisDerivation {
        criteria = List.copyOf(criteria == null ? List.of() : criteria);
        rules = List.copyOf(rules == null ? List.of() : rules);
    }

    /**
     * One criterion, with the arithmetic that decided it.
     *
     * @param expression   the rule as authored, e.g. {@code count ≥ 1}
     * @param substitution the same rule with observed values filled in, e.g.
     *                     {@code count=148 ≥ 1} — computed here rather than in
     *                     the browser so the console cannot drift from the engine
     */
    public record CriterionEvaluation(
            String signal,
            String sourceRequestId,
            String description,
            String kind,
            String expression,
            String substitution,
            CriterionOutcome outcome,
            EvidenceStatus evidenceStatus) {
    }

    /**
     * One candidate conclusion and, when it lost, why.
     *
     * <p>{@code missingSignals} is split by outcome on purpose: a rule that
     * failed only because a criterion was excluded is genuinely off the table,
     * while one blocked by an unevaluated criterion is still open and tells the
     * operator which evidence to go get.</p>
     *
     * @param unsatisfiedByExclusion required signals whose criteria evaluated false
     * @param unsatisfiedByGap       required signals whose criteria never ran
     * @param undefinedSignals       required signals no criterion produces at all —
     *                               a knowledge gap in the SOP, since such a rule
     *                               can never fire
     */
    public record RuleEvaluation(
            String ruleId,
            List<String> requiredSignals,
            String rootCause,
            Confidence confidence,
            boolean fired,
            List<String> unsatisfiedByExclusion,
            List<String> unsatisfiedByGap,
            List<String> undefinedSignals) {

        public RuleEvaluation {
            requiredSignals = List.copyOf(requiredSignals == null ? List.of() : requiredSignals);
            unsatisfiedByExclusion =
                    List.copyOf(unsatisfiedByExclusion == null ? List.of() : unsatisfiedByExclusion);
            unsatisfiedByGap = List.copyOf(unsatisfiedByGap == null ? List.of() : unsatisfiedByGap);
            undefinedSignals = List.copyOf(undefinedSignals == null ? List.of() : undefinedSignals);
        }
    }
}
