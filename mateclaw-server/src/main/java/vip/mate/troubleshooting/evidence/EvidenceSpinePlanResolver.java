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
    private static final String OPTIONAL_CTI_FAILURE_PATTERN_KIND =
            "cti_failure_pattern_scan";

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
        if (playbook == null || (playbook.evidenceRequests().size() != KINDS.size()
                && playbook.evidenceRequests().size() != KINDS.size() + 1)) {
            return null;
        }
        Map<String, EvidenceRequest> byKind = new HashMap<>();
        for (EvidenceRequest request : playbook.evidenceRequests()) {
            if (!(KINDS.contains(request.signalKind())
                    || OPTIONAL_CTI_FAILURE_PATTERN_KIND.equals(request.signalKind()))
                    || byKind.putIfAbsent(request.signalKind(), request) != null) {
                return null;
            }
        }
        if (!byKind.keySet().containsAll(KINDS)
                || byKind.keySet().stream().anyMatch(kind -> !KINDS.contains(kind)
                        && !OPTIONAL_CTI_FAILURE_PATTERN_KIND.equals(kind))) {
            return null;
        }

        EvidenceRequest search = byKind.get("log_search");
        EvidenceRequest trace = byKind.get("log_trace_bundle");
        EvidenceRequest contrast = byKind.get("contrast_sample");
        EvidenceRequest failurePatterns = byKind.get(OPTIONAL_CTI_FAILURE_PATTERN_KIND);
        String searchTerm = targetString(search, "search_term");
        Object contrastScenario = contrast.target().get("scenario_key");
        if (contrastScenario != null
                && (!(contrastScenario instanceof String value)
                || !searchTerm.equals(value.trim()))) {
            throw new IllegalArgumentException(
                    "Evidence Spine search and contrast targets must match");
        }
        if (failurePatterns != null) {
            String failureScenario = targetString(failurePatterns, "scenario_key");
            if (!searchTerm.equals(failureScenario)
                    || !failurePatterns.target().keySet().equals(Set.of("scenario_key"))) {
                throw new IllegalArgumentException(
                        "CTI failure pattern target must match the Evidence Spine scenario");
            }
            if (failurePatterns.required()) {
                throw new IllegalArgumentException(
                        "CTI failure pattern evidence must remain optional");
            }
        }

        String window = normalizedWindow(search.window());
        if (!window.equals(normalizedWindow(trace.window()))
                || !window.equals(normalizedWindow(contrast.window()))
                || failurePatterns != null
                        && !window.equals(normalizedWindow(failurePatterns.window()))) {
            throw new IllegalArgumentException(
                    "Evidence Spine requests must use one bounded time window");
        }
        return new EvidenceSpinePlan(
                search.requestId(),
                trace.requestId(),
                contrast.requestId(),
                failurePatterns == null ? null : failurePatterns.requestId(),
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
