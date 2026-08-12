package vip.mate.troubleshooting.evidence;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared ownership policy for server-rendered evidence-template parameters. */
final class EvidenceTemplateParameterPolicy {

    /**
     * Required placeholder: {@code {{name}}}.
     *
     * <p>Optional clause (omit when the named value is absent/blank):
     * {@code {{?name}} ... {{name}} ... {{/name}}}.
     */
    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\{\\{([A-Za-z0-9_]+)}}");
    private static final Pattern OPTIONAL_SECTION =
            Pattern.compile("\\{\\{\\?([A-Za-z0-9_]+)}}([\\s\\S]*?)\\{\\{/\\1}}");
    private static final Pattern CLOSE_TAG =
            Pattern.compile("\\{\\{/[A-Za-z0-9_]+}}");
    private static final Set<String> RUNTIME_PARAMETERS = Set.of(
            "incident_id", "system", "service", "error_code", "trace_id",
            "window", "window_span", "ps_id");

    private EvidenceTemplateParameterPolicy() {
    }

    static Matcher matcher(String template) {
        return PLACEHOLDER.matcher(template == null ? "" : template);
    }

    /**
     * Expands optional sections before placeholder substitution.
     *
     * <p>When {@code present} is false for {@code name}, the whole
     * {@code {{?name}}...{{/name}}} block is removed. Nested sections are
     * applied up to a small fixed depth.
     */
    static String applyOptionalSections(String template, Predicate<String> present) {
        String current = template == null ? "" : template;
        Predicate<String> safePresent = present == null ? name -> false : present;
        for (int depth = 0; depth < 8; depth++) {
            Matcher matcher = OPTIONAL_SECTION.matcher(current);
            StringBuilder rendered = new StringBuilder();
            boolean found = false;
            while (matcher.find()) {
                found = true;
                String name = normalize(matcher.group(1));
                String body = matcher.group(2);
                matcher.appendReplacement(
                        rendered,
                        Matcher.quoteReplacement(safePresent.test(name) ? body : ""));
            }
            if (!found) {
                break;
            }
            matcher.appendTail(rendered);
            current = rendered.toString();
        }
        if (OPTIONAL_SECTION.matcher(current).find()
                || current.contains("{{?")
                || CLOSE_TAG.matcher(current).find()) {
            throw new IllegalArgumentException("unresolved optional query template section");
        }
        return current;
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

    /** Names referenced by optional sections ({@code {{?name}}...{{/name}}}). */
    static Set<String> optionalParameterNames(List<String> templates) {
        Set<String> result = new LinkedHashSet<>();
        for (String template : templates == null ? List.<String>of() : templates) {
            Matcher matcher = OPTIONAL_SECTION.matcher(template == null ? "" : template);
            while (matcher.find()) {
                result.add(normalize(matcher.group(1)));
            }
        }
        return Set.copyOf(result);
    }

    static boolean usesCanonicalLowercaseNames(List<String> templates) {
        for (String template : templates == null ? List.<String>of() : templates) {
            Matcher placeholder = matcher(template);
            while (placeholder.find()) {
                if (!placeholder.group(1).equals(normalize(placeholder.group(1)))) {
                    return false;
                }
            }
            Matcher optional = OPTIONAL_SECTION.matcher(template == null ? "" : template);
            while (optional.find()) {
                if (!optional.group(1).equals(normalize(optional.group(1)))) {
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
