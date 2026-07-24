package vip.mate.troubleshooting.engine;

import org.springframework.stereotype.Component;
import vip.mate.troubleshooting.model.AnomalyCriterion;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

/** Pure evaluator for the sealed criterion vocabulary. */
@Component
public final class CriterionEvaluator {

    public boolean matches(Criterion criterion, Map<String, ?> observed) {
        if (criterion == null || observed == null) {
            return false;
        }
        return switch (criterion) {
            case Criterion.NumericGte rule -> number(observed, rule.field())
                    .stream()
                    .anyMatch(value -> value >= rule.threshold());
            case Criterion.MissingOrLte rule -> !truthy(observed.get(rule.presenceField()))
                    || number(observed, rule.field())
                    .stream()
                    .anyMatch(value -> value <= rule.threshold());
            case Criterion.RatioOfSumGt rule -> ratioMatches(rule, observed);
            case Criterion.MultipleGt rule -> multipleMatches(rule, observed);
            case Criterion.ContainsAndIn rule -> containsAndInMatches(rule, observed);
            case Criterion.BooleanEquals rule -> observed.get(rule.field()) instanceof Boolean value
                    && value == rule.expected();
        };
    }

    public List<String> matchingSignals(
            List<AnomalyCriterion> criteria,
            List<EvidenceResult> evidence) {
        Map<String, EvidenceResult> byRequestId = new HashMap<>();
        for (EvidenceResult result : safe(evidence)) {
            byRequestId.put(result.queryId(), result);
        }
        return safe(criteria).stream()
                .filter(criterion -> {
                    EvidenceResult result = byRequestId.get(criterion.sourceRequestId());
                    return result != null
                            && result.status() != EvidenceStatus.MISSING
                            && matches(criterion.rule(), result.observed());
                })
                .map(AnomalyCriterion::signal)
                .toList();
    }

    private boolean ratioMatches(Criterion.RatioOfSumGt rule, Map<String, ?> observed) {
        OptionalDouble numerator = number(observed, rule.numeratorField());
        OptionalDouble addend = number(observed, rule.addendField());
        if (numerator.isEmpty() || addend.isEmpty()) {
            return false;
        }
        double denominator = numerator.getAsDouble() + addend.getAsDouble();
        return denominator > 0 && numerator.getAsDouble() / denominator > rule.threshold();
    }

    private boolean multipleMatches(Criterion.MultipleGt rule, Map<String, ?> observed) {
        OptionalDouble value = number(observed, rule.field());
        OptionalDouble baseline = number(observed, rule.baselineField());
        return value.isPresent()
                && baseline.isPresent()
                && baseline.getAsDouble() > 0
                && value.getAsDouble() > baseline.getAsDouble() * rule.multiplier();
    }

    private boolean containsAndInMatches(Criterion.ContainsAndIn rule, Map<String, ?> observed) {
        String containsValue = string(observed.get(rule.containsField()));
        String membershipValue = string(observed.get(rule.membershipField()));
        Set<String> accepted = new HashSet<>();
        rule.acceptedValues().forEach(value -> accepted.add(value.toLowerCase(Locale.ROOT)));
        return containsValue.contains(rule.substring().toLowerCase(Locale.ROOT))
                && accepted.contains(membershipValue);
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

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value).toLowerCase(Locale.ROOT);
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }
}
