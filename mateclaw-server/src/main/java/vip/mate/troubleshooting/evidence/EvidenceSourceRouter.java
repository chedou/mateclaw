package vip.mate.troubleshooting.evidence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.IncidentContext;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Routes semantic evidence requests to explicitly configured read-only adapters. */
public final class EvidenceSourceRouter {

    private static final Logger log = LoggerFactory.getLogger(EvidenceSourceRouter.class);

    private final Map<String, EvidenceSourceAdapter> adapters;
    private final EvidenceProperties properties;
    private final WorkspaceEvidenceRoutes workspaceRoutes;
    private final Clock clock;

    public EvidenceSourceRouter(
            List<EvidenceSourceAdapter> adapters,
            EvidenceProperties properties,
            Clock clock) {
        this(adapters, properties, WorkspaceEvidenceRoutes.NONE, clock);
    }

    public EvidenceSourceRouter(
            List<EvidenceSourceAdapter> adapters,
            EvidenceProperties properties,
            WorkspaceEvidenceRoutes workspaceRoutes,
            Clock clock) {
        this.adapters = index(adapters);
        this.properties = properties == null ? new EvidenceProperties() : properties;
        this.workspaceRoutes = workspaceRoutes == null
                ? WorkspaceEvidenceRoutes.NONE : workspaceRoutes;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** Collects one request, trying sources in configured order and never surfacing source failure. */
    public EvidenceResult collect(
            long workspaceId,
            EvidenceRequest request,
            IncidentContext incident) {
        return collect(workspaceId, request, incident, null);
    }

    /**
     * Collects from a caller-constrained subset of the configured route.
     *
     * <p>The allowlist is evaluated before adapter invocation. This is a
     * security boundary for flows that are intentionally fixture-only and must
     * never fall through to a live observability source.</p>
     */
    public EvidenceResult collect(
            long workspaceId,
            EvidenceRequest request,
            IncidentContext incident,
            Set<String> permittedPlatforms) {
        if (workspaceId <= 0) {
            throw new IllegalArgumentException("workspaceId must be positive");
        }
        if (request == null || incident == null) {
            throw new IllegalArgumentException("request and incident are required");
        }
        Set<String> permitted = normalizePermitted(permittedPlatforms);
        List<String> route = routeFor(
                workspaceId, incident.system(), request.signalKind());
        if (route.isEmpty()) {
            // 拒绝要说出下一步。只报「没配路由」，读者分不清是该去配路由、还是
            // 这台部署压根没启用任何源——两件事的下一步完全不同，而猜的时候最省事
            // 的做法是把闸门放宽。
            return missing(request, "router:unconfigured",
                    "no evidence source route for system '" + incident.system()
                            + "' signal '" + request.signalKind() + "'; "
                            + routingAdvice());
        }

        for (String sourceName : route) {
            if (permitted != null && !permitted.contains(normalize(sourceName))) {
                continue;
            }
            EvidenceSourceAdapter adapter = adapters.get(normalize(sourceName));
            if (adapter == null || !supports(adapter, request.signalKind())) {
                continue;
            }
            try {
                EvidenceResult result = adapter.collect(workspaceId, request, incident);
                if (usable(request, result)) {
                    return result;
                }
            } catch (RuntimeException failure) {
                log.warn("Evidence source {} failed for request {} ({})",
                        adapter.platform(), request.requestId(),
                        failure.getClass().getSimpleName());
            }
        }
        return missing(request, "router:unavailable", "all configured evidence sources unavailable");
    }

    private Set<String> normalizePermitted(Set<String> permittedPlatforms) {
        if (permittedPlatforms == null) {
            return null;
        }
        return permittedPlatforms.stream()
                .map(this::normalize)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Snapshot used by health/capability endpoints without exposing credentials or query text. */
    public List<EvidenceSourceHealth> health() {
        return adapters.values().stream().map(this::safeHealth).toList();
    }

    /**
     * Read-only route/capability check used before presenting a source action.
     *
     * <p>必须和 {@link #collect} 问同一张表、带同一个 workspaceId。少传一个参数，
     * 这里说「能路由」而那里实际走了另一条路，就又是一道指着错误对象的闸门。</p>
     */
    public boolean canRoute(
            long workspaceId, String system, String signalKind, String platform) {
        String normalizedPlatform = normalize(platform);
        if (normalizedPlatform.isEmpty()) {
            return false;
        }
        boolean configured = routeFor(workspaceId, system, signalKind).stream()
                .map(this::normalize)
                .anyMatch(normalizedPlatform::equals);
        EvidenceSourceAdapter adapter = adapters.get(normalizedPlatform);
        return configured && adapter != null && supports(adapter, signalKind);
    }

    private Map<String, EvidenceSourceAdapter> index(List<EvidenceSourceAdapter> sources) {
        Map<String, EvidenceSourceAdapter> indexed = new LinkedHashMap<>();
        for (EvidenceSourceAdapter adapter : sources == null ? List.<EvidenceSourceAdapter>of() : sources) {
            if (adapter == null || adapter.platform() == null || adapter.platform().isBlank()) {
                throw new IllegalArgumentException("evidence adapter platform must not be blank");
            }
            String key = normalize(adapter.platform());
            if (indexed.putIfAbsent(key, adapter) != null) {
                throw new IllegalArgumentException("duplicate evidence platform: " + adapter.platform());
            }
        }
        return Map.copyOf(indexed);
    }

    /**
     * 可用平台清单——只报名字，不报端点、不报凭据。
     *
     * <p>一台什么源都没启用的部署和一个还没配路由的租户，下一步不是一回事。</p>
     */
    private String routingAdvice() {
        List<String> ready = adapters.values().stream()
                // READY 而已，不是 verified——「能取」和「取到的东西已被 owner 验收」
                // 是两条轴，这里只回答前者。
                .filter(adapter -> safeHealth(adapter).status()
                        == EvidenceSourceHealth.Status.READY)
                .map(EvidenceSourceAdapter::platform)
                .toList();
        if (ready.isEmpty()) {
            return "no evidence source is enabled on this deployment;"
                    + " an operator must enable one before any route can collect";
        }
        return "declare one via PUT /api/v1/troubleshooting/evidence/routes;"
                + " sources currently available: " + String.join(", ", ready);
    }

    /**
     * Workspace 声明优先，其次才是部署级配置。
     *
     * <p>顺序是刻意的：YAML 那张表只按 system 名字索引，**任何 workspace 只要把
     * 系统命名成 CSDP 就继承了 CSDP 的路由**。让 workspace 自己的声明先答，是在收窄
     * 而不是放宽。回落保留，是为了让既有部署在没人声明任何路由前行为完全不变。</p>
     */
    private List<String> routeFor(long workspaceId, String system, String signalKind) {
        List<String> declared = workspaceRoutes
                .find(workspaceId, system, signalKind)
                .orElse(null);
        if (declared != null) {
            // 「声明了但为空」是一个答案——租户明说这一格不取证，不该被回落覆盖。
            return declared;
        }
        return deploymentRouteFor(system, signalKind);
    }

    /**
     * 部署级路由。没有这一格就是没有——**不再有全局默认源兜底**。
     *
     * <p>那一层（{@code default-sources}）从没被设成过非空值，却是最后兜底，而且
     * system 命中、signalKind 未命中时也会落到它身上：一旦有人填了值，某个已知系统
     * 里所有未声明的信号会**静默**打到那些源上。取证是 fail-closed 的，路由必须显式，
     * 一个全局默认正是这条原则的反面。行为不变（它一直是空的），少掉的是那个洞。</p>
     */
    private List<String> deploymentRouteFor(String system, String signalKind) {
        Map<String, Map<String, List<String>>> routes = properties.getRoutes();
        if (routes == null) {
            return List.of();
        }
        for (Map.Entry<String, Map<String, List<String>>> systemRoute : routes.entrySet()) {
            if (!normalize(systemRoute.getKey()).equals(normalize(system))) {
                continue;
            }
            Map<String, List<String>> signalRoutes = systemRoute.getValue();
            if (signalRoutes == null) {
                return List.of();
            }
            for (Map.Entry<String, List<String>> signalRoute : signalRoutes.entrySet()) {
                if (normalize(signalRoute.getKey()).equals(normalize(signalKind))) {
                    return signalRoute.getValue() == null ? List.of() : signalRoute.getValue();
                }
            }
            return List.of();
        }
        return List.of();
    }

    private boolean supports(EvidenceSourceAdapter adapter, String signalKind) {
        try {
            return adapter.supports(signalKind);
        } catch (RuntimeException failure) {
            log.warn("Evidence source {} support check failed ({})",
                    adapter.platform(), failure.getClass().getSimpleName());
            return false;
        }
    }

    private boolean usable(EvidenceRequest request, EvidenceResult result) {
        return result != null
                && request.requestId().equals(result.queryId())
                && result.status() != EvidenceStatus.MISSING;
    }

    private EvidenceSourceHealth safeHealth(EvidenceSourceAdapter adapter) {
        try {
            EvidenceSourceHealth health = adapter.health();
            return health == null
                    ? degraded(adapter, "source returned no health state")
                    : health;
        } catch (RuntimeException failure) {
            return degraded(adapter, "health check failed: " + failure.getClass().getSimpleName());
        }
    }

    private EvidenceSourceHealth degraded(EvidenceSourceAdapter adapter, String detail) {
        return new EvidenceSourceHealth(
                adapter.platform(), EvidenceSourceHealth.Status.DEGRADED, false, detail);
    }

    private EvidenceResult missing(EvidenceRequest request, String source, String summary) {
        return new EvidenceResult(
                request.requestId(), "UNKNOWN", "", EvidenceStatus.MISSING, summary,
                Map.of(), source, Instant.now(clock));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
