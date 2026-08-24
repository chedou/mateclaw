package vip.mate.troubleshooting.investigation;

import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.engine.Criterion;
import vip.mate.troubleshooting.engine.CriterionEvaluator;
import vip.mate.troubleshooting.evidence.FormalEvidenceAuthorityException;
import vip.mate.troubleshooting.model.AnomalyCriterion;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundedInvestigationPlannerTest {

    private static final Instant NOW = Instant.parse("2026-08-16T10:00:00Z");

    @Test
    void investigatesOneQuestionPerRoundAndLocatesOnlyAfterAlternativesAreExcluded() {
        SequencedTool tool = new SequencedTool(Map.of(
                "q-app", Map.of("error_count", 2),
                "q-runtime", Map.of(
                        "pod_count", 4,
                        "container_count", 4,
                        "running_container_count", 4,
                        "unhealthy_container_count", 0,
                        "max_cpu_percent", 45,
                        "max_memory_percent", 52)));
        BoundedInvestigationPlanner planner = planner(tool, Clock.fixed(NOW, ZoneOffset.UTC));

        BoundedInvestigationPlanner.Outcome outcome = planner.investigate(
                1L,
                incident(),
                graph(),
                new BoundedInvestigationPlanner.Budget(
                        4, 4, Duration.ofSeconds(10), Set.of("canonical-evidence@1")),
                Set.of("guance"));

        assertThat(outcome.finding().type()).isEqualTo(RootCauseFinding.Type.LOCATED);
        assertThat(outcome.finding().cause()).isEqualTo("应用自身错误");
        assertThat(outcome.iterations()).isEqualTo(2);
        assertThat(outcome.toolCalls()).isEqualTo(2);
        assertThat(outcome.stopReason())
                .isEqualTo(BoundedInvestigationPlanner.StopReason.ROOT_CAUSE_LOCATED);
        assertThat(tool.requestIds()).containsExactly("q-app", "q-runtime");
    }

    @Test
    void stopsBeforeASecondQuestionWhenTheIterationBudgetIsExhausted() {
        SequencedTool tool = new SequencedTool(Map.of(
                "q-app", Map.of("error_count", 2),
                "q-runtime", Map.of("unhealthy_container_count", 0)));
        BoundedInvestigationPlanner planner = planner(tool, Clock.fixed(NOW, ZoneOffset.UTC));

        BoundedInvestigationPlanner.Outcome outcome = planner.investigate(
                1L,
                incident(),
                graph(),
                new BoundedInvestigationPlanner.Budget(
                        1, 4, Duration.ofSeconds(10), Set.of("canonical-evidence@1")),
                Set.of("guance"));

        assertThat(outcome.stopReason())
                .isEqualTo(BoundedInvestigationPlanner.StopReason.ITERATION_BUDGET_EXHAUSTED);
        assertThat(outcome.finding().type()).isEqualTo(RootCauseFinding.Type.HYPOTHESIS);
        assertThat(outcome.toolCalls()).isEqualTo(1);
        assertThat(tool.calls()).isEqualTo(1);
    }

    @Test
    void anExpiredTimeBudgetMakesNoToolCallAndAbstains() {
        SequencedTool tool = new SequencedTool(Map.of("q-app", Map.of("error_count", 2)));
        Clock afterDeadline = Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC);
        BoundedInvestigationPlanner planner = planner(tool, afterDeadline);

        BoundedInvestigationPlanner.Outcome outcome = planner.investigate(
                1L,
                incident(),
                graph(),
                new BoundedInvestigationPlanner.Budget(
                        4, 4, Duration.ZERO, Set.of("canonical-evidence@1")),
                Set.of("guance"));

        assertThat(outcome.stopReason())
                .isEqualTo(BoundedInvestigationPlanner.StopReason.TIME_BUDGET_EXHAUSTED);
        assertThat(outcome.finding().type()).isEqualTo(RootCauseFinding.Type.ABSTAINED);
        assertThat(tool.calls()).isZero();
    }

    @Test
    void formalAuthorityFailureAbortsInsteadOfProducingAnInsufficientEvidenceFinding() {
        ReadOnlyEvidenceTool blocked = new ReadOnlyEvidenceTool() {
            @Override
            public Descriptor descriptor() {
                return new Descriptor(
                        "canonical-evidence", "1", Capability.READ_EVIDENCE,
                        Set.of("error_log_scan", "k8s_workload_health"));
            }

            @Override
            public EvidenceResult collect(
                    ReadOnlyToolRegistry.Context context,
                    EvidenceRequest request) {
                throw FormalEvidenceAuthorityException.configurationDrift(
                        "formal Guance binding changed after admission");
            }
        };
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        BoundedInvestigationPlanner planner = new BoundedInvestigationPlanner(
                new ReadOnlyToolRegistry(List.of(blocked), clock),
                new CriterionEvaluator(),
                clock);

        assertThatThrownBy(() -> planner.investigate(
                        1L,
                        incident(),
                        graph(),
                        new BoundedInvestigationPlanner.Budget(
                                4, 4, Duration.ofSeconds(10),
                                Set.of("canonical-evidence@1")),
                        Set.of("guance"),
                        Set.of("error_log_scan", "k8s_workload_health"),
                        "a".repeat(64)))
                .isInstanceOf(FormalEvidenceAuthorityException.class)
                .extracting(failure ->
                        ((FormalEvidenceAuthorityException) failure).reason())
                .isEqualTo(FormalEvidenceAuthorityException.Reason.CONFIGURATION_DRIFT);
    }

    private static BoundedInvestigationPlanner planner(SequencedTool tool, Clock clock) {
        return new BoundedInvestigationPlanner(
                new ReadOnlyToolRegistry(List.of(tool), clock), new CriterionEvaluator(), clock);
    }

    private static HypothesisGraph graph() {
        return HypothesisGraph.of(List.of(
                hypothesis("app", "应用自身错误", 100,
                        question("q-app", "error_log_scan", "error_count")),
                hypothesis("runtime", "运行环境异常", 50,
                        question("q-runtime", "k8s_workload_health", "unhealthy_container_count"))));
    }

    private static HypothesisGraph.Hypothesis hypothesis(
            String id, String statement, int priority, HypothesisGraph.Question question) {
        return new HypothesisGraph.Hypothesis(id, statement, priority, List.of(question));
    }

    private static HypothesisGraph.Question question(
            String id, String signalKind, String field) {
        EvidenceRequest request = new EvidenceRequest(
                id, signalKind, "investigate", Map.of(), "-15m", true);
        return new HypothesisGraph.Question(
                id, 10, "canonical-evidence", "1", request,
                new AnomalyCriterion(
                        id + "-criterion", id, "anomaly",
                        new Criterion.NumericGte(field, 1)));
    }

    private static IncidentContext incident() {
        return new IncidentContext(
                "incident-1", "CSDP", "csdp-wechat", "904003", "ITGW访问失败",
                "P1", "客户受影响", null, NOW, null, "web",
                IncidentCompleteness.STRUCTURED, null);
    }

    private static final class SequencedTool implements ReadOnlyEvidenceTool {
        private final Map<String, Map<String, Object>> observedByRequest;
        private final java.util.ArrayList<String> requestIds = new java.util.ArrayList<>();
        private final AtomicInteger calls = new AtomicInteger();

        private SequencedTool(Map<String, Map<String, Object>> observedByRequest) {
            this.observedByRequest = observedByRequest;
        }

        @Override
        public Descriptor descriptor() {
            return new Descriptor(
                    "canonical-evidence", "1", Capability.READ_EVIDENCE,
                    Set.of("error_log_scan", "k8s_workload_health"));
        }

        @Override
        public EvidenceResult collect(
                ReadOnlyToolRegistry.Context context,
                EvidenceRequest request) {
            calls.incrementAndGet();
            requestIds.add(request.requestId());
            return new EvidenceResult(
                    request.requestId(), "observability", "", EvidenceStatus.ANOMALY,
                    "canonical", observedByRequest.get(request.requestId()), "stub", NOW);
        }

        int calls() {
            return calls.get();
        }

        List<String> requestIds() {
            return List.copyOf(requestIds);
        }
    }
}
