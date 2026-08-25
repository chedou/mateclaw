package vip.mate.troubleshooting.service;

import vip.mate.troubleshooting.model.IncidentContext;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves an observable service identity when an alert reports only a
 * process entrypoint such as {@code main}.
 *
 * <p>The resolver deliberately ignores URL targets because they identify the
 * callee, not necessarily the process that emitted the alert. It accepts only
 * one repository name corroborated by at least two business stack frames, and
 * that repository must have a reviewed mapping to the observability service
 * field. A repository name is never promoted directly into query authority.</p>
 */
public final class IncidentServiceIdentityResolver {

    private static final String PLACEHOLDER_SERVICE = "main";
    private static final Map<String, String> REVIEWED_SERVICE_ALIASES =
            Map.of("csp-wechat", "csdp-wechat");
    /**
     * Deployment-reviewed identities. The csp-service -> csp-api mapping was
     * confirmed from Guance service-grouped evidence. The date and the
     * supporting query remain in the immutable investigation record rather
     * than becoming a runtime assumption here.
     *
     * <p>This deliberately remains a small allow-list until the same reviewed
     * association is backed by the workspace system registry.</p>
     */
    private static final Map<String, String> REVIEWED_REPOSITORY_TO_SERVICE =
            Map.of("csp-service", "csp-api");
    private static final Pattern BUSINESS_STACK_REPOSITORY = Pattern.compile(
            "(?m)^(?!\\s*https?://)\\s*[A-Za-z0-9.-]+/"
                    + "(?:[A-Za-z0-9._-]+/)*"
                    + "((?!v\\d+(?:/|$))[A-Za-z0-9][A-Za-z0-9._-]{0,63})"
                    + "(?:/v\\d+)?/(?:pkg|cmd|internal)/");

    private IncidentServiceIdentityResolver() {
    }

    public static IncidentContext resolve(IncidentContext incident) {
        if (incident == null) {
            return null;
        }
        String alias = REVIEWED_SERVICE_ALIASES.get(normalize(incident.service()));
        if (alias != null) {
            return incident.withResolvedService(alias);
        }
        if (!isPlaceholder(incident.service())) {
            return incident;
        }
        String rawInput = incident.rawInput();
        if (rawInput == null || rawInput.isBlank()) {
            throw unresolved();
        }

        Map<String, Integer> occurrences = new LinkedHashMap<>();
        Matcher matcher = BUSINESS_STACK_REPOSITORY.matcher(rawInput);
        while (matcher.find()) {
            String repository = matcher.group(1).toLowerCase(Locale.ROOT);
            if (!isPlaceholder(repository)) {
                occurrences.merge(repository, 1, Integer::sum);
            }
        }
        java.util.List<String> corroborated = occurrences.entrySet().stream()
                .filter(entry -> entry.getValue() >= 2)
                .map(Map.Entry::getKey)
                .toList();
        if (corroborated.size() != 1) {
            throw unresolved();
        }

        String resolvedService =
                REVIEWED_REPOSITORY_TO_SERVICE.get(corroborated.getFirst());
        if (resolvedService == null) {
            throw new IllegalArgumentException(
                    "调用栈只能确认代码仓，但尚无代码仓到观测服务映射；"
                            + "请先登记真实服务名后重试。");
        }
        return incident.withResolvedService(resolvedService);
    }

    private static boolean isPlaceholder(String service) {
        return service != null
                && PLACEHOLDER_SERVICE.equals(service.trim().toLowerCase(Locale.ROOT));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static IllegalArgumentException unresolved() {
        return new IllegalArgumentException(
                "告警应用 main 不是可查询服务，且调用栈无法唯一确认真实服务；"
                        + "请补充真实服务名后重试。");
    }
}
