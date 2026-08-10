package vip.mate.troubleshooting.evaluation;

import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.evidence.EvidenceSpineTimings;
import vip.mate.troubleshooting.evidence.GuanceEvidenceReadiness;
import vip.mate.troubleshooting.evidence.GuanceEvidenceSpinePreview;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceEvaluationSampleLedgerTest {

    private static final Instant NOW = Instant.parse("2026-07-29T08:00:00Z");

    @Test
    void calculatesNearestRankPercentilesWithoutMixingSourcePlatforms() {
        List<EvidenceEvaluationSample> samples = List.of(
                sample(1, EvidenceEvaluationSample.SourcePlatform.GUANCE,
                        new EvidenceSpineTimings(1L, 2L, 3L, 4L), 12L),
                sample(2, EvidenceEvaluationSample.SourcePlatform.GUANCE,
                        new EvidenceSpineTimings(4L, 6L, 10L, 8L), 30L),
                sample(3, EvidenceEvaluationSample.SourcePlatform.GUANCE,
                        new EvidenceSpineTimings(5L, 10L, 15L, 12L), 50L),
                sample(4, EvidenceEvaluationSample.SourcePlatform.GUANCE,
                        new EvidenceSpineTimings(10L, 10L, 20L, 16L), 70L),
                sample(5, EvidenceEvaluationSample.SourcePlatform.GUANCE,
                        EvidenceSpineTimings.unmeasured(), 9L),
                sample(6, EvidenceEvaluationSample.SourcePlatform.RECORDED_REPLAY,
                        new EvidenceSpineTimings(30L, 30L, 40L, 30L), 150L),
                sample(7, EvidenceEvaluationSample.SourcePlatform.RECORDED_REPLAY,
                        new EvidenceSpineTimings(50L, 70L, 80L, 60L), 300L));

        EvidenceEvaluationSampleLedger.Summary summary =
                EvidenceEvaluationSampleLedger.from(samples).summary();

        assertThat(summary.timingMeasuredSamples()).isEqualTo(6);
        assertThat(summary.guanceLatency()).isEqualTo(
                new EvidenceEvaluationSampleLedger.LatencySummary(
                        4, 20L, 40L, 8L, 16L, 30L, 70L));
        assertThat(summary.recordedReplayLatency()).isEqualTo(
                new EvidenceEvaluationSampleLedger.LatencySummary(
                        2, 100L, 200L, 30L, 60L, 150L, 300L));
    }

    private EvidenceEvaluationSample sample(
            int sequence,
            EvidenceEvaluationSample.SourcePlatform platform,
            EvidenceSpineTimings timings,
            long totalDurationMs) {
        GuanceEvidenceSpinePreview preview = new GuanceEvidenceSpinePreview(
                GuanceEvidenceSpinePreview.Stage.FULL_SPINE_OBSERVED,
                readiness(),
                4L,
                "ps-message-" + sequence,
                3,
                List.of("gateway", "session-svc", "openim"),
                2,
                42L,
                new GuanceEvidenceSpinePreview.Contrast(
                        true, "session_state_conflict",
                        100, 92, 100, 3, 0.92, 0.03, 0.89),
                3,
                totalDurationMs,
                timings,
                List.of(
                        observed("log_search", "T8-GUANCE-LOG-SEARCH"),
                        observed("log_trace_bundle", "T8-GUANCE-TRACE-BUNDLE"),
                        observed("contrast_sample", "T8-GUANCE-CONTRAST-SAMPLE")),
                NOW,
                List.of());
        EvidenceEvaluationSample guance = EvidenceEvaluationSample.captured(
                "eval-" + sequence,
                Integer.toHexString(sequence).repeat(64),
                "diag-" + sequence,
                "CSDP",
                "session-svc",
                "message_send_failed",
                preview,
                false,
                "tester",
                NOW);
        if (platform == EvidenceEvaluationSample.SourcePlatform.GUANCE) {
            return guance;
        }
        EvidenceEvaluationSample.EvidenceSnapshot evidence = guance.evidence();
        EvidenceEvaluationSample.EvidenceSnapshot replay =
                new EvidenceEvaluationSample.EvidenceSnapshot(
                        evidence.stage(),
                        true,
                        evidence.matchCount(),
                        evidence.psId(),
                        evidence.traceEntries(),
                        evidence.serviceSequence(),
                        evidence.anomalyCount(),
                        evidence.traceElapsedMs(),
                        evidence.contrast(),
                        evidence.sourceRequestCount(),
                        evidence.totalDurationMs(),
                        evidence.timings(),
                        evidence.steps(),
                        evidence.completedAt());
        return new EvidenceEvaluationSample(
                guance.sampleId(), guance.sampleKey(), guance.diagnosisId(),
                guance.system(), guance.service(), guance.scenarioKey(), platform,
                replay, false, guance.referenceStatus(), null, null, 0,
                guance.capturedBy(), null, guance.capturedAt(), null);
    }

    private GuanceEvidenceReadiness readiness() {
        return new GuanceEvidenceReadiness(
                "CSDP", "session-svc",
                GuanceEvidenceReadiness.Status.READY_FOR_VALIDATION,
                true, true,
                GuanceEvidenceReadiness.CredentialState.CONFIGURED,
                true, List.of(), List.of());
    }

    private GuanceEvidenceSpinePreview.Step observed(String kind, String ref) {
        return new GuanceEvidenceSpinePreview.Step(
                kind,
                GuanceEvidenceSpinePreview.StepStatus.CANONICAL_RESULT_OBSERVED,
                ref,
                NOW);
    }
}
