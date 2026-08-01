package vip.mate.troubleshooting.evaluation;

import org.junit.jupiter.api.Test;

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
