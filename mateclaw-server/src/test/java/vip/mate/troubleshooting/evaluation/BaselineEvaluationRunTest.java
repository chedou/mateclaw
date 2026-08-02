package vip.mate.troubleshooting.evaluation;

import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.evidence.GuanceEvidenceSpinePreview;
import vip.mate.troubleshooting.synthesis.PlaybookDraft;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BaselineEvaluationRunTest {

    private static final Instant NOW = Instant.parse("2026-07-29T08:00:00Z");

    @Test
    void storesOnlyBoundedScoringFactsAndNeverAReviewOrGateVerdict() {
        BaselineEvaluationRun run = scoredRun(
                EvidenceEvaluationSample.SourcePlatform.GUANCE,
                BaselineEvaluationRun.Classification.UNHELPFUL,
                0.6,
                List.of("verify_recovery"),
                List.of());

        assertThat(run.status()).isEqualTo(BaselineEvaluationRun.Status.SCORED);
        assertThat(run.quality().citationComplete()).isTrue();
        assertThat(run.quality().requiredIntentCoverage()).isEqualTo(0.6);
        assertThat(run.model().totalTokens()).isEqualTo(480L);
        assertThat(run.composedTotalDurationMs()).isEqualTo(950L);
        assertThat(run.toString())
                .doesNotContain("raw log", "L::logs", "source_lookup_key", "passed", "approved");
    }

    @Test
    void rejectsAHelpfulClassificationWhenForbiddenIntentsWereProposed() {
        assertThatThrownBy(() -> scoredRun(
                EvidenceEvaluationSample.SourcePlatform.GUANCE,
                BaselineEvaluationRun.Classification.HELPFUL,
                1.0,
                List.of(),
                List.of("restart_production")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dangerous");
    }

    @Test
    void calculatesSourceSeparatedDescriptiveMetricsWithoutAGateVerdict() {
        BaselineEvaluationRun guance = scoredRun(
                EvidenceEvaluationSample.SourcePlatform.GUANCE,
                BaselineEvaluationRun.Classification.HELPFUL,
                1.0,
                List.of(),
                List.of());
        BaselineEvaluationRun replay = new BaselineEvaluationRun(
                "baseline-2",
                "c".repeat(64),
                "eval-2",
                "diag-2",
                1,
                EvidenceEvaluationSample.SourcePlatform.RECORDED_REPLAY,
                true,
                true,
                "d".repeat(64),
                BaselineEvaluationRun.Status.VALIDATION_REJECTED,
                List.of(),
                new BaselineEvaluationRun.ValidationSnapshot(
                        true,
                        false,
                        List.of("ABSTAIN_REASON_UNGROUNDED")),
                new BaselineEvaluationRun.QualitySnapshot(
                        EvidenceEvaluationSample.ExpectedDisposition.DRAFT,
                        BaselineEvaluationRun.ActualDisposition.ABSTAIN,
                        BaselineEvaluationRun.Classification.UNHELPFUL,
                        null,
                        null,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of("ABSTAIN_REASON_UNGROUNDED"),
                        false),
                model(),
                80,
                400,
                480,
                "reviewer",
                NOW);

        BaselineEvaluationLedger ledger = BaselineEvaluationLedger.from(
                List.of(guance, replay));

        assertThat(ledger.summary().total()).isEqualTo(2);
        assertThat(ledger.summary().guance().runCount()).isEqualTo(1);
        assertThat(ledger.summary().recordedReplay().runCount()).isEqualTo(1);
        assertThat(ledger.summary().guance().realDiagnosis().modelP50Ms()).isEqualTo(150L);
        assertThat(ledger.summary().recordedReplay().fixtureDiagnosis().modelP50Ms())
                .isEqualTo(400L);
        assertThat(ledger.summary().guance().realDiagnosis().totalTokens()).isEqualTo(480L);
        assertThat(ledger.summary().guance().evidenceFixtureRuns()).isZero();
        assertThat(ledger.summary().recordedReplay().evidenceFixtureRuns()).isEqualTo(1);
        assertThat(ledger.toString()).doesNotContain("gatePassed", "T8_PASSED");
    }

    @Test
    void systemConfidenceIsServerAssignedBeforeTheHumanOracleScoresCorrectness() {
        BaselineEvaluationRun helpful = realGuanceFullSpine(
                "baseline-helpful",
                BaselineEvaluationRun.Classification.HELPFUL);
        BaselineEvaluationRun wrong = realGuanceFullSpine(
                "baseline-wrong",
                BaselineEvaluationRun.Classification.UNHELPFUL);

        assertThat(helpful.systemConfidence())
                .isEqualTo(BaselineEvaluationRun.SystemConfidence.HIGH);
        assertThat(wrong.systemConfidence())
                .as("同样的真源权威条件必须得到相同置信度，不能用参考答案反推置信度")
                .isEqualTo(BaselineEvaluationRun.SystemConfidence.HIGH);
        assertThat(helpful.highConfidenceError()).isFalse();
        assertThat(wrong.highConfidenceError())
                .as("真源、完整取证、校验通过但人工判错，必须成为一条可见的高置信错误")
                .isTrue();
    }

    @Test
    void fixturesCoreOnlyEvidenceAndRejectedOutputsCannotBecomeHighConfidence() {
        BaselineEvaluationRun replay = scoredRun(
                EvidenceEvaluationSample.SourcePlatform.RECORDED_REPLAY,
                BaselineEvaluationRun.Classification.HELPFUL,
                1.0,
                List.of(),
                List.of());
        BaselineEvaluationRun coreOnly = realGuanceScored(
                "baseline-core",
                GuanceEvidenceSpinePreview.Stage.CORE_CHAIN_OBSERVED,
                BaselineEvaluationRun.Classification.HELPFUL);

        assertThat(replay.systemConfidence())
                .isEqualTo(BaselineEvaluationRun.SystemConfidence.MEDIUM);
        assertThat(coreOnly.systemConfidence())
                .isEqualTo(BaselineEvaluationRun.SystemConfidence.MEDIUM);
        assertThat(coreOnly.highConfidenceError()).isFalse();
    }

    @Test
    void historicalRunsWithoutAnEvidenceStageNeverUpgradeToHigh() {
        BaselineEvaluationRun historical = new BaselineEvaluationRun(
                "baseline-historical",
                "a".repeat(64),
                "eval-historical",
                "diag-historical",
                1,
                EvidenceEvaluationSample.SourcePlatform.GUANCE,
                false,
                false,
                "b".repeat(64),
                BaselineEvaluationRun.Status.SCORED,
                List.of(),
                new BaselineEvaluationRun.ValidationSnapshot(true, true, List.of()),
                new BaselineEvaluationRun.QualitySnapshot(
                        EvidenceEvaluationSample.ExpectedDisposition.DRAFT,
                        BaselineEvaluationRun.ActualDisposition.DRAFT,
                        BaselineEvaluationRun.Classification.HELPFUL,
                        true,
                        1.0d,
                        List.of(), List.of(), List.of(), List.of(), List.of(), false),
                model(),
                800,
                150,
                950,
                "reviewer",
                NOW);

        assertThat(historical.evidenceStage())
                .isEqualTo(GuanceEvidenceSpinePreview.Stage.CORE_CHAIN_OBSERVED);
        assertThat(historical.systemConfidence())
                .as("缺失的新字段不能把历史真源记录反推成完整三次取证")
                .isEqualTo(BaselineEvaluationRun.SystemConfidence.MEDIUM);
    }

    @Test
    void modelSelfReportedConfidenceCannotCrossIntoTheT8DraftOrLedgerContract() {
        assertThat(PlaybookDraft.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("confidence", "modelConfidence", "selfReportedConfidence");
        assertThat(PlaybookDraft.DiagnosisHypothesis.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("confidence", "modelConfidence", "selfReportedConfidence");
        assertThat(BaselineEvaluationRun.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("confidence", "modelConfidence", "selfReportedConfidence");
    }

    private BaselineEvaluationRun scoredRun(
            EvidenceEvaluationSample.SourcePlatform platform,
            BaselineEvaluationRun.Classification classification,
            double coverage,
            List<String> missing,
            List<String> forbidden) {
        boolean dangerous = !forbidden.isEmpty();
        return new BaselineEvaluationRun(
                "baseline-1",
                "a".repeat(64),
                "eval-1",
                "diag-1",
                1,
                platform,
                platform == EvidenceEvaluationSample.SourcePlatform.RECORDED_REPLAY,
                false,
                "b".repeat(64),
                BaselineEvaluationRun.Status.SCORED,
                List.of(),
                new BaselineEvaluationRun.ValidationSnapshot(true, true, List.of()),
                new BaselineEvaluationRun.QualitySnapshot(
                        EvidenceEvaluationSample.ExpectedDisposition.DRAFT,
                        BaselineEvaluationRun.ActualDisposition.DRAFT,
                        classification,
                        true,
                        coverage,
                        missing,
                        forbidden,
                        List.of(),
                        List.of(),
                        List.of(),
                        dangerous),
                model(),
                800,
                150,
                950,
                "reviewer",
                NOW);
    }

    private BaselineEvaluationRun realGuanceFullSpine(
            String runId,
            BaselineEvaluationRun.Classification classification) {
        return realGuanceScored(
                runId,
                GuanceEvidenceSpinePreview.Stage.FULL_SPINE_OBSERVED,
                classification);
    }

    private BaselineEvaluationRun realGuanceScored(
            String runId,
            GuanceEvidenceSpinePreview.Stage evidenceStage,
            BaselineEvaluationRun.Classification classification) {
        return new BaselineEvaluationRun(
                runId,
                ("%08x".formatted(runId.hashCode()) + "0".repeat(64)).substring(0, 64),
                "eval-" + runId,
                "diag-" + runId,
                1,
                EvidenceEvaluationSample.SourcePlatform.GUANCE,
                false,
                false,
                evidenceStage,
                "b".repeat(64),
                BaselineEvaluationRun.Status.SCORED,
                List.of(),
                new BaselineEvaluationRun.ValidationSnapshot(true, true, List.of()),
                new BaselineEvaluationRun.QualitySnapshot(
                        EvidenceEvaluationSample.ExpectedDisposition.DRAFT,
                        BaselineEvaluationRun.ActualDisposition.DRAFT,
                        classification,
                        true,
                        1.0d,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        false),
                model(),
                800,
                150,
                950,
                "reviewer",
                NOW);
    }

    private BaselineEvaluationRun.ModelSnapshot model() {
        return new BaselineEvaluationRun.ModelSnapshot(
                "openai",
                "fixed-model",
                "7:model-config-v1",
                NOW,
                1,
                320L,
                160L,
                480L);
    }
}
