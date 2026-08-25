package vip.mate.troubleshooting.investigation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import vip.mate.troubleshooting.agent.TroubleshootingAgentProperties;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.service.FormalOpenDiscoveryPlan;

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
    public static final String REVIEWED_REPORT_PLAN_KEY = "reviewed-incident-report-v1";
    public static final String CSDP_WECHAT_SLOW_REQUEST_PLAN_KEY =
            "csdp-wechat-slow-request-v1";
    private static final Duration REVIEWED_REPORT_TIMEOUT = Duration.ofSeconds(2);

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
        HypothesisGraph graph = graphFactory.create(incident);
        return investigate(
                workspaceId,
                incident,
                platforms,
                graph,
                Set.of(
                        EvidenceRouterReadOnlyTool.TOOL_KEY
                                + "@" + EvidenceRouterReadOnlyTool.VERSION,
                        IncidentReportReadOnlyTool.TOOL_KEY
                                + "@" + IncidentReportReadOnlyTool.VERSION),
                signalKinds(graph),
                null,
                PLAN_KEY);
    }

    /**
     * Formal generic investigations are Guance-only even when rehearsal
     * configuration permits additional adapters. The restriction is passed to
     * the registry before the first source call rather than checked after I/O.
     */
    public Optional<Execution> investigateFormal(
            long workspaceId,
            IncidentContext incident,
            FormalOpenDiscoveryPlan formalPlan) {
        return investigateFormal(workspaceId, incident, formalPlan, null);
    }

    public Optional<Execution> investigateFormal(
            long workspaceId,
            IncidentContext incident,
            FormalOpenDiscoveryPlan formalPlan,
            String expectedBindingFingerprint) {
        if (formalPlan == null) {
            throw new IllegalArgumentException("formal plan is required");
        }
        Set<String> configured = permittedPlatforms();
        if (!configured.contains("guance")) {
            return Optional.empty();
        }
        HypothesisGraph graph = graphFactory.createFormal(incident, formalPlan);
        boolean reviewedSlowRequest =
                ReviewedIncidentPolicy.isCsdpWechatSlowRequest(incident)
                        && graph.nodes().stream().anyMatch(node ->
                                "csdp-wechat-partner-user-info-hotspot"
                                        .equals(node.hypothesisId()));
        return investigate(
                workspaceId,
                incident,
                Set.of("guance"),
                graph,
                Set.of(EvidenceRouterReadOnlyTool.TOOL_KEY
                        + "@" + EvidenceRouterReadOnlyTool.VERSION),
                formalPlan.allowedSignalKinds(),
                expectedBindingFingerprint,
                reviewedSlowRequest ? CSDP_WECHAT_SLOW_REQUEST_PLAN_KEY : PLAN_KEY)
                .map(execution -> reviewedSlowRequest
                        ? execution : limitFormalGenericConclusion(execution));
    }

    /**
     * A generic graph is deliberately non-exhaustive. Even when every question
     * in that small graph was answered, it can support only a candidate
     * direction; it can never prove that every production root-cause family was
     * excluded.
     */
    private Execution limitFormalGenericConclusion(Execution execution) {
        RootCauseFinding finding = execution.finding();
        if (finding.type() != RootCauseFinding.Type.LOCATED) {
            return execution;
        }
        BoundedInvestigationPlanner.StopReason stopReason =
                BoundedInvestigationPlanner.StopReason.EVIDENCE_EXHAUSTED;
        RootCauseFinding boundedFinding = new RootCauseFinding(
                RootCauseFinding.Type.HYPOTHESIS,
                finding.cause(),
                "现有只读证据支持候选方向“" + finding.cause()
                        + "”，但通用调查无法穷尽所有根因方向，未宣称唯一根因。",
                finding.evidenceRefs(),
                finding.supportedHypothesisIds(),
                finding.excludedHypothesisIds(),
                finding.missingHypothesisIds(),
                stopReason);
        BoundedInvestigationPlanner.Outcome outcome = execution.outcome();
        return new Execution(
                new BoundedInvestigationPlanner.Outcome(
                        outcome.graph(),
                        boundedFinding,
                        outcome.evidence(),
                        outcome.iterations(),
                        outcome.toolCalls(),
                        outcome.startedAt(),
                        outcome.completedAt(),
                        stopReason),
                execution.planKey(),
                execution.planFingerprint(),
                execution.plannedSignalKinds(),
                execution.maxIterations(),
                execution.maxToolCalls(),
                execution.timeBudget());
    }

    private Optional<Execution> investigate(
            long workspaceId,
            IncidentContext incident,
            Set<String> platforms,
            HypothesisGraph graph,
            Set<String> allowedToolIdentities,
            Set<String> allowedSignalKinds,
            String sourceBindingFingerprint,
            String planKey) {
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
                        allowedToolIdentities),
                platforms,
                allowedSignalKinds,
                sourceBindingFingerprint);
        return Optional.of(new Execution(
                outcome,
                planKey,
                fingerprint(planKey, graph, platforms, maxIterations, maxToolCalls, timeout),
                signalKinds,
                maxIterations,
                maxToolCalls,
                timeout));
    }

    /**
     * Runs one local-only fact check after the caller verifies IntakeSession provenance.
     * It deliberately ignores Agent/platform feature flags: this plan calls only
     * {@code incident-report@1}, never an external source or a model.
     */
    public Optional<Execution> investigateReviewedIncidentReport(
            long workspaceId,
            IncidentContext incident) {
        if (!ReviewedIncidentPolicy.isReviewedIcareFinishRejection(incident)) {
            return Optional.empty();
        }
        HypothesisGraph graph = graphFactory.createReviewedIncidentReport(incident);
        Set<String> localPlatform = Set.of("incident-report");
        int maxIterations = 1;
        int maxToolCalls = 1;
        BoundedInvestigationPlanner.Outcome outcome = planner.investigate(
                workspaceId,
                incident,
                graph,
                new BoundedInvestigationPlanner.Budget(
                        maxIterations,
                        maxToolCalls,
                        REVIEWED_REPORT_TIMEOUT,
                        Set.of(IncidentReportReadOnlyTool.TOOL_KEY
                                + "@" + IncidentReportReadOnlyTool.VERSION)),
                localPlatform,
                signalKinds(graph),
                null);
        List<String> signalKinds = graph.nodes().stream()
                .flatMap(node -> node.questions().stream())
                .map(question -> question.request().signalKind())
                .distinct()
                .toList();
        return Optional.of(new Execution(
                outcome,
                REVIEWED_REPORT_PLAN_KEY,
                fingerprint(
                        REVIEWED_REPORT_PLAN_KEY, graph, localPlatform, maxIterations, maxToolCalls,
                        REVIEWED_REPORT_TIMEOUT),
                signalKinds,
                maxIterations,
                maxToolCalls,
                REVIEWED_REPORT_TIMEOUT));
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

    private Set<String> signalKinds(HypothesisGraph graph) {
        return graph.nodes().stream()
                .flatMap(node -> node.questions().stream())
                .map(question -> question.request().signalKind())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    static String fingerprint(
            HypothesisGraph graph,
            Set<String> platforms,
            int maxIterations,
            int maxToolCalls,
            Duration timeout) {
        return fingerprint(PLAN_KEY, graph, platforms, maxIterations, maxToolCalls, timeout);
    }

    static String fingerprint(
            String planKey,
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
                planKey,
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
