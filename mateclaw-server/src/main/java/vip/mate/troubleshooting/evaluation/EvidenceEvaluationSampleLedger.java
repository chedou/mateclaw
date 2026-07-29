package vip.mate.troubleshooting.evaluation;

import vip.mate.troubleshooting.evidence.GuanceEvidenceSpinePreview;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/** Read-only counts for sample accumulation; this projection never declares the T8 gate passed. */
public record EvidenceEvaluationSampleLedger(
        List<EvidenceEvaluationSample> samples,
        Summary summary) {

    public EvidenceEvaluationSampleLedger {
        samples = List.copyOf(samples == null ? List.of() : samples);
        summary = summary == null ? Summary.from(samples) : summary;
    }

    public static EvidenceEvaluationSampleLedger from(List<EvidenceEvaluationSample> samples) {
        List<EvidenceEvaluationSample> immutable =
                List.copyOf(samples == null ? List.of() : samples);
        return new EvidenceEvaluationSampleLedger(immutable, Summary.from(immutable));
    }

    public record Summary(
            int total,
            int guance,
            int recordedReplay,
            int evidenceCaptured,
            int readyForEvaluation,
            int fullSpineObserved,
            int coreChainObserved,
            int linkedFixtureDiagnoses,
            int timingMeasuredSamples,
            LatencySummary guanceLatency,
            LatencySummary recordedReplayLatency,
            int minimumEvaluationTarget,
            int targetRangeMax) {

        public Summary {
            guanceLatency = guanceLatency == null
                    ? LatencySummary.unavailable()
                    : guanceLatency;
            recordedReplayLatency = recordedReplayLatency == null
                    ? LatencySummary.unavailable()
                    : recordedReplayLatency;
        }

        static Summary from(List<EvidenceEvaluationSample> samples) {
            return new Summary(
                    samples.size(),
                    count(samples, sample -> sample.sourcePlatform()
                            == EvidenceEvaluationSample.SourcePlatform.GUANCE),
                    count(samples, sample -> sample.sourcePlatform()
                            == EvidenceEvaluationSample.SourcePlatform.RECORDED_REPLAY),
                    count(samples, sample -> sample.referenceStatus()
                            == EvidenceEvaluationSample.ReferenceStatus.EVIDENCE_CAPTURED),
                    count(samples, sample -> sample.referenceStatus()
                            == EvidenceEvaluationSample.ReferenceStatus.READY_FOR_EVALUATION),
                    count(samples, sample -> sample.evidence().stage()
                            == GuanceEvidenceSpinePreview.Stage.FULL_SPINE_OBSERVED),
                    count(samples, sample -> sample.evidence().stage()
                            == GuanceEvidenceSpinePreview.Stage.CORE_CHAIN_OBSERVED),
                    count(samples, EvidenceEvaluationSample::diagnosisFixtureMode),
                    count(samples, sample -> sample.evidence().timings().complete()),
                    LatencySummary.from(
                            samples, EvidenceEvaluationSample.SourcePlatform.GUANCE),
                    LatencySummary.from(
                            samples, EvidenceEvaluationSample.SourcePlatform.RECORDED_REPLAY),
                    20,
                    30);
        }

        private static int count(
                List<EvidenceEvaluationSample> samples,
                java.util.function.Predicate<EvidenceEvaluationSample> predicate) {
            return (int) samples.stream().filter(predicate).count();
        }
    }

    /** Descriptive nearest-rank percentiles for one evidence source; never a gate verdict. */
    public record LatencySummary(
            int sampleCount,
            Long evidenceP50Ms,
            Long evidenceP95Ms,
            Long compressionP50Ms,
            Long compressionP95Ms,
            Long totalP50Ms,
            Long totalP95Ms) {

        public LatencySummary {
            List<Long> values = Stream.of(
                            evidenceP50Ms,
                            evidenceP95Ms,
                            compressionP50Ms,
                            compressionP95Ms,
                            totalP50Ms,
                            totalP95Ms)
                    .filter(Objects::nonNull)
                    .toList();
            if (sampleCount < 0 || values.stream().anyMatch(value -> value < 0L)) {
                throw new IllegalArgumentException("latency summary values must not be negative");
            }
            if ((sampleCount == 0) != values.isEmpty()) {
                throw new IllegalArgumentException(
                        "latency percentiles require at least one measured sample");
            }
            if (sampleCount > 0 && values.size() != 6) {
                throw new IllegalArgumentException(
                        "a measured latency summary requires all percentiles");
            }
        }

        static LatencySummary unavailable() {
            return new LatencySummary(0, null, null, null, null, null, null);
        }

        static LatencySummary from(
                List<EvidenceEvaluationSample> samples,
                EvidenceEvaluationSample.SourcePlatform platform) {
            List<EvidenceEvaluationSample> measured = samples.stream()
                    .filter(sample -> sample.sourcePlatform() == platform)
                    .filter(sample -> sample.evidence().timings().complete())
                    .toList();
            if (measured.isEmpty()) {
                return unavailable();
            }
            List<Long> evidence = measured.stream()
                    .map(sample -> sample.evidence().timings()
                            .evidenceAcquisitionDurationMs())
                    .toList();
            List<Long> compression = measured.stream()
                    .map(sample -> sample.evidence().timings().compressionDurationMs())
                    .toList();
            List<Long> total = measured.stream()
                    .map(sample -> sample.evidence().totalDurationMs())
                    .toList();
            return new LatencySummary(
                    measured.size(),
                    percentile(evidence, 0.50D),
                    percentile(evidence, 0.95D),
                    percentile(compression, 0.50D),
                    percentile(compression, 0.95D),
                    percentile(total, 0.50D),
                    percentile(total, 0.95D));
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
}
