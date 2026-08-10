package vip.mate.troubleshooting.engine;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Renders a criterion as text a human can check by eye.
 *
 * <p>Two forms: the rule as the SOP author wrote it, and the same rule with the
 * observed values filled in. The second is the one that matters — an operator
 * asked to confirm "connection pool exhausted" can verify
 * {@code 2000 ÷ (2000 + 0) = 1 > 0.95} in a second, but can do nothing with the
 * symbolic form alone.</p>
 *
 * <p>Rendering lives beside the evaluator rather than in the browser so the two
 * cannot drift: a console that re-implemented this arithmetic would eventually
 * display a different verdict than the engine reached.</p>
 */
@Component
public final class CriterionRenderer {

    /** The rule as authored, with field names rather than values. */
    public String expression(Criterion criterion) {
        if (criterion == null) {
            return "";
        }
        return switch (criterion) {
            case Criterion.NumericGte rule ->
                    rule.field() + " ≥ " + number(rule.threshold());
            case Criterion.MissingOrLte rule ->
                    "¬truthy(" + rule.presenceField() + ")  ∨  "
                            + rule.field() + " ≤ " + number(rule.threshold());
            case Criterion.RatioOfSumGt rule ->
                    rule.numeratorField() + " ÷ (" + rule.numeratorField()
                            + " + " + rule.addendField() + ") > " + number(rule.threshold());
            case Criterion.FailureSuccessRateContrast rule ->
                    rule.failureMatchField() + " ÷ " + rule.failureSampleField()
                            + " ≥ " + number(rule.minFailureRate()) + "  ∧  "
                            + rule.successMatchField() + " ÷ " + rule.successSampleField()
                            + " ≤ " + number(rule.maxSuccessRate()) + "  ∧  rate_delta ≥ "
                            + number(rule.minRateDelta());
            case Criterion.MultipleGt rule ->
                    rule.field() + " > " + number(rule.multiplier()) + " × " + rule.baselineField();
            case Criterion.ContainsAndIn rule ->
                    rule.containsField() + " ∋ \"" + rule.substring() + "\"  ∧  "
                            + rule.membershipField() + " ∈ {" + String.join(", ", rule.acceptedValues()) + "}";
            case Criterion.BooleanEquals rule ->
                    rule.field() + " == " + rule.expected();
        };
    }

    /**
     * The rule with observed values substituted.
     *
     * @param observed the evidence row's normalized fields; {@code null} when the
     *                 collection failed, in which case there is nothing to substitute
     */
    public String substitution(Criterion criterion, Map<String, ?> observed) {
        if (criterion == null) {
            return "";
        }
        if (observed == null || observed.isEmpty()) {
            return "证据缺失，判据未执行";
        }
        return switch (criterion) {
            case Criterion.NumericGte rule -> {
                Double value = number(observed, rule.field());
                yield value == null
                        ? rule.field() + " 不在 observed 中"
                        : rule.field() + "=" + number(value)
                                + (value >= rule.threshold() ? " ≥ " : " < ") + number(rule.threshold());
            }
            case Criterion.MissingOrLte rule -> {
                Object presence = observed.get(rule.presenceField());
                if (!truthy(presence)) {
                    yield rule.presenceField() + "=" + display(presence) + " → 非真，左支成立";
                }
                Double value = number(observed, rule.field());
                yield value == null
                        ? rule.presenceField() + " 为真，且 " + rule.field() + " 不可读"
                        : rule.presenceField() + "=真，" + rule.field() + "=" + number(value)
                                + (value <= rule.threshold() ? " ≤ " : " > ") + number(rule.threshold());
            }
            case Criterion.RatioOfSumGt rule -> {
                Double numerator = number(observed, rule.numeratorField());
                Double addend = number(observed, rule.addendField());
                if (numerator == null || addend == null) {
                    yield (numerator == null ? rule.numeratorField() : rule.addendField()) + " 不可读";
                }
                double denominator = numerator + addend;
                if (denominator <= 0) {
                    yield number(numerator) + " ÷ (" + number(numerator) + " + "
                            + number(addend) + ") → 分母为 0";
                }
                double ratio = numerator / denominator;
                yield number(numerator) + " ÷ (" + number(numerator) + " + " + number(addend)
                        + ") = " + number(ratio)
                        + (ratio > rule.threshold() ? " > " : " ≤ ") + number(rule.threshold());
            }
            case Criterion.FailureSuccessRateContrast rule -> {
                Double failureMatches = number(observed, rule.failureMatchField());
                Double failureSamples = number(observed, rule.failureSampleField());
                Double successMatches = number(observed, rule.successMatchField());
                Double successSamples = number(observed, rule.successSampleField());
                if (failureMatches == null
                        || failureSamples == null
                        || successMatches == null
                        || successSamples == null) {
                    yield "成功/失败样本计数不完整，判据未执行";
                }
                if (failureSamples <= 0D
                        || successSamples <= 0D
                        || failureMatches < 0D
                        || successMatches < 0D
                        || failureMatches > failureSamples
                        || successMatches > successSamples) {
                    yield "成功/失败样本计数不合法，判据未执行";
                }
                double failureRate = failureMatches / failureSamples;
                double successRate = successMatches / successSamples;
                double delta = failureRate - successRate;
                yield "失败命中率=" + number(failureRate)
                        + (failureRate >= rule.minFailureRate() ? " ≥ " : " < ")
                        + number(rule.minFailureRate()) + "，成功命中率="
                        + number(successRate)
                        + (successRate <= rule.maxSuccessRate() ? " ≤ " : " > ")
                        + number(rule.maxSuccessRate()) + "，差值=" + number(delta)
                        + (delta >= rule.minRateDelta() ? " ≥ " : " < ")
                        + number(rule.minRateDelta());
            }
            case Criterion.MultipleGt rule -> {
                Double value = number(observed, rule.field());
                Double baseline = number(observed, rule.baselineField());
                if (value == null || baseline == null) {
                    yield (value == null ? rule.field() : rule.baselineField()) + " 不可读";
                }
                if (baseline <= 0) {
                    yield "基线 " + rule.baselineField() + "=" + number(baseline) + " ≤ 0";
                }
                double threshold = baseline * rule.multiplier();
                yield number(value) + (value > threshold ? " > " : " ≤ ")
                        + number(rule.multiplier()) + " × " + number(baseline) + " = " + number(threshold);
            }
            case Criterion.ContainsAndIn rule -> {
                Object contains = observed.get(rule.containsField());
                Object membership = observed.get(rule.membershipField());
                boolean hasSubstring = contains != null
                        && String.valueOf(contains).contains(rule.substring());
                boolean accepted = rule.acceptedValues().contains(String.valueOf(membership));
                yield rule.containsField() + "=\"" + display(contains) + "\""
                        + (hasSubstring ? " ∋ \"" : " ∌ \"") + rule.substring() + "\"   ∧   "
                        + rule.membershipField() + "=\"" + display(membership) + "\""
                        + (accepted ? " ∈ {" : " ∉ {")
                        + String.join(", ", rule.acceptedValues()) + "}";
            }
            case Criterion.BooleanEquals rule -> {
                Object value = observed.get(rule.field());
                if (!(value instanceof Boolean actual)) {
                    yield rule.field() + "=" + display(value) + " 非布尔值";
                }
                yield rule.field() + "=" + actual + (actual == rule.expected() ? " == " : " ≠ ")
                        + rule.expected();
            }
        };
    }

    private Double number(Map<String, ?> observed, String field) {
        Object raw = observed.get(field);
        if (raw instanceof Number value) {
            return value.doubleValue();
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /** Trims trailing zeros so a threshold reads {@code 0.95}, not {@code 0.9500000}. */
    private String number(double value) {
        BigDecimal decimal = BigDecimal.valueOf(value)
                .round(new MathContext(6, RoundingMode.HALF_UP))
                .stripTrailingZeros();
        return decimal.scale() <= 0 ? decimal.toBigInteger().toString() : decimal.toPlainString();
    }

    private String display(Object value) {
        return value == null ? "（缺失）" : String.valueOf(value);
    }

    /** Mirrors the evaluator's notion of truthiness. */
    private boolean truthy(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }
}
