package vip.mate.troubleshooting.engine;

import org.springframework.stereotype.Component;
import vip.mate.troubleshooting.model.CriterionOutcome;
import vip.mate.troubleshooting.model.DiagnosisRule;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared pure rule semantics for the live hit path and fixed replay. */
@Component
public final class DiagnosisRuleEvaluator {

    public Evaluation evaluate(
            List<DiagnosisRule> rules,
            Map<String, CriterionOutcome> outcomes) {
        if (rules == null || outcomes == null) {
            throw new IllegalArgumentException("rules and criterion outcomes are required");
        }
        List<DiagnosisRule> safeRules = List.copyOf(rules);
        List<String> activeSignals = outcomes.entrySet().stream()
                .filter(entry -> entry.getValue() == CriterionOutcome.SATISFIED)
                .map(Map.Entry::getKey)
                .toList();
        Set<String> active = new HashSet<>(activeSignals);
        for (DiagnosisRule rule : safeRules) {
            if (active.containsAll(rule.requiredSignals())) {
                return new Evaluation(
                        activeSignals,
                        rule.abstained() ? Disposition.ABSTAINED : Disposition.MATCHED,
                        rule);
            }
        }
        return new Evaluation(
                activeSignals,
                allRulesDefinitivelyExcluded(safeRules, outcomes)
                        ? Disposition.EXCLUDED
                        : Disposition.ABSTAINED,
                null);
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

    public enum Disposition {
        MATCHED,
        EXCLUDED,
        ABSTAINED
    }

    public record Evaluation(
            List<String> activeSignals,
            Disposition disposition,
            DiagnosisRule matchedRule) {

        public Evaluation {
            activeSignals = List.copyOf(activeSignals == null ? List.of() : activeSignals);
            if (disposition == null) {
                throw new IllegalArgumentException("rule disposition is required");
            }
            if ((disposition == Disposition.MATCHED) != (matchedRule != null
                    && !matchedRule.abstained())) {
                throw new IllegalArgumentException(
                        "matched disposition must own one non-abstaining rule");
            }
            if (matchedRule != null
                    && disposition == Disposition.ABSTAINED
                    && !matchedRule.abstained()) {
                throw new IllegalArgumentException(
                        "an abstaining matched rule must be explicitly marked");
            }
            if (disposition == Disposition.EXCLUDED && matchedRule != null) {
                throw new IllegalArgumentException(
                        "an excluded decision cannot own a matched rule");
            }
        }
    }
}
