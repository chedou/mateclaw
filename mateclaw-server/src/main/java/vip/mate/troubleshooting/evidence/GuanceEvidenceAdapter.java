package vip.mate.troubleshooting.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.IncidentContext;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read-only Guance DQL adapter using the official query-data API shape. */
public final class GuanceEvidenceAdapter implements EvidenceSourceAdapter {

    private static final Logger log = LoggerFactory.getLogger(GuanceEvidenceAdapter.class);
    private static final String PLATFORM = "guance";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z0-9_]+)}}");
    private static final Pattern SAFE_VALUE =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}");
    private static final Pattern WINDOW = Pattern.compile("-?([1-9][0-9]*)([smhd])");
    private static final int MAX_BOUND_ROWS = 500;

    private final EvidenceProperties.Guance config;
    private final ObjectMapper objectMapper;
    private final EvidenceHttpTransport transport;
    private final Clock clock;
    private final AtomicBoolean reachable = new AtomicBoolean();

    GuanceEvidenceAdapter(
            EvidenceProperties.Guance config,
            ObjectMapper objectMapper,
            EvidenceHttpTransport transport,
            Clock clock) {
        this.config = config == null ? new EvidenceProperties.Guance() : config;
        this.objectMapper = objectMapper;
        this.transport = transport;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public String platform() {
        return PLATFORM;
    }

    @Override
    public boolean supports(String signalKind) {
        return hasAuthorizedBinding(signalKind);
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
        EvidenceProperties.Binding binding = authorizedBinding(
                workspaceId, incident.system(), incident.service(), request.signalKind());
        if (binding == null) {
            return missing(request, "workspace asset or signal binding is not authorized");
        }
        if (!baseConfigured()) {
            return missing(request, "adapter disabled or base configuration missing");
        }

        try {
            WindowRange window = window(request.window(), incident.occurredAt());
            String query = render(binding.getQueryTemplate(), request, incident, window.expression());
            String body = requestBody(query, window, binding);
            EvidenceHttpTransport.Response response = transport.postJson(
                    queryUri(),
                    Map.of(
                            "Content-Type", "application/json",
                            "DF-API-KEY", config.getApiKey()),
                    body,
                    timeout());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return missing(request, "Guance returned HTTP " + response.statusCode());
            }

            Map<String, Object> observed = normalize(
                    response.body(), binding, request);
            if (observed.isEmpty()) {
                return missing(request, "Guance returned no canonical evidence rows");
            }
            reachable.set(true);
            return new EvidenceResult(
                    request.requestId(), namespace(binding), "", EvidenceStatus.NORMAL,
                    summary(binding, request), observed,
                    "guance:" + normalizeKey(request.signalKind()), Instant.now(clock));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return missing(request, "Guance collection interrupted");
        } catch (Exception failure) {
            log.warn("Guance evidence collection failed for request {} ({})",
                    request.requestId(), failure.getClass().getSimpleName());
            return missing(request, "Guance collection failed: "
                    + failure.getClass().getSimpleName());
        }
    }

    @Override
    public EvidenceSourceHealth health() {
        if (!config.isEnabled()) {
            return new EvidenceSourceHealth(
                    PLATFORM, EvidenceSourceHealth.Status.DISABLED, false, "adapter disabled");
        }
        if (!hasAnyAuthorizedBinding()) {
            return new EvidenceSourceHealth(
                    PLATFORM, EvidenceSourceHealth.Status.DEGRADED, false,
                    "explicit workspace asset authorization missing or invalid");
        }
        if (!baseConfigured()) {
            return new EvidenceSourceHealth(
                    PLATFORM, EvidenceSourceHealth.Status.DEGRADED, false,
                    "base URL, API key, or bindings missing");
        }
        if (reachable.get()) {
            return new EvidenceSourceHealth(
                    PLATFORM, EvidenceSourceHealth.Status.READY, false,
                    "API reachable; query semantics are not live-verified");
        }
        return new EvidenceSourceHealth(
                PLATFORM, EvidenceSourceHealth.Status.DEGRADED, false,
                "authorized but not live-verified");
    }

    private boolean baseConfigured() {
        return config.isEnabled()
                && present(config.getBaseUrl())
                && present(config.getApiKey())
                && config.getBindings() != null
                && !config.getBindings().isEmpty();
    }

    private boolean hasAnyAuthorizedBinding() {
        return assetBindings().stream()
                .filter(this::hasUniqueAssetScope)
                .anyMatch(asset -> asset.getSignalBindings() != null
                        && asset.getSignalBindings().keySet().stream()
                                .anyMatch(signal -> bindingFor(asset, signal) != null));
    }

    private boolean hasAuthorizedBinding(String signalKind) {
        if (!present(signalKind)) {
            return false;
        }
        return assetBindings().stream()
                .filter(this::hasUniqueAssetScope)
                .anyMatch(asset -> bindingFor(asset, signalKind) != null);
    }

    private EvidenceProperties.Binding authorizedBinding(
            long workspaceId,
            String system,
            String service,
            String signalKind) {
        List<EvidenceProperties.AssetBinding> matches = assetBindings().stream()
                .filter(asset -> asset != null
                        && asset.getWorkspaceId() == workspaceId
                        && normalizeKey(asset.getSystem()).equals(normalizeKey(system))
                        && normalizeKey(asset.getService()).equals(normalizeKey(service)))
                .toList();
        if (matches.size() != 1) {
            return null;
        }
        return bindingFor(matches.getFirst(), signalKind);
    }

    private EvidenceProperties.Binding bindingFor(
            EvidenceProperties.AssetBinding asset,
            String signalKind) {
        if (asset == null || !present(signalKind) || asset.getSignalBindings() == null) {
            return null;
        }
        String wantedSignal = normalizeKey(signalKind);
        List<Map.Entry<String, String>> signalEntries = asset.getSignalBindings().entrySet().stream()
                .filter(entry -> normalizeKey(entry.getKey()).equals(wantedSignal))
                .toList();
        if (signalEntries.size() != 1
                || !present(signalEntries.getFirst().getValue())
                || config.getBindings() == null) {
            return null;
        }
        String wantedBinding = normalizeKey(signalEntries.getFirst().getValue());
        List<Map.Entry<String, EvidenceProperties.Binding>> bindingEntries =
                config.getBindings().entrySet().stream()
                .filter(entry -> normalizeKey(entry.getKey()).equals(wantedBinding))
                .toList();
        if (bindingEntries.size() != 1) {
            return null;
        }
        EvidenceProperties.Binding binding = bindingEntries.getFirst().getValue();
        return validBinding(signalKind, binding) ? binding : null;
    }

    private boolean hasUniqueAssetScope(EvidenceProperties.AssetBinding candidate) {
        if (candidate == null
                || candidate.getWorkspaceId() <= 0
                || !present(candidate.getSystem())
                || !present(candidate.getService())) {
            return false;
        }
        long matches = assetBindings().stream()
                .filter(asset -> asset != null
                        && asset.getWorkspaceId() == candidate.getWorkspaceId()
                        && normalizeKey(asset.getSystem()).equals(normalizeKey(candidate.getSystem()))
                        && normalizeKey(asset.getService()).equals(normalizeKey(candidate.getService())))
                .count();
        return matches == 1;
    }

    private List<EvidenceProperties.AssetBinding> assetBindings() {
        List<EvidenceProperties.AssetBinding> configured = config.getAssetBindings();
        return configured == null ? List.of() : configured;
    }

    private boolean validBinding(String signalKind, EvidenceProperties.Binding binding) {
        if (!CanonicalEvidenceSchema.supports(signalKind)
                || binding == null
                || !present(binding.getQueryTemplate())
                || binding.getMaxRows() < 1
                || binding.getMaxRows() > MAX_BOUND_ROWS) {
            return false;
        }
        Set<String> canonicalFields = CanonicalEvidenceSchema.fields(signalKind);
        Map<String, String> aliases = binding.getFieldAliases();
        return aliases == null || aliases.entrySet().stream().allMatch(entry ->
                present(entry.getKey())
                        && present(entry.getValue())
                        && canonicalFields.contains(entry.getValue()));
    }

    private String requestBody(
            String query,
            WindowRange window,
            EvidenceProperties.Binding binding) throws Exception {
        Map<String, Object> querySpec = new LinkedHashMap<>();
        querySpec.put("q", query);
        querySpec.put("timeRange", List.of(window.start().toEpochMilli(), window.end().toEpochMilli()));
        querySpec.put("limit", binding.getMaxRows() + 1);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("qtype", "dql");
        item.put("query", querySpec);
        return objectMapper.writeValueAsString(Map.of("queries", List.of(item)));
    }

    private String render(
            String template,
            EvidenceRequest request,
            IncidentContext incident,
            String windowExpression) {
        Map<String, Object> values = new LinkedHashMap<>(request.target());
        values.put("incident_id", incident.incidentId());
        values.put("system", incident.system());
        values.put("service", incident.service());
        values.put("error_code", incident.errorCode());
        values.put("trace_id", incident.traceId());
        values.put("window", windowExpression);

        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            Object raw = values.get(key);
            if (raw == null) {
                throw new IllegalArgumentException("missing query template value: " + key);
            }
            String value = String.valueOf(raw).trim();
            if ("window".equals(key)) {
                if (!WINDOW.matcher(value).matches()) {
                    throw new IllegalArgumentException("unsafe window value");
                }
            } else if (!SAFE_VALUE.matcher(value).matches()) {
                throw new IllegalArgumentException("unsafe query template value: " + key);
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(rendered);
        if (PLACEHOLDER.matcher(rendered).find()) {
            throw new IllegalArgumentException("unresolved query template placeholder");
        }
        return rendered.toString();
    }

    private WindowRange window(String expression, Instant occurredAt) {
        String normalized = expression == null || expression.isBlank() ? "-15m" : expression.trim();
        Matcher matcher = WINDOW.matcher(normalized);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("unsupported evidence window: " + normalized);
        }
        long amount = Long.parseLong(matcher.group(1));
        Duration duration = switch (matcher.group(2)) {
            case "s" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            case "d" -> Duration.ofDays(amount);
            default -> throw new IllegalArgumentException("unsupported evidence window unit");
        };
        Instant end = occurredAt == null ? Instant.now(clock) : occurredAt;
        return new WindowRange(normalized.startsWith("-") ? normalized : "-" + normalized,
                end.minus(duration), end);
    }

    private Map<String, Object> normalize(
            String responseBody,
            EvidenceProperties.Binding binding,
            EvidenceRequest request) throws Exception {
        String signalKind = request.signalKind();
        JsonNode root = objectMapper.readTree(responseBody);
        if (root.path("code").asInt(-1) != 200 || !root.path("success").asBoolean(false)) {
            return Map.of();
        }

        List<JsonNode> populatedSeries = new ArrayList<>();
        JsonNode data = root.path("content").path("data");
        if (!data.isArray()) {
            return Map.of();
        }
        for (JsonNode dataset : data) {
            JsonNode series = dataset.path("series");
            if (!series.isArray()) {
                continue;
            }
            for (JsonNode item : series) {
                if (hasRows(item)) {
                    populatedSeries.add(item);
                }
            }
        }
        if (populatedSeries.size() != 1) {
            return Map.of();
        }
        JsonNode series = populatedSeries.getFirst();
        if (CanonicalEvidenceSchema.isRowSet(signalKind)) {
            return normalizeRowSet(
                    series, binding, signalKind, targetValue(request, "ps_id"));
        }
        if (series.path("values").size() > binding.getMaxRows()) {
            return Map.of();
        }
        Map<String, Object> row = latestCanonicalRow(series, binding, signalKind);
        return CanonicalEvidenceSchema.isValid(signalKind, row) ? row : Map.of();
    }

    private boolean hasRows(JsonNode series) {
        JsonNode values = series.path("values");
        return values.isArray() && !values.isEmpty();
    }

    private Map<String, Object> latestCanonicalRow(
            JsonNode series,
            EvidenceProperties.Binding binding,
            String signalKind) {
        JsonNode columns = series.path("columns");
        JsonNode values = series.path("values");
        if (!columns.isArray() || !values.isArray() || values.isEmpty()) {
            return Map.of();
        }
        JsonNode row = latestRow(columns, values);
        if (row == null || !row.isArray()) {
            return Map.of();
        }

        return canonicalRow(columns, row, binding, signalKind);
    }

    private Map<String, Object> normalizeRowSet(
            JsonNode series,
            EvidenceProperties.Binding binding,
            String signalKind,
            String expectedPsId) {
        JsonNode columns = series.path("columns");
        JsonNode values = series.path("values");
        if (!columns.isArray()
                || !values.isArray()
                || values.isEmpty()
                || values.size() > binding.getMaxRows()) {
            return Map.of();
        }

        String psId = null;
        List<Map<String, Object>> entries = new ArrayList<>();
        for (JsonNode row : values) {
            if (!row.isArray()) {
                return Map.of();
            }
            Map<String, Object> canonical = canonicalRow(columns, row, binding, signalKind);
            if (!CanonicalEvidenceSchema.isValidRow(signalKind, canonical)) {
                return Map.of();
            }
            String rowPsId = String.valueOf(canonical.get("ps_id"));
            if (psId != null && !psId.equals(rowPsId)) {
                return Map.of();
            }
            psId = rowPsId;
            Map<String, Object> entry = new LinkedHashMap<>(canonical);
            entry.remove("ps_id");
            entries.add(entry);
        }
        if (!present(expectedPsId) || !expectedPsId.equals(psId)) {
            return Map.of();
        }
        entries.sort(Comparator
                .comparing((Map<String, Object> entry) -> number(entry.get("timestamp")))
                .thenComparing(entry -> String.valueOf(entry.get("service")))
                .thenComparing(entry -> String.valueOf(entry.get("message"))));

        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("ps_id", psId);
        observed.put("entries", List.copyOf(entries));
        return CanonicalEvidenceSchema.isValid(signalKind, observed) ? observed : Map.of();
    }

    private Map<String, Object> canonicalRow(
            JsonNode columns,
            JsonNode row,
            EvidenceProperties.Binding binding,
            String signalKind) {

        Map<String, Object> observed = new LinkedHashMap<>();
        Map<String, String> aliases = binding.getFieldAliases() == null
                ? Map.of()
                : binding.getFieldAliases();
        Set<String> canonicalFields = CanonicalEvidenceSchema.fields(signalKind);
        for (int index = 0; index < columns.size() && index < row.size(); index++) {
            String sourceField = columns.get(index).asText();
            String canonicalField = aliases.getOrDefault(sourceField, sourceField);
            if (!present(canonicalField)
                    || !canonicalFields.contains(canonicalField)
                    || row.get(index).isNull()) {
                continue;
            }
            Object value = objectMapper.convertValue(row.get(index), Object.class);
            if (observed.putIfAbsent(canonicalField, value) != null) {
                return Map.of();
            }
        }
        return observed;
    }

    private BigDecimal number(Object value) {
        return new BigDecimal(String.valueOf(value));
    }

    private String targetValue(EvidenceRequest request, String key) {
        Object value = request.target().get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    private JsonNode latestRow(JsonNode columns, JsonNode values) {
        if (values.size() == 1) {
            return values.get(0);
        }
        int timeIndex = -1;
        for (int index = 0; index < columns.size(); index++) {
            if ("time".equalsIgnoreCase(columns.get(index).asText())) {
                timeIndex = index;
                break;
            }
        }
        if (timeIndex < 0) {
            return null;
        }

        JsonNode latest = null;
        BigDecimal latestTimestamp = null;
        int latestCount = 0;
        for (JsonNode candidate : values) {
            if (!candidate.isArray() || candidate.size() <= timeIndex) {
                return null;
            }
            JsonNode rawTimestamp = candidate.get(timeIndex);
            if (rawTimestamp == null || !rawTimestamp.isNumber()) {
                return null;
            }
            BigDecimal timestamp = rawTimestamp.decimalValue();
            if (latestTimestamp == null || timestamp.compareTo(latestTimestamp) > 0) {
                latestTimestamp = timestamp;
                latest = candidate;
                latestCount = 1;
            } else if (timestamp.compareTo(latestTimestamp) == 0) {
                latestCount++;
            }
        }
        return latestCount == 1 ? latest : null;
    }

    private URI queryUri() {
        String base = config.getBaseUrl().trim().replaceAll("/+$", "");
        String path = present(config.getQueryPath())
                ? config.getQueryPath().trim()
                : "/api/v1/df/query_data_v1";
        URI uri = URI.create(base + (path.startsWith("/") ? path : "/" + path));
        if (!"https".equalsIgnoreCase(uri.getScheme())
                && !(config.isAllowInsecureHttp() && "http".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException(
                    "Guance base URL must use HTTPS unless insecure HTTP is explicitly allowed");
        }
        return uri;
    }

    private Duration timeout() {
        Duration configured = config.getTimeout();
        return configured == null || configured.isZero() || configured.isNegative()
                ? Duration.ofSeconds(5)
                : configured;
    }

    private EvidenceResult missing(EvidenceRequest request, String summary) {
        return new EvidenceResult(
                request.requestId(), "UNKNOWN", "", EvidenceStatus.MISSING, summary,
                Map.of(), "guance:unavailable", Instant.now(clock));
    }

    private String namespace(EvidenceProperties.Binding binding) {
        return present(binding.getNamespace()) ? binding.getNamespace().trim() : "UNKNOWN";
    }

    private String summary(EvidenceProperties.Binding binding, EvidenceRequest request) {
        return present(binding.getSummary()) ? binding.getSummary().trim() : request.purpose();
    }

    private boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private String normalizeKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private record WindowRange(String expression, Instant start, Instant end) {
    }
}
