package vip.mate.troubleshooting.evidence;

import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Produces a secret-free identity for the exact Guance configuration an owner
 * accepted during T7.
 *
 * <p>The digest includes the settings origin, credential identity, endpoint,
 * route, query template, row budget and field aliases, but never exposes any
 * of those values. Credential rotation deliberately invalidates an acceptance:
 * a formal invocation may only use the credential scope that was reviewed.</p>
 */
@Service
public class GuanceBindingFingerprintService {

    private static final Pattern SAFE_SCOPE =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}");
    private static final String CONTRACT = "guance-binding/v2";

    private final EvidenceProperties properties;
    private final WorkspaceObservabilityAssets workspaceAssets;
    private final WorkspaceEvidenceContracts workspaceContracts;
    private final WorkspaceEvidenceRoutes workspaceRoutes;
    /**
     * Nullable; when present the endpoint half of the digest comes from the
     * workspace row instead of application.yml. Without this an owner could
     * repoint Guance from the UI and a T7 acceptance taken against the old
     * endpoint would still compare equal — the acceptance would silently
     * outlive the configuration it certified.
     */
    private final WorkspaceEvidenceSettingsService workspaceSettings;

    public GuanceBindingFingerprintService(EvidenceProperties properties) {
        this(
                properties,
                WorkspaceObservabilityAssets.NONE,
                WorkspaceEvidenceContracts.NONE,
                WorkspaceEvidenceRoutes.NONE,
                null);
    }

    public GuanceBindingFingerprintService(
            EvidenceProperties properties,
            WorkspaceObservabilityAssets workspaceAssets) {
        this(
                properties,
                workspaceAssets,
                WorkspaceEvidenceContracts.NONE,
                WorkspaceEvidenceRoutes.NONE,
                null);
    }

    public GuanceBindingFingerprintService(
            EvidenceProperties properties,
            WorkspaceObservabilityAssets workspaceAssets,
            WorkspaceEvidenceSettingsService workspaceSettings) {
        this(
                properties,
                workspaceAssets,
                WorkspaceEvidenceContracts.NONE,
                WorkspaceEvidenceRoutes.NONE,
                workspaceSettings);
    }

    @Autowired
    public GuanceBindingFingerprintService(
            EvidenceProperties properties,
            WorkspaceObservabilityAssets workspaceAssets,
            WorkspaceEvidenceContracts workspaceContracts,
            WorkspaceEvidenceRoutes workspaceRoutes,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            WorkspaceEvidenceSettingsService workspaceSettings) {
        this.properties = properties == null ? new EvidenceProperties() : properties;
        this.workspaceAssets = workspaceAssets == null
                ? WorkspaceObservabilityAssets.NONE : workspaceAssets;
        this.workspaceContracts = workspaceContracts == null
                ? WorkspaceEvidenceContracts.NONE : workspaceContracts;
        this.workspaceRoutes = workspaceRoutes == null
                ? WorkspaceEvidenceRoutes.NONE : workspaceRoutes;
        this.workspaceSettings = workspaceSettings;
    }

    private EffectiveEvidenceSettings deploymentSettings() {
        EvidenceProperties.Guance guance = properties.getGuance();
        return new EffectiveEvidenceSettings(
                guance.isEnabled(), guance.getBaseUrl(), guance::getApiKey,
                guance.isAllowInsecureHttp(), false, false,
                EffectiveEvidenceSettings.Origin.DEPLOYMENT);
    }

    /**
     * Resolves and freezes the settings identity covered by an authority
     * fingerprint. Formal verification may never inherit deployment settings
     * when the workspace settings store failed; non-formal inspection retains
     * that historical fallback.
     */
    private SettingsSnapshot settingsSnapshot(long workspaceId, boolean formal) {
        try {
            EffectiveEvidenceSettings effective = workspaceSettings == null
                    ? deploymentSettings()
                    : workspaceSettings.effective(workspaceId);
            EffectiveEvidenceSettings frozen = freezeSettings(effective);
            return new SettingsSnapshot(
                    frozen, settingsFingerprint(workspaceId, frozen));
        } catch (FormalEvidenceAuthorityException authorityFailure) {
            throw authorityFailure;
        } catch (RuntimeException settingsFailure) {
            if (formal) {
                throw FormalEvidenceAuthorityException.verifierFailure(
                        "formal Guance workspace settings lookup failed",
                        settingsFailure);
            }
            EffectiveEvidenceSettings fallback = freezeSettings(deploymentSettings());
            return new SettingsSnapshot(
                    fallback, settingsFingerprint(workspaceId, fallback));
        }
    }

    /**
     * Returns no snapshot when the exact asset, core route or referenced
     * binding is absent or ambiguous.
     */
    public Optional<Snapshot> current(
            long workspaceId,
            String system,
            String service) {
        return current(workspaceId, system, service, false);
    }

    /** Exact no-fallback fingerprint used by formal admission and execution. */
    public Optional<Snapshot> currentForFormalAuthority(
            long workspaceId,
            String system,
            String service) {
        return current(workspaceId, system, service, true);
    }

    private Optional<Snapshot> current(
            long workspaceId,
            String system,
            String service,
            boolean formal) {
        validateWorkspace(workspaceId);
        String safeSystem = safeScope(system, "system");
        String safeService = safeScope(service, "service");
        String normalizedSystem = normalize(safeSystem);
        String normalizedService = normalize(safeService);

        EffectiveAsset asset;
        try {
            Optional<WorkspaceObservabilityAsset> exact = workspaceAssets.find(
                    workspaceId, normalizedSystem, normalizedService);
            if (exact.isPresent() && !exact.orElseThrow().enabled()) {
                return Optional.empty();
            }
            Optional<WorkspaceObservabilityAsset> declared = exact
                    .filter(candidate -> SystemObservabilityScopePolicy.hasAnyAllowedSignal(
                            candidate.signalBindings()));
            if (declared.isEmpty()) {
                declared = workspaceAssets.findSystem(workspaceId, normalizedSystem);
            }
            if (declared.isEmpty()) {
                declared = exact;
            }
            if (declared.isPresent()) {
                WorkspaceObservabilityAsset workspaceAsset = declared.orElseThrow();
                if (!workspaceAsset.enabled()
                        || !"guance".equals(normalize(workspaceAsset.platform()))) {
                    return Optional.empty();
                }
                asset = new EffectiveAsset(
                        "workspace",
                        workspaceAsset.version(),
                        workspaceAsset.platform(),
                        workspaceAsset.signalBindings(),
                        workspaceAsset.parameters(),
                        normalize(workspaceAsset.service()));
            } else {
                List<EvidenceProperties.AssetBinding> assets =
                        properties.getGuance().getAssetBindings() == null
                                ? List.of()
                                : properties.getGuance().getAssetBindings().stream()
                                        .filter(candidate ->
                                                candidate.getWorkspaceId() == workspaceId)
                                        .filter(candidate -> normalizedSystem.equals(
                                                normalize(candidate.getSystem())))
                                        .filter(candidate -> normalizedService.equals(
                                                normalize(candidate.getService())))
                                        .toList();
                if (assets.size() != 1) {
                    return Optional.empty();
                }
                asset = new EffectiveAsset(
                        "deployment", 0, "guance",
                        assets.getFirst().getSignalBindings(), Map.of(),
                        normalizedService);
            }
        } catch (RuntimeException registryFailure) {
            return Optional.empty();
        }

        Map<String, String> signalBindings =
                normalizedStringMap(asset.signalBindings());
        Map<String, String> assetParameters =
                runtimeAssetParameterMap(asset.parameters());
        if (signalBindings == null
                || assetParameters == null
                || signalBindings.isEmpty()) {
            return Optional.empty();
        }

        Map<String, List<String>> routes = effectiveRoutes(
                workspaceId,
                normalizedSystem,
                signalBindings.keySet());
        if (routes == null) {
            return Optional.empty();
        }

        Map<String, EvidenceProperties.Binding> configuredBindings;
        try {
            configuredBindings = resolvedBindingMap(workspaceId);
        } catch (RuntimeException unavailableWorkspaceContracts) {
            return Optional.empty();
        }
        if (configuredBindings == null
                || signalBindings.values().stream()
                        .anyMatch(reference -> !configuredBindings.containsKey(
                                normalize(reference)))) {
            return Optional.empty();
        }
        Set<String> readOnlySignalKinds = fingerprintCoveredSignalKinds(
                signalBindings, assetParameters, routes, configuredBindings);
        if (SystemObservabilityScopePolicy.isSystemService(
                asset.authorityService())) {
            readOnlySignalKinds = readOnlySignalKinds.stream()
                    .filter(SystemObservabilityScopePolicy::allowsSignal)
                    .filter(signal -> {
                        String reference = signalBindings.get(signal);
                        EvidenceProperties.Binding binding = reference == null
                                ? null : configuredBindings.get(normalize(reference));
                        return SystemObservabilityScopePolicy
                                .safelyFiltersRuntimeService(binding);
                    })
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
        if (readOnlySignalKinds.isEmpty()) {
            return Optional.empty();
        }

        Digest digest = new Digest();
        digest.add("contract", CONTRACT);
        digest.add("workspaceId", Long.toString(workspaceId));
        digest.add("system", normalizedSystem);
        digest.add("service", asset.authorityService());
        SettingsSnapshot resolvedSettings = settingsSnapshot(workspaceId, formal);
        EffectiveEvidenceSettings endpoint = resolvedSettings.settings();
        digest.add("settings.origin", endpoint.origin().name());
        digest.add("settings.guanceEnabled", Boolean.toString(endpoint.guanceEnabled()));
        digest.add("baseUrl", trim(endpoint.guanceBaseUrl()));
        digest.add("queryPath", trim(properties.getGuance().getQueryPath()));
        digest.add("allowInsecureHttp",
                Boolean.toString(endpoint.guanceAllowInsecureHttp()));
        // The credential enters only the outer digest. Neither the key nor a
        // standalone key hash is exposed through Snapshot, logs, or audit.
        digest.add("credential", exact(endpoint.guanceApiKey()));
        digest.add("timeout", String.valueOf(properties.getGuance().getTimeout()));
        digest.add("asset.origin", asset.origin());
        digest.add("asset.version", Integer.toString(asset.version()));
        digest.add("asset.platform", normalize(asset.platform()));
        assetParameters.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    digest.add("asset.parameter.name", entry.getKey());
                    digest.add("asset.parameter.value", entry.getValue());
                });

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
                    digest.add("binding.signalKind", normalize(binding.getSignalKind()));
                    List<String> assetParameterNames = binding.getAssetParameters() == null
                            ? List.of()
                            : binding.getAssetParameters().stream()
                                    .map(this::normalize)
                                    .sorted()
                                    .toList();
                    digest.add("binding.assetParameters.count",
                            Integer.toString(assetParameterNames.size()));
                    assetParameterNames.forEach(parameter ->
                            digest.add("binding.assetParameters.item", parameter));
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
                            .sorted(java.util.Comparator.comparing(
                                    alias -> exact(alias.getKey())))
                            .forEach(alias -> {
                                // Runtime column matching and canonical-field lookup are
                                // exact. Whitespace/case here is not cosmetic and therefore
                                // must not collapse to an already accepted fingerprint.
                                digest.add("binding.alias.source", exact(alias.getKey()));
                                digest.add("binding.alias.canonical", exact(alias.getValue()));
                            });
                    Map<String, String> constants = binding.getConstantFields() == null
                            ? Map.of()
                            : binding.getConstantFields();
                    constants.entrySet().stream()
                            .sorted(java.util.Comparator.comparing(
                                    constant -> exact(constant.getKey())))
                            .forEach(constant -> {
                                // Canonical constant names are matched exactly at runtime.
                                digest.add("binding.constant.canonical", exact(constant.getKey()));
                                // Constant values are validated and emitted in trimmed form.
                                digest.add("binding.constant.value", trim(constant.getValue()));
                            });
                });

        String scopeKey = digestScopeKey(
                workspaceId, normalizedSystem, asset.authorityService());
        return Optional.of(new Snapshot(
                scopeKey,
                digest.hex(),
                safeSystem,
                asset.authorityService(),
                readOnlySignalKinds,
                resolvedSettings.fingerprint()));
    }

    /** Secret-free identity of the exact settings frozen for one invocation. */
    String settingsFingerprint(
            long workspaceId,
            EffectiveEvidenceSettings settings) {
        validateWorkspace(workspaceId);
        if (settings == null || settings.origin() == null) {
            throw new IllegalArgumentException("effective evidence settings are required");
        }
        Digest digest = new Digest();
        digest.add("contract", "guance-settings/v1");
        digest.add("workspaceId", Long.toString(workspaceId));
        digest.add("origin", settings.origin().name());
        digest.add("enabled", Boolean.toString(settings.guanceEnabled()));
        digest.add("baseUrl", trim(settings.guanceBaseUrl()));
        digest.add("allowInsecureHttp",
                Boolean.toString(settings.guanceAllowInsecureHttp()));
        digest.add("credential", exact(settings.guanceApiKey()));
        return digest.hex();
    }

    private EffectiveEvidenceSettings freezeSettings(
            EffectiveEvidenceSettings settings) {
        if (settings == null) {
            throw new IllegalArgumentException("effective evidence settings are required");
        }
        return EffectiveEvidenceSettings.resolved(
                settings.guanceEnabled(),
                settings.guanceBaseUrl(),
                settings.guanceApiKey(),
                settings.guanceAllowInsecureHttp(),
                settings.replayEnabled(),
                settings.agentEnabled(),
                settings.origin());
    }

    public String scopeKey(long workspaceId, String system, String service) {
        validateWorkspace(workspaceId);
        return digestScopeKey(
                workspaceId,
                normalize(safeScope(system, "system")),
                normalize(safeScope(service, "service")));
    }

    private Map<String, List<String>> effectiveRoutes(
            long workspaceId,
            String normalizedSystem,
            Set<String> signalKinds) {
        Map<String, List<String>> deployed = deploymentRoutes(normalizedSystem);
        if (deployed == null) {
            return null;
        }
        Map<String, List<String>> effective = new LinkedHashMap<>();
        try {
            for (String signalKind : new TreeSet<>(signalKinds)) {
                Optional<List<String>> declared = workspaceRoutes.find(
                        workspaceId, normalizedSystem, signalKind);
                List<String> sources = declared.orElseGet(
                        () -> deployed.getOrDefault(signalKind, List.of()));
                effective.put(
                        signalKind,
                        sources == null ? List.of() : List.copyOf(sources));
            }
        } catch (RuntimeException unavailableWorkspaceRoutes) {
            return null;
        }
        return Map.copyOf(effective);
    }

    private Map<String, List<String>> deploymentRoutes(String normalizedSystem) {
        Map<String, Map<String, List<String>>> configured = properties.getRoutes();
        if (configured == null) {
            return Map.of();
        }
        List<Map.Entry<String, Map<String, List<String>>>> matches =
                configured.entrySet().stream()
                        .filter(entry -> normalizedSystem.equals(normalize(entry.getKey())))
                        .toList();
        if (matches.isEmpty()) {
            return Map.of();
        }
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

    private Map<String, EvidenceProperties.Binding> resolvedBindingMap(
            long workspaceId) {
        Map<String, EvidenceProperties.Binding> deployed =
                normalizedBindingMap(properties.getGuance().getBindings());
        Map<String, EvidenceProperties.Binding> declared =
                normalizedBindingMap(workspaceContracts.bindings(workspaceId));
        if (deployed == null || declared == null) {
            return null;
        }
        Map<String, EvidenceProperties.Binding> merged =
                new LinkedHashMap<>(deployed);
        merged.putAll(declared);
        return Map.copyOf(merged);
    }

    private boolean routesExactlyOnceToGuance(List<String> sources) {
        if (sources == null) {
            return false;
        }
        return sources.stream()
                .filter(source -> "guance".equals(normalize(source)))
                .count() == 1;
    }

    /**
     * Projects only the exact semantic capabilities structurally covered by
     * this fingerprint. A binding reference alone is not enough: its declared
     * signal must agree with the asset key, the system route must reach
     * Guance, and every asset-owned placeholder must be fixed by this exact
     * system/service asset.
     */
    private Set<String> fingerprintCoveredSignalKinds(
            Map<String, String> signalBindings,
            Map<String, String> assetParameters,
            Map<String, List<String>> routes,
            Map<String, EvidenceProperties.Binding> configuredBindings) {
        TreeSet<String> covered = new TreeSet<>();
        for (Map.Entry<String, String> entry : signalBindings.entrySet()) {
            String signalKind = normalize(entry.getKey());
            EvidenceProperties.Binding binding =
                    configuredBindings.get(normalize(entry.getValue()));
            if (binding == null
                    || !signalKind.equals(normalize(binding.getSignalKind()))
                    || !routesExactlyOnceToGuance(routes.get(signalKind))
                    || !hasReviewedQuery(binding)
                    || !hasAssetOwnedParameters(binding, assetParameters)) {
                continue;
            }
            covered.add(signalKind);
        }
        return Set.copyOf(covered);
    }

    private boolean hasReviewedQuery(EvidenceProperties.Binding binding) {
        boolean single = !trim(binding.getQueryTemplate()).isBlank();
        boolean compound = binding.getQueryTemplates() != null
                && binding.getQueryTemplates().stream()
                        .anyMatch(template -> !trim(template).isBlank());
        return binding.getMaxRows() > 0 && (single || compound);
    }

    private boolean hasAssetOwnedParameters(
            EvidenceProperties.Binding binding,
            Map<String, String> assetParameters) {
        List<String> required = binding.getAssetParameters() == null
                ? List.of()
                : binding.getAssetParameters().stream()
                        .map(this::normalize)
                        .toList();
        return required.stream()
                .allMatch(parameter -> !parameter.isBlank()
                        && assetParameters.containsKey(parameter));
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

    /**
     * Mirrors the adapter's workspace-parameter semantics: declared parameter
     * names are canonical lowercase, then looked up by exact key; values are
     * trimmed before query rendering. A raw key with extra whitespace therefore
     * cannot share authority with the canonical key.
     */
    private Map<String, String> runtimeAssetParameterMap(Map<String, String> input) {
        if (input == null) {
            return Map.of();
        }
        Map<String, String> runtime = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : input.entrySet()) {
            String key = exact(entry.getKey());
            String value = trim(entry.getValue());
            if (key.isBlank()
                    || value.isBlank()
                    || runtime.putIfAbsent(key, value) != null) {
                return null;
            }
        }
        return runtime;
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

    private String exact(String value) {
        return value == null ? "" : value;
    }

    private record EffectiveAsset(
            String origin,
            int version,
            String platform,
            Map<String, String> signalBindings,
            Map<String, String> parameters,
            String authorityService) {

        private EffectiveAsset {
            signalBindings = Map.copyOf(
                    signalBindings == null ? Map.of() : signalBindings);
            parameters = Map.copyOf(parameters == null ? Map.of() : parameters);
            authorityService = authorityService == null ? "" : authorityService;
        }
    }

    private record SettingsSnapshot(
            EffectiveEvidenceSettings settings,
            String fingerprint) {
    }

    public record Snapshot(
            String scopeKey,
            String bindingFingerprint,
            String system,
            String service,
            Set<String> readOnlySignalKinds,
            String settingsFingerprint) {

        public Snapshot(
                String scopeKey,
                String bindingFingerprint,
                String system,
                String service) {
            this(
                    scopeKey, bindingFingerprint, system, service, Set.of(),
                    "0".repeat(64));
        }

        public Snapshot(
                String scopeKey,
                String bindingFingerprint,
                String system,
                String service,
                Set<String> readOnlySignalKinds) {
            this(
                    scopeKey, bindingFingerprint, system, service,
                    readOnlySignalKinds, "0".repeat(64));
        }

        public Snapshot {
            if (scopeKey == null
                    || !scopeKey.matches("[a-f0-9]{64}")
                    || bindingFingerprint == null
                    || !bindingFingerprint.matches("[a-f0-9]{64}")
                    || settingsFingerprint == null
                    || !settingsFingerprint.matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException(
                        "scope, binding and settings fingerprints must be SHA-256 hex");
            }
            system = system == null ? "" : system.trim();
            service = service == null ? "" : service.trim();
            readOnlySignalKinds = Set.copyOf(
                    readOnlySignalKinds == null ? Set.of() : readOnlySignalKinds);
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
