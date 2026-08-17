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
import vip.mate.troubleshooting.model.TroubleshootingEvidenceContractEntity;
import vip.mate.troubleshooting.repository.TroubleshootingEvidenceContractMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Workspace-managed evidence method library.
 *
 * <p>Deployment YAML remains the reviewed base. Workspace revisions may add or override
 * a contractRef for that tenant. Query templates stay server-side and are only returned
 * on admin detail responses.</p>
 */
@Service
public class EvidenceContractService implements WorkspaceEvidenceContracts {

    private static final Logger log = LoggerFactory.getLogger(EvidenceContractService.class);
    private static final Pattern SAFE_REF =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern SAFE_SCOPE =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern SAFE_PARAMETER_NAME =
            Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");
    private static final int MAX_REASON = 500;
    private static final int MAX_QUERY = 8_000;
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final TypeReference<LinkedHashMap<String, String>> STRING_MAP =
            new TypeReference<>() { };

    private final TroubleshootingEvidenceContractMapper mapper;
    private final EvidenceProperties properties;
    private final ObjectMapper objectMapper;

    public EvidenceContractService(
            TroubleshootingEvidenceContractMapper mapper,
            EvidenceProperties properties,
            ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public EvidenceContractCatalogView catalog(long workspaceId, boolean includeQueryTemplate) {
        requireWorkspace(workspaceId);
        Map<String, EvidenceContractCatalogView.EvidenceContractView> effective =
                new LinkedHashMap<>();
        for (Map.Entry<String, EvidenceProperties.Binding> entry : deploymentBindings().entrySet()) {
            EvidenceProperties.Binding binding = entry.getValue();
            if (binding == null) {
                continue;
            }
            effective.put(normalize(entry.getKey()), deploymentView(entry.getKey(), binding));
        }
        for (TroubleshootingEvidenceContractEntity entity : latestEnabled(workspaceId)) {
            effective.put(normalize(entity.getContractRef()), view(entity, includeQueryTemplate));
        }
        List<EvidenceContractCatalogView.EvidenceContractView> contracts = effective.values()
                .stream()
                .sorted(Comparator
                        .comparing(EvidenceContractCatalogView.EvidenceContractView::scopeType)
                        .thenComparing(EvidenceContractCatalogView.EvidenceContractView::signalKind)
                        .thenComparing(EvidenceContractCatalogView.EvidenceContractView::contractRef))
                .toList();
        return new EvidenceContractCatalogView(workspaceId, contracts);
    }

    public EvidenceContractCatalogView.EvidenceContractView detail(
            long workspaceId, String contractRef, boolean includeQueryTemplate) {
        requireWorkspace(workspaceId);
        String reference = safeRef(contractRef, "contractRef");
        TroubleshootingEvidenceContractEntity latest = latest(workspaceId, reference);
        if (latest != null) {
            return view(latest, includeQueryTemplate);
        }
        EvidenceProperties.Binding binding = deploymentBindings().get(reference);
        if (binding == null) {
            // try case-insensitive
            binding = deploymentBindings().entrySet().stream()
                    .filter(entry -> normalize(entry.getKey()).equals(reference))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
        }
        if (binding == null) {
            throw notFound("evidence contract '" + reference + "' was not found");
        }
        EvidenceContractCatalogView.EvidenceContractView deployed =
                deploymentView(reference, binding);
        return includeQueryTemplate ? deployed : deployed.withoutQueryTemplate();
    }

    @Transactional
    public EvidenceContractCatalogView.EvidenceContractView declare(
            long workspaceId,
            EvidenceContractDeclaration declaration,
            String actor) {
        requireWorkspace(workspaceId);
        if (declaration == null) {
            throw invalid("contract declaration is required");
        }
        String contractRef = safeRef(declaration.contractRef(), "contractRef");
        String signalKind = safeRef(declaration.signalKind(), "signalKind");
        if (!CanonicalEvidenceSchema.isExternallyRoutable(signalKind)) {
            throw invalid("unknown signalKind '" + signalKind + "'");
        }
        // normalize() lowercases; accept case-insensitive input, store canonical uppercase.
        String scopeType = normalize(declaration.scopeType());
        if (!List.of("generic", "system", "module").contains(scopeType)) {
            throw invalid("scopeType must be GENERIC, SYSTEM or MODULE");
        }
        scopeType = scopeType.toUpperCase(Locale.ROOT);
        String system = "";
        String service = "";
        if ("SYSTEM".equals(scopeType) || "MODULE".equals(scopeType)) {
            system = safeScope(declaration.system(), "system");
        }
        if ("MODULE".equals(scopeType)) {
            service = safeScope(declaration.service(), "service");
        }
        String scenario = safeText(declaration.scenario(), "scenario", 256, null);
        String question = safeText(declaration.question(), "question", 512, null);
        String summary = safeText(declaration.summary(), "summary", 512, scenario);
        String namespace = blank(declaration.namespace()) ? "L" : safeRef(declaration.namespace(), "namespace");
        int maxRows = declaration.maxRows() == null ? 200 : declaration.maxRows();
        if (maxRows < 1 || maxRows > 500) {
            throw invalid("maxRows must be between 1 and 500");
        }
        String queryTemplate = safeQueryTemplate(declaration.queryTemplate());
        List<String> fixedConditions = sanitizeStringList(declaration.fixedConditions(), 16, 160);
        List<String> assetParameters = sanitizeParameterNames(declaration.requiredAssetParameters());
        Map<String, String> fieldAliases = sanitizeAliases(declaration.fieldAliases());
        String reason = safeText(declaration.reason(), "reason", MAX_REASON, null);
        String safeActor = safeText(actor, "actor", 128, "unknown");

        TroubleshootingEvidenceContractEntity current = latest(workspaceId, contractRef);
        int nextVersion = nextVersion(current, declaration.expectedVersion());

        TroubleshootingEvidenceContractEntity entity = new TroubleshootingEvidenceContractEntity();
        entity.setWorkspaceId(workspaceId);
        entity.setContractRef(contractRef);
        entity.setSignalKind(signalKind);
        entity.setScopeType(scopeType);
        entity.setSystemName(blank(system) ? null : system);
        entity.setServiceName(blank(service) ? null : service);
        entity.setScenario(scenario);
        entity.setQuestion(question);
        entity.setSummary(summary);
        entity.setNamespace(namespace);
        entity.setMaxRows(maxRows);
        entity.setQueryTemplate(queryTemplate);
        entity.setFixedConditionsJson(writeList(fixedConditions));
        entity.setAssetParametersJson(writeList(assetParameters));
        entity.setFieldAliasesJson(writeMap(fieldAliases));
        entity.setEnabled(declaration.enabled() ? 1 : 0);
        entity.setVersion(nextVersion);
        entity.setChangedBy(safeActor);
        entity.setChangeReason(reason);
        entity.setCreateTime(LocalDateTime.now(ZoneOffset.UTC));
        try {
            mapper.insert(entity);
        } catch (DuplicateKeyException raced) {
            throw conflict("contract version changed concurrently; reload and retry");
        }
        return view(entity, true);
    }

    @Override
    public Map<String, EvidenceProperties.Binding> bindings(long workspaceId) {
        if (workspaceId <= 0) {
            return Map.of();
        }
        Map<String, EvidenceProperties.Binding> merged = new LinkedHashMap<>(deploymentBindings());
        for (TroubleshootingEvidenceContractEntity entity : latestEnabled(workspaceId)) {
            merged.put(normalize(entity.getContractRef()), toBinding(entity));
        }
        return Map.copyOf(merged);
    }

    @Override
    public Optional<EvidenceProperties.Binding> find(long workspaceId, String contractRef) {
        if (workspaceId <= 0 || blank(contractRef)) {
            return Optional.empty();
        }
        String reference = normalize(contractRef);
        TroubleshootingEvidenceContractEntity latest = latest(workspaceId, reference);
        if (latest != null && Integer.valueOf(1).equals(latest.getEnabled())) {
            return Optional.of(toBinding(latest));
        }
        EvidenceProperties.Binding deployed = deploymentBindings().entrySet().stream()
                .filter(entry -> normalize(entry.getKey()).equals(reference))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
        return Optional.ofNullable(deployed);
    }

    /** Whether a module may bind this contract given its declared scope. */
    public boolean allowsModule(EvidenceContractCatalogView.EvidenceContractView contract,
            String system, String service) {
        if (contract == null) {
            return false;
        }
        return switch (normalize(contract.scopeType())) {
            case "generic" -> true;
            case "system" -> normalize(system).equals(normalize(contract.system()));
            case "module" -> normalize(system).equals(normalize(contract.system()))
                    && normalize(service).equals(normalize(contract.service()));
            default -> false;
        };
    }

    private List<TroubleshootingEvidenceContractEntity> latestEnabled(long workspaceId) {
        Map<String, TroubleshootingEvidenceContractEntity> latest = new LinkedHashMap<>();
        List<TroubleshootingEvidenceContractEntity> rows = mapper.selectList(
                new LambdaQueryWrapper<TroubleshootingEvidenceContractEntity>()
                        .eq(TroubleshootingEvidenceContractEntity::getWorkspaceId, workspaceId)
                        .orderByAsc(TroubleshootingEvidenceContractEntity::getContractRef)
                        .orderByDesc(TroubleshootingEvidenceContractEntity::getVersion));
        for (TroubleshootingEvidenceContractEntity row : rows) {
            String key = normalize(row.getContractRef());
            if (!latest.containsKey(key)) {
                latest.put(key, row);
            }
        }
        return latest.values().stream()
                .filter(row -> Integer.valueOf(1).equals(row.getEnabled()))
                .toList();
    }

    private TroubleshootingEvidenceContractEntity latest(long workspaceId, String contractRef) {
        return mapper.selectList(new LambdaQueryWrapper<TroubleshootingEvidenceContractEntity>()
                        .eq(TroubleshootingEvidenceContractEntity::getWorkspaceId, workspaceId)
                        .eq(TroubleshootingEvidenceContractEntity::getContractRef, contractRef)
                        .orderByDesc(TroubleshootingEvidenceContractEntity::getVersion)
                        .last("LIMIT 1"))
                .stream()
                .findFirst()
                .orElse(null);
    }

    private int nextVersion(
            TroubleshootingEvidenceContractEntity current, Integer expectedVersion) {
        if (current == null) {
            if (expectedVersion != null && expectedVersion > 0) {
                throw conflict("expectedVersion does not match the current contract");
            }
            return 1;
        }
        if (expectedVersion == null || expectedVersion != current.getVersion()) {
            throw conflict("expectedVersion does not match the current contract");
        }
        return current.getVersion() + 1;
    }

    private EvidenceContractCatalogView.EvidenceContractView view(
            TroubleshootingEvidenceContractEntity entity, boolean includeQueryTemplate) {
        return new EvidenceContractCatalogView.EvidenceContractView(
                entity.getContractRef(),
                entity.getSignalKind(),
                entity.getScopeType(),
                entity.getSystemName() == null ? "" : entity.getSystemName(),
                entity.getServiceName() == null ? "" : entity.getServiceName(),
                entity.getScenario(),
                entity.getQuestion(),
                entity.getSummary(),
                entity.getNamespace(),
                entity.getMaxRows() == null ? 200 : entity.getMaxRows(),
                readList(entity.getFixedConditionsJson()),
                readList(entity.getAssetParametersJson()),
                "WORKSPACE",
                Integer.valueOf(1).equals(entity.getEnabled()),
                entity.getVersion() == null ? 0 : entity.getVersion(),
                includeQueryTemplate ? entity.getQueryTemplate() : null);
    }

    private EvidenceContractCatalogView.EvidenceContractView deploymentView(
            String contractRef, EvidenceProperties.Binding binding) {
        List<String> required = binding.getAssetParameters() == null
                ? List.of()
                : binding.getAssetParameters().stream().filter(value -> !blank(value)).toList();
        List<String> fixed = binding.getFixedConditions() == null
                ? List.of() : List.copyOf(binding.getFixedConditions());
        String signal = blank(binding.getSignalKind()) ? "" : normalize(binding.getSignalKind());
        return new EvidenceContractCatalogView.EvidenceContractView(
                contractRef.trim(),
                signal,
                "GENERIC",
                "",
                "",
                textOr(binding.getScenario(), signal),
                textOr(binding.getQuestion(), "该合同要回答什么问题？"),
                textOr(binding.getSummary(), signal),
                textOr(binding.getNamespace(), "L"),
                binding.getMaxRows() <= 0 ? 200 : binding.getMaxRows(),
                fixed,
                required,
                "DEPLOYMENT",
                true,
                0,
                binding.getQueryTemplate());
    }

    private EvidenceProperties.Binding toBinding(TroubleshootingEvidenceContractEntity entity) {
        EvidenceProperties.Binding binding = new EvidenceProperties.Binding();
        binding.setSignalKind(entity.getSignalKind());
        binding.setNamespace(entity.getNamespace());
        binding.setSummary(entity.getSummary());
        binding.setScenario(entity.getScenario());
        binding.setQuestion(entity.getQuestion());
        binding.setFixedConditions(readList(entity.getFixedConditionsJson()));
        binding.setQueryTemplate(entity.getQueryTemplate());
        binding.setMaxRows(entity.getMaxRows() == null ? 200 : entity.getMaxRows());
        binding.setFieldAliases(readMap(entity.getFieldAliasesJson()));
        binding.setAssetParameters(readList(entity.getAssetParametersJson()));
        return binding;
    }

    private Map<String, EvidenceProperties.Binding> deploymentBindings() {
        Map<String, EvidenceProperties.Binding> configured =
                properties.getGuance().getBindings();
        if (configured == null || configured.isEmpty()) {
            return Map.of();
        }
        Map<String, EvidenceProperties.Binding> normalized = new LinkedHashMap<>();
        configured.forEach((key, value) -> {
            if (!blank(key) && value != null) {
                normalized.put(normalize(key), value);
            }
        });
        return normalized;
    }

    private List<String> sanitizeStringList(List<String> values, int maxItems, int maxLen) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (blank(value)) {
                continue;
            }
            String trimmed = safeText(value, "fixedCondition", maxLen, null);
            if (!result.contains(trimmed)) {
                result.add(trimmed);
            }
            if (result.size() >= maxItems) {
                break;
            }
        }
        return List.copyOf(result);
    }

    private List<String> sanitizeParameterNames(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (blank(value)) {
                continue;
            }
            if (!SAFE_PARAMETER_NAME.matcher(value.trim()).matches()) {
                throw invalid("requiredAssetParameters contains an unsafe name");
            }
            String normalized = normalize(value);
            if (!result.contains(normalized)) {
                result.add(normalized);
            }
            if (result.size() >= 32) {
                break;
            }
        }
        return List.copyOf(result);
    }

    private Map<String, String> sanitizeAliases(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (blank(key) || blank(value)) {
                return;
            }
            if (!SAFE_PARAMETER_NAME.matcher(key.trim()).matches()
                    || !SAFE_PARAMETER_NAME.matcher(value.trim()).matches()) {
                throw invalid("fieldAliases must use server-safe names");
            }
            result.put(normalize(key), normalize(value));
        });
        return Map.copyOf(result);
    }

    private String writeList(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception impossible) {
            throw new IllegalStateException("failed to serialize evidence contract", impossible);
        }
    }

    private String writeMap(Map<String, String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? Map.of() : values);
        } catch (Exception impossible) {
            throw new IllegalStateException("failed to serialize evidence contract", impossible);
        }
    }

    private List<String> readList(String json) {
        if (blank(json)) {
            return List.of();
        }
        try {
            List<String> values = objectMapper.readValue(json, STRING_LIST);
            return values == null ? List.of() : List.copyOf(values);
        } catch (Exception corrupt) {
            log.warn("Evidence contract contains invalid list JSON ({})",
                    corrupt.getClass().getSimpleName());
            return List.of();
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
            log.warn("Evidence contract contains invalid map JSON ({})",
                    corrupt.getClass().getSimpleName());
            return Map.of();
        }
    }

    private String safeQueryTemplate(String value) {
        if (blank(value)) {
            throw invalid("queryTemplate must not be blank");
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_QUERY) {
            throw invalid("queryTemplate exceeds its size boundary");
        }
        if (!TroubleshootingSecretRedactor.redact(trimmed).equals(trimmed)) {
            throw invalid("queryTemplate must not contain secret material");
        }
        return trimmed;
    }

    private String safeRef(String value, String field) {
        if (blank(value) || !SAFE_REF.matcher(value.trim()).matches()) {
            throw invalid(field + " must be a bounded server-safe name");
        }
        return normalize(value);
    }

    private String safeScope(String value, String field) {
        if (blank(value) || !SAFE_SCOPE.matcher(value.trim()).matches()) {
            throw invalid(field + " must be a bounded server-safe name");
        }
        return normalize(value);
    }

    private String safeText(String value, String field, int maximum, String fallback) {
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

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private MateClawException invalid(String message) {
        return new MateClawException(
                "err.troubleshooting.evidence_contract_invalid", 400, message);
    }

    private MateClawException conflict(String message) {
        return new MateClawException(
                "err.troubleshooting.evidence_contract_conflict", 409, message);
    }

    private MateClawException notFound(String message) {
        return new MateClawException(
                "err.troubleshooting.evidence_contract_not_found", 404, message);
    }
}
