package vip.mate.troubleshooting.investigation;

import java.util.LinkedHashSet;
import java.util.List;

/** Evidence-bounded root-cause result; it explicitly preserves exclusions and gaps. */
public record RootCauseFinding(
        Type type,
        String cause,
        String summary,
        List<String> evidenceRefs,
        List<String> supportedHypothesisIds,
        List<String> excludedHypothesisIds,
        List<String> missingHypothesisIds,
        BoundedInvestigationPlanner.StopReason stopReason) {

    public RootCauseFinding {
        if (type == null || stopReason == null) {
            throw new IllegalArgumentException("type and stopReason are required");
        }
        cause = normalizeNullable(cause);
        summary = summary == null ? "" : summary.trim();
        evidenceRefs = List.copyOf(evidenceRefs == null ? List.of() : evidenceRefs);
        supportedHypothesisIds = List.copyOf(
                supportedHypothesisIds == null ? List.of() : supportedHypothesisIds);
        excludedHypothesisIds = List.copyOf(
                excludedHypothesisIds == null ? List.of() : excludedHypothesisIds);
        missingHypothesisIds = List.copyOf(
                missingHypothesisIds == null ? List.of() : missingHypothesisIds);
        if (type == Type.LOCATED && supportedHypothesisIds.size() != 1) {
            throw new IllegalArgumentException(
                    "located root cause requires exactly one supported hypothesis");
        }
        if (type == Type.ABSTAINED && cause != null) {
            throw new IllegalArgumentException("abstained finding cannot name a cause");
        }
    }

    public static RootCauseFinding from(
            HypothesisGraph graph,
            BoundedInvestigationPlanner.StopReason stopReason) {
        if (graph == null || stopReason == null) {
            throw new IllegalArgumentException("graph and stopReason are required");
        }
        List<HypothesisGraph.Node> supported = graph.nodes().stream()
                .filter(node -> node.status() == HypothesisGraph.Status.SUPPORTED)
                .toList();
        List<String> excluded = graph.nodes().stream()
                .filter(node -> node.status() == HypothesisGraph.Status.EXCLUDED)
                .map(HypothesisGraph.Node::hypothesisId)
                .toList();
        List<String> missing = graph.nodes().stream()
                .filter(node -> node.status() == HypothesisGraph.Status.PENDING
                        || node.status() == HypothesisGraph.Status.UNKNOWN)
                .map(HypothesisGraph.Node::hypothesisId)
                .toList();
        LinkedHashSet<String> evidenceRefs = new LinkedHashSet<>();
        graph.nodes().forEach(node -> evidenceRefs.addAll(node.evidenceRefs()));

        boolean alternativesExcluded = supported.size() == 1
                && excluded.size() == graph.nodes().size() - 1;
        Type type = alternativesExcluded
                ? Type.LOCATED
                : supported.isEmpty() ? Type.ABSTAINED : Type.HYPOTHESIS;
        String cause = supported.isEmpty()
                ? null
                : supported.stream()
                        .map(HypothesisGraph.Node::statement)
                        .collect(java.util.stream.Collectors.joining("；"));
        String summary = switch (type) {
            case LOCATED -> "证据支持“" + cause + "”，并已排除其他已登记方向。";
            case HYPOTHESIS -> supported.size() > 1
                    ? "证据同时支持多个候选方向：“" + cause + "”，系统未选择其中任何一个冒充唯一原因。"
                    : "证据支持候选方向“" + cause + "”，但仍有方向未排除。";
            case ABSTAINED -> "现有只读证据不足以支持任何已登记根因，系统已弃权。";
        };
        return new RootCauseFinding(
                type,
                cause,
                summary,
                List.copyOf(evidenceRefs),
                supported.stream().map(HypothesisGraph.Node::hypothesisId).toList(),
                excluded,
                missing,
                stopReason);
    }

    public enum Type {
        LOCATED,
        HYPOTHESIS,
        ABSTAINED
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
