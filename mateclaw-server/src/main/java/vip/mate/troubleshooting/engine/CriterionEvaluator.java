package vip.mate.troubleshooting.engine;

import org.springframework.stereotype.Component;
import vip.mate.troubleshooting.model.AnomalyCriterion;
import vip.mate.troubleshooting.model.CriterionOutcome;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

/** Pure evaluator for the sealed criterion vocabulary. */
@Component
public final class CriterionEvaluator {

    public boolean matches(Criterion criterion, Map<String, ?> observed) {
        return evaluate(criterion, observed) == CriterionOutcome.SATISFIED;
    }

    /**
     * Evaluates one criterion without collapsing malformed or absent fields
     * into counter-evidence. Only a fully evaluable false expression is
     * {@link CriterionOutcome#EXCLUDED}.
     */
    public CriterionOutcome evaluate(Criterion criterion, Map<String, ?> observed) {
        if (criterion == null || observed == null) {
            return CriterionOutcome.UNEVALUATED;
        }
        return switch (criterion) {
            case Criterion.NumericGte rule -> compare(
                    number(observed, rule.field()), value -> value >= rule.threshold());
            case Criterion.MissingOrLte rule -> evaluateMissingOrLte(rule, observed);
            case Criterion.RatioOfSumGt rule -> evaluateRatio(rule, observed);
            case Criterion.MultipleGt rule -> evaluateMultiple(rule, observed);
            case Criterion.ContainsAndIn rule -> evaluateContainsAndIn(rule, observed);
            case Criterion.BooleanEquals rule -> observed.get(rule.field()) instanceof Boolean value
                    ? outcome(value == rule.expected())
                    : CriterionOutcome.UNEVALUATED;
        };
    }

    public List<String> matchingSignals(
            List<AnomalyCriterion> criteria,
            List<EvidenceResult> evidence) {
        return outcomesBySignal(criteria, evidence).entrySet().stream()
                .filter(entry -> entry.getValue() == CriterionOutcome.SATISFIED)
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * Evaluates every authored signal, preserving the distinction between a
     * false criterion (EXCLUDED) and evidence that never arrived (UNEVALUATED).
     */
    public Map<String, CriterionOutcome> outcomesBySignal(
            List<AnomalyCriterion> criteria,
            List<EvidenceResult> evidence) {
        Map<String, EvidenceResult> byRequestId = new HashMap<>();
        for (EvidenceResult result : safe(evidence)) {
            byRequestId.put(result.queryId(), result);
        }
        Map<String, CriterionOutcome> outcomes = new LinkedHashMap<>();
        for (AnomalyCriterion criterion : safe(criteria)) {
            EvidenceResult result = byRequestId.get(criterion.sourceRequestId());
            CriterionOutcome outcome = result == null || result.status() == EvidenceStatus.MISSING
                    ? CriterionOutcome.UNEVALUATED
                    : evaluate(criterion.rule(), result.observed());
            outcomes.put(criterion.signal(), outcome);
        }
        return java.util.Collections.unmodifiableMap(outcomes);
    }

    private CriterionOutcome evaluateMissingOrLte(
            Criterion.MissingOrLte rule,
            Map<String, ?> observed) {
        if (!observed.containsKey(rule.presenceField())
                || !truthy(observed.get(rule.presenceField()))) {
            return CriterionOutcome.SATISFIED;
        }
        return compare(number(observed, rule.field()), value -> value <= rule.threshold());
    }

    private CriterionOutcome evaluateRatio(
            Criterion.RatioOfSumGt rule,
            Map<String, ?> observed) {
        OptionalDouble numerator = number(observed, rule.numeratorField());
        OptionalDouble addend = number(observed, rule.addendField());
        if (numerator.isEmpty() || addend.isEmpty()) {
            return CriterionOutcome.UNEVALUATED;
        }
        double denominator = numerator.getAsDouble() + addend.getAsDouble();
        return outcome(denominator > 0
                && numerator.getAsDouble() / denominator > rule.threshold());
    }

    private CriterionOutcome evaluateMultiple(
            Criterion.MultipleGt rule,
            Map<String, ?> observed) {
        OptionalDouble value = number(observed, rule.field());
        OptionalDouble baseline = number(observed, rule.baselineField());
        if (value.isEmpty() || baseline.isEmpty()) {
            return CriterionOutcome.UNEVALUATED;
        }
        return outcome(baseline.getAsDouble() > 0
                && value.getAsDouble() > baseline.getAsDouble() * rule.multiplier());
    }

    private CriterionOutcome evaluateContainsAndIn(
            Criterion.ContainsAndIn rule,
            Map<String, ?> observed) {
        if (!(observed.get(rule.containsField()) instanceof String containsValue)
                || !(observed.get(rule.membershipField()) instanceof String membershipValue)) {
            return CriterionOutcome.UNEVALUATED;
        }
        Set<String> accepted = new HashSet<>();
        rule.acceptedValues().forEach(value -> accepted.add(value.toLowerCase(Locale.ROOT)));
        return outcome(containsValue.toLowerCase(Locale.ROOT)
                        .contains(rule.substring().toLowerCase(Locale.ROOT))
                && accepted.contains(membershipValue.toLowerCase(Locale.ROOT)));
    }

    private CriterionOutcome compare(
            OptionalDouble value,
            java.util.function.DoublePredicate predicate) {
        return value.isPresent()
                ? outcome(predicate.test(value.getAsDouble()))
                : CriterionOutcome.UNEVALUATED;
    }

    private CriterionOutcome outcome(boolean matched) {
        return matched ? CriterionOutcome.SATISFIED : CriterionOutcome.EXCLUDED;
    }

    private OptionalDouble number(Map<String, ?> observed, String field) {
        Object value = observed.get(field);
        if (value instanceof Number number) {
            double converted = number.doubleValue();
            return Double.isFinite(converted) ? OptionalDouble.of(converted) : OptionalDouble.empty();
        }
        if (value instanceof String text) {
            try {
                double converted = Double.parseDouble(text.trim());
                return Double.isFinite(converted) ? OptionalDouble.of(converted) : OptionalDouble.empty();
            } catch (NumberFormatException ignored) {
                return OptionalDouble.empty();
            }
        }
        return OptionalDouble.empty();
    }

    private boolean truthy(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0;
        }
        if (value instanceof String text) {
            String normalized = text.trim().toLowerCase(Locale.ROOT);
            return !normalized.isEmpty()
                    && !normalized.equals("false")
                    && !normalized.equals("0")
                    && !normalized.equals("no")
                    && !normalized.equals("null");
        }
        return value != null;
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }
}
