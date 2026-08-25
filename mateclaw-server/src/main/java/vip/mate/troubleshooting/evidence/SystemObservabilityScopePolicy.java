package vip.mate.troubleshooting.evidence;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Safety boundary for an observability asset shared by every runtime service
 * in one business system.
 *
 * <p>The reserved service is persistence-only. A query still receives the
 * incident's real service and every system-wide contract must contain the
 * server-owned {@code {{service}}} placeholder. This removes repeated admin
 * setup without broadening a query to the whole data source.</p>
 */
public final class SystemObservabilityScopePolicy {

    public static final String SYSTEM_SERVICE = "system-scope";
    private static final Set<String> V1_SIGNALS = Set.of("error_log_scan");

    private SystemObservabilityScopePolicy() {
    }

    public static boolean isSystemService(String service) {
        return SYSTEM_SERVICE.equals(normalize(service));
    }

    public static boolean allowsSignal(String signalKind) {
        return V1_SIGNALS.contains(normalize(signalKind));
    }

    public static boolean hasSignal(
            WorkspaceObservabilityAsset asset,
            String signalKind) {
        if (asset == null || asset.signalBindings() == null) {
            return false;
        }
        String wanted = normalize(signalKind);
        return asset.signalBindings().keySet().stream()
                .map(SystemObservabilityScopePolicy::normalize)
                .anyMatch(wanted::equals);
    }

    public static boolean safelyFiltersRuntimeService(
            EvidenceProperties.Binding binding) {
        if (binding == null) {
            return false;
        }
        List<String> templates = binding.getQueryTemplate() != null
                && !binding.getQueryTemplate().isBlank()
                ? List.of(binding.getQueryTemplate())
                : binding.getQueryTemplates() == null
                        ? List.of()
                        : binding.getQueryTemplates();
        return !templates.isEmpty()
                && templates.stream().allMatch(template ->
                        EvidenceTemplateParameterPolicy.placeholders(List.of(template))
                                .contains("service"));
    }

    public static boolean hasAnyAllowedSignal(Map<String, String> bindings) {
        return bindings != null && bindings.keySet().stream().anyMatch(
                SystemObservabilityScopePolicy::allowsSignal);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
