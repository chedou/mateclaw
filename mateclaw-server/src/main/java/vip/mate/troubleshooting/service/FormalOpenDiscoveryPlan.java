package vip.mate.troubleshooting.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Server-owned capability cage for the first formal generic investigation.
 *
 * <p>V1 selects only the safe signals explicitly covered by the exact accepted
 * binding. A service may start with one signal; the formal result policy still
 * prevents that partial graph from being presented as a unique root cause.</p>
 */
public record FormalOpenDiscoveryPlan(
        String planKey,
        Set<String> allowedSignalKinds) {

    public static final String PLAN_KEY = "bounded-open-discovery-v1";
    private static final List<String> SAFE_SIGNAL_KINDS = List.of(
            "error_log_scan",
            "k8s_workload_health",
            "slow_request_analysis");

    public FormalOpenDiscoveryPlan {
        if (!PLAN_KEY.equals(planKey)) {
            throw new IllegalArgumentException("unsupported formal plan key");
        }
        LinkedHashSet<String> normalized = normalize(allowedSignalKinds);
        if (normalized.isEmpty()
                || !Set.copyOf(SAFE_SIGNAL_KINDS).containsAll(normalized)) {
            throw new IllegalArgumentException(
                    "formal plan must contain at least one supported accepted read-only capability");
        }
        allowedSignalKinds = Set.copyOf(normalized);
    }

    public static FormalOpenDiscoveryPlan fromAcceptedCapabilities(
            Set<String> acceptedSignalKinds) {
        LinkedHashSet<String> accepted = normalize(acceptedSignalKinds);
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        SAFE_SIGNAL_KINDS.stream()
                .filter(accepted::contains)
                .forEach(selected::add);
        if (selected.isEmpty()) {
            throw new IllegalArgumentException(
                    "no supported accepted read-only capability is available");
        }
        return new FormalOpenDiscoveryPlan(PLAN_KEY, selected);
    }

    public static FormalOpenDiscoveryPlan current() {
        return new FormalOpenDiscoveryPlan(
                PLAN_KEY, new LinkedHashSet<>(SAFE_SIGNAL_KINDS));
    }

    /** Stable identity for the exact capability set frozen at admission time. */
    public String fingerprint() {
        String canonical = planKey + "\n" + allowedSignalKinds.stream()
                .sorted()
                .reduce((left, right) -> left + "\n" + right)
                .orElseThrow();
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static LinkedHashSet<String> normalize(Set<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values == null ? Set.<String>of() : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("signal kind must not be blank");
            }
            normalized.add(value.trim().toLowerCase(Locale.ROOT));
        }
        return normalized;
    }
}
