package vip.mate.troubleshooting.evaluation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The one number a shadow cohort exists to produce: how long a person used to
 * take, next to how long the machine takes.
 *
 * <p><b>Why it is separate from {@link BaselineEvaluationLedger}.</b> That
 * ledger answers "准不准" — helpful, unhelpful, harmful-blocked — and it times
 * the machine. Both are necessary and neither is the north star, which is
 * 「从一条不完整报障，到一个带证据、可交接、可复用的定位结论所需的时间」.
 * That is a person's time, and it lives on the sample, not on the run.</p>
 *
 * <p><b>What this deliberately does not compute.</b> There is no "saved N
 * minutes" field. A shadow run produces a conclusion a human still has to read
 * and check, and that review cost is not measured here — in the live product it
 * is {@code adoptCost}, the north star's third stage, and a shadow cohort by
 * construction never reaches it. Publishing a savings figure that silently
 * omits the adopt side would overstate the result in exactly the direction
 * everyone wants it to lean. The two figures are reported side by side and the
 * subtraction is left to a human who can see what is missing from it.</p>
 *
 * <p><b>Why measured and estimated never merge.</b> A duration read out of a
 * ticket system and one recalled by the engineer are different evidence.
 * Averaging them would let the weaker number borrow the stronger one's
 * credibility — the same reason {@code EXCLUDED} and {@code UNEVALUATED} are
 * never displayed as one thing.</p>
 */
public record NorthStarComparison(
        int sampleCount,
        int withHumanBaseline,
        Cohort measured,
        Cohort estimated,
        Long machineP50Ms,
        Long machineP95Ms,
        int machineRunCount,
        List<String> caveats) {

    public NorthStarComparison {
        measured = measured == null ? Cohort.empty() : measured;
        estimated = estimated == null ? Cohort.empty() : estimated;
        caveats = List.copyOf(caveats == null ? List.of() : caveats);
        if (sampleCount < 0 || withHumanBaseline < 0 || machineRunCount < 0) {
            throw new IllegalArgumentException("north star counts must not be negative");
        }
        if (withHumanBaseline > sampleCount) {
            throw new IllegalArgumentException(
                    "samples carrying a human baseline cannot exceed the cohort");
        }
    }

    /** One basis of human evidence. Never combined with another basis. */
    public record Cohort(int count, Long p50Minutes, Long p95Minutes) {

        public Cohort {
            if (count < 0) {
                throw new IllegalArgumentException("cohort count must not be negative");
            }
            // Both-or-neither. The weaker "not both null" form would let a
            // cohort report a p50 with no p95, and a half-measured figure reads
            // as a measured one.
            boolean hasBoth = p50Minutes != null && p95Minutes != null;
            boolean hasNeither = p50Minutes == null && p95Minutes == null;
            if (count == 0 ? !hasNeither : !hasBoth) {
                throw new IllegalArgumentException(
                        "an empty cohort has no percentiles, and a non-empty one must have both");
            }
        }

        static Cohort empty() {
            return new Cohort(0, null, null);
        }

        static Cohort from(List<Long> minutes) {
            if (minutes.isEmpty()) {
                return empty();
            }
            return new Cohort(
                    minutes.size(),
                    percentile(minutes, 0.50),
                    percentile(minutes, 0.95));
        }
    }

    /**
     * Takes the baselines rather than the samples they came from. The join
     * belongs to the service that owns both ledgers; this value only has to be
     * honest about the arithmetic, and it should be testable without standing
     * up a full evidence snapshot to do it.
     *
     * @param sampleCount the whole cohort, including samples with no baseline —
     *     otherwise "how many are we missing" could not be stated
     * @param humanBaselines only the samples that carry one
     */
    public static NorthStarComparison from(
            int sampleCount,
            List<EvidenceEvaluationSample.HumanBaseline> humanBaselines,
            List<BaselineEvaluationRun> runs) {
        List<EvidenceEvaluationSample.HumanBaseline> safeBaselines =
                List.copyOf(humanBaselines == null ? List.of() : humanBaselines);
        List<BaselineEvaluationRun> safeRuns = List.copyOf(runs == null ? List.of() : runs);

        Map<EvidenceEvaluationSample.HumanBaseline.Basis, List<Long>> byBasis =
                safeBaselines.stream()
                        .collect(Collectors.groupingBy(
                                EvidenceEvaluationSample.HumanBaseline::basis,
                                Collectors.mapping(
                                        EvidenceEvaluationSample.HumanBaseline::minutesToLocate,
                                        Collectors.toList())));

        List<Long> machine = safeRuns.stream()
                .map(BaselineEvaluationRun::composedTotalDurationMs)
                .toList();
        int withBaseline = safeBaselines.size();

        List<String> caveats = new ArrayList<>();
        // Say what the number does not include, next to the number itself. A
        // caveat that lives only in a design document is not attached to the
        // figure a reader will quote.
        caveats.add("机器耗时是「给出可复核结论」的耗时，不含人复核该结论的时间"
                + "（北极星第三段 adoptCost，影子模式下不存在）");
        if (withBaseline < sampleCount) {
            caveats.add("有 " + (sampleCount - withBaseline)
                    + " 条样本没有人工基线，它们只参与「准不准」，不参与耗时对照");
        }
        if (!byBasis.getOrDefault(
                EvidenceEvaluationSample.HumanBaseline.Basis.ESTIMATED, List.of()).isEmpty()) {
            caveats.add("估算与实测分开统计；估算基线不得当作实测汇报");
        }
        if (safeRuns.isEmpty()) {
            caveats.add("尚无基线运行，机器侧耗时不可用");
        }

        return new NorthStarComparison(
                sampleCount,
                withBaseline,
                Cohort.from(byBasis.getOrDefault(
                        EvidenceEvaluationSample.HumanBaseline.Basis.MEASURED, List.of())),
                Cohort.from(byBasis.getOrDefault(
                        EvidenceEvaluationSample.HumanBaseline.Basis.ESTIMATED, List.of())),
                percentile(machine, 0.50),
                percentile(machine, 0.95),
                safeRuns.size(),
                caveats);
    }

    private static <T extends Comparable<T>> T percentileOf(List<T> values, double percentile) {
        if (values.isEmpty()) {
            return null;
        }
        List<T> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static Long percentile(List<Long> values, double percentile) {
        return percentileOf(values.stream().map(Function.identity()).toList(), percentile);
    }
}
