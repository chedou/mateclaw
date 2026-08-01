package vip.mate.troubleshooting.deployment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.TroubleshootingSecretRedactor;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates and decodes the bounded, secret-free deployment snapshot contract. */
@Component
public final class DeploymentTopologySnapshotParser {

    static final int MAX_NODES = 100;
    static final int MAX_LINKS = 300;

    private static final String SNAPSHOT_KIND = "chain-board.runtime-topology-snapshot";
    private static final Pattern SAFE_IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}");
    private static final Pattern WINDOW = Pattern.compile("([1-9][0-9]{0,3})([smhd])");
    private static final int MAX_SNAPSHOT_BYTES = 512 * 1024;
    private static final int MAX_TEXT = 256;
    private static final Set<String> REQUIRED_GUANCE_QUERY_PARAMETERS =
            Set.of("viewer_source", "w", "query", "time");
    private static final Set<String> OPTIONAL_GUANCE_PRESENTATION_PARAMETERS =
            Set.of("lak", "activeName", "cols", "viewType");
    private static final Set<String> SUPPORTED_GUANCE_QUERY_PARAMETERS =
            Set.of(
                    "viewer_source",
                    "w",
                    "query",
                    "time",
                    "lak",
                    "activeName",
                    "cols",
                    "viewType");

    private final ObjectMapper objectMapper;

    public DeploymentTopologySnapshotParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    ParsedSnapshot parse(JsonNode snapshot) {
        rejectInvalidEnvelope(snapshot);

        String schemaVersion = requiredText(snapshot, "schemaVersion", 32);
        String kind = requiredText(snapshot, "kind", 128);
        if (!SNAPSHOT_KIND.equals(kind)) {
            throw badRequest("unsupported deployment topology snapshot kind");
        }
        Instant exportedAt = instant(snapshot.path("exportedAt"), "exportedAt");
        JsonNode systemNode = requiredObject(snapshot, "system");
        String system = safeIdentifier(requiredText(systemNode, "code", 128), "system.code");
        String systemLabel = optionalText(systemNode, "label", MAX_TEXT);
        if (systemLabel.isBlank()) {
            systemLabel = system;
        }
        safeDisplayText(systemLabel, "system.label");

        JsonNode topology = requiredObject(snapshot, "topology");
        JsonNode nodeArray = requiredArray(topology, "nodes", MAX_NODES);
        JsonNode linkArray = requiredArray(topology, "links", MAX_LINKS);
        List<TopologyNode> nodes = parseNodes(nodeArray);
        List<TopologyLink> links = parseLinks(linkArray, nodes);
        return new ParsedSnapshot(
                schemaVersion,
                system,
                systemLabel,
                exportedAt,
                List.copyOf(nodes),
                List.copyOf(links));
    }

    String canonicalHttpUrl(String value, String field) {
        URI uri = uri(value, field);
        if (!httpScheme(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw badRequest(field
                    + " must be an HTTP(S) URL without user info, query, or fragment");
        }
        String path = uri.getRawPath();
        if (path == null || path.equals("/")) {
            path = "";
        }
        return uri.getScheme().toLowerCase(Locale.ROOT)
                + "://"
                + uri.getHost().toLowerCase(Locale.ROOT)
                + (uri.getPort() < 0 ? "" : ":" + uri.getPort())
                + path;
    }

    private void rejectInvalidEnvelope(JsonNode snapshot) {
        if (snapshot == null || !snapshot.isObject()) {
            throw badRequest("deployment topology snapshot must be a JSON object");
        }
        try {
            byte[] serialized = objectMapper.writeValueAsBytes(snapshot);
            if (serialized.length > MAX_SNAPSHOT_BYTES) {
                throw badRequest("deployment topology snapshot exceeds 512 KiB");
            }
            String serializedText = new String(serialized, StandardCharsets.UTF_8);
            if (!TroubleshootingSecretRedactor.redact(serializedText).equals(serializedText)) {
                throw badRequest("deployment topology snapshot must not contain credentials");
            }
        } catch (MateClawException expected) {
            throw expected;
        } catch (Exception serializationFailure) {
            throw badRequest("deployment topology snapshot cannot be measured safely");
        }
    }

    private List<TopologyNode> parseNodes(JsonNode nodeArray) {
        List<TopologyNode> nodes = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (JsonNode rawNode : nodeArray) {
            if (!rawNode.isObject()) {
                throw badRequest("topology.nodes entries must be objects");
            }
            String key = safeIdentifier(requiredText(rawNode, "key", 128), "node key");
            if (!keys.add(key)) {
                throw badRequest("duplicate node key: " + key);
            }
            String label = optionalText(rawNode, "label", MAX_TEXT);
            if (label.isBlank()) {
                label = key;
            }
            safeDisplayText(label, "node label");
            String type = safeIdentifier(requiredText(rawNode, "type", 128), "node type");
            String targetUrl = optionalText(rawNode, "url", 2048);
            String explorerUrl = optionalText(rawNode, "guance_url", 4096);
            ProbeMetadata probe = null;
            if (!targetUrl.isBlank() || !explorerUrl.isBlank()) {
                if (targetUrl.isBlank() || explorerUrl.isBlank()) {
                    throw badRequest("node " + key
                            + " must provide both url and guance_url for a synthetic probe");
                }
                probe = parseProbe(key, targetUrl, explorerUrl);
            }
            nodes.add(new TopologyNode(key, label, type, probe));
        }
        return nodes;
    }

    private List<TopologyLink> parseLinks(JsonNode linkArray, List<TopologyNode> nodes) {
        Set<String> keys = nodes.stream()
                .map(TopologyNode::key)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<TopologyLink> links = new ArrayList<>();
        for (JsonNode rawLink : linkArray) {
            if (!rawLink.isObject()) {
                throw badRequest("topology.links entries must be objects");
            }
            String source = safeIdentifier(requiredText(rawLink, "source", 128), "link source");
            String target = safeIdentifier(requiredText(rawLink, "target", 128), "link target");
            if (!keys.contains(source) || !keys.contains(target)) {
                throw badRequest("topology link references an unknown node: "
                        + source + " -> " + target);
            }
            links.add(new TopologyLink(source, target));
        }
        return links;
    }

    private ProbeMetadata parseProbe(String nodeKey, String targetUrl, String explorerUrl) {
        String canonicalTarget = canonicalHttpUrl(targetUrl, "node " + nodeKey + " url");
        URI explorer = uri(explorerUrl, "node " + nodeKey + " guance_url");
        if (!httpScheme(explorer.getScheme())
                || explorer.getHost() == null
                || explorer.getUserInfo() != null
                || explorer.getRawFragment() != null) {
            throw badRequest("node " + nodeKey
                    + " guance_url must be an HTTP(S) URL without user info or fragment");
        }
        Map<String, List<String>> params = queryParameters(explorer, nodeKey);
        if (!params.keySet().containsAll(REQUIRED_GUANCE_QUERY_PARAMETERS)
                || !SUPPORTED_GUANCE_QUERY_PARAMETERS.containsAll(params.keySet())) {
            throw badRequest("node " + nodeKey
                    + " guance_url contains unsupported query parameters");
        }
        OPTIONAL_GUANCE_PRESENTATION_PARAMETERS.stream()
                .filter(params::containsKey)
                .forEach(name -> singleParameter(params, name, nodeKey));
        if (!"http_dial_testing".equals(singleParameter(params, "viewer_source", nodeKey))) {
            throw badRequest("node " + nodeKey + " guance_url has an unsupported viewer_source");
        }
        String workspaceRef = singleParameter(params, "w", nodeKey);
        if (!SAFE_IDENTIFIER.matcher(workspaceRef).matches()) {
            throw badRequest("node " + nodeKey + " guance_url has an invalid workspace reference");
        }
        String encodedQuery = singleParameter(params, "query", nodeKey);
        if (!encodedQuery.startsWith("b64-") || encodedQuery.length() <= 4) {
            throw badRequest("node " + nodeKey + " guance_url query must use b64 metadata");
        }
        String decoded = decodeUtf8Base64Url(encodedQuery.substring(4), nodeKey);
        if (!decoded.startsWith("name:") || decoded.length() <= 5) {
            throw badRequest("node " + nodeKey + " guance_url query must identify a probe name");
        }
        String probeName = decoded.substring(5).trim();
        safeDisplayText(probeName, "node " + nodeKey + " probe name");
        if (probeName.length() > 128) {
            throw badRequest("node " + nodeKey + " probe name exceeds 128 characters");
        }
        String window = window(singleParameter(params, "time", nodeKey), nodeKey);
        return new ProbeMetadata(canonicalTarget, probeName, window);
    }

    private Map<String, List<String>> queryParameters(URI uri, String nodeKey) {
        String rawQuery = uri.getRawQuery();
        if (rawQuery == null || rawQuery.isBlank()) {
            throw badRequest("node " + nodeKey + " guance_url has no query metadata");
        }
        Map<String, List<String>> parameters = new LinkedHashMap<>();
        for (String part : rawQuery.split("&", -1)) {
            int separator = part.indexOf('=');
            String rawKey = separator < 0 ? part : part.substring(0, separator);
            String rawValue = separator < 0 ? "" : part.substring(separator + 1);
            String key = urlDecode(rawKey, nodeKey);
            String value = urlDecode(rawValue, nodeKey);
            parameters.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
        }
        return parameters;
    }

    private String singleParameter(
            Map<String, List<String>> parameters,
            String name,
            String nodeKey) {
        List<String> values = parameters.get(name);
        if (values == null || values.size() != 1 || values.getFirst().isBlank()) {
            throw badRequest("node " + nodeKey + " guance_url requires one " + name);
        }
        return values.getFirst().trim();
    }

    private String window(String value, String nodeKey) {
        Matcher matcher = WINDOW.matcher(value);
        if (!matcher.matches()) {
            throw badRequest("node " + nodeKey + " guance_url has an invalid time window");
        }
        long amount = Long.parseLong(matcher.group(1));
        Duration duration = switch (matcher.group(2)) {
            case "s" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            case "d" -> Duration.ofDays(amount);
            default -> throw badRequest("node " + nodeKey + " guance_url has an invalid window unit");
        };
        if (duration.compareTo(Duration.ofDays(1)) > 0) {
            throw badRequest("node " + nodeKey + " guance_url window exceeds 24 hours");
        }
        return "-" + value;
    }

    private URI uri(String value, String field) {
        try {
            return URI.create(value.trim());
        } catch (RuntimeException invalidUri) {
            throw badRequest(field + " is not a valid URI");
        }
    }

    private boolean httpScheme(String scheme) {
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    private String decodeUtf8Base64Url(String value, String nodeKey) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(decoded))
                    .toString();
        } catch (IllegalArgumentException | CharacterCodingException invalid) {
            throw badRequest("node " + nodeKey + " guance_url contains invalid b64 metadata");
        }
    }

    private String urlDecode(String value, String nodeKey) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException invalidEncoding) {
            throw badRequest("node " + nodeKey + " guance_url contains invalid encoding");
        }
    }

    private JsonNode requiredObject(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isObject()) {
            throw badRequest(field + " must be an object");
        }
        return value;
    }

    private JsonNode requiredArray(JsonNode parent, String field, int maximum) {
        JsonNode value = parent.path(field);
        if (!value.isArray()) {
            throw badRequest(field + " must be an array");
        }
        if (value.size() > maximum) {
            throw badRequest(field + " exceeds " + maximum + " entries");
        }
        return value;
    }

    private Instant instant(JsonNode value, String field) {
        try {
            return Instant.parse(value.asText(""));
        } catch (RuntimeException invalidInstant) {
            throw badRequest(field + " must be an ISO-8601 instant");
        }
    }

    private String requiredText(JsonNode parent, String field, int maximum) {
        String value = optionalText(parent, field, maximum);
        if (value.isBlank()) {
            throw badRequest(field + " must not be blank");
        }
        return value;
    }

    private String optionalText(JsonNode parent, String field, int maximum) {
        JsonNode value = parent.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return "";
        }
        if (!value.isTextual()) {
            throw badRequest(field + " must be text");
        }
        String text = value.asText().trim();
        if (text.length() > maximum) {
            throw badRequest(field + " exceeds " + maximum + " characters");
        }
        return text;
    }

    private String safeIdentifier(String value, String field) {
        if (!SAFE_IDENTIFIER.matcher(value).matches()) {
            throw badRequest(field + " must be a safe resource identifier");
        }
        return value;
    }

    private void safeDisplayText(String value, String field) {
        if (value.isBlank()
                || value.chars().anyMatch(character -> Character.isISOControl(character))
                || !TroubleshootingSecretRedactor.redact(value).equals(value)) {
            throw badRequest(field + " is not safe display text");
        }
    }

    private MateClawException badRequest(String message) {
        return new MateClawException(
                "err.troubleshooting.deployment_topology_invalid",
                400,
                message);
    }

    record ParsedSnapshot(
            String schemaVersion,
            String system,
            String systemLabel,
            Instant exportedAt,
            List<TopologyNode> nodes,
            List<TopologyLink> links) {
    }

    record TopologyNode(
            String key,
            String label,
            String type,
            ProbeMetadata probe) {
    }

    record ProbeMetadata(
            String targetUrl,
            String probeName,
            String window) {
    }

    record TopologyLink(String source, String target) {
    }
}
