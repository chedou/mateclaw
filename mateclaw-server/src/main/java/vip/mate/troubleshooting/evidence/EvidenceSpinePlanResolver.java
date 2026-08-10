package vip.mate.troubleshooting.evidence;

import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.SopEntry;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Recognizes the fixed search, trace and comparison protocol in a frozen Playbook. */
public final class EvidenceSpinePlanResolver {

    private static final Set<String> KINDS = Set.of(
            "log_search", "log_trace_bundle", "contrast_sample");

    private EvidenceSpinePlanResolver() {
    }

    /**
     * Returns a server-owned runtime plan when the Playbook declares exactly one
     * request of each spine kind. Other Playbooks keep the generic collection path.
     *
     * @throws IllegalArgumentException when a three-step spine is recognizable but
     *                                  internally inconsistent
     */
    public static EvidenceSpinePlan resolve(SopEntry playbook) {
        if (playbook == null || playbook.evidenceRequests().size() != KINDS.size()) {
            return null;
        }
        Map<String, EvidenceRequest> byKind = new HashMap<>();
        for (EvidenceRequest request : playbook.evidenceRequests()) {
            if (!KINDS.contains(request.signalKind())
                    || byKind.putIfAbsent(request.signalKind(), request) != null) {
                return null;
            }
        }
        if (!byKind.keySet().equals(KINDS)) {
            return null;
        }

        EvidenceRequest search = byKind.get("log_search");
        EvidenceRequest trace = byKind.get("log_trace_bundle");
        EvidenceRequest contrast = byKind.get("contrast_sample");
        String searchTerm = targetString(search, "search_term");
        Object contrastScenario = contrast.target().get("scenario_key");
        if (contrastScenario != null
                && (!(contrastScenario instanceof String value)
                || !searchTerm.equals(value.trim()))) {
            throw new IllegalArgumentException(
                    "Evidence Spine search and contrast targets must match");
        }

        String window = normalizedWindow(search.window());
        if (!window.equals(normalizedWindow(trace.window()))
                || !window.equals(normalizedWindow(contrast.window()))) {
            throw new IllegalArgumentException(
                    "Evidence Spine requests must use one bounded time window");
        }
        return new EvidenceSpinePlan(
                search.requestId(),
                trace.requestId(),
                contrast.requestId(),
                searchTerm,
                window);
    }

    private static String targetString(EvidenceRequest request, String field) {
        Object raw = request.target().get(field);
        if (!(raw instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException("Evidence Spine " + field + " is required");
        }
        return value.trim();
    }

    private static String normalizedWindow(String value) {
        if (value == null || value.isBlank()) {
            return "-15m";
        }
        String normalized = value.trim();
        return normalized.startsWith("-") ? normalized : "-" + normalized;
    }
}
