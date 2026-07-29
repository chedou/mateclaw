package vip.mate.troubleshooting.evidence;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** Shared identity for every stage of the online and synthesis Evidence Spine. */
public enum EvidenceSpineStage {
    SEARCH("ONLINE-LOG-SEARCH", "SYNTH-LOG-SEARCH", "log_search"),
    TRACE("ONLINE-TRACE-BUNDLE", "SYNTH-TRACE-BUNDLE", "log_trace_bundle"),
    CONTRAST("ONLINE-CONTRAST-SAMPLE", "SYNTH-CONTRAST-SAMPLE", "contrast_sample");

    private final String onlineRequestId;
    private final String synthesisRequestId;
    private final String signalKind;

    EvidenceSpineStage(
            String onlineRequestId,
            String synthesisRequestId,
            String signalKind) {
        this.onlineRequestId = onlineRequestId;
        this.synthesisRequestId = synthesisRequestId;
        this.signalKind = signalKind;
    }

    public String onlineRequestId() {
        return onlineRequestId;
    }

    public String synthesisRequestId() {
        return synthesisRequestId;
    }

    public String signalKind() {
        return signalKind;
    }

    public boolean matchesRequestId(String requestId) {
        String normalized = normalize(requestId);
        return normalized.equals(normalize(onlineRequestId))
                || normalized.equals(normalize(synthesisRequestId));
    }

    public static Optional<EvidenceSpineStage> fromRequestId(String requestId) {
        return Arrays.stream(values())
                .filter(stage -> stage.matchesRequestId(requestId))
                .findFirst();
    }

    public static String replayCatalogRequestId(String requestId) {
        return fromRequestId(requestId)
                .filter(stage -> normalize(requestId).equals(
                        normalize(stage.onlineRequestId)))
                .map(EvidenceSpineStage::synthesisRequestId)
                .orElse(requestId);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
