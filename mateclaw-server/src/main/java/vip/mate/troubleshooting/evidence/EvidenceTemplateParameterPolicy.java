package vip.mate.troubleshooting.evidence;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared ownership policy for server-rendered evidence-template parameters. */
final class EvidenceTemplateParameterPolicy {

    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\{\\{([A-Za-z0-9_]+)}}");
    private static final Set<String> RUNTIME_PARAMETERS = Set.of(
            "incident_id", "system", "service", "error_code", "trace_id",
            "window", "window_span", "ps_id");

    private EvidenceTemplateParameterPolicy() {
    }

    static Matcher matcher(String template) {
        return PLACEHOLDER.matcher(template == null ? "" : template);
    }

    static Set<String> placeholders(List<String> templates) {
        Set<String> result = new LinkedHashSet<>();
        for (String template : templates == null ? List.<String>of() : templates) {
            Matcher matcher = matcher(template);
            while (matcher.find()) {
                result.add(normalize(matcher.group(1)));
            }
        }
        return Set.copyOf(result);
    }

    static boolean usesCanonicalLowercaseNames(List<String> templates) {
        for (String template : templates == null ? List.<String>of() : templates) {
            Matcher matcher = matcher(template);
            while (matcher.find()) {
                if (!matcher.group(1).equals(normalize(matcher.group(1)))) {
                    return false;
                }
            }
        }
        return true;
    }

    static boolean runtimeOwned(String name) {
        return RUNTIME_PARAMETERS.contains(normalize(name));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
