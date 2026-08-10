package vip.mate.troubleshooting.synthesis;

import vip.mate.troubleshooting.engine.Criterion;
import vip.mate.troubleshooting.model.AnomalyCriterion;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.SopEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Generates fixed negative and missing cases from the closed criterion vocabulary. */
final class ManualPlaybookReplaySuiteTemplateFactory {

    private static final String CONFLICT_MESSAGE =
            "criterion-shape template cannot produce one deterministic negative case";

    ManualPlaybookReplaySuite generate(ManualPlaybookRecordedEvidenceSeed seed) {
        if (seed == null) {
            throw new IllegalArgumentException("recorded evidence seed is required");
        }
        SopEntry candidate = seed.exampleCandidate();
        Map<String, ManualPlaybookReplaySuite.ReplayEvidence> positiveByRequest =
                seed.positiveCase().evidence().stream().collect(Collectors.toMap(
                        ManualPlaybookReplaySuite.ReplayEvidence::requestId,
                        Function.identity(),
                        (first, second) -> first,
                        LinkedHashMap::new));
        Set<String> candidateRequestIds = candidate.evidenceRequests().stream()
                .map(EvidenceRequest::requestId)
                .collect(Collectors.toUnmodifiableSet());
        if (!positiveByRequest.keySet().equals(candidateRequestIds)) {
            throw new IllegalArgumentException(
                    "recorded positive evidence must cover every candidate request exactly");
        }

        Map<String, Map<String, Object>> generatedWrites = new LinkedHashMap<>();
        for (AnomalyCriterion criterion : candidate.anomalyCriteria()) {
            Map<String, Object> writes = generatedWrites.computeIfAbsent(
                    criterion.sourceRequestId(), ignored -> new LinkedHashMap<>());
            writeCounterexample(criterion.rule(), writes);
        }

        List<ManualPlaybookReplaySuite.ReplayEvidence> negative = new ArrayList<>();
        List<ManualPlaybookReplaySuite.ReplayEvidence> missing = new ArrayList<>();
        for (EvidenceRequest request : candidate.evidenceRequests()) {
            ManualPlaybookReplaySuite.ReplayEvidence positive =
                    positiveByRequest.get(request.requestId());
            Map<String, Object> observed = new LinkedHashMap<>(positive.observed());
            observed.putAll(generatedWrites.getOrDefault(request.requestId(), Map.of()));
            negative.add(new ManualPlaybookReplaySuite.ReplayEvidence(
                    request.requestId(), EvidenceStatus.NORMAL, observed));
            missing.add(new ManualPlaybookReplaySuite.ReplayEvidence(
                    request.requestId(), EvidenceStatus.MISSING, Map.of()));
        }

        EvidenceRequest required = candidate.evidenceRequests().stream()
                .filter(request -> seed.requiredEvidenceRequestId().equals(request.requestId()))
                .findFirst()
                .orElseThrow();
        return new ManualPlaybookReplaySuite(
                ManualPlaybookReplaySuite.CONTRACT_VERSION,
                seed.suiteId(),
                seed.suiteVersion(),
                seed.selectorKey(),
                new ManualPlaybookReplaySuite.RequiredEvidenceRequest(
                        required.requestId(), required.signalKind(),
                        required.required(), required.target()),
                candidate,
                List.of(
                        seed.positiveCase(),
                        new ManualPlaybookReplaySuite.ReplayCase(
                                "criterion-shape-negative",
                                ManualPlaybookReplaySuite.Cohort.NEGATIVE_OR_ABSTAIN,
                                ManualPlaybookReplaySuite.Disposition.EXCLUDED,
                                null,
                                negative),
                        new ManualPlaybookReplaySuite.ReplayCase(
                                "missing-evidence-abstain",
                                ManualPlaybookReplaySuite.Cohort.NEGATIVE_OR_ABSTAIN,
                                ManualPlaybookReplaySuite.Disposition.ABSTAINED,
                                null,
                                missing)));
    }

    private void writeCounterexample(Criterion criterion, Map<String, Object> writes) {
        switch (criterion) {
            case Criterion.NumericGte rule ->
                    put(writes, rule.field(), finite(Math.nextDown(rule.threshold())));
            case Criterion.MissingOrLte rule -> {
                put(writes, rule.presenceField(), true);
                put(writes, rule.field(), finite(Math.nextUp(rule.threshold())));
            }
            case Criterion.RatioOfSumGt rule -> writeRatioCounterexample(rule, writes);
            case Criterion.FailureSuccessRateContrast rule -> {
                put(writes, rule.failureSampleField(), 100D);
                put(writes, rule.failureMatchField(), 1D);
                put(writes, rule.successSampleField(), 100D);
                put(writes, rule.successMatchField(), 0D);
            }
            case Criterion.MultipleGt rule -> {
                put(writes, rule.baselineField(), 1D);
                put(writes, rule.field(), finite(rule.multiplier()));
            }
            case Criterion.ContainsAndIn rule -> writeStringCounterexample(rule, writes);
            case Criterion.BooleanEquals rule -> put(writes, rule.field(), !rule.expected());
        }
    }

    private void writeRatioCounterexample(
            Criterion.RatioOfSumGt rule,
            Map<String, Object> writes) {
        if (rule.threshold() >= 0) {
            put(writes, rule.numeratorField(), 0D);
            put(writes, rule.addendField(), 1D);
            return;
        }
        double numerator = finite(rule.threshold() / 2D);
        put(writes, rule.numeratorField(), numerator);
        put(writes, rule.addendField(), finite(0.5D - numerator));
    }

    private void writeStringCounterexample(
            Criterion.ContainsAndIn rule,
            Map<String, Object> writes) {
        if (!rule.containsField().equals(rule.membershipField())) {
            put(writes, rule.containsField(), "");
            put(writes, rule.membershipField(), rule.acceptedValues().getFirst());
            return;
        }
        Set<String> accepted = rule.acceptedValues().stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        String value = "excluded";
        while (accepted.contains(value.toLowerCase(Locale.ROOT))) {
            value = value + "_";
        }
        put(writes, rule.containsField(), value);
    }

    private void put(Map<String, Object> writes, String field, Object value) {
        Object previous = writes.putIfAbsent(field, value);
        if (previous != null && !Objects.equals(previous, value)) {
            throw new IllegalArgumentException(CONFLICT_MESSAGE);
        }
    }

    private double finite(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(CONFLICT_MESSAGE);
        }
        return value;
    }
}
