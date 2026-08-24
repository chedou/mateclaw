package vip.mate.troubleshooting.investigation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import vip.mate.troubleshooting.engine.CriterionEvaluator;
import vip.mate.troubleshooting.model.CriterionOutcome;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.IncidentContext;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Deterministic loop controller with explicit iteration, tool-call and time budgets. */
@Component
public final class BoundedInvestigationPlanner {

    private final ReadOnlyToolRegistry registry;
    private final CriterionEvaluator criterionEvaluator;
    private final Clock clock;

    @Autowired
    public BoundedInvestigationPlanner(
            ReadOnlyToolRegistry registry,
            CriterionEvaluator criterionEvaluator) {
        this(registry, criterionEvaluator, Clock.systemUTC());
    }

    public BoundedInvestigationPlanner(
            ReadOnlyToolRegistry registry,
            CriterionEvaluator criterionEvaluator,
            Clock clock) {
        if (registry == null || criterionEvaluator == null) {
            throw new IllegalArgumentException("registry and criterionEvaluator are required");
        }
        this.registry = registry;
        this.criterionEvaluator = criterionEvaluator;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public Outcome investigate(
            long workspaceId,
            IncidentContext incident,
            HypothesisGraph initialGraph,
            Budget budget,
            Set<String> permittedPlatforms) {
        Set<String> graphSignalKinds = initialGraph == null
                ? Set.of()
                : initialGraph.nodes().stream()
                        .flatMap(node -> node.questions().stream())
                        .map(question -> question.request().signalKind())
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return investigate(
                workspaceId,
                incident,
                initialGraph,
                budget,
                permittedPlatforms,
                graphSignalKinds,
                null);
    }

    public Outcome investigate(
            long workspaceId,
            IncidentContext incident,
            HypothesisGraph initialGraph,
            Budget budget,
            Set<String> permittedPlatforms,
            Set<String> allowedSignalKinds) {
        return investigate(
                workspaceId,
                incident,
                initialGraph,
                budget,
                permittedPlatforms,
                allowedSignalKinds,
                null);
    }

    public Outcome investigate(
            long workspaceId,
            IncidentContext incident,
            HypothesisGraph initialGraph,
            Budget budget,
            Set<String> permittedPlatforms,
            Set<String> allowedSignalKinds,
            String sourceBindingFingerprint) {
        if (workspaceId <= 0 || incident == null || initialGraph == null || budget == null) {
            throw new IllegalArgumentException(
                    "workspaceId, incident, graph and budget are required");
        }
        Instant startedAt = clock.instant();
        Instant deadline = startedAt.plus(budget.maxDuration());
        HypothesisGraph graph = initialGraph;
        List<EvidenceResult> evidence = new ArrayList<>();
        int iterations = 0;
        int toolCalls = 0;
        StopReason stopReason;

        while (true) {
            if (isLocated(graph)) {
                stopReason = StopReason.ROOT_CAUSE_LOCATED;
                break;
            }
            if (graph.nextQuestion().isEmpty()) {
                stopReason = StopReason.EVIDENCE_EXHAUSTED;
                break;
            }
            if (iterations >= budget.maxIterations()) {
                stopReason = StopReason.ITERATION_BUDGET_EXHAUSTED;
                break;
            }
            if (toolCalls >= budget.maxToolCalls()) {
                stopReason = StopReason.TOOL_BUDGET_EXHAUSTED;
                break;
            }
            if (!clock.instant().isBefore(deadline)) {
                stopReason = StopReason.TIME_BUDGET_EXHAUSTED;
                break;
            }

            HypothesisGraph.Question question = graph.nextQuestion().orElseThrow();
            EvidenceResult result;
            try {
                result = registry.collect(new ReadOnlyToolRegistry.Invocation(
                        question.toolKey(),
                        question.toolVersion(),
                        workspaceId,
                        incident,
                        question.request(),
                        budget.allowedToolIdentities(),
                        allowedSignalKinds,
                        permittedPlatforms,
                        deadline,
                        sourceBindingFingerprint));
            } catch (ReadOnlyToolRegistry.PolicyViolation blocked) {
                stopReason = StopReason.POLICY_BLOCKED;
                break;
            }
            iterations++;
            toolCalls++;
            evidence.add(result);
            CriterionOutcome outcome = result.status() == EvidenceStatus.MISSING
                    ? CriterionOutcome.UNEVALUATED
                    : criterionEvaluator.evaluate(
                            question.criterion().rule(), result.observed());
            graph = graph.recordOutcome(question.questionId(), outcome, result.queryId());
            if (!clock.instant().isBefore(deadline) && !isLocated(graph)) {
                stopReason = StopReason.TIME_BUDGET_EXHAUSTED;
                break;
            }
        }

        Instant completedAt = clock.instant();
        return new Outcome(
                graph,
                RootCauseFinding.from(graph, stopReason),
                List.copyOf(evidence),
                iterations,
                toolCalls,
                startedAt,
                completedAt,
                stopReason);
    }

    private boolean isLocated(HypothesisGraph graph) {
        long supported = graph.nodes().stream()
                .filter(node -> node.status() == HypothesisGraph.Status.SUPPORTED)
                .count();
        long excluded = graph.nodes().stream()
                .filter(node -> node.status() == HypothesisGraph.Status.EXCLUDED)
                .count();
        return supported == 1 && excluded == graph.nodes().size() - 1L;
    }

    public record Budget(
            int maxIterations,
            int maxToolCalls,
            Duration maxDuration,
            Set<String> allowedToolIdentities) {

        public Budget {
            if (maxIterations <= 0 || maxToolCalls <= 0 || maxDuration == null
                    || maxDuration.isNegative()) {
                throw new IllegalArgumentException(
                        "positive iteration/tool budgets and non-negative duration are required");
            }
            LinkedHashSet<String> allowed = new LinkedHashSet<>();
            for (String identity : allowedToolIdentities == null
                    ? Set.<String>of() : allowedToolIdentities) {
                if (identity == null || identity.isBlank()) {
                    throw new IllegalArgumentException("allowed tool identity must not be blank");
                }
                allowed.add(identity.trim().toLowerCase(java.util.Locale.ROOT));
            }
            if (allowed.isEmpty()) {
                throw new IllegalArgumentException("allowed tool identities must not be empty");
            }
            allowedToolIdentities = Set.copyOf(allowed);
        }
    }

    public record Outcome(
            HypothesisGraph graph,
            RootCauseFinding finding,
            List<EvidenceResult> evidence,
            int iterations,
            int toolCalls,
            Instant startedAt,
            Instant completedAt,
            StopReason stopReason) {

        public Outcome {
            evidence = List.copyOf(evidence == null ? List.of() : evidence);
        }
    }

    public enum StopReason {
        ROOT_CAUSE_LOCATED,
        EVIDENCE_EXHAUSTED,
        ITERATION_BUDGET_EXHAUSTED,
        TOOL_BUDGET_EXHAUSTED,
        TIME_BUDGET_EXHAUSTED,
        POLICY_BLOCKED
    }
}
