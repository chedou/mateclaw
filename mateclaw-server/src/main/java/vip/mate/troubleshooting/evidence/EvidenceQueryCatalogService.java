package vip.mate.troubleshooting.evidence;

import org.springframework.stereotype.Service;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.TroubleshootingSecretRedactor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds the scenario-oriented, secret-free evidence query catalog. */
@Service
public class EvidenceQueryCatalogService {

    private static final String CONTRACT_VERSION = "evidence-query-catalog.v1";
    private static final String GUANCE = "guance";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z0-9_]+)}}");
    private static final Map<String, Presentation> PRESENTATIONS = Map.ofEntries(
            Map.entry("log_search", new Presentation(
                    "失败日志检索", "哪些失败请求需要继续追踪？")),
            Map.entry("log_trace_bundle", new Presentation(
                    "关联 ID 链路还原", "这次请求经过了哪些服务，在哪里出错？")),
            Map.entry("contrast_sample", new Presentation(
                    "成功/失败样本对照", "失败样本与成功样本的稳定差异是什么？")),
            Map.entry("error_log_scan", new Presentation(
                    "错误日志巡检", "故障窗口内出现了多少应用 ERROR，涉及多少条链路？")),
            Map.entry("monitor_event_scan", new Presentation(
                    "监控告警巡检", "故障窗口内是否触发了 warning 及以上监控事件？")),
            Map.entry("k8s_workload_health", new Presentation(
                    "K8s 工作负载健康", "目标 Deployment 的 Pod 状态和 CPU、内存是否异常？")),
            Map.entry("synthetic_probe", new Presentation(
                    "部署拓扑拨测", "哪个网络节点的拨测状态异常？")),
            Map.entry("log_count", new Presentation(
                    "日志数量统计", "故障时间窗口内命中了多少条日志？")),
            Map.entry("metric", new Presentation(
                    "服务指标检查", "服务的连通性和容量指标是否异常？")),
            Map.entry("trace", new Presentation(
                    "调用链路检查", "调用链在哪个节点失败或变慢？")),
            Map.entry("incident_impact", new Presentation(
                    "影响范围确认", "当前故障影响了哪些功能和多少用户？")));

    private final EvidenceProperties properties;
    private final EvidenceRouteService routeService;
    private final List<EvidenceSourceAdapter> adapters;
    private final GuanceEvidenceAdapter guanceAdapter;
    private final GuanceEvidenceAcceptanceService acceptanceService;

    public EvidenceQueryCatalogService(
            EvidenceProperties properties,
            EvidenceRouteService routeService,
            List<EvidenceSourceAdapter> adapters,
            GuanceEvidenceAdapter guanceAdapter,
            GuanceEvidenceAcceptanceService acceptanceService) {
        this.properties = properties == null ? new EvidenceProperties() : properties;
        this.routeService = routeService;
        this.adapters = List.copyOf(adapters == null ? List.of() : adapters);
        this.guanceAdapter = guanceAdapter;
        this.acceptanceService = acceptanceService;
    }

    /** Returns configuration and state only; it never contacts an evidence source. */
    public EvidenceQueryCatalogView inspect(long workspaceId) {
        if (workspaceId <= 0) {
            throw new MateClawException(
                    "err.troubleshooting.invalid_request", 400,
                    "workspaceId must be positive");
        }
        Map<RouteKey, EvidenceRouteView> declarations = workspaceDeclarations(workspaceId);
        List<EvidenceQueryCatalogView.SystemView> systems = systems(
                workspaceId, declarations);
        return new EvidenceQueryCatalogView(
                CONTRACT_VERSION, workspaceId, sources(), systems);
    }

    private List<EvidenceQueryCatalogView.SourceView> sources() {
        return adapters.stream()
                .filter(adapter -> adapter != null)
                .map(this::safeHealth)
                .sorted(Comparator.comparing(
                        EvidenceQueryCatalogView.SourceView::platform,
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private EvidenceQueryCatalogView.SourceView safeHealth(
            EvidenceSourceAdapter adapter) {
        List<String> supportedSignals = supportedSignals(adapter);
        String endpointStatus = endpointStatus(adapter);
        String credentialStatus = credentialStatus(adapter);
        try {
            EvidenceSourceHealth health = adapter.health();
            if (health == null) {
                return new EvidenceQueryCatalogView.SourceView(
                        adapter.platform(), "DEGRADED", false,
                        endpointStatus, credentialStatus, supportedSignals,
                        "source returned no health state");
            }
            return new EvidenceQueryCatalogView.SourceView(
                    health.platform(), health.status().name(), health.verified(),
                    endpointStatus, credentialStatus, supportedSignals,
                    TroubleshootingSecretRedactor.redact(health.detail()));
        } catch (RuntimeException failure) {
            return new EvidenceQueryCatalogView.SourceView(
                    adapter.platform(), "DEGRADED", false,
                    endpointStatus, credentialStatus, supportedSignals,
                    "health check failed: " + failure.getClass().getSimpleName());
        }
    }

    private List<String> supportedSignals(EvidenceSourceAdapter adapter) {
        return CanonicalEvidenceSchema.signalKinds().stream()
                .filter(signalKind -> safelySupports(adapter, signalKind))
                .sorted()
                .toList();
    }

    private boolean safelySupports(EvidenceSourceAdapter adapter, String signalKind) {
        try {
            return adapter.supports(signalKind);
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private String endpointStatus(EvidenceSourceAdapter adapter) {
        if (!GUANCE.equals(normalize(adapter.platform()))) {
            return "NOT_REPORTED";
        }
        return guanceAdapter != null && guanceAdapter.endpointConfigured()
                ? "CONFIGURED" : "MISSING";
    }

    private String credentialStatus(EvidenceSourceAdapter adapter) {
        if (!GUANCE.equals(normalize(adapter.platform()))) {
            return "NOT_REPORTED";
        }
        return guanceAdapter == null
                ? GuanceEvidenceReadiness.CredentialState.MISSING.name()
                : guanceAdapter.credentialState().name();
    }

    private List<EvidenceQueryCatalogView.SystemView> systems(
            long workspaceId,
            Map<RouteKey, EvidenceRouteView> declarations) {
        Map<ScopeKey, List<EvidenceProperties.AssetBinding>> scopes = new LinkedHashMap<>();
        List<EvidenceProperties.AssetBinding> configured =
                properties.getGuance().getAssetBindings();
        for (EvidenceProperties.AssetBinding asset :
                configured == null ? List.<EvidenceProperties.AssetBinding>of() : configured) {
            if (asset == null
                    || asset.getWorkspaceId() != workspaceId
                    || blank(asset.getSystem())
                    || blank(asset.getService())) {
                continue;
            }
            ScopeKey key = new ScopeKey(
                    normalize(asset.getSystem()), normalize(asset.getService()));
            scopes.computeIfAbsent(key, ignored -> new ArrayList<>()).add(asset);
        }

        Map<String, List<EvidenceQueryCatalogView.ModuleView>> grouped =
                new LinkedHashMap<>();
        for (List<EvidenceProperties.AssetBinding> assets : scopes.values()) {
            EvidenceProperties.AssetBinding asset = assets.getFirst();
            EvidenceQueryCatalogView.ModuleView module = module(
                    workspaceId, asset, assets.size(), declarations);
            grouped.computeIfAbsent(asset.getSystem().trim(), ignored -> new ArrayList<>())
                    .add(module);
        }
        return grouped.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .map(entry -> new EvidenceQueryCatalogView.SystemView(
                        entry.getKey(),
                        entry.getValue().stream()
                                .sorted(Comparator.comparing(
                                        EvidenceQueryCatalogView.ModuleView::service,
                                        String.CASE_INSENSITIVE_ORDER))
                                .toList()))
                .toList();
    }

    private EvidenceQueryCatalogView.ModuleView module(
            long workspaceId,
            EvidenceProperties.AssetBinding asset,
            int scopeCount,
            Map<RouteKey, EvidenceRouteView> declarations) {
        List<String> moduleBlockers = new ArrayList<>();
        if (scopeCount != 1) {
            moduleBlockers.add("同一 Workspace 内的系统与模块绑定不唯一");
        }
        Map<String, String> signalBindings = asset.getSignalBindings() == null
                ? Map.of() : asset.getSignalBindings();
        List<EvidenceQueryCatalogView.ContractView> contracts = signalBindings.entrySet()
                .stream()
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .map(entry -> contract(
                        workspaceId, asset, entry.getKey(), entry.getValue(), declarations))
                .toList();
        if (contracts.isEmpty()) {
            moduleBlockers.add("该模块尚未绑定任何证据查询合同");
        }
        int runnable = (int) contracts.stream()
                .filter(EvidenceQueryCatalogView.ContractView::runnable)
                .count();
        contracts.stream().flatMap(contract -> contract.blockers().stream())
                .forEach(moduleBlockers::add);
        String status = runnable == contracts.size() && !contracts.isEmpty()
                && moduleBlockers.stream().noneMatch(value -> value.contains("不唯一"))
                ? "READY"
                : runnable > 0 ? "PARTIAL" : "BLOCKED";
        return new EvidenceQueryCatalogView.ModuleView(
                asset.getService(), status, runnable,
                distinct(moduleBlockers),
                acceptance(workspaceId, asset.getSystem(), asset.getService()),
                contracts);
    }

    private EvidenceQueryCatalogView.ContractView contract(
            long workspaceId,
            EvidenceProperties.AssetBinding asset,
            String rawSignalKind,
            String rawBindingRef,
            Map<RouteKey, EvidenceRouteView> declarations) {
        String signalKind = normalize(rawSignalKind);
        String bindingRef = rawBindingRef == null ? "" : rawBindingRef.trim();
        EvidenceProperties.Binding configuredBinding = binding(bindingRef);
        EvidenceProperties.Binding displayBinding = configuredBinding == null
                ? new EvidenceProperties.Binding() : configuredBinding;
        GuanceEvidenceAdapter.SignalInspection inspection = guanceAdapter.inspectSignal(
                workspaceId, asset.getSystem(), asset.getService(), signalKind);
        EvidenceQueryCatalogView.RouteView route = route(
                asset.getSystem(), signalKind, declarations);
        boolean routed = route.platforms().stream()
                .anyMatch(platform -> GUANCE.equals(normalize(platform)));
        boolean bindingReady = inspection.status()
                == GuanceEvidenceReadiness.SignalStatus.READY_FOR_VALIDATION
                || inspection.status()
                == GuanceEvidenceReadiness.SignalStatus.CANONICAL_RESULT_OBSERVED;
        boolean runtimeReady = guanceAdapter.enabled()
                && guanceAdapter.endpointConfigured()
                && guanceAdapter.credentialState()
                == GuanceEvidenceReadiness.CredentialState.CONFIGURED;
        boolean runnable = routed && bindingReady && runtimeReady;
        List<String> blockers = blockers(route, routed, bindingReady, runtimeReady, inspection);
        Presentation fallback = PRESENTATIONS.getOrDefault(
                signalKind, new Presentation(signalKind, "该查询合同要回答什么问题？"));

        return new EvidenceQueryCatalogView.ContractView(
                bindingRef,
                signalKind,
                valueOr(displayBinding.getScenario(), fallback.scenario()),
                valueOr(displayBinding.getQuestion(), fallback.question()),
                valueOr(displayBinding.getSummary(), fallback.scenario()),
                GUANCE,
                valueOr(displayBinding.getNamespace(), "UNKNOWN"),
                safeTexts(displayBinding.getFixedConditions()),
                new EvidenceQueryCatalogView.EndpointView(
                        "DF_QUERY_DATA_V1", "POST", queryPath(), "dql"),
                parameters(displayBinding),
                CanonicalEvidenceSchema.fields(signalKind).stream().sorted().toList(),
                budget(displayBinding),
                route,
                new EvidenceQueryCatalogView.BindingView(
                        inspection.status().name(),
                        inspection.bindingRef(),
                        inspection.lastObservedAt(),
                        TroubleshootingSecretRedactor.redact(inspection.detail())),
                runnable,
                blockers);
    }

    private List<String> blockers(
            EvidenceQueryCatalogView.RouteView route,
            boolean routed,
            boolean bindingReady,
            boolean runtimeReady,
            GuanceEvidenceAdapter.SignalInspection inspection) {
        List<String> blockers = new ArrayList<>();
        if (route.explicitlyDisabled()) {
            blockers.add("当前 Workspace 明确停用了该证据路由");
        } else if ("UNCONFIGURED".equals(route.origin())) {
            blockers.add("该系统和证据类型尚未配置路由");
        } else if (!routed) {
            blockers.add("当前路由没有选择 Guance 适配器");
        }
        if (!guanceAdapter.enabled()) {
            blockers.add("Guance 适配器未启用");
        } else if (!guanceAdapter.endpointConfigured()) {
            blockers.add("Guance 查询端点未正确配置");
        } else if (guanceAdapter.credentialState()
                != GuanceEvidenceReadiness.CredentialState.CONFIGURED) {
            blockers.add("Guance 运行时凭据未配置");
        }
        if (!bindingReady) {
            blockers.add("查询合同绑定不可执行："
                    + TroubleshootingSecretRedactor.redact(inspection.detail()));
        }
        if (!runtimeReady && blockers.isEmpty()) {
            blockers.add("Guance 运行时尚未就绪");
        }
        return distinct(blockers);
    }

    private EvidenceQueryCatalogView.RouteView route(
            String system,
            String signalKind,
            Map<RouteKey, EvidenceRouteView> declarations) {
        EvidenceRouteView declared = declarations.get(
                new RouteKey(normalize(system), normalize(signalKind)));
        if (declared != null) {
            return new EvidenceQueryCatalogView.RouteView(
                    "WORKSPACE", declared.platforms(), declared.platforms().isEmpty(),
                    declared.updatedBy(), declared.reason(), declared.updatedAt());
        }
        List<String> deployed = deploymentRoute(system, signalKind);
        if (deployed != null) {
            return new EvidenceQueryCatalogView.RouteView(
                    "DEPLOYMENT", deployed, deployed.isEmpty(), null, null, null);
        }
        return new EvidenceQueryCatalogView.RouteView(
                "UNCONFIGURED", List.of(), false, null, null, null);
    }

    /** Null means absent; an empty list is an explicit deployment-level disable. */
    private List<String> deploymentRoute(String system, String signalKind) {
        Map<String, Map<String, List<String>>> routes = properties.getRoutes();
        if (routes == null) {
            return null;
        }
        for (Map.Entry<String, Map<String, List<String>>> systemEntry : routes.entrySet()) {
            if (!normalize(systemEntry.getKey()).equals(normalize(system))
                    || systemEntry.getValue() == null) {
                continue;
            }
            for (Map.Entry<String, List<String>> signalEntry :
                    systemEntry.getValue().entrySet()) {
                if (normalize(signalEntry.getKey()).equals(normalize(signalKind))) {
                    return List.copyOf(signalEntry.getValue() == null
                            ? List.of() : signalEntry.getValue());
                }
            }
        }
        return null;
    }

    private Map<RouteKey, EvidenceRouteView> workspaceDeclarations(long workspaceId) {
        Map<RouteKey, EvidenceRouteView> indexed = new LinkedHashMap<>();
        for (EvidenceRouteView route : routeService.list(workspaceId, null)) {
            indexed.put(
                    new RouteKey(normalize(route.system()), normalize(route.signalKind())),
                    route);
        }
        return Map.copyOf(indexed);
    }

    private EvidenceProperties.Binding binding(String bindingRef) {
        if (blank(bindingRef) || properties.getGuance().getBindings() == null) {
            return null;
        }
        List<EvidenceProperties.Binding> matches = properties.getGuance().getBindings()
                .entrySet().stream()
                .filter(entry -> normalize(entry.getKey()).equals(normalize(bindingRef)))
                .map(Map.Entry::getValue)
                .filter(value -> value != null)
                .toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private EvidenceQueryCatalogView.AcceptanceView acceptance(
            long workspaceId, String system, String service) {
        try {
            GuanceEvidenceAcceptanceView view = acceptanceService.inspect(
                    workspaceId, system, service);
            GuanceEvidenceAcceptance accepted = view.acceptance();
            return new EvidenceQueryCatalogView.AcceptanceView(
                    view.status().name(),
                    view.currentBindingFingerprint(),
                    accepted == null ? null : accepted.acceptedBy(),
                    accepted == null ? null : accepted.acceptedAt(),
                    safeTexts(view.blockers()));
        } catch (RuntimeException failure) {
            return new EvidenceQueryCatalogView.AcceptanceView(
                    "UNAVAILABLE", null, null, null,
                    List.of("验收状态暂时无法读取："
                            + failure.getClass().getSimpleName()));
        }
    }

    private List<EvidenceQueryCatalogView.ParameterView> parameters(
            EvidenceProperties.Binding binding) {
        Map<String, EvidenceQueryCatalogView.ParameterView> parameters =
                new LinkedHashMap<>();
        parameters.put("occurred_at", new EvidenceQueryCatalogView.ParameterView(
                "occurred_at", "INCIDENT_OR_CURRENT_TIME", false,
                "故障发生时间；未记录时由运行时使用当前时间"));
        parameters.put("window", new EvidenceQueryCatalogView.ParameterView(
                "window", "EVIDENCE_REQUEST", false,
                "查询时间窗口；未指定时默认 -15m"));
        for (String template : queryTemplates(binding)) {
            Matcher matcher = PLACEHOLDER.matcher(template);
            while (matcher.find()) {
                String name = matcher.group(1);
                if ("window".equals(name) || "window_span".equals(name)) {
                    continue;
                }
                parameters.putIfAbsent(name, parameter(name));
            }
        }
        return List.copyOf(parameters.values());
    }

    private EvidenceQueryCatalogView.ParameterView parameter(String name) {
        if ("ps_id".equals(name)) {
            return new EvidenceQueryCatalogView.ParameterView(
                    name, "PREVIOUS_EVIDENCE", true,
                    "由前一步失败日志证据提取，不接受浏览器任意输入");
        }
        if (Set.of("incident_id", "system", "service", "error_code", "trace_id")
                .contains(name)) {
            return new EvidenceQueryCatalogView.ParameterView(
                    name, "INCIDENT", true, "来自当前排障事件");
        }
        if ("deployment".equals(name)) {
            return new EvidenceQueryCatalogView.ParameterView(
                    name, "EVIDENCE_REQUEST_TARGET", true,
                    "来自已审核排障方案的 Kubernetes Deployment 名称");
        }
        if ("namespace".equals(name)) {
            return new EvidenceQueryCatalogView.ParameterView(
                    name, "EVIDENCE_REQUEST_TARGET", true,
                    "来自已审核排障方案的 Kubernetes Namespace，禁止跨命名空间猜测");
        }
        if ("monitor_checker".equals(name)) {
            return new EvidenceQueryCatalogView.ParameterView(
                    name, "EVIDENCE_REQUEST_TARGET", true,
                    "来自已审核排障方案的精确监控规则标识，禁止全站告警扫描");
        }
        return new EvidenceQueryCatalogView.ParameterView(
                name, "EVIDENCE_REQUEST_TARGET", true,
                "来自已审核 Playbook 的证据请求目标");
    }

    private EvidenceQueryCatalogView.BudgetView budget(
            EvidenceProperties.Binding binding) {
        List<String> templates = queryTemplates(binding);
        EvidenceProperties.QueryOptions options = binding.getQueryOptions();
        Duration timeout = properties.getGuance().getTimeout();
        int maxRows = binding.getMaxRows();
        return new EvidenceQueryCatalogView.BudgetView(
                templates.size(), maxRows, maxRows + 1,
                timeout == null ? 0L : timeout.toMillis(),
                options == null ? null : options.getMaxPointCount(),
                options == null ? null : options.getInterval(),
                options == null ? null : options.getSeriesLimit(),
                options == null ? null : options.isAlignTime(),
                options == null ? null : options.isDisableSampling(),
                options == null ? null : options.getTimeZone());
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

    private String queryPath() {
        return valueOr(
                properties.getGuance().getQueryPath(),
                "/api/v1/df/query_data_v1");
    }

    private List<String> safeTexts(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(value -> !blank(value))
                .map(String::trim)
                .map(TroubleshootingSecretRedactor::redact)
                .toList();
    }

    private List<String> distinct(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }

    private String valueOr(String value, String fallback) {
        return blank(value) ? fallback : value.trim();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private record Presentation(String scenario, String question) {
    }

    private record ScopeKey(String system, String service) {
    }

    private record RouteKey(String system, String signalKind) {
    }
}
