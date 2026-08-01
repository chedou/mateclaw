package vip.mate.troubleshooting.evaluation;

import vip.mate.troubleshooting.TroubleshootingSecretRedactor;
import vip.mate.troubleshooting.synthesis.SynthesisModelInput;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Deterministic, text-free persisted assessment of one model abstention reason. */
final class BaselineAbstainAssessment {

    private static final int MIN_REASON_CHARS = 6;
    private static final int MAX_REASON_CHARS = 512;
    private static final Pattern INSUFFICIENCY = Pattern.compile(
            "(?i)(insufficient|missing|unavailable|lack(?:ing)?|not enough|"
                    + "\u7f3a\u5931|\u4e0d\u8db3|\u65e0\u6cd5|\u4e0d\u53ef\u7528|\u672a\u63d0\u4f9b)");
    private static final Pattern UNSAFE_OPERATION = Pattern.compile(
            "(?i)(\\bdql\\b|raw\\s*logs?|L::logs|tool[_ -]?calls?|"
                    + "\\b(?:restart|delete|update|insert|write|kubectl|curl)\\b|"
                    + "\u65e5\u5fd7\u539f\u6587|\u539f\u59cb\u65e5\u5fd7|\u91cd\u542f\u751f\u4ea7|\u5220\u9664\u6570\u636e|\u5199\u5165\u751f\u4ea7)");

    private BaselineAbstainAssessment() {
    }

    static List<String> assess(String reason, SynthesisModelInput input) {
        List<String> codes = new ArrayList<>(3);
        String normalized = reason == null ? "" : reason.trim();
        if (normalized.length() < MIN_REASON_CHARS
                || normalized.length() > MAX_REASON_CHARS) {
            codes.add("ABSTAIN_REASON_MISSING_OR_INVALID");
        }
        if (!TroubleshootingSecretRedactor.redact(normalized).equals(normalized)
                || UNSAFE_OPERATION.matcher(normalized).find()) {
            codes.add("ABSTAIN_REASON_UNSAFE");
        }
        if (!grounded(normalized, input)) {
            codes.add("ABSTAIN_REASON_UNGROUNDED");
        }
        return List.copyOf(codes);
    }

    private static boolean grounded(String reason, SynthesisModelInput input) {
        if (reason.isBlank() || input == null) {
            return false;
        }
        String normalized = reason.toLowerCase(Locale.ROOT);
        if (!INSUFFICIENCY.matcher(normalized).find()) {
            return false;
        }
        return input.evidence().stream().anyMatch(descriptor ->
                normalized.contains(descriptor.evidenceId().toLowerCase(Locale.ROOT))
                        || normalized.contains(
                                descriptor.signalKind().toLowerCase(Locale.ROOT)));
    }
}
