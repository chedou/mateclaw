package vip.mate.troubleshooting.evidence;

import vip.mate.troubleshooting.TroubleshootingSecretRedactor;

import java.util.regex.Pattern;

/**
 * Charset allowed for an asset-owned value that reaches a rendered DQL literal.
 *
 * <p>Deployment-owned resource identifiers (service, environment, cluster) stay
 * ASCII and keep their existing validators. This policy covers the other kind of
 * interpolated value: the human-authored name of an observed object, such as a
 * CloudDial task, which is routinely Chinese.
 *
 * <p>The safety property is the exclusion list, not the inclusion list. Values
 * land inside a single-quoted DQL literal, so what must never appear is the
 * quote, the backslash that could escape it, the backtick that quotes
 * identifiers, and the braces that delimit both clauses and template
 * placeholders. {@code \p{L}} contributes no character from that set, so
 * accepting CJK does not widen the injection surface.
 *
 * <p>Refusing CJK here is not free: it forces every Chinese-named probe to get
 * its own reviewed contract with the name hardcoded in DQL, making onboarding
 * cost scale per probe instead of per scenario.
 */
final class EvidenceParameterValuePolicy {

    private static final Pattern SAFE_LABEL =
            Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}._:/ -]{0,127}");

    private EvidenceParameterValuePolicy() {
    }

    static boolean safeLabel(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        return SAFE_LABEL.matcher(trimmed).matches()
                && !trimmed.contains("://")
                && TroubleshootingSecretRedactor.redact(trimmed).equals(trimmed);
    }
}
