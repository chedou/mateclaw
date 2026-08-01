package vip.mate.troubleshooting.evidence;

import org.springframework.stereotype.Service;
import vip.mate.troubleshooting.TroubleshootingSecretRedactor;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Produces a secret-free identity for the exact Guance configuration an owner
 * accepted during T7.
 *
 * <p>The digest includes the endpoint, route, query template, row budget and
 * field aliases, but never exposes those values. Runtime credentials are
 * deliberately excluded so key rotation does not invalidate a field-level
 * acceptance.</p>
 */
@Service
public class GuanceBindingFingerprintService {

    private static final Pattern SAFE_SCOPE =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}");
    private static final List<String> CORE_SIGNALS =
            List.of("log_search", "log_trace_bundle");
    private static final String CONTRACT = "guance-binding/v1";

    private final EvidenceProperties properties;

    public GuanceBindingFingerprintService(EvidenceProperties properties) {
        this.properties = properties == null ? new EvidenceProperties() : properties;
    }

    /**
     * Returns no snapshot when the exact asset, core route or referenced
     * binding is absent or ambiguous.
     */
    public Optional<Snapshot> current(
            long workspaceId,
            String system,
            String service) {
        validateWorkspace(workspaceId);
        String safeSystem = safeScope(system, "system");
        String safeService = safeScope(service, "service");
        String normalizedSystem = normalize(safeSystem);
        String normalizedService = normalize(safeService);

        List<EvidenceProperties.AssetBinding> assets =
                properties.getGuance().getAssetBindings() == null
                        ? List.of()
                        : properties.getGuance().getAssetBindings().stream()
                                .filter(candidate -> candidate.getWorkspaceId() == workspaceId)
                                .filter(candidate -> normalizedSystem.equals(
                                        normalize(candidate.getSystem())))
                                .filter(candidate -> normalizedService.equals(
                                        normalize(candidate.getService())))
                                .toList();
        if (assets.size() != 1) {
            return Optional.empty();
        }

        Map<String, String> signalBindings =
                normalizedStringMap(assets.getFirst().getSignalBindings());
        if (signalBindings == null
                || CORE_SIGNALS.stream().anyMatch(signal -> !signalBindings.containsKey(signal))) {
            return Optional.empty();
        }

        Map<String, List<String>> routes = systemRoutes(normalizedSystem);
        if (routes == null || CORE_SIGNALS.stream().anyMatch(
                signal -> !routesExactlyOnceToGuance(routes.get(signal)))) {
            return Optional.empty();
        }

        Map<String, EvidenceProperties.Binding> configuredBindings =
                normalizedBindingMap(properties.getGuance().getBindings());
        if (configuredBindings == null
                || signalBindings.values().stream()
                        .anyMatch(reference -> !configuredBindings.containsKey(
                                normalize(reference)))) {
            return Optional.empty();
        }

        Digest digest = new Digest();
        digest.add("contract", CONTRACT);
        digest.add("workspaceId", Long.toString(workspaceId));
        digest.add("system", normalizedSystem);
        digest.add("service", normalizedService);
        digest.add("baseUrl", trim(properties.getGuance().getBaseUrl()));
        digest.add("queryPath", trim(properties.getGuance().getQueryPath()));
        digest.add("allowInsecureHttp",
                Boolean.toString(properties.getGuance().isAllowInsecureHttp()));
        digest.add("timeout", String.valueOf(properties.getGuance().getTimeout()));

        routes.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    digest.add("route.signal", entry.getKey());
                    List<String> sources =
                            entry.getValue() == null ? List.of() : entry.getValue();
                    digest.add("route.count", Integer.toString(sources.size()));
                    for (String source : sources) {
                        digest.add("route.source", normalize(source));
                    }
                });

        signalBindings.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String bindingRef = normalize(entry.getValue());
                    EvidenceProperties.Binding binding =
                            configuredBindings.get(bindingRef);
                    digest.add("asset.signal", entry.getKey());
                    digest.add("asset.binding", bindingRef);
                    digest.add("binding.namespace", trim(binding.getNamespace()));
                    digest.add("binding.maxRows", Integer.toString(binding.getMaxRows()));
                    digest.add("binding.queryTemplate", trim(binding.getQueryTemplate()));
                    List<String> queryTemplates = binding.getQueryTemplates() == null
                            ? List.of()
                            : binding.getQueryTemplates();
                    digest.add("binding.queryTemplates.count",
                            Integer.toString(queryTemplates.size()));
                    queryTemplates.forEach(template ->
                            digest.add("binding.queryTemplates.item", trim(template)));
                    EvidenceProperties.QueryOptions queryOptions =
                            binding.getQueryOptions();
                    if (queryOptions != null) {
                        digest.add("binding.query.maxPointCount",
                                Integer.toString(queryOptions.getMaxPointCount()));
                        digest.add("binding.query.interval",
                                Integer.toString(queryOptions.getInterval()));
                        digest.add("binding.query.alignTime",
                                Boolean.toString(queryOptions.isAlignTime()));
                        digest.add("binding.query.seriesLimit",
                                Integer.toString(queryOptions.getSeriesLimit()));
                        digest.add("binding.query.disableSampling",
                                Boolean.toString(queryOptions.isDisableSampling()));
                        digest.add("binding.query.timeZone",
                                trim(queryOptions.getTimeZone()));
                    }
                    Map<String, String> aliases = binding.getFieldAliases() == null
                            ? Map.of()
                            : binding.getFieldAliases();
                    aliases.entrySet().stream()
                            .sorted(Map.Entry.comparingByKey())
                            .forEach(alias -> {
                                digest.add("binding.alias.source", trim(alias.getKey()));
                                digest.add("binding.alias.canonical", trim(alias.getValue()));
                            });
                    Map<String, String> constants = binding.getConstantFields() == null
                            ? Map.of()
                            : binding.getConstantFields();
                    constants.entrySet().stream()
                            .sorted(Map.Entry.comparingByKey())
                            .forEach(constant -> {
                                digest.add("binding.constant.canonical", trim(constant.getKey()));
                                digest.add("binding.constant.value", trim(constant.getValue()));
                            });
                });

        String scopeKey = digestScopeKey(
                workspaceId, normalizedSystem, normalizedService);
        return Optional.of(new Snapshot(
                scopeKey,
                digest.hex(),
                safeSystem,
                safeService));
    }

    public String scopeKey(long workspaceId, String system, String service) {
        validateWorkspace(workspaceId);
        return digestScopeKey(
                workspaceId,
                normalize(safeScope(system, "system")),
                normalize(safeScope(service, "service")));
    }

    private Map<String, List<String>> systemRoutes(String normalizedSystem) {
        Map<String, Map<String, List<String>>> configured = properties.getRoutes();
        if (configured == null) {
            return null;
        }
        List<Map.Entry<String, Map<String, List<String>>>> matches =
                configured.entrySet().stream()
                        .filter(entry -> normalizedSystem.equals(normalize(entry.getKey())))
                        .toList();
        if (matches.size() != 1 || matches.getFirst().getValue() == null) {
            return null;
        }
        Map<String, List<String>> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry :
                matches.getFirst().getValue().entrySet()) {
            String key = normalize(entry.getKey());
            if (key.isBlank() || normalized.putIfAbsent(key, entry.getValue()) != null) {
                return null;
            }
        }
        return normalized;
    }

    private boolean routesExactlyOnceToGuance(List<String> sources) {
        if (sources == null) {
            return false;
        }
        return sources.stream()
                .filter(source -> "guance".equals(normalize(source)))
                .count() == 1;
    }

    private Map<String, String> normalizedStringMap(Map<String, String> input) {
        if (input == null) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : input.entrySet()) {
            String key = normalize(entry.getKey());
            String value = trim(entry.getValue());
            if (key.isBlank()
                    || value.isBlank()
                    || normalized.putIfAbsent(key, value) != null) {
                return null;
            }
        }
        return normalized;
    }

    private Map<String, EvidenceProperties.Binding> normalizedBindingMap(
            Map<String, EvidenceProperties.Binding> input) {
        if (input == null) {
            return Map.of();
        }
        Map<String, EvidenceProperties.Binding> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, EvidenceProperties.Binding> entry : input.entrySet()) {
            String key = normalize(entry.getKey());
            if (key.isBlank()
                    || entry.getValue() == null
                    || normalized.putIfAbsent(key, entry.getValue()) != null) {
                return null;
            }
        }
        return normalized;
    }

    private String digestScopeKey(
            long workspaceId,
            String normalizedSystem,
            String normalizedService) {
        Digest digest = new Digest();
        digest.add("contract", "guance-acceptance-scope/v1");
        digest.add("workspaceId", Long.toString(workspaceId));
        digest.add("system", normalizedSystem);
        digest.add("service", normalizedService);
        return digest.hex();
    }

    private void validateWorkspace(long workspaceId) {
        if (workspaceId <= 0) {
            throw new IllegalArgumentException("workspaceId must be positive");
        }
    }

    private String safeScope(String value, String field) {
        String normalized = trim(value);
        if (!SAFE_SCOPE.matcher(normalized).matches()
                || !TroubleshootingSecretRedactor.redact(normalized).equals(normalized)) {
            throw new IllegalArgumentException(
                    field + " must be a safe resource identifier");
        }
        return normalized;
    }

    private String normalize(String value) {
        return trim(value).toLowerCase(Locale.ROOT);
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    public record Snapshot(
            String scopeKey,
            String bindingFingerprint,
            String system,
            String service) {

        public Snapshot {
            if (scopeKey == null
                    || !scopeKey.matches("[a-f0-9]{64}")
                    || bindingFingerprint == null
                    || !bindingFingerprint.matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException(
                        "scope and binding fingerprints must be SHA-256 hex");
            }
            system = system == null ? "" : system.trim();
            service = service == null ? "" : service.trim();
        }
    }

    private static final class Digest {

        private final MessageDigest delegate;

        private Digest() {
            try {
                delegate = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 unavailable", impossible);
            }
        }

        private void add(String label, String value) {
            update(label);
            update(value == null ? "" : value);
        }

        private void update(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            delegate.update(ByteBuffer.allocate(Integer.BYTES)
                    .putInt(bytes.length)
                    .array());
            delegate.update(bytes);
        }

        private String hex() {
            return HexFormat.of().formatHex(delegate.digest());
        }
    }
}
