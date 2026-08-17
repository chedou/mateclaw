package vip.mate.troubleshooting.investigation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import vip.mate.troubleshooting.agent.TroubleshootingAgentProperties;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.IncidentContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** Executes the server-owned bounded fallback when the hard-scoped Agent cannot run. */
@Service
public final class BoundedOpenDiscoveryInvestigationService {

    public static final String PLAN_KEY = "bounded-open-discovery-v1";

    private final TroubleshootingAgentProperties properties;
    private final BoundedInvestigationPlanner planner;
    private final DefaultOpenDiscoveryHypothesisGraphFactory graphFactory;
    private final Clock clock;

    @Autowired
    public BoundedOpenDiscoveryInvestigationService(
            TroubleshootingAgentProperties properties,
            BoundedInvestigationPlanner planner,
            DefaultOpenDiscoveryHypothesisGraphFactory graphFactory) {
        this(properties, planner, graphFactory, Clock.systemUTC());
    }

    public BoundedOpenDiscoveryInvestigationService(
            TroubleshootingAgentProperties properties,
            BoundedInvestigationPlanner planner,
            DefaultOpenDiscoveryHypothesisGraphFactory graphFactory,
            Clock clock) {
        if (properties == null || planner == null || graphFactory == null) {
            throw new IllegalArgumentException("properties, planner and graphFactory are required");
        }
        this.properties = properties;
        this.planner = planner;
        this.graphFactory = graphFactory;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public Optional<Execution> investigate(long workspaceId, IncidentContext incident) {
        Set<String> platforms = permittedPlatforms();
        if (!properties.isBoundedInvestigationEnabled() || platforms.isEmpty()) {
            return Optional.empty();
        }
        int maxIterations = properties.getBoundedInvestigationMaxIterations();
        int maxToolCalls = Math.min(
                properties.getBoundedInvestigationMaxToolCalls(),
                properties.getMaxEvidenceRequests());
        Duration timeout = properties.getBoundedInvestigationTimeout();
        if (maxIterations <= 0 || maxToolCalls <= 0 || timeout == null
                || timeout.isZero() || timeout.isNegative()) {
            return Optional.empty();
        }

        HypothesisGraph graph = graphFactory.create(incident);
        List<String> signalKinds = graph.nodes().stream()
                .flatMap(node -> node.questions().stream())
                .map(question -> question.request().signalKind())
                .distinct()
                .toList();
        BoundedInvestigationPlanner.Outcome outcome = planner.investigate(
                workspaceId,
                incident,
                graph,
                new BoundedInvestigationPlanner.Budget(
                        maxIterations,
                        maxToolCalls,
                        timeout,
                        Set.of(
                                EvidenceRouterReadOnlyTool.TOOL_KEY
                                        + "@" + EvidenceRouterReadOnlyTool.VERSION,
                                IncidentReportReadOnlyTool.TOOL_KEY
                                        + "@" + IncidentReportReadOnlyTool.VERSION)),
                platforms);
        return Optional.of(new Execution(
                outcome,
                PLAN_KEY,
                fingerprint(graph, platforms, maxIterations, maxToolCalls, timeout),
                signalKinds,
                maxIterations,
                maxToolCalls,
                timeout));
    }

    private Set<String> permittedPlatforms() {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String platform : properties.getBoundedInvestigationPermittedPlatforms() == null
                ? List.<String>of() : properties.getBoundedInvestigationPermittedPlatforms()) {
            if (platform != null && platform.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
                normalized.add(platform.toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(normalized);
    }

    static String fingerprint(
            HypothesisGraph graph,
            Set<String> platforms,
            int maxIterations,
            int maxToolCalls,
            Duration timeout) {
        String graphShape = graph.nodes().stream()
                .flatMap(node -> node.questions().stream()
                        .map(question -> String.join(
                                "\u001e",
                                node.hypothesisId(),
                                node.statement(),
                                Integer.toString(node.priority()),
                                question.questionId(),
                                Integer.toString(question.priority()),
                                question.toolIdentity(),
                                question.request().signalKind(),
                                question.request().purpose(),
                                String.valueOf(question.request().window()),
                                Boolean.toString(question.request().required()),
                                new TreeMap<>(question.request().target()).toString(),
                                question.criterion().signal(),
                                question.criterion().sourceRequestId(),
                                question.criterion().description(),
                                question.criterion().rule().toString())))
                .collect(java.util.stream.Collectors.joining("|"));
        String canonical = String.join(
                "\u001f",
                PLAN_KEY,
                graphShape,
                platforms.stream().sorted().collect(java.util.stream.Collectors.joining(",")),
                Integer.toString(maxIterations),
                Integer.toString(maxToolCalls),
                timeout.toString());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public record Execution(
            BoundedInvestigationPlanner.Outcome outcome,
            String planKey,
            String planFingerprint,
            List<String> plannedSignalKinds,
            int maxIterations,
            int maxToolCalls,
            Duration timeBudget) {

        public Execution {
            if (outcome == null || planKey == null || planKey.isBlank()
                    || planFingerprint == null || !planFingerprint.matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException("bounded investigation execution is incomplete");
            }
            plannedSignalKinds = List.copyOf(
                    plannedSignalKinds == null ? List.of() : plannedSignalKinds);
        }

        public RootCauseFinding finding() {
            return outcome.finding();
        }

        public List<EvidenceResult> evidence() {
            return outcome.evidence();
        }

        public int sourceRequestCount() {
            return outcome.toolCalls();
        }
    }
}
