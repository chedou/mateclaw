package vip.mate.troubleshooting.evidence;

import vip.mate.troubleshooting.TroubleshootingEvidenceSanitizer;

import java.time.Duration;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Server-owned plan for the fixed log search, trace and control-sample spine. */
public record EvidenceSpinePlan(
        String searchRequestId,
        String traceRequestId,
        String contrastRequestId,
        String ctiFailurePatternRequestId,
        String searchTerm,
        String window) {

    private static final Pattern SAFE_TARGET =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}");
    private static final Pattern SAFE_WINDOW = Pattern.compile("-?([1-9][0-9]*)([smhd])");
    private static final long MAX_LOOKBACK_SECONDS = Duration.ofHours(24).toSeconds();
    private static final String DEFAULT_WINDOW = "-15m";

    public EvidenceSpinePlan {
        searchRequestId = safeEvidenceId(searchRequestId, "searchRequestId");
        traceRequestId = safeEvidenceId(traceRequestId, "traceRequestId");
        contrastRequestId = safeEvidenceId(contrastRequestId, "contrastRequestId");
        ctiFailurePatternRequestId = ctiFailurePatternRequestId == null
                ? null : safeEvidenceId(
                        ctiFailurePatternRequestId, "ctiFailurePatternRequestId");
        if (searchRequestId.equals(traceRequestId)
                || searchRequestId.equals(contrastRequestId)
                || traceRequestId.equals(contrastRequestId)
                || ctiFailurePatternRequestId != null && Set.of(
                        searchRequestId, traceRequestId, contrastRequestId)
                        .contains(ctiFailurePatternRequestId)) {
            throw new IllegalArgumentException("evidence spine request ids must be unique");
        }
        searchTerm = safeTarget(searchTerm);
        window = safeWindow(window);
    }

    public EvidenceSpinePlan(
            String searchRequestId,
            String traceRequestId,
            String contrastRequestId,
            String searchTerm,
            String window) {
        this(searchRequestId, traceRequestId, contrastRequestId, null, searchTerm, window);
    }

    private static String safeEvidenceId(String value, String name) {
        if (!TroubleshootingEvidenceSanitizer.isSafeEvidenceId(value)) {
            throw new IllegalArgumentException(name + " must be a safe evidence id");
        }
        return value.trim();
    }

    private static String safeTarget(String value) {
        if (value == null || !SAFE_TARGET.matcher(value.trim()).matches()) {
            throw new IllegalArgumentException("searchTerm must be a mapped safe identifier");
        }
        return value.trim();
    }

    private static String safeWindow(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_WINDOW;
        }
        if (value.length() > 12) {
            throw new IllegalArgumentException("window must be a bounded relative duration");
        }
        String normalized = value.trim();
        Matcher matcher = SAFE_WINDOW.matcher(normalized);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("window must be a bounded relative duration");
        }
        long amount;
        try {
            amount = Long.parseLong(matcher.group(1));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("window must be a bounded relative duration", invalid);
        }
        long seconds;
        try {
            seconds = Math.multiplyExact(amount, switch (matcher.group(2)) {
                case "s" -> 1L;
                case "m" -> 60L;
                case "h" -> 3_600L;
                case "d" -> 86_400L;
                default -> throw new IllegalArgumentException("unsupported window unit");
            });
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("window must be a bounded relative duration", overflow);
        }
        if (seconds > MAX_LOOKBACK_SECONDS) {
            throw new IllegalArgumentException("window must not exceed 24 hours");
        }
        return normalized.startsWith("-") ? normalized : "-" + normalized;
    }
}
