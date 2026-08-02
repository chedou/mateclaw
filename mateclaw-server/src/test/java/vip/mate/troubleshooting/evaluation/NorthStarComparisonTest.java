package vip.mate.troubleshooting.evaluation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shadow cohort's second question — 省不省时间 — and the ways a number here
 * could quietly overstate the answer.
 */
class NorthStarComparisonTest {

    private static EvidenceEvaluationSample.HumanBaseline measured(long minutes) {
        return new EvidenceEvaluationSample.HumanBaseline(
                minutes, EvidenceEvaluationSample.HumanBaseline.Basis.MEASURED, "工单时间戳");
    }

    private static EvidenceEvaluationSample.HumanBaseline estimated(long minutes) {
        return new EvidenceEvaluationSample.HumanBaseline(
                minutes, EvidenceEvaluationSample.HumanBaseline.Basis.ESTIMATED, "处置人回忆");
    }

    @Test
    @DisplayName("实测与估算分开统计，绝不合并成一个数")
    void measuredAndEstimatedNeverMerge() {
        NorthStarComparison comparison = NorthStarComparison.from(
                3, List.of(measured(40), measured(60), estimated(120)), List.of());

        assertThat(comparison.measured().count()).isEqualTo(2);
        assertThat(comparison.estimated().count()).isEqualTo(1);
        assertThat(comparison.measured().p50Minutes())
                .as("估算的 120 分钟不得把实测中位数拉高")
                .isEqualTo(40L);
        assertThat(comparison.caveats())
                .anyMatch(caveat -> caveat.contains("估算基线不得当作实测汇报"));
    }

    /**
     * The omission that would flatter the result most, pinned. A "saved N
     * minutes" field is easy to add and would be wrong: the human still has to
     * read and check the machine's conclusion, and that cost is not in here.
     */
    @Test
    @DisplayName("不产出「节省了多少」，并在数字旁边写明少算了哪一段")
    void itRefusesToPublishASavingsFigureAndSaysWhatIsMissing() {
        NorthStarComparison comparison = NorthStarComparison.from(
                1, List.of(measured(45)), List.of());

        assertThat(NorthStarComparison.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .as("对外不得暴露一个把 adoptCost 漏掉的「节省」字段")
                .doesNotContain("savedMinutes", "savingsMinutes", "timeSaved");
        assertThat(comparison.caveats())
                .anyMatch(caveat -> caveat.contains("adoptCost"));
    }

    @Test
    @DisplayName("没有人工基线的样本只参与准不准，且这件事被明说")
    void samplesWithoutAHumanBaselineAreCountedOutLoud() {
        NorthStarComparison comparison = NorthStarComparison.from(
                2, List.of(measured(30)), List.of());

        assertThat(comparison.sampleCount()).isEqualTo(2);
        assertThat(comparison.withHumanBaseline()).isEqualTo(1);
        assertThat(comparison.caveats())
                .anyMatch(caveat -> caveat.contains("没有人工基线"));
    }

    @Test
    @DisplayName("空队列不伪造百分位")
    void anEmptyCohortHasNoPercentiles() {
        NorthStarComparison comparison = NorthStarComparison.from(0, List.of(), List.of());

        assertThat(comparison.measured().p50Minutes()).isNull();
        assertThat(comparison.machineP50Ms()).isNull();
        assertThat(comparison.caveats())
                .anyMatch(caveat -> caveat.contains("尚无基线运行"));
    }

    @Test
    @DisplayName("非空队列必须两个百分位都有，不允许半个数")
    void aNonEmptyCohortCannotCarryHalfAMeasurement() {
        assertThatThrownBy(() -> new NorthStarComparison.Cohort(2, 40L, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("人工基线必须是正数且在合理范围内")
    void aHumanBaselineMustBePlausible() {
        assertThatThrownBy(() -> measured(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> measured(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> measured(60L * 24 * 31))
                .isInstanceOf(IllegalArgumentException.class);
    }

}
