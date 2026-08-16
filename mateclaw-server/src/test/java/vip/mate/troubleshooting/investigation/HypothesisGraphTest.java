package vip.mate.troubleshooting.investigation;

import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.engine.Criterion;
import vip.mate.troubleshooting.model.AnomalyCriterion;
import vip.mate.troubleshooting.model.CriterionOutcome;
import vip.mate.troubleshooting.model.EvidenceRequest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HypothesisGraphTest {

    @Test
    void advancesTheMostValuableQuestionAndPreservesEvidenceReferences() {
        HypothesisGraph graph = HypothesisGraph.of(List.of(
                hypothesis("runtime", "运行环境异常", 50,
                        question("q-runtime", 10, "k8s_workload_health", "unhealthy_container_count")),
                hypothesis("application", "应用自身错误", 100,
                        question("q-application", 20, "error_log_scan", "error_count"))));

        assertThat(graph.nextQuestion()).get()
                .extracting(HypothesisGraph.Question::questionId)
                .isEqualTo("q-application");

        HypothesisGraph updated = graph.recordOutcome(
                "q-application", CriterionOutcome.SATISFIED, "ev-application");

        assertThat(updated.node("application").status())
                .isEqualTo(HypothesisGraph.Status.SUPPORTED);
        assertThat(updated.node("application").evidenceRefs())
                .containsExactly("ev-application");
        assertThat(updated.nextQuestion()).get()
                .extracting(HypothesisGraph.Question::questionId)
                .isEqualTo("q-runtime");
    }

    @Test
    void missingEvidenceLeavesTheHypothesisUnknownRatherThanExcluded() {
        HypothesisGraph graph = HypothesisGraph.of(List.of(
                hypothesis("runtime", "运行环境异常", 50,
                        question("q-runtime", 10, "k8s_workload_health", "unhealthy_container_count"))));

        HypothesisGraph updated = graph.recordOutcome(
                "q-runtime", CriterionOutcome.UNEVALUATED, "ev-missing");

        assertThat(updated.node("runtime").status())
                .isEqualTo(HypothesisGraph.Status.UNKNOWN);
        assertThat(updated.node("runtime").evidenceRefs()).containsExactly("ev-missing");
        assertThat(updated.nextQuestion()).isEmpty();
    }

    @Test
    void completedQuestionsCannotBeRewritten() {
        HypothesisGraph graph = HypothesisGraph.of(List.of(
                hypothesis("runtime", "运行环境异常", 50,
                        question("q-runtime", 10, "k8s_workload_health", "unhealthy_container_count"))))
                .recordOutcome("q-runtime", CriterionOutcome.EXCLUDED, "ev-1");

        assertThatThrownBy(() -> graph.recordOutcome(
                "q-runtime", CriterionOutcome.SATISFIED, "ev-2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already answered");
    }

    @Test
    void rootCauseIsLocatedOnlyWhenOneCauseIsSupportedAndAlternativesAreExcluded() {
        HypothesisGraph graph = HypothesisGraph.of(List.of(
                        hypothesis("application", "应用自身错误", 100,
                                question("q-application", 20, "error_log_scan", "error_count")),
                        hypothesis("runtime", "运行环境异常", 50,
                                question("q-runtime", 10, "k8s_workload_health", "unhealthy_container_count"))))
                .recordOutcome("q-application", CriterionOutcome.SATISFIED, "ev-app")
                .recordOutcome("q-runtime", CriterionOutcome.EXCLUDED, "ev-runtime");

        RootCauseFinding finding = RootCauseFinding.from(
                graph, BoundedInvestigationPlanner.StopReason.ROOT_CAUSE_LOCATED);

        assertThat(finding.type()).isEqualTo(RootCauseFinding.Type.LOCATED);
        assertThat(finding.cause()).isEqualTo("应用自身错误");
        assertThat(finding.evidenceRefs()).containsExactlyInAnyOrder("ev-app", "ev-runtime");
        assertThat(finding.excludedHypothesisIds()).containsExactly("runtime");
    }

    @Test
    void oneSupportedCauseWithAnUntestedAlternativeRemainsAHypothesis() {
        HypothesisGraph graph = HypothesisGraph.of(List.of(
                        hypothesis("application", "应用自身错误", 100,
                                question("q-application", 20, "error_log_scan", "error_count")),
                        hypothesis("runtime", "运行环境异常", 50,
                                question("q-runtime", 10, "k8s_workload_health", "unhealthy_container_count"))))
                .recordOutcome("q-application", CriterionOutcome.SATISFIED, "ev-app");

        RootCauseFinding finding = RootCauseFinding.from(
                graph, BoundedInvestigationPlanner.StopReason.ITERATION_BUDGET_EXHAUSTED);

        assertThat(finding.type()).isEqualTo(RootCauseFinding.Type.HYPOTHESIS);
        assertThat(finding.missingHypothesisIds()).containsExactly("runtime");
    }

    @Test
    void multipleSupportedCausesRemainExplicitParallelCandidates() {
        HypothesisGraph graph = HypothesisGraph.of(List.of(
                        hypothesis("application", "应用自身错误", 100,
                                question("q-application", 20, "error_log_scan", "error_count")),
                        hypothesis("runtime", "运行环境异常", 50,
                                question("q-runtime", 10, "k8s_workload_health", "unhealthy_container_count"))))
                .recordOutcome("q-application", CriterionOutcome.SATISFIED, "ev-app")
                .recordOutcome("q-runtime", CriterionOutcome.SATISFIED, "ev-runtime");

        RootCauseFinding finding = RootCauseFinding.from(
                graph, BoundedInvestigationPlanner.StopReason.EVIDENCE_EXHAUSTED);

        assertThat(finding.type()).isEqualTo(RootCauseFinding.Type.HYPOTHESIS);
        assertThat(finding.cause()).isEqualTo("应用自身错误；运行环境异常");
        assertThat(finding.summary())
                .contains("多个候选方向", "应用自身错误；运行环境异常", "未选择");
        assertThat(finding.supportedHypothesisIds())
                .containsExactly("application", "runtime");
    }

    private static HypothesisGraph.Hypothesis hypothesis(
            String id,
            String statement,
            int priority,
            HypothesisGraph.Question question) {
        return new HypothesisGraph.Hypothesis(id, statement, priority, List.of(question));
    }

    private static HypothesisGraph.Question question(
            String id,
            int priority,
            String signalKind,
            String field) {
        EvidenceRequest request = new EvidenceRequest(
                id, signalKind, "验证" + signalKind, Map.of(), "-15m", true);
        return new HypothesisGraph.Question(
                id,
                priority,
                "canonical-evidence",
                "1",
                request,
                new AnomalyCriterion(
                        id + "-criterion",
                        id,
                        "存在异常",
                        new Criterion.NumericGte(field, 1)));
    }
}
