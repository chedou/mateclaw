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
    private final Clock clock;

    public EvidenceSourceRouter(
            List<EvidenceSourceAdapter> adapters,
            EvidenceProperties properties,
            Clock clock) {
        this.adapters = index(adapters);
        this.properties = properties == null ? new EvidenceProperties() : properties;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** Collects one request, trying sources in configured order and never surfacing source failure. */
    public EvidenceResult collect(EvidenceRequest request, IncidentContext incident) {
        return collect(request, incident, null);
    }

    /**
     * Collects from a caller-constrained subset of the configured route.
     *
     * <p>The allowlist is evaluated before adapter invocation. This is a
     * security boundary for flows that are intentionally fixture-only and must
     * never fall through to a live observability source.</p>
     */
    public EvidenceResult collect(
            EvidenceRequest request,
            IncidentContext incident,
            Set<String> permittedPlatforms) {
        if (request == null || incident == null) {
            throw new IllegalArgumentException("request and incident are required");
        }
        Set<String> permitted = normalizePermitted(permittedPlatforms);
        List<String> route = routeFor(incident.system(), request.signalKind());
        if (route.isEmpty()) {
            return missing(request, "router:unconfigured", "no evidence source route configured");
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
                EvidenceResult result = adapter.collect(request, incident);
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

    private List<String> routeFor(String system, String signalKind) {
        Map<String, Map<String, List<String>>> routes = properties.getRoutes();
        if (routes != null) {
            for (Map.Entry<String, Map<String, List<String>>> systemRoute : routes.entrySet()) {
                if (!normalize(systemRoute.getKey()).equals(normalize(system))) {
                    continue;
                }
                Map<String, List<String>> signalRoutes = systemRoute.getValue();
                if (signalRoutes == null) {
                    break;
                }
                for (Map.Entry<String, List<String>> signalRoute : signalRoutes.entrySet()) {
                    if (normalize(signalRoute.getKey()).equals(normalize(signalKind))) {
                        return signalRoute.getValue() == null ? List.of() : signalRoute.getValue();
                    }
                }
                break;
            }
        }
        List<String> defaults = properties.getDefaultSources();
        return defaults == null ? List.of() : defaults;
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
