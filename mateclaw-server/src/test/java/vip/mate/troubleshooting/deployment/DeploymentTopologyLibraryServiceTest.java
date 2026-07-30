package vip.mate.troubleshooting.deployment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.model.TroubleshootingDeploymentTopologyEntity;
import vip.mate.troubleshooting.repository.TroubleshootingDeploymentTopologyMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeploymentTopologyLibraryServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-30T10:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TroubleshootingDeploymentTopologyMapper mapper =
            mock(TroubleshootingDeploymentTopologyMapper.class);
    private final DeploymentTopologySopService analyzer = mock(DeploymentTopologySopService.class);
    private final DeploymentTopologyLibraryService service = new DeploymentTopologyLibraryService(
            mapper,
            new DeploymentTopologySnapshotParser(objectMapper),
            objectMapper,
            analyzer,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void locksAnExistingWorkspace() {
        when(mapper.lockWorkspace(anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(0, Long.class));
    }

    @Test
    void importsAnImmutableWorkspaceTopologyAndProjectsOnlySafeMetadata() {
        DeploymentTopologyImportResult result = service.importTopology(
                7L, "马来西亚生产拓扑", snapshot(), "alice");

        assertThat(result.created()).isTrue();
        assertThat(result.topology().name()).isEqualTo("马来西亚生产拓扑");
        assertThat(result.topology().system()).isEqualTo("csp-deployment");
        assertThat(result.topology().nodeCount()).isEqualTo(2);
        assertThat(result.topology().linkCount()).isEqualTo(1);
        assertThat(result.topology().configuredProbeNodes()).isEqualTo(1);
        assertThat(result.topology().importedBy()).isEqualTo("alice");
        assertThat(result.topology().importedAt()).isEqualTo(NOW);

        verify(mapper).insert(any(TroubleshootingDeploymentTopologyEntity.class));
        org.mockito.InOrder importOrder = inOrder(mapper);
        importOrder.verify(mapper).lockWorkspace(7L);
        importOrder.verify(mapper).findByFingerprint(eq(7L), any());
        importOrder.verify(mapper).countByWorkspace(7L);
        importOrder.verify(mapper).insert(any(TroubleshootingDeploymentTopologyEntity.class));
    }

    @Test
    void persistsOnlyTheValidatedTopologyContractAndDropsUnknownPayloads() {
        ObjectNode supplied = snapshot();
        supplied.put("dql", "D::http_dial_testing:(*)");
        supplied.withObject("topology").putObject("rawResponse").put("status", 200);
        supplied.withObject("system").put("unreviewedMetadata", "must not persist");

        service.importTopology(7L, "安全拓扑", supplied, "alice");

        org.mockito.ArgumentCaptor<TroubleshootingDeploymentTopologyEntity> entityCaptor =
                org.mockito.ArgumentCaptor.forClass(
                        TroubleshootingDeploymentTopologyEntity.class);
        verify(mapper, atLeastOnce()).insert(entityCaptor.capture());
        String persisted = entityCaptor.getValue().getSnapshotJson();
        assertThat(persisted)
                .doesNotContain("dql")
                .doesNotContain("rawResponse")
                .doesNotContain("unreviewedMetadata")
                .doesNotContain("guance.example.test")
                .doesNotContain("wksp_example")
                .contains("guance_url")
                .contains("metadata.invalid")
                .contains("\"key\":\"entry\"");
    }

    @Test
    void rejectsGuanceMetadataUrlsThatTryToCarryDqlOrOtherExtraParameters() {
        ObjectNode supplied = snapshot();
        ArrayNode nodes = (ArrayNode) supplied.withObject("topology").get("nodes");
        ((ObjectNode) nodes.get(0)).put(
                "guance_url",
                nodes.get(0).path("guance_url").asText() + "&dql=D::http_dial_testing:(*)");

        assertThatThrownBy(() -> service.importTopology(7L, "夹带查询拓扑", supplied, "alice"))
                .isInstanceOf(MateClawException.class)
                .satisfies(error -> assertThat(((MateClawException) error).getCode()).isEqualTo(400))
                .hasMessageContaining("unsupported query parameters");

        verify(mapper, never()).insert(any(TroubleshootingDeploymentTopologyEntity.class));
    }

    @Test
    void acceptsKnownGuanceExplorerPresentationParametersWithoutPersistingThem() {
        ObjectNode supplied = snapshot();
        ArrayNode nodes = (ArrayNode) supplied.withObject("topology").get("nodes");
        ObjectNode probeNode = (ObjectNode) nodes.get(0);
        probeNode.put(
                "guance_url",
                probeNode.path("guance_url").asText()
                        + "&lak=CloudDial"
                        + "&activeName=CloudDialExplorer"
                        + "&cols=time,url,response_time,country,province,city"
                        + "&viewType=view");

        service.importTopology(7L, "真实观测云拓扑", supplied, "alice");

        org.mockito.ArgumentCaptor<TroubleshootingDeploymentTopologyEntity> entityCaptor =
                org.mockito.ArgumentCaptor.forClass(
                        TroubleshootingDeploymentTopologyEntity.class);
        verify(mapper).insert(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getSnapshotJson())
                .doesNotContain("CloudDialExplorer")
                .doesNotContain("response_time")
                .doesNotContain("viewType")
                .contains("metadata.invalid");
    }

    @Test
    void rejectsImportWhenTheWorkspaceCannotBeLocked() {
        when(mapper.lockWorkspace(7L)).thenReturn(null);

        assertThatThrownBy(() -> service.importTopology(7L, "孤儿拓扑", snapshot(), "alice"))
                .isInstanceOf(MateClawException.class)
                .satisfies(error -> assertThat(((MateClawException) error).getCode()).isEqualTo(404))
                .hasMessageContaining("workspace does not exist");

        verify(mapper, never()).insert(any(TroubleshootingDeploymentTopologyEntity.class));
    }

    @Test
    void reusesTheSameValidatedContractWhenJsonObjectFieldsAreReordered() throws Exception {
        ObjectNode original = snapshot();
        service.importTopology(7L, "原始拓扑", original, "alice");

        org.mockito.ArgumentCaptor<TroubleshootingDeploymentTopologyEntity> entityCaptor =
                org.mockito.ArgumentCaptor.forClass(
                        TroubleshootingDeploymentTopologyEntity.class);
        verify(mapper).insert(entityCaptor.capture());
        TroubleshootingDeploymentTopologyEntity existing = entityCaptor.getValue();
        when(mapper.findByFingerprint(7L, existing.getSnapshotFingerprint()))
                .thenReturn(existing);

        ObjectNode reordered = objectMapper.createObjectNode();
        reordered.set("topology", original.path("topology").deepCopy());
        reordered.set("system", original.path("system").deepCopy());
        reordered.put("kind", original.path("kind").asText());
        reordered.put("exportedAt", original.path("exportedAt").asText());
        reordered.put("schemaVersion", original.path("schemaVersion").asText());
        reordered.putObject("rawResponse").put("ignored", true);

        DeploymentTopologyImportResult reused = service.importTopology(
                7L, "重排字段拓扑", reordered, "bob");

        assertThat(reused.created()).isFalse();
        assertThat(reused.topology().topologyId()).isEqualTo(existing.getTopologyId());
        verify(mapper, times(1)).insert(any(TroubleshootingDeploymentTopologyEntity.class));
    }

    @Test
    void reusesTheSameSnapshotButRejectsANameThatWouldOverwriteDifferentContent() {
        service.importTopology(7L, "马来西亚生产拓扑", snapshot(), "alice");
        org.mockito.ArgumentCaptor<TroubleshootingDeploymentTopologyEntity> entityCaptor =
                org.mockito.ArgumentCaptor.forClass(
                        TroubleshootingDeploymentTopologyEntity.class);
        verify(mapper).insert(entityCaptor.capture());
        TroubleshootingDeploymentTopologyEntity existing = entityCaptor.getValue();
        when(mapper.findByFingerprint(7L, existing.getSnapshotFingerprint()))
                .thenReturn(existing);

        DeploymentTopologyImportResult reused = service.importTopology(
                7L, "重复上传", snapshot(), "bob");

        assertThat(reused.created()).isFalse();
        assertThat(reused.topology().topologyId()).isEqualTo(existing.getTopologyId());
        verify(mapper, times(1)).insert(any(TroubleshootingDeploymentTopologyEntity.class));

        ObjectNode changed = snapshot();
        changed.withObject("system").put("label", "另一份拓扑");
        when(mapper.findByName(7L, "马来西亚生产拓扑")).thenReturn(existing);

        assertThatThrownBy(() -> service.importTopology(
                7L, "马来西亚生产拓扑", changed, "bob"))
                .isInstanceOf(MateClawException.class)
                .satisfies(error -> assertThat(((MateClawException) error).getCode()).isEqualTo(409))
                .hasMessageContaining("already exists");
    }

    @Test
    void listsOnlyTheRequestedWorkspaceAndAnalyzesTheStoredSnapshotServerSide() {
        TroubleshootingDeploymentTopologyEntity entity = entity(
                "topology-shared", "共享拓扑", service.fingerprint(snapshot()));
        when(mapper.listByWorkspace(7L, 100)).thenReturn(List.of(entity));
        when(mapper.findByTopologyId(7L, "topology-shared")).thenReturn(entity);

        assertThat(service.list(7L, 100))
                .extracting(DeploymentTopologyAssetSummary::topologyId)
                .containsExactly("topology-shared");

        service.analyze(7L, "topology-shared");

        verify(analyzer).analyze(eq(7L), any());
        verify(mapper, never()).findByTopologyId(8L, "topology-shared");
    }

    @Test
    void suppliesAValidDownloadableExampleWithOneConfiguredProbe() {
        ObjectNode example = service.example();
        DeploymentTopologySnapshotParser.ParsedSnapshot parsed =
                new DeploymentTopologySnapshotParser(objectMapper).parse(example);

        assertThat(parsed.system()).isEqualTo("example-deployment");
        assertThat(parsed.nodes()).hasSize(2);
        assertThat(parsed.nodes()).filteredOn(node -> node.probe() != null).hasSize(1);
        assertThat(parsed.links()).hasSize(1);
    }

    @Test
    void refusesToPersistAnAssetThatTheAnalyzerWillRejectForTooManyProbes() {
        ObjectNode supplied = snapshotWithProbeCount(33);

        assertThatThrownBy(() -> service.importTopology(7L, "超限拓扑", supplied, "alice"))
                .isInstanceOf(MateClawException.class)
                .satisfies(error -> assertThat(((MateClawException) error).getCode()).isEqualTo(400))
                .hasMessageContaining("32 configured probes");

        verify(mapper, never()).insert(any(TroubleshootingDeploymentTopologyEntity.class));
    }

    private TroubleshootingDeploymentTopologyEntity entity(
            String topologyId,
            String name,
            String fingerprint) {
        TroubleshootingDeploymentTopologyEntity entity =
                new TroubleshootingDeploymentTopologyEntity();
        entity.setWorkspaceId(7L);
        entity.setTopologyId(topologyId);
        entity.setName(name);
        entity.setSystem("csp-deployment");
        entity.setSystemLabel("CSP 部署架构");
        entity.setSchemaVersion("1.0");
        entity.setExportedAt(java.time.LocalDateTime.ofInstant(
                NOW.minusSeconds(3600), ZoneOffset.UTC));
        entity.setSnapshotJson(snapshot().toString());
        entity.setSnapshotFingerprint(fingerprint);
        entity.setNodeCount(2);
        entity.setLinkCount(1);
        entity.setConfiguredProbeNodes(1);
        entity.setImportedBy("alice");
        entity.setDeleted(0);
        entity.setCreateTime(java.time.LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        entity.setUpdateTime(java.time.LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        return entity;
    }

    private ObjectNode snapshot() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", "1.0");
        root.put("kind", "chain-board.runtime-topology-snapshot");
        root.put("exportedAt", "2026-07-30T07:00:43.589Z");
        root.putObject("system")
                .put("code", "csp-deployment")
                .put("label", "CSP 部署架构");
        ObjectNode topology = root.putObject("topology");
        ArrayNode nodes = topology.putArray("nodes");
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("name:示例-首页拨测".getBytes(StandardCharsets.UTF_8));
        nodes.addObject()
                .put("key", "entry")
                .put("label", "入口")
                .put("type", "client")
                .put("url", "https://example.test")
                .put("guance_url", "https://guance.example.test/cloudDial"
                        + "?time=5m&query=b64-" + encoded
                        + "&viewer_source=http_dial_testing&w=wksp_example");
        nodes.addObject()
                .put("key", "gateway")
                .put("label", "网关")
                .put("type", "gateway");
        topology.putArray("links").addObject()
                .put("source", "entry")
                .put("target", "gateway");
        return root;
    }

    private ObjectNode snapshotWithProbeCount(int count) {
        ObjectNode root = snapshot();
        ArrayNode nodes = (ArrayNode) root.withObject("topology").get("nodes");
        nodes.removeAll();
        ((ArrayNode) root.withObject("topology").get("links")).removeAll();
        for (int index = 0; index < count; index++) {
            String probeName = "示例拨测-" + index;
            String encoded = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(("name:" + probeName)
                            .getBytes(StandardCharsets.UTF_8));
            nodes.addObject()
                    .put("key", "entry-" + index)
                    .put("label", "入口 " + index)
                    .put("type", "client")
                    .put("url", "https://example.test/probe-" + index)
                    .put("guance_url", "https://guance.example.test/cloudDial"
                            + "?time=5m&query=b64-" + encoded
                            + "&viewer_source=http_dial_testing&w=wksp_example");
        }
        return root;
    }
}
