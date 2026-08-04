package vip.mate.troubleshooting.evidence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.TroubleshootingBusinessTextPolicy;
import vip.mate.troubleshooting.TroubleshootingSecretRedactor;
import vip.mate.troubleshooting.model.TroubleshootingObservabilityAssetEntity;
import vip.mate.troubleshooting.repository.TroubleshootingObservabilityAssetMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Workspace-owned registry connecting one business module to reviewed source contracts.
 *
 * <p>Every change inserts a new revision. The registry stores resource identifiers only;
 * endpoint hosts, credentials, query text and raw source rows remain deployment-owned.</p>
 */
@Service
public class ObservabilityAssetService implements WorkspaceObservabilityAssets {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityAssetService.class);
    private static final Pattern SAFE_SCOPE =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern SAFE_PARAMETER_NAME =
            Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");
    private static final Pattern SAFE_PARAMETER_VALUE =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}");
    private static final int MAX_BINDINGS = 32;
    private static final int MAX_REASON = 500;
    private static final int MAX_DISPLAY_NAME = 160;
    private static final TypeReference<LinkedHashMap<String, String>> STRING_MAP =
            new TypeReference<>() { };

    private final TroubleshootingObservabilityAssetMapper mapper;
    private final ObjectMapper objectMapper;
    private final EvidenceProperties properties;

    public ObservabilityAssetService(
            TroubleshootingObservabilityAssetMapper mapper,
            ObjectMapper objectMapper,
            EvidenceProperties properties) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.properties = properties == null ? new EvidenceProperties() : properties;
    }

    @Override
    public Optional<WorkspaceObservabilityAsset> find(
            long workspaceId, String system, String service) {
        if (workspaceId <= 0 || blank(system) || blank(service)) {
            return Optional.empty();
        }
        TroubleshootingObservabilityAssetEntity entity = latest(
                workspaceId, normalize(system), normalize(service));
        return entity == null ? Optional.empty() : Optional.of(runtime(entity));
    }

    @Override
    public Set<String> activeBindingReferences(String signalKind) {
        if (blank(signalKind)) {
            return Set.of();
        }
        String wanted = normalize(signalKind);
        Set<String> references = new LinkedHashSet<>();
        for (TroubleshootingObservabilityAssetEntity entity : latestAcrossWorkspaces()) {
            if (!Boolean.TRUE.equals(entity.getEnabled())) {
                continue;
            }
            String reference = readMap(entity.getSignalBindings()).get(wanted);
            if (!blank(reference)) {
                references.add(reference.trim());
            }
        }
        return Set.copyOf(references);
    }

    /** Effective workspace catalog: latest declarations shadow deployment YAML by exact scope. */
    public ObservabilityAssetCatalogView catalog(long workspaceId) {
        requireWorkspace(workspaceId);
        Map<ScopeKey, ObservabilityAssetView> effective = new LinkedHashMap<>();
        for (TroubleshootingObservabilityAssetEntity entity : latestForWorkspace(workspaceId)) {
            ObservabilityAssetView view = view(entity);
            effective.put(new ScopeKey(view.system(), view.service()), view);
        }

        Map<ScopeKey, List<EvidenceProperties.AssetBinding>> deployed = new LinkedHashMap<>();
        for (EvidenceProperties.AssetBinding asset : deploymentAssets()) {
            if (asset == null
                    || asset.getWorkspaceId() != workspaceId
                    || blank(asset.getSystem())
                    || blank(asset.getService())) {
                continue;
            }
            ScopeKey key = new ScopeKey(
                    normalize(asset.getSystem()), normalize(asset.getService()));
            deployed.computeIfAbsent(key, ignored -> new ArrayList<>()).add(asset);
        }
        deployed.forEach((scope, matches) -> effective.putIfAbsent(
                scope, deploymentView(workspaceId, matches)));

        List<ObservabilityAssetView> assets = effective.values().stream()
                .sorted(Comparator.comparing(ObservabilityAssetView::system)
                        .thenComparing(ObservabilityAssetView::service))
                .toList();
        return new ObservabilityAssetCatalogView(workspaceId, assets, contractOptions());
    }

    /** Inserts the next immutable revision after validating every source-side dimension. */
    @Transactional
    public ObservabilityAssetView declare(
            long workspaceId,
            ObservabilityAssetDeclaration declaration,
            String actor) {
        requireWorkspace(workspaceId);
        if (declaration == null) {
            throw invalid("asset declaration is required");
        }
        String system = safeScope(declaration.system(), "system");
        String service = safeScope(declaration.service(), "service");
        String platform = safeScope(declaration.platform(), "platform");
        if (!"guance".equals(platform)) {
            throw invalid("the current asset registry only accepts the installed guance platform");
        }
        String environment = safeParameterValue(declaration.environment(), "environment", true);
        String region = safeParameterValue(declaration.region(), "region", false);
        String cluster = safeParameterValue(declaration.cluster(), "cluster", false);
        String namespace = safeParameterValue(declaration.namespace(), "namespace", false);
        String displayName = safeText(
                declaration.displayName(), "displayName", MAX_DISPLAY_NAME, service);
        String safeActor = safeText(actor, "actor", 128, null);
        String reason = safeText(declaration.reason(), "reason", MAX_REASON, null);

        ValidatedBindings validated = validateBindings(
                declaration.signalBindings(), declaration.parameters(),
                environment, region, cluster, namespace,
                declaration.enabled());

        TroubleshootingObservabilityAssetEntity current = latest(
                workspaceId, system, service);
        int nextVersion = nextVersion(current, declaration.expectedVersion());
        TroubleshootingObservabilityAssetEntity entity =
                new TroubleshootingObservabilityAssetEntity();
        entity.setWorkspaceId(workspaceId);
        entity.setSystem(system);
        entity.setService(service);
        entity.setDisplayName(displayName);
        entity.setPlatform(platform);
        entity.setEnvironment(environment);
        entity.setRegion(emptyToNull(region));
        entity.setClusterName(emptyToNull(cluster));
        entity.setNamespaceName(emptyToNull(namespace));
        entity.setEnabled(declaration.enabled());
        entity.setSignalBindings(writeMap(validated.signalBindings()));
        entity.setAssetParameters(writeMap(validated.parameters()));
        entity.setVersion(nextVersion);
        entity.setChangedBy(safeActor);
        entity.setChangeReason(reason);
        entity.setCreateTime(LocalDateTime.now(ZoneOffset.UTC));
        try {
            mapper.insert(entity);
        } catch (DuplicateKeyException raced) {
            throw conflict("asset version changed concurrently; reload and retry");
        }
        return view(entity);
    }

    private ValidatedBindings validateBindings(
            Map<String, String> rawBindings,
            Map<String, String> rawParameters,
            String environment,
            String region,
            String cluster,
            String namespace,
            boolean enabled) {
        Map<String, String> signalBindings = new LinkedHashMap<>();
        Map<String, EvidenceProperties.Binding> boundContracts = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : safeEntries(rawBindings)) {
            String signal = safeScope(entry.getKey(), "signalKind");
            if (!CanonicalEvidenceSchema.supports(signal)) {
                throw invalid("unknown signalKind '" + signal + "'");
            }
            if (signalBindings.containsKey(signal)) {
                throw invalid("a signal binding must be unique after normalization");
            }
            String reference = safeScope(entry.getValue(), "contractRef");
            EvidenceProperties.Binding binding = exactBinding(reference);
            if (binding == null) {
                throw invalid("reviewed query contract '" + reference + "' is not installed");
            }
            if (blank(binding.getSignalKind())
                    || !signal.equals(normalize(binding.getSignalKind()))) {
                throw invalid("query contract '" + reference
                        + "' has no exact matching signal kind");
            }
            signalBindings.put(signal, reference);
            boundContracts.put(reference, binding);
        }
        if (signalBindings.size() > MAX_BINDINGS) {
            throw invalid("an asset may bind at most " + MAX_BINDINGS + " query contracts");
        }
        if (enabled && signalBindings.isEmpty()) {
            throw invalid("an enabled asset must bind at least one reviewed query contract");
        }

        Set<String> required = new LinkedHashSet<>();
        for (EvidenceProperties.Binding binding : boundContracts.values()) {
            List<String> templates = queryTemplates(binding);
            if (templates.isEmpty()
                    || !blank(binding.getQueryTemplate())
                    && binding.getQueryTemplates() != null
                    && binding.getQueryTemplates().stream().anyMatch(value -> !blank(value))) {
                throw invalid("query contract must declare either one query template "
                        + "or one compound template list");
            }
            if (!EvidenceTemplateParameterPolicy.usesCanonicalLowercaseNames(templates)) {
                throw invalid("query contract placeholders must use canonical lowercase names");
            }
            Set<String> contractPlaceholders =
                    EvidenceTemplateParameterPolicy.placeholders(templates);
            for (String raw : binding.getAssetParameters() == null
                    ? List.<String>of() : binding.getAssetParameters()) {
                String parameter = safeParameterName(raw);
                if (EvidenceTemplateParameterPolicy.runtimeOwned(parameter)
                        || !contractPlaceholders.contains(parameter)) {
                    throw invalid("query contract declares invalid asset parameter '"
                            + parameter + "'");
                }
                required.add(parameter);
            }
        }

        Map<String, String> parameters = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : safeEntries(rawParameters)) {
            String name = safeParameterName(entry.getKey());
            if (EvidenceTemplateParameterPolicy.runtimeOwned(name)) {
                throw invalid("runtime parameter '" + name + "' cannot be owned by an asset");
            }
            if (!required.contains(name)) {
                throw invalid("asset parameter '" + name
                        + "' is not declared as asset-owned by a selected query contract");
            }
            if (parameters.putIfAbsent(
                    name, safeParameterValue(entry.getValue(), name, true)) != null) {
                throw invalid("asset parameter names must be unique after normalization");
            }
        }
        putMetadataParameter(parameters, required, "environment", environment);
        putMetadataParameter(parameters, required, "region", region);
        putMetadataParameter(parameters, required, "cluster", cluster);
        putMetadataParameter(parameters, required, "namespace", namespace);
        for (String name : required) {
            if (!parameters.containsKey(name)) {
                throw invalid("required asset parameter '" + name + "' is missing");
            }
        }
        return new ValidatedBindings(
                Map.copyOf(signalBindings), Map.copyOf(parameters));
    }

    private void putMetadataParameter(
            Map<String, String> parameters,
            Set<String> required,
            String name,
            String value) {
        if (!required.contains(name)) {
            return;
        }
        if (blank(value)) {
            if (parameters.containsKey(name)) {
                throw invalid(name + " must be declared in the asset metadata field");
            }
            return;
        }
        String explicit = parameters.get(name);
        if (explicit != null && !value.equals(explicit)) {
            throw invalid(name + " conflicts with the asset metadata field");
        }
        parameters.put(name, value);
    }

    private int nextVersion(
            TroubleshootingObservabilityAssetEntity current,
            Integer expectedVersion) {
        if (current == null) {
            if (expectedVersion != null && expectedVersion != 0) {
                throw conflict("asset does not exist at expected version " + expectedVersion);
            }
            return 1;
        }
        int currentVersion = current.getVersion() == null ? 0 : current.getVersion();
        if (expectedVersion == null || expectedVersion != currentVersion) {
            throw conflict("asset version changed; expected " + currentVersion);
        }
        return currentVersion + 1;
    }

    private TroubleshootingObservabilityAssetEntity latest(
            long workspaceId, String system, String service) {
        return mapper.selectList(
                        new LambdaQueryWrapper<TroubleshootingObservabilityAssetEntity>()
                                .eq(TroubleshootingObservabilityAssetEntity::getWorkspaceId,
                                        workspaceId)
                                .eq(TroubleshootingObservabilityAssetEntity::getSystem, system)
                                .eq(TroubleshootingObservabilityAssetEntity::getService, service))
                .stream()
                .max(Comparator.comparingInt(this::version))
                .orElse(null);
    }

    private List<TroubleshootingObservabilityAssetEntity> latestForWorkspace(long workspaceId) {
        List<TroubleshootingObservabilityAssetEntity> rows = mapper.selectList(
                new LambdaQueryWrapper<TroubleshootingObservabilityAssetEntity>()
                        .eq(TroubleshootingObservabilityAssetEntity::getWorkspaceId, workspaceId));
        return latestByScope(rows);
    }

    private List<TroubleshootingObservabilityAssetEntity> latestAcrossWorkspaces() {
        return latestByScope(mapper.selectList(
                new LambdaQueryWrapper<TroubleshootingObservabilityAssetEntity>()));
    }

    private List<TroubleshootingObservabilityAssetEntity> latestByScope(
            List<TroubleshootingObservabilityAssetEntity> rows) {
        Map<WorkspaceScopeKey, TroubleshootingObservabilityAssetEntity> latest =
                new LinkedHashMap<>();
        for (TroubleshootingObservabilityAssetEntity row : rows == null
                ? List.<TroubleshootingObservabilityAssetEntity>of() : rows) {
            WorkspaceScopeKey key = new WorkspaceScopeKey(
                    row.getWorkspaceId() == null ? 0L : row.getWorkspaceId(),
                    normalize(row.getSystem()), normalize(row.getService()));
            TroubleshootingObservabilityAssetEntity previous = latest.get(key);
            if (previous == null || version(row) > version(previous)) {
                latest.put(key, row);
            }
        }
        return List.copyOf(latest.values());
    }

    private int version(TroubleshootingObservabilityAssetEntity entity) {
        return entity.getVersion() == null ? 0 : entity.getVersion();
    }

    private WorkspaceObservabilityAsset runtime(
            TroubleshootingObservabilityAssetEntity entity) {
        return new WorkspaceObservabilityAsset(
                String.valueOf(entity.getId()),
                entity.getWorkspaceId(),
                entity.getSystem(),
                entity.getService(),
                entity.getPlatform(),
                Boolean.TRUE.equals(entity.getEnabled()),
                readMap(entity.getSignalBindings()),
                readMap(entity.getAssetParameters()),
                version(entity));
    }

    private ObservabilityAssetView view(TroubleshootingObservabilityAssetEntity entity) {
        return new ObservabilityAssetView(
                String.valueOf(entity.getId()),
                "WORKSPACE",
                entity.getWorkspaceId(),
                entity.getSystem(),
                entity.getService(),
                entity.getDisplayName(),
                entity.getPlatform(),
                entity.getEnvironment(),
                entity.getRegion(),
                entity.getClusterName(),
                entity.getNamespaceName(),
                Boolean.TRUE.equals(entity.getEnabled()),
                readMap(entity.getSignalBindings()),
                readMap(entity.getAssetParameters()),
                version(entity),
                entity.getChangedBy(),
                entity.getChangeReason(),
                entity.getCreateTime() == null
                        ? null : entity.getCreateTime().toInstant(ZoneOffset.UTC));
    }

    private ObservabilityAssetView deploymentView(
            long workspaceId,
            List<EvidenceProperties.AssetBinding> matches) {
        EvidenceProperties.AssetBinding first = matches.getFirst();
        boolean unique = matches.size() == 1;
        Map<String, String> bindings = unique
                ? normalizedBindings(first.getSignalBindings()) : Map.of();
        String service = normalize(first.getService());
        return new ObservabilityAssetView(
                null,
                "DEPLOYMENT",
                workspaceId,
                normalize(first.getSystem()),
                service,
                service,
                "guance",
                null,
                null,
                null,
                null,
                unique,
                bindings,
                Map.of(),
                0,
                null,
                unique ? "随部署提供的兼容绑定" : "部署绑定不唯一，运行时已停止",
                null);
    }

    private Map<String, String> normalizedBindings(Map<String, String> source) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : safeEntries(source)) {
            if (!blank(entry.getKey()) && !blank(entry.getValue())) {
                result.put(normalize(entry.getKey()), entry.getValue().trim());
            }
        }
        return Map.copyOf(result);
    }

    private List<ObservabilityAssetCatalogView.ContractOption> contractOptions() {
        Map<String, Set<String>> inferredSignals = inferredSignals();
        List<ObservabilityAssetCatalogView.ContractOption> options = new ArrayList<>();
        for (Map.Entry<String, EvidenceProperties.Binding> entry : bindings().entrySet()) {
            String reference = entry.getKey();
            EvidenceProperties.Binding binding = entry.getValue();
            if (blank(reference) || binding == null) {
                continue;
            }
            String signal = normalize(binding.getSignalKind());
            if (blank(signal)) {
                Set<String> inferred = inferredSignals.getOrDefault(normalize(reference), Set.of());
                signal = inferred.size() == 1 ? inferred.iterator().next() : "";
            }
            if (!CanonicalEvidenceSchema.supports(signal)) {
                continue;
            }
            List<String> required = (binding.getAssetParameters() == null
                    ? List.<String>of() : binding.getAssetParameters()).stream()
                    .filter(value -> !blank(value))
                    .map(this::normalize)
                    .distinct()
                    .sorted()
                    .toList();
            options.add(new ObservabilityAssetCatalogView.ContractOption(
                    reference.trim(),
                    signal,
                    textOr(binding.getScenario(), signal),
                    textOr(binding.getQuestion(), "该合同要回答什么问题？"),
                    textOr(binding.getSummary(), signal),
                    required));
        }
        return options.stream()
                .sorted(Comparator.comparing(
                                ObservabilityAssetCatalogView.ContractOption::signalKind)
                        .thenComparing(
                                ObservabilityAssetCatalogView.ContractOption::contractRef))
                .toList();
    }

    private Map<String, Set<String>> inferredSignals() {
        Map<String, Set<String>> inferred = new LinkedHashMap<>();
        for (EvidenceProperties.AssetBinding asset : deploymentAssets()) {
            for (Map.Entry<String, String> entry : safeEntries(
                    asset == null ? null : asset.getSignalBindings())) {
                if (blank(entry.getKey()) || blank(entry.getValue())) {
                    continue;
                }
                inferred.computeIfAbsent(normalize(entry.getValue()), ignored -> new LinkedHashSet<>())
                        .add(normalize(entry.getKey()));
            }
        }
        return inferred;
    }

    private EvidenceProperties.Binding exactBinding(String reference) {
        List<EvidenceProperties.Binding> matches = bindings().entrySet().stream()
                .filter(entry -> !blank(entry.getKey())
                        && normalize(entry.getKey()).equals(normalize(reference)))
                .map(Map.Entry::getValue)
                .filter(value -> value != null)
                .toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private Map<String, EvidenceProperties.Binding> bindings() {
        Map<String, EvidenceProperties.Binding> configured =
                properties.getGuance().getBindings();
        return configured == null ? Map.of() : configured;
    }

    private List<EvidenceProperties.AssetBinding> deploymentAssets() {
        List<EvidenceProperties.AssetBinding> configured =
                properties.getGuance().getAssetBindings();
        return configured == null ? List.of() : configured;
    }

    private List<String> queryTemplates(EvidenceProperties.Binding binding) {
        if (binding == null) {
            return List.of();
        }
        if (!blank(binding.getQueryTemplate())) {
            return List.of(binding.getQueryTemplate().trim());
        }
        return binding.getQueryTemplates() == null ? List.of()
                : binding.getQueryTemplates().stream()
                        .filter(value -> !blank(value))
                        .map(String::trim)
                        .toList();
    }

    private List<Map.Entry<String, String>> safeEntries(Map<String, String> values) {
        return values == null ? List.of() : List.copyOf(values.entrySet());
    }

    private String writeMap(Map<String, String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? Map.of() : values);
        } catch (Exception impossible) {
            throw new IllegalStateException("failed to serialize observability asset", impossible);
        }
    }

    private Map<String, String> readMap(String json) {
        if (blank(json)) {
            return Map.of();
        }
        try {
            Map<String, String> values = objectMapper.readValue(json, STRING_MAP);
            return values == null ? Map.of() : Map.copyOf(values);
        } catch (Exception corrupt) {
            log.warn("Observability asset revision contains invalid map JSON ({})",
                    corrupt.getClass().getSimpleName());
            // Corrupt authorization state must remove capability, never broaden it.
            return Map.of();
        }
    }

    private String safeScope(String value, String field) {
        if (blank(value) || !SAFE_SCOPE.matcher(value.trim()).matches()) {
            throw invalid(field + " must be a bounded server-safe name");
        }
        return normalize(value);
    }

    private String safeParameterName(String value) {
        if (blank(value) || !SAFE_PARAMETER_NAME.matcher(value.trim()).matches()) {
            throw invalid("asset parameter name must be server-safe");
        }
        return normalize(value);
    }

    private String safeParameterValue(String value, String field, boolean required) {
        if (blank(value)) {
            if (required) {
                throw invalid(field + " must not be blank");
            }
            return "";
        }
        String trimmed = value.trim();
        if (!SAFE_PARAMETER_VALUE.matcher(trimmed).matches()) {
            throw invalid(field + " must be a bounded safe resource identifier");
        }
        if (!TroubleshootingSecretRedactor.redact(trimmed).equals(trimmed)) {
            throw invalid(field + " must not contain secret material");
        }
        if (trimmed.contains("://")) {
            throw invalid(field + " must not contain a URI endpoint");
        }
        return trimmed;
    }

    private String safeText(
            String value, String field, int maximum, String fallback) {
        String candidate = blank(value) ? fallback : value.trim();
        if (blank(candidate)) {
            throw invalid(field + " must not be blank");
        }
        if (candidate.length() > maximum
                || candidate.chars().anyMatch(Character::isISOControl)) {
            throw invalid(field + " exceeds its text boundary");
        }
        if (!TroubleshootingSecretRedactor.redact(candidate).equals(candidate)) {
            throw invalid(field + " must not contain secret material");
        }
        try {
            TroubleshootingBusinessTextPolicy.requireNoDeveloperEvidence(candidate, field);
        } catch (IllegalArgumentException unsafeDeveloperText) {
            throw invalid(field + " must not contain DQL or raw developer evidence");
        }
        return candidate;
    }

    private void requireWorkspace(long workspaceId) {
        if (workspaceId <= 0) {
            throw invalid("workspaceId must be positive");
        }
    }

    private String textOr(String value, String fallback) {
        return blank(value) ? fallback : value.trim();
    }

    private String emptyToNull(String value) {
        return blank(value) ? null : value;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private MateClawException invalid(String message) {
        return new MateClawException(
                "err.troubleshooting.observability_asset_invalid", 400, message);
    }

    private MateClawException conflict(String message) {
        return new MateClawException(
                "err.troubleshooting.observability_asset_conflict", 409, message);
    }

    private record ScopeKey(String system, String service) {
    }

    private record WorkspaceScopeKey(long workspaceId, String system, String service) {
    }

    private record ValidatedBindings(
            Map<String, String> signalBindings,
            Map<String, String> parameters) {
    }
}
