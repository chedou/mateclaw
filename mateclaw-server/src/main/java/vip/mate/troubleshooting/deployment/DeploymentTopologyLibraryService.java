package vip.mate.troubleshooting.deployment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.TroubleshootingSecretRedactor;
import vip.mate.troubleshooting.deployment.DeploymentTopologySnapshotParser.ParsedSnapshot;
import vip.mate.troubleshooting.model.TroubleshootingDeploymentTopologyEntity;
import vip.mate.troubleshooting.repository.TroubleshootingDeploymentTopologyMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Workspace-shared, immutable library for validated deployment snapshots. */
@Service
public class DeploymentTopologyLibraryService {

    static final int MAX_LIBRARY_ITEMS = 100;
    private static final int MAX_NAME_LENGTH = 128;
    private static final int MAX_ACTOR_LENGTH = 192;

    private final TroubleshootingDeploymentTopologyMapper mapper;
    private final DeploymentTopologySnapshotParser snapshotParser;
    private final ObjectMapper objectMapper;
    private final DeploymentTopologySopService analyzer;
    private final Clock clock;

    @Autowired
    public DeploymentTopologyLibraryService(
            TroubleshootingDeploymentTopologyMapper mapper,
            DeploymentTopologySnapshotParser snapshotParser,
            ObjectMapper objectMapper,
            DeploymentTopologySopService analyzer) {
        this(mapper, snapshotParser, objectMapper, analyzer, Clock.systemUTC());
    }

    DeploymentTopologyLibraryService(
            TroubleshootingDeploymentTopologyMapper mapper,
            DeploymentTopologySnapshotParser snapshotParser,
            ObjectMapper objectMapper,
            DeploymentTopologySopService analyzer,
            Clock clock) {
        this.mapper = mapper;
        this.snapshotParser = snapshotParser;
        this.objectMapper = objectMapper;
        this.analyzer = analyzer;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public List<DeploymentTopologyAssetSummary> list(long workspaceId, int limit) {
        validateWorkspace(workspaceId);
        int boundedLimit = Math.max(1, Math.min(limit, MAX_LIBRARY_ITEMS));
        return mapper.listByWorkspace(workspaceId, boundedLimit).stream()
                .map(this::summary)
                .toList();
    }

    @Transactional
    public DeploymentTopologyImportResult importTopology(
            long workspaceId,
            String name,
            JsonNode snapshot,
            String actor) {
        validateWorkspace(workspaceId);
        String safeName = safeText(name, "topology name", MAX_NAME_LENGTH);
        String safeActor = safeText(actor, "importedBy", MAX_ACTOR_LENGTH);
        ParsedSnapshot parsed = snapshotParser.parse(snapshot);
        ObjectNode canonicalSnapshot = canonicalSnapshot(parsed);
        String fingerprint = fingerprint(canonicalSnapshot);
        int configuredProbeNodes = (int) parsed.nodes().stream()
                .filter(node -> node.probe() != null)
                .count();
        if (configuredProbeNodes > DeploymentTopologySopService.MAX_CONFIGURED_PROBES) {
            throw badRequest("deployment topology exceeds "
                    + DeploymentTopologySopService.MAX_CONFIGURED_PROBES
                    + " configured probes");
        }

        // Serializes imports per Workspace so idempotency and the 100-item
        // quota remain true under concurrent requests on every supported DB.
        if (mapper.lockWorkspace(workspaceId) == null) {
            throw new MateClawException(
                    "err.troubleshooting.workspace_not_found",
                    404,
                    "workspace does not exist");
        }

        TroubleshootingDeploymentTopologyEntity existing =
                mapper.findByFingerprint(workspaceId, fingerprint);
        if (existing != null) {
            return new DeploymentTopologyImportResult(summary(existing), false);
        }
        if (mapper.findByName(workspaceId, safeName) != null) {
            throw conflict("a deployment topology named " + safeName
                    + " already exists; import the changed snapshot under a new name");
        }
        if (mapper.countByWorkspace(workspaceId) >= MAX_LIBRARY_ITEMS) {
            throw conflict("deployment topology library already contains the maximum of "
                    + MAX_LIBRARY_ITEMS + " items");
        }

        Instant now = Instant.now(clock);
        TroubleshootingDeploymentTopologyEntity entity =
                new TroubleshootingDeploymentTopologyEntity();
        entity.setWorkspaceId(workspaceId);
        entity.setTopologyId("topology-" + UUID.randomUUID());
        entity.setName(safeName);
        entity.setSystem(parsed.system());
        entity.setSystemLabel(parsed.systemLabel());
        entity.setSchemaVersion(parsed.schemaVersion());
        entity.setExportedAt(LocalDateTime.ofInstant(parsed.exportedAt(), ZoneOffset.UTC));
        entity.setSnapshotJson(write(canonicalSnapshot));
        entity.setSnapshotFingerprint(fingerprint);
        entity.setNodeCount(parsed.nodes().size());
        entity.setLinkCount(parsed.links().size());
        entity.setConfiguredProbeNodes(configuredProbeNodes);
        entity.setImportedBy(safeActor);
        entity.setDeleted(0);
        entity.setCreateTime(LocalDateTime.ofInstant(now, ZoneOffset.UTC));
        entity.setUpdateTime(LocalDateTime.ofInstant(now, ZoneOffset.UTC));
        mapper.insert(entity);
        return new DeploymentTopologyImportResult(summary(entity), true);
    }

    public DeploymentTopologySopResult analyze(long workspaceId, String topologyId) {
        validateWorkspace(workspaceId);
        String safeTopologyId = safeText(topologyId, "topologyId", 128);
        TroubleshootingDeploymentTopologyEntity entity =
                mapper.findByTopologyId(workspaceId, safeTopologyId);
        if (entity == null) {
            throw new MateClawException(
                    "err.troubleshooting.deployment_topology_not_found",
                    404,
                    "deployment topology does not exist in this workspace");
        }
        try {
            return analyzer.analyze(workspaceId, objectMapper.readTree(entity.getSnapshotJson()));
        } catch (JsonProcessingException invalidStoredSnapshot) {
            throw new MateClawException(
                    "err.troubleshooting.deployment_topology_invalid",
                    500,
                    "stored deployment topology cannot be decoded");
        }
    }

    /** A valid, secret-free case that callers can download and adapt. */
    public ObjectNode example() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", "1.0");
        root.put("kind", "chain-board.runtime-topology-snapshot");
        root.put("exportedAt", "2026-07-30T10:00:00Z");
        root.putObject("system")
                .put("code", "example-deployment")
                .put("label", "示例部署拓扑");
        ObjectNode topology = root.putObject("topology");
        ArrayNode nodes = topology.putArray("nodes");
        String encodedProbe = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("name:示例-首页拨测".getBytes(StandardCharsets.UTF_8));
        nodes.addObject()
                .put("key", "public-entry")
                .put("label", "公网入口")
                .put("type", "client")
                .put("url", "https://example.test")
                .put("guance_url", "https://guance.example.test/cloudDial/explorer"
                        + "?time=5m&query=b64-" + encodedProbe
                        + "&viewer_source=http_dial_testing&w=wksp_example");
        nodes.addObject()
                .put("key", "api-gateway")
                .put("label", "API 网关")
                .put("type", "gateway");
        topology.putArray("links").addObject()
                .put("source", "public-entry")
                .put("target", "api-gateway");
        return root;
    }

    String fingerprint(JsonNode snapshot) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsBytes(snapshot));
            return HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException | NoSuchAlgorithmException failure) {
            throw new MateClawException(
                    "err.troubleshooting.deployment_topology_fingerprint_failed",
                    500,
                    "deployment topology fingerprint cannot be calculated");
        }
    }

    /**
     * Rebuilds the persisted document from the validated analysis contract.
     * Unknown input fields never enter the shared library, so callers cannot
     * smuggle raw responses, DQL, credentials or future unreviewed payloads
     * beside an otherwise valid topology.
     */
    private ObjectNode canonicalSnapshot(ParsedSnapshot parsed) {
        ObjectNode canonical = objectMapper.createObjectNode();
        canonical.put("schemaVersion", parsed.schemaVersion());
        canonical.put("kind", "chain-board.runtime-topology-snapshot");
        canonical.put("exportedAt", parsed.exportedAt().toString());
        canonical.putObject("system")
                .put("code", parsed.system())
                .put("label", parsed.systemLabel());

        ObjectNode canonicalTopology = canonical.putObject("topology");
        ArrayNode canonicalNodes = canonicalTopology.putArray("nodes");
        for (DeploymentTopologySnapshotParser.TopologyNode node : parsed.nodes()) {
            ObjectNode canonicalNode = canonicalNodes.addObject();
            canonicalNode.put("key", node.key());
            canonicalNode.put("label", node.label());
            canonicalNode.put("type", node.type());
            if (node.probe() != null) {
                canonicalNode.put("url", node.probe().targetUrl());
                canonicalNode.put("guance_url", canonicalProbeMetadataUrl(node.probe()));
            }
        }
        ArrayNode canonicalLinks = canonicalTopology.putArray("links");
        for (DeploymentTopologySnapshotParser.TopologyLink link : parsed.links()) {
            canonicalLinks.addObject()
                    .put("source", link.source())
                    .put("target", link.target());
        }
        return canonical;
    }

    private String canonicalProbeMetadataUrl(
            DeploymentTopologySnapshotParser.ProbeMetadata probe) {
        String encodedProbe = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("name:" + probe.probeName())
                        .getBytes(StandardCharsets.UTF_8));
        return "https://metadata.invalid/cloudDial"
                + "?time=" + probe.window().substring(1)
                + "&query=b64-" + encodedProbe
                + "&viewer_source=http_dial_testing&w=stored";
    }

    private DeploymentTopologyAssetSummary summary(
            TroubleshootingDeploymentTopologyEntity entity) {
        return new DeploymentTopologyAssetSummary(
                entity.getTopologyId(),
                entity.getName(),
                entity.getSystem(),
                entity.getSystemLabel(),
                entity.getSchemaVersion(),
                entity.getExportedAt().toInstant(ZoneOffset.UTC),
                entity.getNodeCount(),
                entity.getLinkCount(),
                entity.getConfiguredProbeNodes(),
                entity.getImportedBy(),
                entity.getCreateTime().toInstant(ZoneOffset.UTC));
    }

    private String write(JsonNode snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException failure) {
            throw new MateClawException(
                    "err.troubleshooting.deployment_topology_invalid",
                    400,
                    "deployment topology cannot be serialized safely");
        }
    }

    private String safeText(String value, String field, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > maxLength
                || normalized.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw badRequest(field + " must contain 1-" + maxLength + " display characters");
        }
        if (!TroubleshootingSecretRedactor.redact(normalized).equals(normalized)) {
            throw badRequest(field + " must not contain credentials");
        }
        return normalized;
    }

    private void validateWorkspace(long workspaceId) {
        if (workspaceId <= 0) {
            throw badRequest("workspaceId must be positive");
        }
    }

    private MateClawException badRequest(String message) {
        return new MateClawException(
                "err.troubleshooting.deployment_topology_invalid", 400, message);
    }

    private MateClawException conflict(String message) {
        return new MateClawException(
                "err.troubleshooting.deployment_topology_conflict", 409, message);
    }
}
