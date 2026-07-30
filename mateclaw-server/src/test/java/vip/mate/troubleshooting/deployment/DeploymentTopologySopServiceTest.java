package vip.mate.troubleshooting.deployment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.evidence.EvidenceSourceRouter;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.IncidentContext;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeploymentTopologySopServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-30T09:00:00Z");
    private static final String TASK = "客服数字化平台-首页-可用性监控";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EvidenceSourceRouter router = mock(EvidenceSourceRouter.class);
    private final DeploymentTopologySopService service = new DeploymentTopologySopService(
            router,
            new DeploymentTopologySnapshotParser(objectMapper),
            Clock.fixed(NOW, ZoneOffset.UTC),
            Duration.ofSeconds(25));

    @Test
    void batchesConfiguredNodesThroughTheGuanceOnlyRouterAndFindsAdjacentSuspectLinks() {
        JsonNode snapshot = snapshot(
                node("entry-a", "入口 A", "client", "https://a.example.test", TASK),
                node("entry-b", "入口 B", "client", "https://b.example.test", "入口-B-拨测"),
                node("gateway", "网关", "gw", "", ""));
        ((ArrayNode) snapshot.path("topology").path("links"))
                .add(link("entry-a", "gateway"))
                .add(link("entry-b", "gateway"));

        when(router.collect(
                eq(7L), any(EvidenceRequest.class), any(IncidentContext.class),
                eq(Set.of("guance"))))
                .thenAnswer(invocation -> {
                    EvidenceRequest request = invocation.getArgument(1);
                    IncidentContext incident = invocation.getArgument(2);
                    if (incident.service().equals("entry-a")) {
                        return observed(request, 503, "https://a.example.test", TASK);
                    }
                    return observed(request, 204, "https://b.example.test", "入口-B-拨测");
                });

        DeploymentTopologySopResult result = service.analyze(7L, snapshot);

        assertThat(result.status())
                .isEqualTo(DeploymentTopologySopResult.AnalysisStatus.NETWORK_PROBLEM_DETECTED);
        assertThat(result.summary().nodeCount()).isEqualTo(3);
        assertThat(result.summary().configuredProbeNodes()).isEqualTo(2);
        assertThat(result.summary().observedProbeNodes()).isEqualTo(2);
        assertThat(result.summary().healthyProbeNodes()).isEqualTo(1);
        assertThat(result.summary().failingProbeNodes()).isEqualTo(1);
        assertThat(result.summary().unavailableProbeNodes()).isZero();
        assertThat(result.observations())
                .extracting(DeploymentTopologySopResult.NodeObservation::nodeKey)
                .containsExactly("entry-a", "entry-b");
        assertThat(result.observations().getFirst().status())
                .isEqualTo(DeploymentTopologySopResult.ObservationStatus.FAILED);
        assertThat(result.observations().getFirst().window()).isEqualTo("-5m");
        assertThat(result.suspectLinks())
                .containsExactly(new DeploymentTopologySopResult.SuspectLink(
                        "entry-a", "gateway", "ADJACENT_TO_FAILED_PROBE"));
        assertThat(result.unconfiguredNodeKeys()).containsExactly("gateway");
        assertThat(result.modelCalled()).isFalse();
        assertThat(result.persisted()).isFalse();
        verify(router, org.mockito.Mockito.times(2)).collect(
                eq(7L), any(EvidenceRequest.class), any(IncidentContext.class),
                eq(Set.of("guance")));
    }

    @Test
    void keepsMissingOrIdentityMismatchedEvidenceOutOfTheHealthyCount() {
        JsonNode snapshot = snapshot(
                node("entry-a", "入口 A", "client", "https://a.example.test", TASK),
                node("entry-b", "入口 B", "client", "https://b.example.test", "入口-B-拨测"));
        when(router.collect(
                eq(7L), any(EvidenceRequest.class), any(IncidentContext.class),
                eq(Set.of("guance"))))
                .thenAnswer(invocation -> {
                    EvidenceRequest request = invocation.getArgument(1);
                    IncidentContext incident = invocation.getArgument(2);
                    if (incident.service().equals("entry-a")) {
                        return missing(request);
                    }
                    return observed(request, 200, "https://other.example.test", "其他拨测");
                });

        DeploymentTopologySopResult result = service.analyze(7L, snapshot);

        assertThat(result.status())
                .isEqualTo(DeploymentTopologySopResult.AnalysisStatus.INSUFFICIENT_EVIDENCE);
        assertThat(result.summary().observedProbeNodes()).isZero();
        assertThat(result.summary().healthyProbeNodes()).isZero();
        assertThat(result.summary().unavailableProbeNodes()).isEqualTo(2);
        assertThat(result.observations())
                .extracting(DeploymentTopologySopResult.NodeObservation::status)
                .containsExactly(
                        DeploymentTopologySopResult.ObservationStatus.UNAVAILABLE,
                        DeploymentTopologySopResult.ObservationStatus.IDENTITY_MISMATCH);
        assertThat(result.suspectLinks()).isEmpty();
    }

    @Test
    void reportsNoProblemOnlyWhenEveryConfiguredProbeHasMatchingHealthyEvidence() {
        JsonNode snapshot = snapshot(
                node("entry-a", "入口 A", "client", "https://a.example.test/path", TASK));
        when(router.collect(
                eq(7L), any(EvidenceRequest.class), any(IncidentContext.class),
                eq(Set.of("guance"))))
                .thenAnswer(invocation -> observed(
                        invocation.getArgument(1), 302,
                        "https://a.example.test/path", TASK));

        DeploymentTopologySopResult result = service.analyze(7L, snapshot);

        assertThat(result.status())
                .isEqualTo(DeploymentTopologySopResult.AnalysisStatus.NO_PROBLEM_OBSERVED);
        assertThat(result.summary().healthyProbeNodes()).isEqualTo(1);
        assertThat(result.observations().getFirst().status())
                .isEqualTo(DeploymentTopologySopResult.ObservationStatus.HEALTHY);
    }

    @Test
    void rejectsMalformedProbeMetadataBeforeAnyEvidenceSourceCall() {
        ObjectNode malformed = node(
                "entry-a", "入口 A", "client", "https://a.example.test", TASK);
        malformed.put("guance_url", "http://dataflux.example.test/cloudDial?time=5m&query=not-base64");

        assertThatThrownBy(() -> service.analyze(7L, snapshot(malformed)))
                .isInstanceOf(MateClawException.class)
                .satisfies(error -> assertThat(((MateClawException) error).getCode()).isEqualTo(400))
                .hasMessageContaining("entry-a");

        verify(router, never()).collect(
                anyLong(), any(EvidenceRequest.class), any(IncidentContext.class), any());
    }

    @Test
    void rejectsDuplicateNodeKeysAndUnknownLinkEndpoints() {
        ObjectNode duplicateSnapshot = snapshot(
                node("entry-a", "入口 A", "client", "", ""),
                node("entry-a", "入口 A2", "client", "", ""));
        assertThatThrownBy(() -> service.analyze(7L, duplicateSnapshot))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("duplicate node key");

        ObjectNode unknownLinkSnapshot = snapshot(
                node("entry-a", "入口 A", "client", "", ""));
        ((ArrayNode) unknownLinkSnapshot.path("topology").path("links"))
                .add(link("entry-a", "missing"));
        assertThatThrownBy(() -> service.analyze(7L, unknownLinkSnapshot))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("unknown node");
    }

    @Test
    void rejectsCredentialShapedSnapshotFieldsBeforeAnyEvidenceCall() {
        ObjectNode unsafe = snapshot(
                node("entry-a", "入口 A", "client", "https://a.example.test", TASK));
        unsafe.put("apiKey", "must-not-enter-the-sop");

        assertThatThrownBy(() -> service.analyze(7L, unsafe))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("must not contain credentials");

        verify(router, never()).collect(
                anyLong(), any(EvidenceRequest.class), any(IncidentContext.class), any());
    }

    @Test
    void keepsCompletedProbeEvidenceWhenTheBoundedBatchTimesOut() {
        JsonNode snapshot = snapshot(
                node("entry-a", "入口 A", "client", "https://a.example.test", TASK),
                node("entry-b", "入口 B", "client", "https://b.example.test", "入口-B-拨测"));
        when(router.collect(
                eq(7L), any(EvidenceRequest.class), any(IncidentContext.class),
                eq(Set.of("guance"))))
                .thenAnswer(invocation -> {
                    EvidenceRequest request = invocation.getArgument(1);
                    IncidentContext incident = invocation.getArgument(2);
                    if (incident.service().equals("entry-a")) {
                        return observed(request, 200, "https://a.example.test", TASK);
                    }
                    try {
                        Thread.sleep(Duration.ofSeconds(5));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return missing(request);
                });
        DeploymentTopologySopService boundedService = new DeploymentTopologySopService(
                router,
                new DeploymentTopologySnapshotParser(objectMapper),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMillis(75));

        DeploymentTopologySopResult result = boundedService.analyze(7L, snapshot);

        assertThat(result.status())
                .isEqualTo(DeploymentTopologySopResult.AnalysisStatus.PARTIAL_OBSERVATION);
        assertThat(result.summary().healthyProbeNodes()).isEqualTo(1);
        assertThat(result.summary().unavailableProbeNodes()).isEqualTo(1);
        assertThat(result.observations().get(1).detail()).contains("总时间预算");
    }

    @Test
    void rejectsMoreThanTheBoundedNumberOfConfiguredProbesBeforeCollection() {
        ObjectNode[] nodes = new ObjectNode[DeploymentTopologySopService.MAX_CONFIGURED_PROBES + 1];
        for (int index = 0; index < nodes.length; index++) {
            nodes[index] = node(
                    "entry-" + index,
                    "入口 " + index,
                    "client",
                    "https://entry-" + index + ".example.test",
                    "入口-" + index + "-拨测");
        }

        assertThatThrownBy(() -> service.analyze(7L, snapshot(nodes)))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("32 configured probes");

        verify(router, never()).collect(
                anyLong(), any(EvidenceRequest.class), any(IncidentContext.class), any());
    }

    private ObjectNode snapshot(ObjectNode... nodes) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", "1.0");
        root.put("kind", "chain-board.runtime-topology-snapshot");
        root.put("exportedAt", "2026-07-30T07:00:43.589Z");
        root.putObject("system")
                .put("code", "csp-deployment")
                .put("label", "CSP 部署架构");
        ObjectNode topology = root.putObject("topology");
        ArrayNode nodeArray = topology.putArray("nodes");
        for (ObjectNode node : nodes) {
            nodeArray.add(node);
        }
        topology.putArray("links");
        return root;
    }

    private ObjectNode node(
            String key,
            String label,
            String type,
            String targetUrl,
            String taskName) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("key", key);
        node.put("label", label);
        node.put("type", type);
        node.put("url", targetUrl);
        node.put("guance_url", taskName.isBlank() ? "" : guanceUrl(taskName));
        return node;
    }

    private ObjectNode link(String source, String target) {
        return objectMapper.createObjectNode().put("source", source).put("target", target);
    }

    private String guanceUrl(String taskName) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("name:" + taskName).getBytes(StandardCharsets.UTF_8));
        return "http://dataflux.example.test/cloudDial/explorer"
                + "?time=5m&query=b64-" + encoded
                + "&viewer_source=http_dial_testing&w=wksp_test";
    }

    private EvidenceResult observed(
            EvidenceRequest request,
            int statusCode,
            String targetUrl,
            String probeName) {
        return new EvidenceResult(
                request.requestId(), "D", "", EvidenceStatus.NORMAL,
                "synthetic probe", Map.of(
                        "status_code", statusCode,
                        "target_url", targetUrl,
                        "probe_name", probeName),
                "guance:synthetic_probe", NOW);
    }

    private EvidenceResult missing(EvidenceRequest request) {
        return new EvidenceResult(
                request.requestId(), "UNKNOWN", "", EvidenceStatus.MISSING,
                "source unavailable", Map.of(), "router:unavailable", NOW);
    }
}
