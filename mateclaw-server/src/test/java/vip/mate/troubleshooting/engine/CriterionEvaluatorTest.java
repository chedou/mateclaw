package vip.mate.troubleshooting.engine;

import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.model.AnomalyCriterion;
import vip.mate.troubleshooting.model.CriterionOutcome;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CriterionEvaluatorTest {

    private final CriterionEvaluator evaluator = new CriterionEvaluator();

    @Test
    void numericGteAcceptsNumbersAndNumericStrings() {
        Criterion rule = new Criterion.NumericGte("count", 3);

        assertTrue(evaluator.matches(rule, Map.of("count", 3)));
        assertTrue(evaluator.matches(rule, Map.of("count", "3.5")));
        assertFalse(evaluator.matches(rule, Map.of("count", 2.99)));
        assertFalse(evaluator.matches(rule, Map.of("count", "not-a-number")));
    }

    @Test
    void missingOrLteMatchesMissingPresenceOrLowValue() {
        Criterion rule = new Criterion.MissingOrLte("reachable", "latency_ms", 100);

        assertTrue(evaluator.matches(rule, Map.of("reachable", false, "latency_ms", 999)));
        assertTrue(evaluator.matches(rule, Map.of("latency_ms", 99)));
        assertTrue(evaluator.matches(rule, Map.of("reachable", true, "latency_ms", 100)));
        assertFalse(evaluator.matches(rule, Map.of("reachable", true, "latency_ms", 101)));
    }

    @Test
    void ratioOfSumGtIsStrictAndRejectsInvalidDenominators() {
        Criterion rule = new Criterion.RatioOfSumGt("current", "available", 0.9);

        assertTrue(evaluator.matches(rule, Map.of("current", 91, "available", 9)));
        assertFalse(evaluator.matches(rule, Map.of("current", 90, "available", 10)));
        assertFalse(evaluator.matches(rule, Map.of("current", 0, "available", 0)));
        assertFalse(evaluator.matches(rule, Map.of("current", "bad", "available", 10)));
    }

    @Test
    void failureSuccessRateContrastRejectsATinyFailureHitEvenWhenSuccessHasNoHits() {
        Criterion rule = new Criterion.FailureSuccessRateContrast(
                "failure_matches", "failure_samples",
                "success_matches", "success_samples",
                0.9, 0.1, 0.8);

        assertTrue(evaluator.matches(rule, Map.of(
                "failure_matches", 9, "failure_samples", 9,
                "success_matches", 0, "success_samples", 25)));
        assertFalse(evaluator.matches(rule, Map.of(
                "failure_matches", 1, "failure_samples", 100,
                "success_matches", 0, "success_samples", 100)));
        assertFalse(evaluator.matches(rule, Map.of(
                "failure_matches", 90, "failure_samples", 100,
                "success_matches", 20, "success_samples", 100)));
        assertEquals(CriterionOutcome.UNEVALUATED, evaluator.evaluate(rule, Map.of(
                "failure_matches", 2, "failure_samples", 1,
                "success_matches", 0, "success_samples", 0)));
    }

    @Test
    void multipleGtRequiresPositiveBaselineAndStrictlyGreaterValue() {
        Criterion rule = new Criterion.MultipleGt("slow", "baseline", 3);

        assertTrue(evaluator.matches(rule, Map.of("slow", 31, "baseline", 10)));
        assertFalse(evaluator.matches(rule, Map.of("slow", 30, "baseline", 10)));
        assertFalse(evaluator.matches(rule, Map.of("slow", 31, "baseline", 0)));
    }

    @Test
    void allOfCanProveASlowRequestHotspotWithoutConfusingTrafficOrTimeoutsForTheCause() {
        Criterion rule = new Criterion.AllOf(List.of(
                new Criterion.RateMultipleGt(
                        "current_slow_request_count", "current_request_count",
                        "baseline_slow_request_count", "baseline_request_count", 5),
                new Criterion.FractionGte(
                        "partner_user_info_slow_count", "current_slow_request_count", 0.5),
                new Criterion.MultipleLte(
                        "current_request_count", "baseline_request_count", 1.5),
                new Criterion.NumericLte("timeout_error_count", 0)));

        Map<String, Object> observed = Map.of(
                "baseline_request_count", 20417,
                "baseline_slow_request_count", 1,
                "current_request_count", 19585,
                "current_slow_request_count", 19,
                "partner_user_info_slow_count", 10,
                "timeout_error_count", 0);

        assertTrue(evaluator.matches(rule, observed));
        assertEquals(
                CriterionOutcome.EXCLUDED,
                evaluator.evaluate(rule, with(observed, "current_request_count", 40000)));
        assertEquals(
                CriterionOutcome.EXCLUDED,
                evaluator.evaluate(rule, with(observed, "timeout_error_count", 1)));
        assertEquals(
                CriterionOutcome.UNEVALUATED,
                evaluator.evaluate(rule, without(observed, "baseline_request_count")));
    }

    @Test
    void containsAndInIsCaseInsensitive() {
        Criterion rule = new Criterion.ContainsAndIn(
                "failed_hop", "mongo", "status", List.of("error", "timeout"));

        assertTrue(evaluator.matches(rule, Map.of("failed_hop", "MongoDB-primary", "status", "TIMEOUT")));
        assertFalse(evaluator.matches(rule, Map.of("failed_hop", "redis", "status", "timeout")));
        assertFalse(evaluator.matches(rule, Map.of("failed_hop", "mongo", "status", "ok")));
    }

    @Test
    void booleanEqualsRequiresAnActualBoolean() {
        Criterion rule = new Criterion.BooleanEquals("reachable", false);

        assertTrue(evaluator.matches(rule, Map.of("reachable", false)));
        assertFalse(evaluator.matches(rule, Map.of("reachable", true)));
        assertFalse(evaluator.matches(rule, Map.of("reachable", "false")));
    }

    @Test
    void matchingSignalsIgnoresMissingEvidenceAndUsesRequestIdentity() {
        List<AnomalyCriterion> criteria = List.of(
                new AnomalyCriterion("log_hit", "error-log", "count >= 1",
                        new Criterion.NumericGte("count", 1)),
                new AnomalyCriterion("mongo_down", "mongo-metrics", "reachable=false",
                        new Criterion.BooleanEquals("reachable", false)));
        List<EvidenceResult> evidence = List.of(
                evidence("error-log", EvidenceStatus.ANOMALY, Map.of("count", 4)),
                evidence("mongo-metrics", EvidenceStatus.MISSING, Map.of("reachable", false)));

        assertEquals(List.of("log_hit"), evaluator.matchingSignals(criteria, evidence));
    }

    @Test
    void outcomesKeepUnavailableFieldsSeparateFromExplicitCounterEvidence() {
        AnomalyCriterion criterion = new AnomalyCriterion(
                "mongo_down", "mongo-metrics", "reachable=false",
                new Criterion.BooleanEquals("reachable", false));

        assertEquals(
                CriterionOutcome.UNEVALUATED,
                evaluator.outcomesBySignal(
                                List.of(criterion),
                                List.of(evidence(
                                        "mongo-metrics", EvidenceStatus.NORMAL, Map.of())))
                        .get("mongo_down"));
        assertEquals(
                CriterionOutcome.UNEVALUATED,
                evaluator.outcomesBySignal(
                                List.of(criterion),
                                List.of(evidence(
                                        "mongo-metrics", EvidenceStatus.NORMAL,
                                        Map.of("reachable", "false"))))
                        .get("mongo_down"));
        assertEquals(
                CriterionOutcome.EXCLUDED,
                evaluator.outcomesBySignal(
                                List.of(criterion),
                                List.of(evidence(
                                        "mongo-metrics", EvidenceStatus.NORMAL,
                                        Map.of("reachable", true))))
                        .get("mongo_down"));
    }

    private EvidenceResult evidence(String queryId, EvidenceStatus status, Map<String, Object> observed) {
        return new EvidenceResult(
                queryId,
                "fixture",
                "fixture://" + queryId,
                status,
                "fixture result",
                observed,
                "fixture",
                Instant.parse("2026-07-25T00:00:00Z"));
    }

    private Map<String, Object> with(
            Map<String, Object> source, String field, Object value) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>(source);
        copy.put(field, value);
        return Map.copyOf(copy);
    }

    private Map<String, Object> without(Map<String, Object> source, String field) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>(source);
        copy.remove(field);
        return Map.copyOf(copy);
    }
}
