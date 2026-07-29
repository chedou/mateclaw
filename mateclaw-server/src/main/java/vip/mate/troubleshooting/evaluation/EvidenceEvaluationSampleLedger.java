package vip.mate.troubleshooting.evaluation;

import vip.mate.troubleshooting.evidence.GuanceEvidenceSpinePreview;

import java.util.List;

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
            int minimumEvaluationTarget,
            int targetRangeMax) {

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
                    20,
                    30);
        }

        private static int count(
                List<EvidenceEvaluationSample> samples,
                java.util.function.Predicate<EvidenceEvaluationSample> predicate) {
            return (int) samples.stream().filter(predicate).count();
        }
    }
}
