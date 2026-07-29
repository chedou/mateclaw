package vip.mate.troubleshooting.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.IncidentContext;

import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Deterministic replay source for sanitized regression fixtures. */
public final class RecordedReplayAdapter implements EvidenceSourceAdapter {

    private static final String PLATFORM = "recorded-replay";
    private final EvidenceProperties.RecordedReplay config;
    private final Clock clock;
    private final Map<ReplayKey, ReplayRecord> records;
    private final EvidenceSourceHealth health;

    RecordedReplayAdapter(
            EvidenceProperties.RecordedReplay config,
            ObjectMapper objectMapper,
            Resource resource,
            Clock clock) {
        this.config = config == null ? new EvidenceProperties.RecordedReplay() : config;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        if (!this.config.isEnabled()) {
            this.records = Map.of();
            this.health = new EvidenceSourceHealth(
                    PLATFORM, EvidenceSourceHealth.Status.DISABLED, false, "adapter disabled");
            return;
        }

        Map<ReplayKey, ReplayRecord> loaded;
        EvidenceSourceHealth loadedHealth;
        try (InputStream input = resource.getInputStream()) {
            loaded = load(objectMapper.readTree(input), objectMapper);
            loadedHealth = new EvidenceSourceHealth(
                    PLATFORM, EvidenceSourceHealth.Status.READY, false,
                    "sanitized replay catalog loaded: " + loaded.size() + " records");
        } catch (Exception failure) {
            loaded = Map.of();
            loadedHealth = new EvidenceSourceHealth(
                    PLATFORM, EvidenceSourceHealth.Status.DEGRADED, false,
                    "replay catalog unavailable: " + failure.getClass().getSimpleName());
        }
        this.records = Map.copyOf(loaded);
        this.health = loadedHealth;
    }

    @Override
    public String platform() {
        return PLATFORM;
    }

    @Override
    public boolean supports(String signalKind) {
        if (!config.isEnabled() || health.status() != EvidenceSourceHealth.Status.READY) {
            return false;
        }
        String normalized = normalize(signalKind);
        return records.keySet().stream().anyMatch(key -> key.signalKind().equals(normalized));
    }

    @Override
    public EvidenceResult collect(
            long workspaceId,
            EvidenceRequest request,
            IncidentContext incident) {
        if (workspaceId <= 0) {
            throw new IllegalArgumentException("workspaceId must be positive");
        }
        if (request == null || incident == null) {
            throw new IllegalArgumentException("request and incident are required");
        }
        ReplayRecord replay = records.get(new ReplayKey(
                normalize(incident.system()),
                normalize(incident.errorCode()),
                normalize(incident.service()),
                replayRequestId(request.requestId()),
                normalize(request.signalKind())));
        if (replay == null || !matchesRequestTarget(request, replay)) {
            return missing(request);
        }
        return new EvidenceResult(
                request.requestId(), replay.namespace(), replay.query(), replay.status(),
                replay.summary(), replay.observed(), replay.source(), replay.collectedAt());
    }

    @Override
    public EvidenceSourceHealth health() {
        return health;
    }

    private Map<ReplayKey, ReplayRecord> load(JsonNode root, ObjectMapper objectMapper) {
        if (root.path("version").asInt(-1) != 1 || !root.path("records").isArray()) {
            throw new IllegalArgumentException("unsupported replay catalog");
        }
        Map<ReplayKey, ReplayRecord> loaded = new LinkedHashMap<>();
        for (JsonNode item : root.path("records")) {
            ReplayKey key = new ReplayKey(
                    required(item, "system"),
                    optional(item, "errorCode"),
                    required(item, "service"),
                    required(item, "requestId"),
                    required(item, "signalKind"));
            EvidenceStatus status = EvidenceStatus.valueOf(
                    requiredRaw(item, "status").toUpperCase(Locale.ROOT));
            Map<String, String> expectedTarget = target(item.path("target"));
            if ("log_search".equals(key.signalKind())
                    && !expectedTarget.keySet().equals(java.util.Set.of("search_term"))) {
                throw new IllegalArgumentException(
                        "log_search replay record must bind search_term");
            }
            Map<String, Object> canonicalObserved = observed(
                    item.path("observed"), objectMapper);
            if (status == EvidenceStatus.MISSING
                    || !CanonicalEvidenceSchema.isValid(key.signalKind(), canonicalObserved)) {
                throw new IllegalArgumentException("replay record violates canonical schema");
            }
            ReplayRecord value = new ReplayRecord(
                    requiredRaw(item, "namespace"),
                    item.path("query").asText(""),
                    status,
                    item.path("summary").asText(""),
                    canonicalObserved,
                    expectedTarget,
                    item.path("source").asText("recorded-replay"),
                    instant(item.path("collectedAt").asText(null)));
            if (loaded.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("duplicate replay key");
            }
        }
        return loaded;
    }

    private boolean matchesRequestTarget(EvidenceRequest request, ReplayRecord replay) {
        for (Map.Entry<String, String> expected : replay.expectedTarget().entrySet()) {
            Object actual = request.target().get(expected.getKey());
            if (actual == null
                    || !expected.getValue().equals(String.valueOf(actual).trim())) {
                return false;
            }
        }
        if (!"log_trace_bundle".equals(normalize(request.signalKind()))) {
            return true;
        }
        Object expected = request.target().get("ps_id");
        Object actual = replay.observed().get("ps_id");
        return expected != null
                && actual != null
                && String.valueOf(expected).trim().equals(String.valueOf(actual).trim());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> observed(JsonNode node, ObjectMapper objectMapper) {
        if (!node.isObject()) {
            return Map.of();
        }
        return objectMapper.convertValue(node, LinkedHashMap.class);
    }

    private Map<String, String> target(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("replay target must be an object");
        }
        Map<String, String> target = new LinkedHashMap<>();
        node.fields().forEachRemaining(field -> {
            String key = field.getKey() == null ? "" : field.getKey().trim();
            JsonNode valueNode = field.getValue();
            String value = valueNode == null || !valueNode.isValueNode()
                    ? ""
                    : valueNode.asText("").trim();
            if (key.isEmpty() || value.isEmpty() || target.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("replay target contains an invalid field");
            }
        });
        return Map.copyOf(target);
    }

    private String required(JsonNode item, String field) {
        return normalize(requiredRaw(item, field));
    }

    private String optional(JsonNode item, String field) {
        return normalize(item.path(field).asText(null));
    }

    private String requiredRaw(JsonNode item, String field) {
        String value = item.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("replay record missing " + field);
        }
        return value.trim();
    }

    private Instant instant(String value) {
        return value == null || value.isBlank() ? Instant.now(clock) : Instant.parse(value);
    }

    private EvidenceResult missing(EvidenceRequest request) {
        return new EvidenceResult(
                request.requestId(), "UNKNOWN", "", EvidenceStatus.MISSING,
                "no matching sanitized replay record", Map.of(),
                "recorded-replay:missing", Instant.now(clock));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String replayRequestId(String requestId) {
        String normalized = normalize(requestId);
        // Online and synthesis use the same fixture facts through explicit,
        // server-owned aliases. Unknown ids remain exact misses.
        return normalize(EvidenceSpineStage.replayCatalogRequestId(normalized));
    }

    private record ReplayKey(
            String system,
            String errorCode,
            String service,
            String requestId,
            String signalKind) {
    }

    private record ReplayRecord(
            String namespace,
            String query,
            EvidenceStatus status,
            String summary,
            Map<String, Object> observed,
            Map<String, String> expectedTarget,
            String source,
            Instant collectedAt) {
    }
}
