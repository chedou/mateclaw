package vip.mate.troubleshooting.evaluation;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
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
            long totalTokens,
            QualityMetrics quality) {

        /**
         * 引用完整率、必需意图覆盖率、abstain 质量、危险提议——v4 §5.7 退出校准期
         * 要看的那几个。
         *
         * <p><b>Why it is defined before the samples exist.</b> Every one of
         * these signals was already stored per run and never summed. Written
         * after the 20–30 samples land, each definition would be chosen with the
         * data already on screen, and a threshold fitted to its own data is not
         * a threshold. §5.7 says 退出条件是数据达标、不是排期到点; that only means
         * something if the metric predates the data.</p>
         *
         * <p><b>Counts with denominators, never rates.</b> There is no
         * {@code citationCompletionRate} and no {@code dangerousProposalRate}.
         * "100%" over two assessed runs renders identically to "100%" over
         * thirty, and a cohort this small is exactly where that difference
         * decides everything. The reader gets both numbers and does the division
         * knowing what it was over — the same reason {@link NorthStarComparison}
         * refuses to publish a savings figure.</p>
         *
         * <p><b>Assessed is not the same as run.</b> A model-rejected or
         * validation-rejected run carries no comparison at all, so it can
         * neither pass nor fail these; it stays outside the denominator rather
         * than being silently counted as a pass.</p>
         */
        public record QualityMetrics(
                int citationAssessedRuns,
                int citationCompleteRuns,
                int coverageAssessedRuns,
                Double coverageP50,
                Double coverageMin,
                int fullCoverageRuns,
                int abstentions,
                int cleanAbstentions,
                Map<String, Integer> abstainFailureCounts,
                int dangerousProposalRuns,
                int confidenceAssessedRuns,
                int highConfidenceRuns,
                int highConfidenceErrorRuns) {

            /** Backward-compatible shape for callers and historical JSON. */
            public QualityMetrics(
                    int citationAssessedRuns,
                    int citationCompleteRuns,
                    int coverageAssessedRuns,
                    Double coverageP50,
                    Double coverageMin,
                    int fullCoverageRuns,
                    int abstentions,
                    int cleanAbstentions,
                    Map<String, Integer> abstainFailureCounts,
                    int dangerousProposalRuns) {
                this(
                        citationAssessedRuns,
                        citationCompleteRuns,
                        coverageAssessedRuns,
                        coverageP50,
                        coverageMin,
                        fullCoverageRuns,
                        abstentions,
                        cleanAbstentions,
                        abstainFailureCounts,
                        dangerousProposalRuns,
                        0,
                        0,
                        0);
            }

            public QualityMetrics {
                abstainFailureCounts = Collections.unmodifiableMap(new TreeMap<>(
                        abstainFailureCounts == null ? Map.of() : abstainFailureCounts));
                if (citationAssessedRuns < 0 || citationCompleteRuns < 0
                        || coverageAssessedRuns < 0 || fullCoverageRuns < 0
                        || abstentions < 0 || cleanAbstentions < 0
                        || dangerousProposalRuns < 0 || confidenceAssessedRuns < 0
                        || highConfidenceRuns < 0 || highConfidenceErrorRuns < 0) {
                    throw new IllegalArgumentException(
                            "quality metric counts must not be negative");
                }
                if (citationCompleteRuns > citationAssessedRuns
                        || fullCoverageRuns > coverageAssessedRuns
                        || cleanAbstentions > abstentions
                        || highConfidenceRuns > confidenceAssessedRuns
                        || highConfidenceErrorRuns > highConfidenceRuns) {
                    throw new IllegalArgumentException(
                            "a quality pass count cannot exceed what was assessed");
                }
                // Both-or-neither, same discipline as the north-star cohorts: a
                // coverage figure with no denominator reads as a measured one.
                boolean hasBoth = coverageP50 != null && coverageMin != null;
                boolean hasNeither = coverageP50 == null && coverageMin == null;
                if (coverageAssessedRuns == 0 ? !hasNeither : !hasBoth) {
                    throw new IllegalArgumentException(
                            "coverage percentiles exist exactly when coverage was assessed");
                }
                if (hasBoth && (coverageMin > coverageP50
                        || coverageMin < 0 || coverageP50 > 1)) {
                    throw new IllegalArgumentException(
                            "coverage min must not exceed p50 and both must be a ratio");
                }
                if (abstainFailureCounts.values().stream()
                        .anyMatch(value -> value == null || value <= 0)) {
                    throw new IllegalArgumentException(
                            "an abstain failure code with no occurrences is not a tally entry");
                }
            }

            static QualityMetrics unavailable() {
                return new QualityMetrics(
                        0, 0, 0, null, null, 0, 0, 0, Map.of(), 0, 0, 0, 0);
            }

            static QualityMetrics from(List<BaselineEvaluationRun> runs) {
                if (runs.isEmpty()) {
                    return unavailable();
                }
                List<BaselineEvaluationRun> citationAssessed = runs.stream()
                        .filter(run -> run.quality().citationComplete() != null)
                        .toList();
                List<Double> coverage = runs.stream()
                        .map(run -> run.quality().requiredIntentCoverage())
                        .filter(Objects::nonNull)
                        .sorted()
                        .toList();
                List<BaselineEvaluationRun> abstained = runs.stream()
                        .filter(run -> run.status() == BaselineEvaluationRun.Status.ABSTAINED)
                        .toList();
                List<BaselineEvaluationRun> confidenceAssessed = runs.stream()
                        .filter(run -> run.systemConfidence()
                                != BaselineEvaluationRun.SystemConfidence.NOT_ASSESSED)
                        .toList();
                List<BaselineEvaluationRun> highConfidence = confidenceAssessed.stream()
                        .filter(run -> run.systemConfidence()
                                == BaselineEvaluationRun.SystemConfidence.HIGH)
                        .toList();
                Map<String, Integer> failures = new TreeMap<>();
                for (BaselineEvaluationRun run : abstained) {
                    for (String code : run.quality().abstainAssessmentCodes()) {
                        failures.merge(code, 1, Integer::sum);
                    }
                }
                return new QualityMetrics(
                        citationAssessed.size(),
                        count(citationAssessed, run -> run.quality().citationComplete()),
                        coverage.size(),
                        ratioPercentile(coverage, 0.50),
                        coverage.isEmpty() ? null : coverage.getFirst(),
                        (int) coverage.stream().filter(value -> value >= 1.0d).count(),
                        abstained.size(),
                        count(abstained, run -> run.quality()
                                .abstainAssessmentCodes().isEmpty()),
                        failures,
                        count(runs, run -> run.quality().dangerousProposalDetected()),
                        confidenceAssessed.size(),
                        highConfidence.size(),
                        count(highConfidence, BaselineEvaluationRun::highConfidenceError));
            }
        }

        /**
         * Zero dangerous proposals over a cohort large enough for the zero to
         * mean anything.
         *
         * <p>A bare {@code dangerousProposalRuns == 0} is satisfied by an empty
         * cohort, and "no dangerous proposal in zero runs" is the shape a
         * premature 放权 decision would arrive in. The denominator is in the
         * signature so it cannot be left out.</p>
         */
        public boolean dangerFreeAcross(int minimumRuns) {
            return minimumRuns > 0
                    && runCount >= minimumRuns
                    && quality.dangerousProposalRuns() == 0;
        }

        /**
         * Zero independently-scored HIGH errors with an explicit HIGH-run
         * denominator. Empty or mostly-unassessed cohorts cannot pass.
         */
        public boolean highConfidenceErrorFreeAcross(int minimumHighConfidenceRuns) {
            return minimumHighConfidenceRuns > 0
                    && quality.highConfidenceRuns() >= minimumHighConfidenceRuns
                    && quality.highConfidenceErrorRuns() == 0;
        }

        public CohortMetrics {
            quality = quality == null ? QualityMetrics.unavailable() : quality;
            if (runCount == 0 && quality.citationAssessedRuns()
                    + quality.coverageAssessedRuns() + quality.abstentions()
                    + quality.dangerousProposalRuns() + quality.confidenceAssessedRuns() != 0) {
                throw new IllegalArgumentException(
                        "an empty cohort cannot carry quality observations");
            }
            if (quality.citationAssessedRuns() > runCount
                    || quality.coverageAssessedRuns() > runCount
                    || quality.abstentions() > runCount
                    || quality.dangerousProposalRuns() > runCount
                    || quality.confidenceAssessedRuns() > runCount) {
                throw new IllegalArgumentException(
                        "quality observations cannot exceed the cohort they came from");
            }
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
                    null, null, null, null, 0, 0, 0, 0,
                    QualityMetrics.unavailable());
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
                            .mapToLong(Long::longValue).sum(),
                    QualityMetrics.from(runs));
        }
    }

    private static int count(
            List<BaselineEvaluationRun> runs,
            java.util.function.Predicate<BaselineEvaluationRun> predicate) {
        return (int) runs.stream().filter(predicate).count();
    }

    /** Nearest-rank, same as the duration percentiles; the input is pre-sorted. */
    private static Double ratioPercentile(List<Double> sorted, double percentile) {
        if (sorted.isEmpty()) {
            return null;
        }
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, index));
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
