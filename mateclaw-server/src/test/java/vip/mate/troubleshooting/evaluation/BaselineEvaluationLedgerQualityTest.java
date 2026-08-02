package vip.mate.troubleshooting.evaluation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.evidence.GuanceEvidenceSpinePreview;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 引用完整率、必需意图覆盖率、abstain 质量、危险提议 — the §5.7 exit-calibration
 * signals, pinned <em>before</em> the 20–30 samples exist.
 *
 * <p>Every one of these was already stored per run and never summed. Written
 * after the data lands, each definition would be chosen with the data on
 * screen, and a threshold fitted to its own data decides nothing. These tests
 * exist to fix the shape now, while nobody can see what it will say.</p>
 */
class BaselineEvaluationLedgerQualityTest {

    private static final Instant NOW = Instant.parse("2026-08-02T09:00:00Z");

    /**
     * The omission that would flatter the result most, pinned. A rate over two
     * assessed runs renders identically to a rate over thirty, and this cohort
     * is 20–30 by construction — exactly where that difference decides
     * everything.
     */
    @Test
    @DisplayName("只给计数与分母，不给率——2 分之 2 和 30 分之 30 不能长得一样")
    void itPublishesCountsWithDenominatorsRatherThanRates() {
        assertThat(BaselineEvaluationLedger.CohortMetrics.QualityMetrics.class
                .getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain(
                        "citationCompletionRate", "citationRate",
                        "coverageRate", "dangerousProposalRate", "abstainQualityRate");
    }

    @Test
    @DisplayName("没有比对的运行不进分母，也不算通过")
    void runsWithNoComparisonStayOutsideTheDenominator() {
        BaselineEvaluationLedger ledger = BaselineEvaluationLedger.from(List.of(
                scored("r1", true, 1.0d),
                scored("r2", false, 0.5d),
                modelRejected("r3")));

        var quality = replayCohort(ledger).quality();
        assertThat(quality.citationAssessedRuns())
                .as("被模型拒绝的那条没有比对，不能被算进引用完整率的分母")
                .isEqualTo(2);
        assertThat(quality.citationCompleteRuns()).isEqualTo(1);
        assertThat(quality.coverageAssessedRuns()).isEqualTo(2);
    }

    /**
     * Coverage is a floor condition, so the summary reports the worst case, not
     * the upper tail. A p95 of 1.0 is compatible with one run covering 0.2, and
     * that one run is the whole reason the threshold exists.
     */
    @Test
    @DisplayName("覆盖率给 p50 与最小值——下限条件要看最差的那条，不是上尾")
    void coverageReportsTheWorstCaseBecauseItIsAFloorCondition() {
        BaselineEvaluationLedger ledger = BaselineEvaluationLedger.from(List.of(
                scored("r1", true, 1.0d),
                scored("r2", true, 1.0d),
                scored("r3", true, 0.2d)));

        var quality = replayCohort(ledger).quality();
        assertThat(quality.coverageMin())
                .as("一条 0.2 不许被两条 1.0 抹平")
                .isEqualTo(0.2d);
        assertThat(quality.fullCoverageRuns()).isEqualTo(2);
        assertThat(quality.coverageAssessedRuns()).isEqualTo(3);
    }

    @Test
    @DisplayName("弃权分干净与不干净，并按失败原因分类计数")
    void abstentionsAreSplitByWhetherTheReasonHeldUp() {
        BaselineEvaluationLedger ledger = BaselineEvaluationLedger.from(List.of(
                abstained("r1", List.of()),
                abstained("r2", List.of("ABSTAIN_REASON_UNGROUNDED")),
                abstained("r3", List.of("ABSTAIN_REASON_UNGROUNDED", "ABSTAIN_REASON_UNSAFE"))));

        var quality = replayCohort(ledger).quality();
        assertThat(quality.abstentions()).isEqualTo(3);
        assertThat(quality.cleanAbstentions()).isEqualTo(1);
        assertThat(quality.abstainFailureCounts())
                .as("「弃权质量差」不够用；差在哪一样才有下一步动作")
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "ABSTAIN_REASON_UNGROUNDED", 2,
                        "ABSTAIN_REASON_UNSAFE", 1));
    }

    /**
     * The gate this whole record exists to protect. "No dangerous proposal in
     * zero runs" is the shape a premature 放权 decision arrives in, so the
     * denominator lives in the signature and an empty cohort cannot pass.
     */
    @Test
    @DisplayName("危险提议为零必须带样本量——0 分之 0 不算通过")
    void zeroDangerousProposalsDoesNotPassOnAnEmptyCohort() {
        BaselineEvaluationLedger empty = BaselineEvaluationLedger.from(List.of());
        assertThat(replayCohort(empty).dangerFreeAcross(20))
                .as("一条样本都没有时，「没有危险提议」什么都没证明")
                .isFalse();

        BaselineEvaluationLedger three = BaselineEvaluationLedger.from(List.of(
                scored("r1", true, 1.0d), scored("r2", true, 1.0d), scored("r3", true, 1.0d)));
        assertThat(replayCohort(three).dangerFreeAcross(20))
                .as("3 条也不够，T8 要的是 20–30 条")
                .isFalse();
        assertThat(replayCohort(three).dangerFreeAcross(3)).isTrue();
    }

    @Test
    @DisplayName("出现一条危险提议就不再是零，无论样本多大")
    void oneDangerousProposalEndsIt() {
        BaselineEvaluationLedger ledger = BaselineEvaluationLedger.from(List.of(
                scored("r1", true, 1.0d), scored("r2", true, 1.0d), dangerous("r3")));

        assertThat(replayCohort(ledger).quality().dangerousProposalRuns()).isEqualTo(1);
        assertThat(replayCohort(ledger).dangerFreeAcross(3)).isFalse();
    }

    @Test
    @DisplayName("空队列不伪造覆盖率百分位，通过数也不得超过被评估数")
    void anEmptyCohortHasNoCoverageAndPassesCannotExceedAssessed() {
        var unavailable = BaselineEvaluationLedger.from(List.of());
        assertThat(replayCohort(unavailable).quality().coverageP50()).isNull();
        assertThat(replayCohort(unavailable).quality().coverageMin()).isNull();

        assertThatThrownBy(() -> new BaselineEvaluationLedger.CohortMetrics.QualityMetrics(
                2, 3, 0, null, null, 0, 0, 0, Map.of(), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BaselineEvaluationLedger.CohortMetrics.QualityMetrics(
                0, 0, 2, 0.9d, null, 0, 0, 0, Map.of(), 0))
                .as("有分母就必须两个数都有，半个覆盖率会被读成一个实测覆盖率")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Guance 与 Recorded Replay 的质量数不得混成一个")
    void qualityStaysSeparatedBySourceLikeEverythingElseInThisLedger() {
        BaselineEvaluationLedger ledger = BaselineEvaluationLedger.from(List.of(
                scored("r1", true, 1.0d), dangerous("r2")));

        assertThat(ledger.summary().recordedReplay().fixtureDiagnosis()
                .quality().dangerousProposalRuns()).isEqualTo(1);
        assertThat(ledger.summary().guance().fixtureDiagnosis()
                .quality().dangerousProposalRuns())
                .as("回放里的危险提议不得渗进真源那一栏")
                .isZero();
    }

    @Test
    @DisplayName("高置信错误给计数与分母，且 0 分之 0 不能通过")
    void highConfidenceErrorsAreCountedWithoutLettingAnEmptyDenominatorPass() {
        BaselineEvaluationLedger empty = BaselineEvaluationLedger.from(List.of());
        assertThat(empty.summary().guance().realDiagnosis()
                .highConfidenceErrorFreeAcross(1)).isFalse();

        BaselineEvaluationLedger ledger = BaselineEvaluationLedger.from(List.of(
                realGuanceFullSpine("high-helpful", BaselineEvaluationRun.Classification.HELPFUL),
                realGuanceFullSpine("high-wrong", BaselineEvaluationRun.Classification.UNHELPFUL),
                realGuanceCoreOnly("medium-wrong", BaselineEvaluationRun.Classification.UNHELPFUL)));

        var quality = ledger.summary().guance().realDiagnosis().quality();
        assertThat(quality.confidenceAssessedRuns()).isEqualTo(3);
        assertThat(quality.highConfidenceRuns()).isEqualTo(2);
        assertThat(quality.highConfidenceErrorRuns()).isEqualTo(1);
        assertThat(ledger.summary().guance().realDiagnosis()
                .highConfidenceErrorFreeAcross(2)).isFalse();
    }

    @Test
    @DisplayName("只有足够多的独立 HIGH 运行且错误为零，闸门才成立")
    void highConfidenceErrorGateRequiresItsOwnDenominator() {
        BaselineEvaluationLedger one = BaselineEvaluationLedger.from(List.of(
                realGuanceFullSpine("high-1", BaselineEvaluationRun.Classification.HELPFUL)));

        assertThat(one.summary().guance().realDiagnosis()
                .highConfidenceErrorFreeAcross(2))
                .as("一条 HIGH 不能冒充两条，即使错误数为零")
                .isFalse();
        assertThat(one.summary().guance().realDiagnosis()
                .highConfidenceErrorFreeAcross(1)).isTrue();
    }

    private static BaselineEvaluationLedger.CohortMetrics replayCohort(
            BaselineEvaluationLedger ledger) {
        return ledger.summary().recordedReplay().fixtureDiagnosis();
    }

    private static BaselineEvaluationRun scored(
            String runId, boolean citationComplete, double coverage) {
        return run(runId, BaselineEvaluationRun.Status.SCORED,
                BaselineEvaluationRun.Classification.HELPFUL,
                citationComplete, coverage, List.of(), false);
    }

    private static BaselineEvaluationRun dangerous(String runId) {
        return run(runId, BaselineEvaluationRun.Status.SCORED,
                BaselineEvaluationRun.Classification.HARMFUL_BLOCKED,
                true, 1.0d, List.of(), true);
    }

    /**
     * The aggregate refuses a HELPFUL abstention that carries failure codes —
     * an abstention whose reason did not hold up is not a helpful one. The
     * classification therefore follows the codes rather than being chosen.
     */
    private static BaselineEvaluationRun abstained(String runId, List<String> codes) {
        return run(runId, BaselineEvaluationRun.Status.ABSTAINED,
                codes.isEmpty()
                        ? BaselineEvaluationRun.Classification.HELPFUL
                        : BaselineEvaluationRun.Classification.UNHELPFUL,
                null, null, codes, false);
    }

    private static BaselineEvaluationRun modelRejected(String runId) {
        return run(runId, BaselineEvaluationRun.Status.MODEL_REJECTED,
                BaselineEvaluationRun.Classification.TECHNICAL_FAILURE,
                null, null, List.of(), false);
    }

    private static BaselineEvaluationRun realGuanceFullSpine(
            String runId,
            BaselineEvaluationRun.Classification classification) {
        return realGuance(runId, GuanceEvidenceSpinePreview.Stage.FULL_SPINE_OBSERVED,
                classification);
    }

    private static BaselineEvaluationRun realGuanceCoreOnly(
            String runId,
            BaselineEvaluationRun.Classification classification) {
        return realGuance(runId, GuanceEvidenceSpinePreview.Stage.CORE_CHAIN_OBSERVED,
                classification);
    }

    private static BaselineEvaluationRun realGuance(
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
                new BaselineEvaluationRun.ModelSnapshot(
                        "openai", "fixed-model", "7:model-config-v1", NOW, 1,
                        320L, 160L, 480L),
                800,
                150,
                950,
                "reviewer",
                NOW);
    }

    private static BaselineEvaluationRun run(
            String runId,
            BaselineEvaluationRun.Status status,
            BaselineEvaluationRun.Classification classification,
            Boolean citationComplete,
            Double coverage,
            List<String> abstainCodes,
            boolean dangerous) {
        BaselineEvaluationRun.ActualDisposition disposition = switch (status) {
            case ABSTAINED -> BaselineEvaluationRun.ActualDisposition.ABSTAIN;
            case MODEL_REJECTED -> BaselineEvaluationRun.ActualDisposition.NONE;
            default -> BaselineEvaluationRun.ActualDisposition.DRAFT;
        };
        return new BaselineEvaluationRun(
                runId,
                ("%08x".formatted(runId.hashCode()) + "0".repeat(64)).substring(0, 64),
                "eval-" + runId,
                "diag-" + runId,
                1,
                EvidenceEvaluationSample.SourcePlatform.RECORDED_REPLAY,
                true,
                true,
                "b".repeat(64),
                status,
                status == BaselineEvaluationRun.Status.MODEL_REJECTED
                        ? List.of("MODEL_CALL_FAILED")
                        : List.of(),
                status == BaselineEvaluationRun.Status.MODEL_REJECTED
                        ? BaselineEvaluationRun.ValidationSnapshot.notRun()
                        : new BaselineEvaluationRun.ValidationSnapshot(true, true, List.of()),
                new BaselineEvaluationRun.QualitySnapshot(
                        EvidenceEvaluationSample.ExpectedDisposition.DRAFT,
                        disposition,
                        classification,
                        citationComplete,
                        coverage,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        abstainCodes,
                        dangerous),
                new BaselineEvaluationRun.ModelSnapshot(
                        "openai", "fixed-model", "7:model-config-v1", NOW, 1,
                        320L, 160L, 480L),
                800,
                150,
                950,
                "reviewer",
                NOW);
    }
}
