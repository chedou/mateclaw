package vip.mate.troubleshooting.deployment;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.CanonicalNumberParser;
import vip.mate.troubleshooting.TroubleshootingSecretRedactor;
import vip.mate.troubleshooting.deployment.DeploymentTopologySnapshotParser.ParsedSnapshot;
import vip.mate.troubleshooting.deployment.DeploymentTopologySnapshotParser.TopologyLink;
import vip.mate.troubleshooting.deployment.DeploymentTopologySnapshotParser.TopologyNode;
import vip.mate.troubleshooting.evidence.EvidenceSourceRouter;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.model.IncidentImpact;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Runs the first deployment-topology SOP through the existing Guance-only evidence route.
 *
 * <p>The uploaded explorer URL is parsed only as bounded task metadata. It never controls
 * the HTTP destination or DQL: those remain server-owned Guance bindings behind
 * {@link EvidenceSourceRouter}.</p>
 */
@Service
public class DeploymentTopologySopService {

    static final int MAX_CONFIGURED_PROBES = 32;
    static final int MAX_PARALLEL_PROBES = 8;

    private static final String SIGNAL_KIND = "synthetic_probe";
    private static final Set<String> GUANCE_ONLY = Set.of("guance");
    private static final Duration DEFAULT_BATCH_TIMEOUT = Duration.ofSeconds(25);
    private static final int MAX_TEXT = 256;

    private final EvidenceSourceRouter router;
    private final DeploymentTopologySnapshotParser snapshotParser;
    private final Clock clock;
    private final Duration batchTimeout;

    @Autowired
    public DeploymentTopologySopService(
            EvidenceSourceRouter router,
            DeploymentTopologySnapshotParser snapshotParser) {
        this(router, snapshotParser, Clock.systemUTC(), DEFAULT_BATCH_TIMEOUT);
    }

    DeploymentTopologySopService(
            EvidenceSourceRouter router,
            DeploymentTopologySnapshotParser snapshotParser,
            Clock clock,
            Duration batchTimeout) {
        this.router = router;
        this.snapshotParser = snapshotParser;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.batchTimeout = batchTimeout == null || batchTimeout.isNegative()
                || batchTimeout.isZero()
                ? DEFAULT_BATCH_TIMEOUT
                : batchTimeout;
    }

    public DeploymentTopologySopResult analyze(long workspaceId, JsonNode snapshot) {
        if (workspaceId <= 0) {
            throw badRequest("workspaceId must be positive");
        }
        ParsedSnapshot parsed = snapshotParser.parse(snapshot);
        List<TopologyNode> configuredNodes = parsed.nodes().stream()
                .filter(node -> node.probe() != null)
                .toList();
        if (configuredNodes.size() > MAX_CONFIGURED_PROBES) {
            throw badRequest("deployment topology snapshot exceeds "
                    + MAX_CONFIGURED_PROBES + " configured probes");
        }

        Instant observationEnd = Instant.now(clock);
        List<DeploymentTopologySopResult.NodeObservation> observations =
                collectObservations(workspaceId, parsed, configuredNodes, observationEnd);
        List<String> unconfigured = parsed.nodes().stream()
                .filter(node -> node.probe() == null)
                .map(TopologyNode::key)
                .toList();
        int healthy = count(observations, DeploymentTopologySopResult.ObservationStatus.HEALTHY);
        int failing = count(observations, DeploymentTopologySopResult.ObservationStatus.FAILED);
        int observed = healthy + failing;
        int unavailable = observations.size() - observed;
        DeploymentTopologySopResult.Summary summary = new DeploymentTopologySopResult.Summary(
                parsed.nodes().size(),
                parsed.links().size(),
                observations.size(),
                observed,
                healthy,
                failing,
                unavailable);
        DeploymentTopologySopResult.AnalysisStatus status = analysisStatus(summary);
        List<DeploymentTopologySopResult.SuspectLink> suspectLinks =
                suspectLinks(parsed.links(), observations);

        return new DeploymentTopologySopResult(
                parsed.schemaVersion(),
                parsed.system(),
                parsed.systemLabel(),
                parsed.exportedAt(),
                SIGNAL_KIND,
                status,
                summary,
                List.copyOf(observations),
                suspectLinks,
                unconfigured,
                warnings(summary, unconfigured.size(), suspectLinks),
                Instant.now(clock),
                false,
                false);
    }

    private List<DeploymentTopologySopResult.NodeObservation> collectObservations(
            long workspaceId,
            ParsedSnapshot parsed,
            List<TopologyNode> configuredNodes,
            Instant observationEnd) {
        if (configuredNodes.isEmpty()) {
            return List.of();
        }
        Semaphore concurrency = new Semaphore(MAX_PARALLEL_PROBES);
        List<Callable<DeploymentTopologySopResult.NodeObservation>> tasks = new ArrayList<>();
        for (int index = 0; index < configuredNodes.size(); index++) {
            TopologyNode node = configuredNodes.get(index);
            int probeIndex = index + 1;
            tasks.add(() -> {
                concurrency.acquire();
                try {
                    return collectOne(workspaceId, parsed, node, probeIndex, observationEnd);
                } finally {
                    concurrency.release();
                }
            });
        }

        ExecutorService executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("deployment-topology-probe-", 0).factory());
        try {
            List<Future<DeploymentTopologySopResult.NodeObservation>> futures =
                    executor.invokeAll(tasks, batchTimeout.toMillis(), TimeUnit.MILLISECONDS);
            List<DeploymentTopologySopResult.NodeObservation> observations = new ArrayList<>();
            for (int index = 0; index < futures.size(); index++) {
                Future<DeploymentTopologySopResult.NodeObservation> future = futures.get(index);
                TopologyNode node = configuredNodes.get(index);
                if (future.isCancelled()) {
                    observations.add(unavailableObservation(
                            node, "批量拨测超过总时间预算，当前节点未形成证据"));
                    continue;
                }
                try {
                    observations.add(future.get());
                } catch (ExecutionException | CancellationException failure) {
                    observations.add(unavailableObservation(
                            node, "拨测任务未形成可用证据"));
                }
            }
            return List.copyOf(observations);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new MateClawException(
                    "err.troubleshooting.deployment_topology_interrupted",
                    503,
                    "deployment topology probe batch was interrupted");
        } finally {
            executor.shutdownNow();
        }
    }

    private DeploymentTopologySopResult.NodeObservation collectOne(
            long workspaceId,
            ParsedSnapshot parsed,
            TopologyNode node,
            int probeIndex,
            Instant observationEnd) {
        EvidenceRequest request = new EvidenceRequest(
                "DEPLOYMENT-PROBE-" + probeIndex + "-" + node.key(),
                SIGNAL_KIND,
                "Read the server-authorized synthetic probe for a deployment node",
                Map.of(),
                node.probe().window(),
                true);
        IncidentContext incident = new IncidentContext(
                "deployment-topology-" + observationEnd.toEpochMilli() + "-" + probeIndex,
                parsed.system(),
                node.key(),
                null,
                parsed.systemLabel() + " / " + node.label(),
                "P2",
                IncidentImpact.unknown("部署图拨测只读分析"),
                null,
                observationEnd,
                null,
                "deployment-topology-sop",
                IncidentCompleteness.STRUCTURED,
                "");
        EvidenceResult result = router.collect(workspaceId, request, incident, GUANCE_ONLY);
        return project(node, result);
    }

    private DeploymentTopologySopResult.NodeObservation project(
            TopologyNode node,
            EvidenceResult result) {
        if (result == null || result.status() == EvidenceStatus.MISSING) {
            return observation(
                    node,
                    DeploymentTopologySopResult.ObservationStatus.UNAVAILABLE,
                    null,
                    "",
                    "",
                    result == null ? "guance:unavailable" : safeDetail(result.source()),
                    result == null ? "观测源未返回证据" : safeDetail(result.summary()),
                    result == null ? null : result.collectedAt());
        }
        Long parsedStatus = CanonicalNumberParser.parseExactLong(
                result.observed().get("status_code"));
        String observedTarget = stringValue(result.observed().get("target_url"));
        String observedProbe = stringValue(result.observed().get("probe_name"));
        String canonicalObservedTarget;
        try {
            canonicalObservedTarget = snapshotParser.canonicalHttpUrl(
                    observedTarget, "observed target_url");
        } catch (MateClawException invalidObservedTarget) {
            canonicalObservedTarget = "";
        }
        Integer statusCode = parsedStatus != null
                && parsedStatus >= Integer.MIN_VALUE
                && parsedStatus <= Integer.MAX_VALUE
                ? parsedStatus.intValue()
                : null;
        if (statusCode == null
                || !node.probe().targetUrl().equals(canonicalObservedTarget)
                || !node.probe().probeName().equals(observedProbe)) {
            return observation(
                    node,
                    DeploymentTopologySopResult.ObservationStatus.IDENTITY_MISMATCH,
                    statusCode,
                    canonicalObservedTarget,
                    safeDisplayValue(observedProbe),
                    safeDetail(result.source()),
                    "返回证据与部署图中的目标 URL 或拨测任务不一致，未用于健康判断",
                    result.collectedAt());
        }
        DeploymentTopologySopResult.ObservationStatus status =
                statusCode >= 200 && statusCode < 400
                        ? DeploymentTopologySopResult.ObservationStatus.HEALTHY
                        : DeploymentTopologySopResult.ObservationStatus.FAILED;
        return observation(
                node,
                status,
                statusCode,
                canonicalObservedTarget,
                observedProbe,
                safeDetail(result.source()),
                status == DeploymentTopologySopResult.ObservationStatus.HEALTHY
                        ? "拨测返回 HTTP 可达状态"
                        : "拨测返回非成功 HTTP 状态，需沿相邻拓扑继续核查",
                result.collectedAt());
    }

    private DeploymentTopologySopResult.NodeObservation unavailableObservation(
            TopologyNode node,
            String detail) {
        return observation(
                node,
                DeploymentTopologySopResult.ObservationStatus.UNAVAILABLE,
                null,
                "",
                "",
                "router:unavailable",
                detail,
                Instant.now(clock));
    }

    private DeploymentTopologySopResult.NodeObservation observation(
            TopologyNode node,
            DeploymentTopologySopResult.ObservationStatus status,
            Integer statusCode,
            String observedTargetUrl,
            String observedProbeName,
            String evidenceRef,
            String detail,
            Instant collectedAt) {
        return new DeploymentTopologySopResult.NodeObservation(
                node.key(),
                node.label(),
                node.type(),
                node.probe().targetUrl(),
                node.probe().probeName(),
                node.probe().window(),
                status,
                statusCode,
                observedTargetUrl,
                observedProbeName,
                evidenceRef,
                detail,
                collectedAt);
    }

    private DeploymentTopologySopResult.AnalysisStatus analysisStatus(
            DeploymentTopologySopResult.Summary summary) {
        if (summary.configuredProbeNodes() == 0) {
            return DeploymentTopologySopResult.AnalysisStatus.NO_PROBES_CONFIGURED;
        }
        if (summary.failingProbeNodes() > 0) {
            return DeploymentTopologySopResult.AnalysisStatus.NETWORK_PROBLEM_DETECTED;
        }
        if (summary.observedProbeNodes() == 0) {
            return DeploymentTopologySopResult.AnalysisStatus.INSUFFICIENT_EVIDENCE;
        }
        if (summary.unavailableProbeNodes() > 0
                || summary.configuredProbeNodes() < summary.nodeCount()) {
            return DeploymentTopologySopResult.AnalysisStatus.PARTIAL_OBSERVATION;
        }
        return DeploymentTopologySopResult.AnalysisStatus.NO_PROBLEM_OBSERVED;
    }

    private List<DeploymentTopologySopResult.SuspectLink> suspectLinks(
            List<TopologyLink> links,
            List<DeploymentTopologySopResult.NodeObservation> observations) {
        Set<String> failingNodes = observations.stream()
                .filter(observation -> observation.status()
                        == DeploymentTopologySopResult.ObservationStatus.FAILED)
                .map(DeploymentTopologySopResult.NodeObservation::nodeKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return links.stream()
                .filter(link -> failingNodes.contains(link.source())
                        || failingNodes.contains(link.target()))
                .map(link -> new DeploymentTopologySopResult.SuspectLink(
                        link.source(), link.target(), "ADJACENT_TO_FAILED_PROBE"))
                .toList();
    }

    private List<String> warnings(
            DeploymentTopologySopResult.Summary summary,
            int unconfigured,
            List<DeploymentTopologySopResult.SuspectLink> suspectLinks) {
        List<String> warnings = new ArrayList<>();
        if (unconfigured > 0) {
            warnings.add(unconfigured + " 个节点没有可执行的拨测元数据，未查询且未宣称健康。");
        }
        if (summary.unavailableProbeNodes() > 0) {
            warnings.add(summary.unavailableProbeNodes()
                    + " 个已配置拨测未形成匹配的 canonical 证据。");
        }
        if (!suspectLinks.isEmpty()) {
            warnings.add("疑似链路仅表示与失败拨测节点拓扑相邻，不等于已证明的故障 hop 或根因。");
        }
        warnings.add("本次仅执行 Guance 只读查询；不调用模型、不持久化原始响应、不改变 T7/T8 状态。");
        return List.copyOf(warnings);
    }

    private int count(
            List<DeploymentTopologySopResult.NodeObservation> observations,
            DeploymentTopologySopResult.ObservationStatus status) {
        return Math.toIntExact(observations.stream()
                .filter(observation -> observation.status() == status)
                .count());
    }

    private String safeDisplayValue(String value) {
        String redacted = TroubleshootingSecretRedactor.redact(value == null ? "" : value.trim())
                .replaceAll("\\p{Cntrl}", "");
        return redacted.length() <= MAX_TEXT ? redacted : redacted.substring(0, MAX_TEXT);
    }

    private String safeDetail(String value) {
        String redacted = safeDisplayValue(value);
        return redacted.isBlank() ? "unavailable" : redacted;
    }

    private String stringValue(Object value) {
        return value instanceof String text ? text.trim() : "";
    }

    private MateClawException badRequest(String message) {
        return new MateClawException(
                "err.troubleshooting.deployment_topology_invalid",
                400,
                message);
    }
}
