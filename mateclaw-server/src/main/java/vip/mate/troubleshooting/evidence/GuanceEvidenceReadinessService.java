package vip.mate.troubleshooting.evidence;

import org.springframework.stereotype.Service;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.TroubleshootingSecretRedactor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Builds a workspace-specific readiness view without querying Guance. */
@Service
public class GuanceEvidenceReadinessService {

    private static final Pattern SAFE_SCOPE =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}");
    private static final List<String> SIGNALS = List.of(
            "log_search", "log_trace_bundle", "contrast_sample", "incident_impact");
    private static final List<String> CORE_SIGNALS = List.of(
            "log_search", "log_trace_bundle");

    private final EvidenceProperties properties;
    private final GuanceEvidenceAdapter adapter;

    public GuanceEvidenceReadinessService(
            EvidenceProperties properties,
            GuanceEvidenceAdapter adapter) {
        this.properties = properties == null ? new EvidenceProperties() : properties;
        this.adapter = adapter;
    }

    public GuanceEvidenceReadiness inspect(long workspaceId, String system, String service) {
        String safeSystem = safeScope(system, "system");
        String safeService = safeScope(service, "service");
        if (workspaceId <= 0) {
            throw invalid("workspaceId must be positive");
        }

        List<GuanceEvidenceReadiness.SignalReadiness> signals = new ArrayList<>();
        List<String> blockers = new ArrayList<>();
        for (String signalKind : SIGNALS) {
            RouteState route = routeState(safeSystem, signalKind);
            if (route == RouteState.NOT_ROUTED) {
                signals.add(new GuanceEvidenceReadiness.SignalReadiness(
                        signalKind,
                        false,
                        GuanceEvidenceReadiness.SignalStatus.NOT_ROUTED,
                        "",
                        null,
                        "signal is not routed to Guance"));
                continue;
            }
            if (route == RouteState.AMBIGUOUS) {
                signals.add(new GuanceEvidenceReadiness.SignalReadiness(
                        signalKind,
                        true,
                        GuanceEvidenceReadiness.SignalStatus.INVALID_BINDING,
                        "",
                        null,
                        "Guance route is duplicated"));
                continue;
            }

            GuanceEvidenceAdapter.SignalInspection inspection = adapter.inspectSignal(
                    workspaceId, safeSystem, safeService, signalKind);
            signals.add(new GuanceEvidenceReadiness.SignalReadiness(
                    signalKind,
                    true,
                    inspection.status(),
                    inspection.bindingRef(),
                    inspection.lastObservedAt(),
                    inspection.detail()));
        }

        boolean uniqueAsset = adapter.hasUniqueAssetScope(workspaceId, safeSystem, safeService);
        boolean coreAuthorized = CORE_SIGNALS.stream().allMatch(signal -> signals.stream()
                .filter(candidate -> candidate.signalKind().equals(signal))
                .map(GuanceEvidenceReadiness.SignalReadiness::status)
                .anyMatch(this::readyOrObserved));
        boolean endpointConfigured = adapter.endpointConfigured(workspaceId);
        GuanceEvidenceReadiness.CredentialState credentials =
                GuanceEvidenceReadiness.CredentialState.NOT_INSPECTED;

        GuanceEvidenceReadiness.Status status;
        if (!adapter.enabled(workspaceId)) {
            status = GuanceEvidenceReadiness.Status.DISABLED;
            blockers.add("Guance adapter is disabled");
        } else if (!uniqueAsset || !coreAuthorized) {
            status = GuanceEvidenceReadiness.Status.UNAUTHORIZED;
            blockers.add("exact workspace asset and core signal authorization is required");
        } else {
            // Credential material is inspected only after the exact tenant/resource
            // boundary and both core signal bindings have passed fail-closed checks.
            credentials = adapter.credentialState(workspaceId);
            if (!endpointConfigured
                    || credentials != GuanceEvidenceReadiness.CredentialState.CONFIGURED) {
                status = GuanceEvidenceReadiness.Status.CONFIGURATION_INCOMPLETE;
                blockers.add("Guance endpoint or runtime credential is not configured");
            } else {
                boolean coreObserved = CORE_SIGNALS.stream().allMatch(signal -> signals.stream()
                        .filter(candidate -> candidate.signalKind().equals(signal))
                        .map(GuanceEvidenceReadiness.SignalReadiness::status)
                        .anyMatch(value -> value
                                == GuanceEvidenceReadiness.SignalStatus.CANONICAL_RESULT_OBSERVED));
                status = coreObserved
                        ? GuanceEvidenceReadiness.Status.CANONICAL_SIGNALS_OBSERVED
                        : GuanceEvidenceReadiness.Status.READY_FOR_VALIDATION;
            }
        }

        return new GuanceEvidenceReadiness(
                safeSystem,
                safeService,
                status,
                adapter.enabled(workspaceId),
                endpointConfigured,
                credentials,
                uniqueAsset,
                signals,
                blockers);
    }

    private boolean readyOrObserved(GuanceEvidenceReadiness.SignalStatus status) {
        return status == GuanceEvidenceReadiness.SignalStatus.READY_FOR_VALIDATION
                || status == GuanceEvidenceReadiness.SignalStatus.CANONICAL_RESULT_OBSERVED;
    }

    private RouteState routeState(String system, String signalKind) {
        Map<String, Map<String, List<String>>> routes = properties.getRoutes();
        if (routes == null) {
            return RouteState.NOT_ROUTED;
        }
        List<Map.Entry<String, Map<String, List<String>>>> systemEntries =
                routes.entrySet().stream()
                        .filter(entry -> normalize(entry.getKey()).equals(normalize(system)))
                        .toList();
        if (systemEntries.isEmpty()) {
            return RouteState.NOT_ROUTED;
        }
        if (systemEntries.size() != 1) {
            return RouteState.AMBIGUOUS;
        }
        Map<String, List<String>> signalRoutes = systemEntries.getFirst().getValue();
        if (signalRoutes == null) {
            return RouteState.NOT_ROUTED;
        }
        List<Map.Entry<String, List<String>>> signalEntries = signalRoutes.entrySet().stream()
                .filter(entry -> normalize(entry.getKey()).equals(normalize(signalKind)))
                .toList();
        if (signalEntries.isEmpty()) {
            return RouteState.NOT_ROUTED;
        }
        if (signalEntries.size() != 1) {
            return RouteState.AMBIGUOUS;
        }
        long guanceRoutes = signalEntries.getFirst().getValue() == null
                ? 0
                : signalEntries.getFirst().getValue().stream()
                        .filter(value -> normalize(value).equals("guance"))
                        .count();
        if (guanceRoutes == 0) {
            return RouteState.NOT_ROUTED;
        }
        return guanceRoutes == 1 ? RouteState.ROUTED : RouteState.AMBIGUOUS;
    }

    private String safeScope(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (!SAFE_SCOPE.matcher(normalized).matches()
                || !TroubleshootingSecretRedactor.redact(normalized).equals(normalized)) {
            throw invalid(field + " must be a safe resource identifier");
        }
        return normalized;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private MateClawException invalid(String message) {
        return new MateClawException(
                "err.troubleshooting.invalid_request", 400, message);
    }

    private enum RouteState {
        NOT_ROUTED,
        ROUTED,
        AMBIGUOUS
    }
}
