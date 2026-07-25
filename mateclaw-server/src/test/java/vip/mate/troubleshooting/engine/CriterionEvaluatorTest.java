package vip.mate.troubleshooting.engine;

import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.model.AnomalyCriterion;
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
    void multipleGtRequiresPositiveBaselineAndStrictlyGreaterValue() {
        Criterion rule = new Criterion.MultipleGt("slow", "baseline", 3);

        assertTrue(evaluator.matches(rule, Map.of("slow", 31, "baseline", 10)));
        assertFalse(evaluator.matches(rule, Map.of("slow", 30, "baseline", 10)));
        assertFalse(evaluator.matches(rule, Map.of("slow", 31, "baseline", 0)));
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
}
