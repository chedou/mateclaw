package vip.mate.troubleshooting.engine;

import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.model.Confidence;
import vip.mate.troubleshooting.model.CriterionOutcome;
import vip.mate.troubleshooting.model.DiagnosisRule;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosisRuleEvaluatorTest {

    private final DiagnosisRuleEvaluator evaluator = new DiagnosisRuleEvaluator();

    @Test
    void returnsTheFirstMatchingRuleAndTheSameActiveSignalsUsedByDiagnosis() {
        DiagnosisRule first = rule("R-1", List.of("signal-a"), false);
        DiagnosisRule second = rule("R-2", List.of("signal-b"), false);

        DiagnosisRuleEvaluator.Evaluation result = evaluator.evaluate(
                List.of(first, second),
                Map.of(
                        "signal-a", CriterionOutcome.SATISFIED,
                        "signal-b", CriterionOutcome.SATISFIED));

        assertThat(result.disposition())
                .isEqualTo(DiagnosisRuleEvaluator.Disposition.MATCHED);
        assertThat(result.matchedRule()).isEqualTo(first);
        assertThat(result.activeSignals()).containsExactlyInAnyOrder("signal-a", "signal-b");
    }

    @Test
    void doesNotCallAPartiallyObservedRuleExcluded() {
        DiagnosisRule rule = rule("R-1", List.of("signal-a", "signal-b"), false);

        DiagnosisRuleEvaluator.Evaluation result = evaluator.evaluate(
                List.of(rule),
                Map.of(
                        "signal-a", CriterionOutcome.EXCLUDED,
                        "signal-b", CriterionOutcome.UNEVALUATED));

        assertThat(result.disposition())
                .isEqualTo(DiagnosisRuleEvaluator.Disposition.ABSTAINED);
        assertThat(result.matchedRule()).isNull();
    }

    @Test
    void preservesAnExplicitAbstainingRule() {
        DiagnosisRule abstaining = rule("R-ABSTAIN", List.of("signal-a"), true);

        DiagnosisRuleEvaluator.Evaluation result = evaluator.evaluate(
                List.of(abstaining),
                Map.of("signal-a", CriterionOutcome.SATISFIED));

        assertThat(result.disposition())
                .isEqualTo(DiagnosisRuleEvaluator.Disposition.ABSTAINED);
        assertThat(result.matchedRule()).isEqualTo(abstaining);
    }

    private DiagnosisRule rule(String id, List<String> signals, boolean abstained) {
        return new DiagnosisRule(
                id, signals, "根因", "摘要", Confidence.MEDIUM, abstained);
    }
}
