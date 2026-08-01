package vip.mate.troubleshooting.synthesis;

import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.engine.Criterion;
import vip.mate.troubleshooting.engine.CriterionEvaluator;
import vip.mate.troubleshooting.engine.DiagnosisRuleEvaluator;
import vip.mate.troubleshooting.model.AnomalyCriterion;
import vip.mate.troubleshooting.model.Confidence;
import vip.mate.troubleshooting.model.DiagnosisRule;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.SopEntry;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static vip.mate.troubleshooting.synthesis.ManualPlaybookReplaySuite.Disposition.ABSTAINED;
import static vip.mate.troubleshooting.synthesis.ManualPlaybookReplaySuite.Disposition.EXCLUDED;
import static vip.mate.troubleshooting.synthesis.ManualPlaybookReplaySuite.Disposition.MATCHED;

class ManualPlaybookRecordedEvidenceSeedTest {

    private final ManualPlaybookReplaySuiteTemplateFactory factory =
            new ManualPlaybookReplaySuiteTemplateFactory();
    private final ManualPlaybookReplayEvaluator evaluator =
            new ManualPlaybookReplayEvaluator(
                    new CriterionEvaluator(), new DiagnosisRuleEvaluator());

    @Test
    void generatesANumericGteCounterexample() {
        assertGeneratedSuitePasses(
                new Criterion.NumericGte("count", 1),
                Map.of("count", 2));
    }

    @Test
    void generatesAMissingOrLteCounterexample() {
        assertGeneratedSuitePasses(
                new Criterion.MissingOrLte("present", "lag", 5),
                Map.of("present", false, "lag", 99));
    }

    @Test
    void generatesARatioCounterexample() {
        assertGeneratedSuitePasses(
                new Criterion.RatioOfSumGt("failures", "successes", 0.5),
                Map.of("failures", 9, "successes", 1));
    }

    @Test
    void generatesAMultipleCounterexample() {
        assertGeneratedSuitePasses(
                new Criterion.MultipleGt("current", "baseline", 3),
                Map.of("current", 4, "baseline", 1));
    }

    @Test
    void generatesAContainsAndInCounterexample() {
        assertGeneratedSuitePasses(
                new Criterion.ContainsAndIn(
                        "message", "producer", "status", List.of("error", "fatal")),
                Map.of("message", "mq producer send failed", "status", "error"));
    }

    @Test
    void generatesABooleanCounterexample() {
        assertGeneratedSuitePasses(
                new Criterion.BooleanEquals("reachable", false),
                Map.of("reachable", false));
    }

    @Test
    void rejectsCriteriaThatNeedConflictingValuesForOneNegativeCase() {
        SopEntry candidate = candidate(
                List.of(
                        new AnomalyCriterion(
                                "large", "EV-1", "value is large",
                                new Criterion.NumericGte("value", 10)),
                        new AnomalyCriterion(
                                "missing-or-small", "EV-1", "value is missing or small",
                                new Criterion.MissingOrLte("present", "value", 20))),
                List.of(new DiagnosisRule(
                        "RULE-1", List.of("large", "missing-or-small"),
                        "recorded failure", "recorded failure", Confidence.MEDIUM, false)));
        ManualPlaybookRecordedEvidenceSeed seed = seed(
                candidate, Map.of("present", false, "value", 11));

        assertThatThrownBy(() -> factory.generate(seed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot produce one deterministic negative case");
    }

    @Test
    void rejectsSecretShapedFieldsFromRecordedEvidence() {
        SopEntry candidate = singleCriterionCandidate();

        assertThatThrownBy(() -> seed(candidate, Map.of("api_key", "secret-value")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("safe bounded aggregate");
    }

    @Test
    void rejectsOversizedStringsFromRecordedEvidence() {
        SopEntry candidate = singleCriterionCandidate();

        assertThatThrownBy(() -> seed(candidate, Map.of("count", "x".repeat(1_001))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("safe bounded aggregate");
    }

    @Test
    void appliesTheItemBudgetAcrossTheWholeRecordedAggregate() {
        SopEntry candidate = singleCriterionCandidate();
        Map<String, Object> observed = Map.of(
                "count", 2,
                "first_bucket", java.util.Collections.nCopies(16, 1),
                "second_bucket", java.util.Collections.nCopies(16, 1));

        assertThatThrownBy(() -> seed(candidate, observed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("safe bounded aggregate");
    }

    private void assertGeneratedSuitePasses(
            Criterion criterion,
            Map<String, Object> positiveObserved) {
        SopEntry candidate = candidate(
                List.of(new AnomalyCriterion(
                        "recorded-signal", "EV-1", "recorded signal", criterion)),
                List.of(new DiagnosisRule(
                        "RULE-1", List.of("recorded-signal"),
                        "recorded failure", "recorded failure", Confidence.MEDIUM, false)));

        ManualPlaybookReplaySuite suite = factory.generate(seed(candidate, positiveObserved));

        assertThat(suite.cases())
                .extracting(ManualPlaybookReplaySuite.ReplayCase::expectedDisposition)
                .containsExactly(MATCHED, EXCLUDED, ABSTAINED);
        assertThat(evaluator.evaluate(candidate, suite).passed()).isTrue();
    }

    private ManualPlaybookRecordedEvidenceSeed seed(
            SopEntry candidate,
            Map<String, Object> positiveObserved) {
        return new ManualPlaybookRecordedEvidenceSeed(
                ManualPlaybookRecordedEvidenceSeed.CONTRACT_VERSION,
                "recorded-seed-test/v1",
                1,
                candidate.routingKey(),
                "EV-1",
                "sanitized-recorded-test",
                candidate,
                new ManualPlaybookReplaySuite.ReplayCase(
                        "recorded-positive",
                        ManualPlaybookReplaySuite.Cohort.POSITIVE,
                        MATCHED,
                        "RULE-1",
                        List.of(new ManualPlaybookReplaySuite.ReplayEvidence(
                                "EV-1", EvidenceStatus.ANOMALY, positiveObserved))));
    }

    private SopEntry singleCriterionCandidate() {
        return candidate(
                List.of(new AnomalyCriterion(
                        "recorded-signal", "EV-1", "recorded signal",
                        new Criterion.NumericGte("count", 1))),
                List.of(new DiagnosisRule(
                        "RULE-1", List.of("recorded-signal"),
                        "recorded failure", "recorded failure", Confidence.MEDIUM, false)));
    }

    private SopEntry candidate(
            List<AnomalyCriterion> criteria,
            List<DiagnosisRule> rules) {
        return new SopEntry(
                "manual-recorded-test",
                SopEntry.CURRENT_CONTRACT_VERSION,
                "CSDP",
                "RECORDED_TEST",
                "test-service",
                "Recorded replay test",
                "Recorded failure",
                "dependency",
                "Test owner",
                "candidate",
                false,
                List.of(new EvidenceRequest(
                        "EV-1", "metric", "recorded aggregate",
                        Map.of("target", "test"), "-15m", true)),
                criteria,
                rules,
                List.of());
    }
}
