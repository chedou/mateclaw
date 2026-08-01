package vip.mate.troubleshooting.evaluation;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/** Source- and Diagnosis-fixture-separated telemetry; intentionally no gate verdict. */
public record BaselineEvaluationLedger(
        List<BaselineEvaluationRun> runs,
        Summary summary) {

    public BaselineEvaluationLedger {
        runs = List.copyOf(runs == null ? List.of() : runs);
        summary = summary == null ? Summary.from(runs) : summary;
    }

    public static BaselineEvaluationLedger from(List<BaselineEvaluationRun> runs) {
        List<BaselineEvaluationRun> immutable = List.copyOf(runs == null ? List.of() : runs);
        return new BaselineEvaluationLedger(immutable, Summary.from(immutable));
    }

    public record Summary(
            int total,
            int scored,
            int abstained,
            int modelRejected,
            int validationRejected,
            SourceMetrics guance,
            SourceMetrics recordedReplay) {

        public Summary {
            guance = guance == null ? SourceMetrics.unavailable() : guance;
            recordedReplay = recordedReplay == null
                    ? SourceMetrics.unavailable()
                    : recordedReplay;
        }

        static Summary from(List<BaselineEvaluationRun> runs) {
            return new Summary(
                    runs.size(),
                    count(runs, run -> run.status() == BaselineEvaluationRun.Status.SCORED),
                    count(runs, run -> run.status() == BaselineEvaluationRun.Status.ABSTAINED),
                    count(runs, run -> run.status()
                            == BaselineEvaluationRun.Status.MODEL_REJECTED),
                    count(runs, run -> run.status()
                            == BaselineEvaluationRun.Status.VALIDATION_REJECTED),
                    SourceMetrics.from(
                            runs, EvidenceEvaluationSample.SourcePlatform.GUANCE),
                    SourceMetrics.from(
                            runs, EvidenceEvaluationSample.SourcePlatform.RECORDED_REPLAY));
        }
    }

    /** Source total plus non-mixed real-Diagnosis and fixture-Diagnosis cohorts. */
    public record SourceMetrics(
            int runCount,
            int evidenceFixtureRuns,
            CohortMetrics realDiagnosis,
            CohortMetrics fixtureDiagnosis) {

        public SourceMetrics {
            realDiagnosis = realDiagnosis == null
                    ? CohortMetrics.unavailable()
                    : realDiagnosis;
            fixtureDiagnosis = fixtureDiagnosis == null
                    ? CohortMetrics.unavailable()
                    : fixtureDiagnosis;
            if (runCount < 0 || evidenceFixtureRuns < 0 || evidenceFixtureRuns > runCount
                    || realDiagnosis.runCount() + fixtureDiagnosis.runCount() != runCount) {
                throw new IllegalArgumentException(
                        "source metrics must partition real and fixture Diagnosis cohorts");
            }
        }

        static SourceMetrics unavailable() {
            return new SourceMetrics(
                    0, 0, CohortMetrics.unavailable(), CohortMetrics.unavailable());
        }

        static SourceMetrics from(
                List<BaselineEvaluationRun> allRuns,
                EvidenceEvaluationSample.SourcePlatform platform) {
            List<BaselineEvaluationRun> runs = allRuns.stream()
                    .filter(run -> run.sourcePlatform() == platform)
                    .toList();
            if (runs.isEmpty()) {
                return unavailable();
            }
            return new SourceMetrics(
                    runs.size(),
                    count(runs, BaselineEvaluationRun::evidenceFixtureMode),
                    CohortMetrics.from(runs.stream()
                            .filter(run -> !run.diagnosisFixtureMode())
                            .toList()),
                    CohortMetrics.from(runs.stream()
                            .filter(BaselineEvaluationRun::diagnosisFixtureMode)
                            .toList()));
        }
    }

    public record CohortMetrics(
            int runCount,
            int helpful,
            int unhelpful,
            int harmfulBlocked,
            int technicalFailure,
            Long modelP50Ms,
            Long modelP95Ms,
            Long composedTotalP50Ms,
            Long composedTotalP95Ms,
            int tokenMeasuredRuns,
            long promptTokens,
            long completionTokens,
            long totalTokens) {

        public CohortMetrics {
            List<Long> durations = Stream.of(
                            modelP50Ms,
                            modelP95Ms,
                            composedTotalP50Ms,
                            composedTotalP95Ms)
                    .filter(Objects::nonNull)
                    .toList();
            if (runCount < 0 || helpful < 0 || unhelpful < 0 || harmfulBlocked < 0
                    || technicalFailure < 0 || tokenMeasuredRuns < 0
                    || promptTokens < 0 || completionTokens < 0 || totalTokens < 0
                    || durations.stream().anyMatch(value -> value < 0)) {
                throw new IllegalArgumentException(
                        "baseline metric counts and durations must not be negative");
            }
            if ((runCount == 0) != durations.isEmpty()) {
                throw new IllegalArgumentException(
                        "baseline duration percentiles require at least one run");
            }
            if (runCount > 0 && durations.size() != 4) {
                throw new IllegalArgumentException(
                        "baseline metrics require all duration percentiles");
            }
            if (helpful + unhelpful + harmfulBlocked + technicalFailure != runCount) {
                throw new IllegalArgumentException(
                        "baseline classifications must partition the cohort runs");
            }
            if (tokenMeasuredRuns == 0
                    && (promptTokens != 0 || completionTokens != 0 || totalTokens != 0)) {
                throw new IllegalArgumentException(
                        "token totals require measured token usage");
            }
        }

        static CohortMetrics unavailable() {
            return new CohortMetrics(0, 0, 0, 0, 0,
                    null, null, null, null, 0, 0, 0, 0);
        }

        static CohortMetrics from(List<BaselineEvaluationRun> runs) {
            if (runs.isEmpty()) {
                return unavailable();
            }
            List<BaselineEvaluationRun> tokenMeasured = runs.stream()
                    .filter(run -> run.model().totalTokens() != null)
                    .toList();
            return new CohortMetrics(
                    runs.size(),
                    count(runs, run -> run.quality().classification()
                            == BaselineEvaluationRun.Classification.HELPFUL),
                    count(runs, run -> run.quality().classification()
                            == BaselineEvaluationRun.Classification.UNHELPFUL),
                    count(runs, run -> run.quality().classification()
                            == BaselineEvaluationRun.Classification.HARMFUL_BLOCKED),
                    count(runs, run -> run.quality().classification()
                            == BaselineEvaluationRun.Classification.TECHNICAL_FAILURE),
                    percentile(
                            runs.stream().map(BaselineEvaluationRun::modelDurationMs).toList(),
                            0.50),
                    percentile(
                            runs.stream().map(BaselineEvaluationRun::modelDurationMs).toList(),
                            0.95),
                    percentile(
                            runs.stream()
                                    .map(BaselineEvaluationRun::composedTotalDurationMs)
                                    .toList(),
                            0.50),
                    percentile(
                            runs.stream()
                                    .map(BaselineEvaluationRun::composedTotalDurationMs)
                                    .toList(),
                            0.95),
                    tokenMeasured.size(),
                    tokenMeasured.stream().map(BaselineEvaluationRun::model)
                            .map(BaselineEvaluationRun.ModelSnapshot::promptTokens)
                            .mapToLong(Long::longValue).sum(),
                    tokenMeasured.stream().map(BaselineEvaluationRun::model)
                            .map(BaselineEvaluationRun.ModelSnapshot::completionTokens)
                            .mapToLong(Long::longValue).sum(),
                    tokenMeasured.stream().map(BaselineEvaluationRun::model)
                            .map(BaselineEvaluationRun.ModelSnapshot::totalTokens)
                            .mapToLong(Long::longValue).sum());
        }
    }

    private static int count(
            List<BaselineEvaluationRun> runs,
            java.util.function.Predicate<BaselineEvaluationRun> predicate) {
        return (int) runs.stream().filter(predicate).count();
    }

    private static Long percentile(List<Long> values, double percentile) {
        if (values.isEmpty()) {
            return null;
        }
        List<Long> sorted = values.stream().sorted().toList();
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, index));
    }
}
