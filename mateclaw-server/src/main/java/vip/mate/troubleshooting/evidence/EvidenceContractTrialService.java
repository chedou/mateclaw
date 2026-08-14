package vip.mate.troubleshooting.evidence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.TroubleshootingSecretRedactor;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.model.TroubleshootingEvidenceContractTrialEntity;
import vip.mate.troubleshooting.repository.TroubleshootingEvidenceContractTrialMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runs one catalog-selected read-only query and freezes only its safe audit facts. */
@Service
public class EvidenceContractTrialService {

    private static final String GUANCE = "guance";
    private static final Pattern SAFE_VALUE =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}");
    private static final Pattern WINDOW = Pattern.compile("-([1-9][0-9]*)([smhd])");
    private static final Pattern STORED_FIELD =
            Pattern.compile("\\\"([A-Za-z][A-Za-z0-9_]*)\\\"");
    private static final Set<String> REQUEST_PARAMETER_SOURCES =
            Set.of("EVIDENCE_REQUEST_TARGET");
    private static final Set<String> ALLOWED_INCIDENT_PARAMETERS =
            Set.of("error_code", "trace_id");
    private static final Set<String> RESOURCE_PARAMETER_NAMES =
            Set.of("monitor_checker", "deployment", "namespace", "cluster", "region",
                    "environment");
    private static final String WARNING =
            "本次只读试跑只证明该资产与查询规则返回了规范证据；不代表 T7/T8 通过。";

    private final EvidenceQueryCatalogService catalogService;
    private final ObservabilityAssetService assetService;
    private final EvidenceSourceRouter router;
    private final TroubleshootingEvidenceContractTrialMapper trialMapper;
    private final Clock clock;
    private final LongSupplier ticker;

    @Autowired
    public EvidenceContractTrialService(
            EvidenceQueryCatalogService catalogService,
            ObservabilityAssetService assetService,
            EvidenceSourceRouter router,
            TroubleshootingEvidenceContractTrialMapper trialMapper) {
        this(catalogService, assetService, router, trialMapper,
                Clock.systemUTC(), System::nanoTime);
    }

    EvidenceContractTrialService(
            EvidenceQueryCatalogService catalogService,
            ObservabilityAssetService assetService,
            EvidenceSourceRouter router,
            TroubleshootingEvidenceContractTrialMapper trialMapper,
            Clock clock,
            LongSupplier ticker) {
        this.catalogService = catalogService;
        this.assetService = assetService;
        this.router = router;
        this.trialMapper = trialMapper;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.ticker = ticker == null ? System::nanoTime : ticker;
    }

    public EvidenceContractTrialView run(
            long workspaceId,
            EvidenceContractTrialRequest request,
            String actor) {
        if (workspaceId <= 0 || request == null) {
            throw invalid("workspace and trial request are required");
        }
        String system = safeScope(request.system(), "system");
        String service = safeScope(request.service(), "service");
        String contractRef = safeScope(request.contractRef(), "contractRef");
        String safeActor = safeText(actor, "actor");
        EvidenceQueryCatalogView.ContractView contract = exactContract(
                workspaceId, system, service, contractRef);
        if (!contract.runnable()) {
            throw conflict("the selected evidence contract is not runnable");
        }
        if (!GUANCE.equals(normalize(contract.adapter()))) {
            throw conflict("only the installed guance read-only adapter can be trialled here");
        }
        requireNoPreviousEvidenceDependency(contract);
        Map<String, String> supplied = safeParameters(request.parameters());
        String window = safeWindow(request.window());
        Instant occurredAt = safeOccurredAt(request.occurredAt());
        WorkspaceObservabilityAsset asset = assetService.find(workspaceId, system, service)
                .orElseThrow(() -> conflict(
                        "a workspace system asset must be registered before admin trial"));
        if (!asset.enabled()) {
            throw conflict("the selected workspace system asset is not active");
        }
        if (!contract.contractRef().equals(asset.signalBindings().get(contract.signalKind()))) {
            throw conflict("the selected contract is not the current asset binding");
        }
        Map<String, Object> target = trialTarget(contract, asset, supplied);

        String requestId = "catalog-trial-" + UUID.randomUUID().toString().replace("-", "");
        EvidenceRequest evidenceRequest = new EvidenceRequest(
                requestId,
                contract.signalKind(),
                "Admin read-only evidence contract trial",
                target,
                window,
                true);
        IncidentContext incident = new IncidentContext(
                requestId,
                system,
                service,
                supplied.get("error_code"),
                "Evidence contract trial",
                "P2",
                "trial only",
                supplied.get("trace_id"),
                occurredAt,
                null,
                "evidence_contract_trial",
                IncidentCompleteness.LOG,
                null);

        long started = ticker.getAsLong();
        EvidenceResult result;
        try {
            result = router.collect(
                    workspaceId, evidenceRequest, incident, Set.of(GUANCE));
        } catch (RuntimeException failure) {
            long durationMs = durationMs(started);
            try {
                persist(workspaceId, system, service, contract, asset,
                        EvidenceContractTrialView.Status.FAILED,
                        "SOURCE_QUERY_FAILED", GUANCE, List.of(), durationMs, safeActor);
            } catch (RuntimeException auditFailure) {
                failure.addSuppressed(auditFailure);
            }
            throw failure;
        }
        long durationMs = durationMs(started);
        boolean observed = result != null
                && requestId.equals(result.queryId())
                && result.status() != EvidenceStatus.MISSING
                && (GUANCE + ":" + contract.signalKind()).equals(result.source())
                && CanonicalEvidenceSchema.isValid(contract.signalKind(), result.observed());
        boolean sourceFailed = result != null
                && result.status() == EvidenceStatus.MISSING
                && normalize(result.source()).startsWith("router:");
        boolean canonicalMissing = result != null
                && result.status() == EvidenceStatus.MISSING
                && normalize(result.source()).endsWith(":no_canonical_evidence");
        List<String> canonicalFields = observed
                ? result.observed().keySet().stream().sorted().toList()
                : List.of();
        EvidenceContractTrialView.Status status = observed
                ? EvidenceContractTrialView.Status.OBSERVED
                : sourceFailed
                        ? EvidenceContractTrialView.Status.FAILED
                        : EvidenceContractTrialView.Status.NO_EVIDENCE;
        String stopReason = observed
                ? "COMPLETED"
                : sourceFailed ? "SOURCE_QUERY_FAILED" : "NO_CANONICAL_EVIDENCE";
        return persist(workspaceId, system, service, contract, asset, status,
                stopReason, observed || canonicalMissing ? GUANCE : sourceFailed ? "router" : "none", canonicalFields,
                durationMs, safeActor);
    }

    private EvidenceContractTrialView persist(
            long workspaceId,
            String system,
            String service,
            EvidenceQueryCatalogView.ContractView contract,
            WorkspaceObservabilityAsset asset,
            EvidenceContractTrialView.Status status,
            String stopReason,
            String source,
            List<String> canonicalFields,
            long durationMs,
            String actor) {
        Instant completedAt = Instant.now(clock);
        TroubleshootingEvidenceContractTrialEntity entity =
                new TroubleshootingEvidenceContractTrialEntity();
        entity.setTrialId("trial-" + UUID.randomUUID().toString().replace("-", ""));
        entity.setWorkspaceId(workspaceId);
        entity.setSystem(system);
        entity.setService(service);
        entity.setContractRef(contract.contractRef());
        entity.setSignalKind(contract.signalKind());
        entity.setAssetId(asset.assetId());
        entity.setAssetVersion(asset.version());
        entity.setStatus(status.name());
        entity.setStopReason(stopReason);
        entity.setSourcePlatform(source);
        entity.setObservedFields(jsonArray(canonicalFields));
        entity.setDurationMs(durationMs);
        entity.setActor(actor);
        LocalDateTime completed = LocalDateTime.ofInstant(completedAt, ZoneOffset.UTC);
        entity.setCompletedAt(completed);
        entity.setCreateTime(completed);
        if (trialMapper.insert(entity) != 1) {
            throw conflict("the evidence contract trial audit could not be stored");
        }

        return view(entity, canonicalFields);
    }

    private long durationMs(long started) {
        return Math.max(0L, (ticker.getAsLong() - started) / 1_000_000L);
    }

    /** Returns the latest immutable audit rows without replaying a source query. */
    public List<EvidenceContractTrialView> list(
            long workspaceId,
            String system,
            String service,
            String contractRef,
            int limit) {
        if (workspaceId <= 0) {
            throw invalid("workspace is required");
        }
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        LambdaQueryWrapper<TroubleshootingEvidenceContractTrialEntity> query =
                new LambdaQueryWrapper<TroubleshootingEvidenceContractTrialEntity>()
                        .eq(TroubleshootingEvidenceContractTrialEntity::getWorkspaceId,
                                workspaceId)
                        .orderByDesc(TroubleshootingEvidenceContractTrialEntity::getCompletedAt)
                        .last("LIMIT " + safeLimit);
        if (system != null && !system.isBlank()) {
            query.eq(TroubleshootingEvidenceContractTrialEntity::getSystemName,
                    safeScope(system, "system"));
        }
        if (service != null && !service.isBlank()) {
            query.eq(TroubleshootingEvidenceContractTrialEntity::getService,
                    safeScope(service, "service"));
        }
        if (contractRef != null && !contractRef.isBlank()) {
            query.eq(TroubleshootingEvidenceContractTrialEntity::getContractRef,
                    safeScope(contractRef, "contractRef"));
        }
        List<TroubleshootingEvidenceContractTrialEntity> rows = trialMapper.selectList(query);
        return (rows == null ? List.<TroubleshootingEvidenceContractTrialEntity>of() : rows)
                .stream()
                .map(entity -> view(entity, storedFields(entity.getObservedFields())))
                .toList();
    }

    private EvidenceQueryCatalogView.ContractView exactContract(
            long workspaceId, String system, String service, String contractRef) {
        List<EvidenceQueryCatalogView.ContractView> matches = new ArrayList<>();
        for (EvidenceQueryCatalogView.SystemView systemView :
                catalogService.inspect(workspaceId).systems()) {
            if (!normalize(systemView.system()).equals(system)) {
                continue;
            }
            for (EvidenceQueryCatalogView.ModuleView module : systemView.modules()) {
                if (!normalize(module.service()).equals(service)) {
                    continue;
                }
                module.contracts().stream()
                        .filter(contract -> normalize(contract.contractRef()).equals(contractRef))
                        .forEach(matches::add);
            }
        }
        if (matches.size() != 1) {
            throw conflict("the selected evidence contract is not unique in this workspace asset");
        }
        return matches.getFirst();
    }

    private void requireNoPreviousEvidenceDependency(
            EvidenceQueryCatalogView.ContractView contract) {
        boolean requiresPrevious = contract.parameters().stream()
                .anyMatch(parameter -> parameter.required()
                        && "PREVIOUS_EVIDENCE".equals(parameter.source()));
        if (requiresPrevious) {
            throw conflict("the selected contract depends on previous evidence; "
                    + "run the canonical chain instead of supplying it from the browser");
        }
    }

    private Map<String, Object> trialTarget(
            EvidenceQueryCatalogView.ContractView contract,
            WorkspaceObservabilityAsset asset,
            Map<String, String> supplied) {
        Map<String, EvidenceQueryCatalogView.ParameterView> parameters =
                new LinkedHashMap<>();
        contract.parameters().forEach(parameter -> parameters.put(parameter.name(), parameter));
        Set<String> allowed = parameters.values().stream()
                .filter(parameter -> (REQUEST_PARAMETER_SOURCES.contains(parameter.source())
                                && !RESOURCE_PARAMETER_NAMES.contains(parameter.name())
                                && !asset.parameters().containsKey(parameter.name()))
                        || ("INCIDENT".equals(parameter.source())
                                && ALLOWED_INCIDENT_PARAMETERS.contains(parameter.name())))
                .map(EvidenceQueryCatalogView.ParameterView::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (String name : supplied.keySet()) {
            if (!allowed.contains(name)) {
                throw invalid("trial parameter is not browser-owned: " + name);
            }
        }
        for (EvidenceQueryCatalogView.ParameterView parameter : parameters.values()) {
            if (parameter.required()
                    && "EVIDENCE_REQUEST_TARGET".equals(parameter.source())
                    && (RESOURCE_PARAMETER_NAMES.contains(parameter.name())
                    || asset.parameters().containsKey(parameter.name()))) {
                throw conflict("resource parameter must be fixed by the system asset: "
                        + parameter.name());
            }
            if (parameter.required()
                    && allowed.contains(parameter.name())
                    && !supplied.containsKey(parameter.name())) {
                throw invalid("required trial parameter is missing: " + parameter.name());
            }
        }
        Map<String, Object> target = new LinkedHashMap<>();
        supplied.forEach((name, value) -> {
            if (!ALLOWED_INCIDENT_PARAMETERS.contains(name)) {
                target.put(name, value);
            }
        });
        return Map.copyOf(target);
    }

    private Map<String, String> safeParameters(Map<String, String> raw) {
        Map<String, String> safe = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry :
                (raw == null ? Map.<String, String>of() : raw).entrySet()) {
            String name = normalize(entry.getKey());
            if (name.isEmpty() || safe.putIfAbsent(
                    name, safeValue(entry.getValue(), "parameter " + name)) != null) {
                throw invalid("trial parameters must have unique canonical names");
            }
        }
        return Map.copyOf(safe);
    }

    private Instant safeOccurredAt(Instant value) {
        Instant now = Instant.now(clock);
        Instant occurredAt = value == null ? now : value;
        if (occurredAt.isAfter(now)) {
            throw invalid("occurredAt must not be in the future");
        }
        return occurredAt;
    }

    private String safeWindow(String value) {
        String candidate = value == null || value.isBlank() ? "-15m" : value.trim();
        Matcher matcher = WINDOW.matcher(candidate);
        if (!matcher.matches()) {
            throw invalid("window must be a bounded relative time");
        }
        long amount;
        Duration duration;
        try {
            amount = Long.parseLong(matcher.group(1));
            duration = switch (matcher.group(2)) {
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                case "d" -> Duration.ofDays(amount);
                default -> throw invalid("unsupported window unit");
            };
        } catch (NumberFormatException | ArithmeticException overflow) {
            throw invalid("window duration is too large");
        }
        if (duration.compareTo(Duration.ofHours(24)) > 0) {
            throw invalid("window must not exceed 24 hours");
        }
        return candidate;
    }

    private String safeScope(String value, String field) {
        return normalize(safeValue(value, field));
    }

    private String safeText(String value, String field) {
        String candidate = value == null ? "" : value.trim();
        if (candidate.isEmpty() || candidate.length() > 128
                || !TroubleshootingSecretRedactor.redact(candidate).equals(candidate)) {
            throw invalid(field + " is invalid");
        }
        return candidate;
    }

    private String safeValue(String value, String field) {
        String candidate = value == null ? "" : value.trim();
        if (!SAFE_VALUE.matcher(candidate).matches()
                || !TroubleshootingSecretRedactor.redact(candidate).equals(candidate)) {
            throw invalid(field + " must be a bounded non-secret identifier");
        }
        return candidate;
    }

    private String jsonArray(List<String> fields) {
        return fields.stream()
                .sorted(Comparator.naturalOrder())
                .map(field -> "\"" + field + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private List<String> storedFields(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> fields = new ArrayList<>();
        Matcher matcher = STORED_FIELD.matcher(value);
        while (matcher.find()) {
            fields.add(matcher.group(1));
        }
        return List.copyOf(fields);
    }

    private EvidenceContractTrialView view(
            TroubleshootingEvidenceContractTrialEntity entity,
            List<String> fields) {
        return new EvidenceContractTrialView(
                entity.getTrialId(), entity.getWorkspaceId(), entity.getSystem(),
                entity.getService(), entity.getContractRef(), entity.getSignalKind(),
                entity.getAssetId(), entity.getAssetVersion(),
                EvidenceContractTrialView.Status.valueOf(entity.getStatus()),
                entity.getStopReason(), entity.getSourcePlatform(), fields,
                entity.getDurationMs(), entity.getActor(),
                entity.getCompletedAt().toInstant(ZoneOffset.UTC), WARNING);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private MateClawException invalid(String message) {
        return new MateClawException(
                "err.troubleshooting.evidence_contract_trial_invalid", 400, message);
    }

    private MateClawException conflict(String message) {
        return new MateClawException(
                "err.troubleshooting.evidence_contract_trial_blocked", 409, message);
    }
}
