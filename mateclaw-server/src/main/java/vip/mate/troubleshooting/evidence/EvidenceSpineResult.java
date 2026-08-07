package vip.mate.troubleshooting.evidence;

import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.synthesis.LogTraceSkeleton;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Canonical evidence and deterministic model-safe projection from one spine run. */
public record EvidenceSpineResult(
        EvidenceResult searchEvidence,
        EvidenceResult traceEvidence,
        EvidenceResult contrastEvidence,
        LogTraceSkeleton skeleton,
        int sourceRequestCount,
        EvidenceSpineTimings timings,
        String coreFailure) {

    private static final Pattern SAFE_ERROR_CODE = Pattern.compile(
            "(?i)(?:err(?:or)?[_ -]?code|code|错误码)[^0-9]{0,12}([0-9]{4,8})");

    public EvidenceSpineResult(
            EvidenceResult searchEvidence,
            EvidenceResult traceEvidence,
            EvidenceResult contrastEvidence,
            LogTraceSkeleton skeleton,
            int sourceRequestCount,
            String coreFailure) {
        this(
                searchEvidence,
                traceEvidence,
                contrastEvidence,
                skeleton,
                sourceRequestCount,
                EvidenceSpineTimings.unmeasured(),
                coreFailure);
    }

    public EvidenceSpineResult {
        if (searchEvidence == null) {
            throw new IllegalArgumentException("searchEvidence is required");
        }
        if (sourceRequestCount < 1 || sourceRequestCount > 3) {
            throw new IllegalArgumentException("sourceRequestCount must be between 1 and 3");
        }
        timings = timings == null ? EvidenceSpineTimings.unmeasured() : timings;
        coreFailure = coreFailure == null || coreFailure.isBlank()
                ? null
                : coreFailure.trim();
        if (skeleton != null && traceEvidence == null) {
            throw new IllegalArgumentException("a skeleton requires trace evidence");
        }
    }

    public boolean coreComplete() {
        return skeleton != null && coreFailure == null;
    }

    public boolean contrastAvailable() {
        return coreComplete() && skeleton.contrast().available();
    }

    public List<EvidenceResult> evidence() {
        List<EvidenceResult> result = new ArrayList<>(3);
        result.add(persistenceSafeSearch());
        if (traceEvidence != null) {
            result.add(persistenceSafeTrace());
        }
        if (contrastEvidence != null) {
            result.add(persistenceSafeContrast());
        }
        return List.copyOf(result);
    }

    private EvidenceResult persistenceSafeSearch() {
        Map<String, Object> observed = copyAllowListed(
                searchEvidence.observed(), List.of("match_count", "ps_id"));
        return safeCopy(
                searchEvidence,
                "bounded log match aggregate; source query and sample prose withheld",
                observed,
                searchEvidence.status());
    }

    /**
     * The adapter response is an input to deterministic compression, never a
     * Diagnosis payload. Persist only a bounded trace skeleton with server-owned
     * event labels and allow-listed numeric error-code markers; log prose stays
     * inside the collection call and cannot reach a model or viewer.
     */
    private EvidenceResult persistenceSafeTrace() {
        if (skeleton == null) {
            return safeCopy(
                    traceEvidence,
                    "trace skeleton unavailable; source rows withheld",
                    Map.of(),
                    vip.mate.troubleshooting.model.EvidenceStatus.MISSING);
        }
        List<Map<String, Object>> entries = skeleton.timeline().stream()
                .map(event -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("timestamp", Math.addExact(
                            skeleton.startedAtEpochMs(), event.offsetMs()));
                    entry.put("service", event.service());
                    entry.put("level", event.level());
                    entry.put("message", safeEventLabel(event));
                    if (event.durationMs() != null) {
                        entry.put("duration_ms", event.durationMs());
                    }
                    return Map.copyOf(entry);
                })
                .toList();
        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("ps_id", skeleton.psId());
        observed.put("entries", entries);
        return safeCopy(
                traceEvidence,
                "deterministically compressed trace skeleton; raw log prose withheld",
                Map.copyOf(observed),
                traceEvidence.status());
    }

    private EvidenceResult persistenceSafeContrast() {
        Map<String, Object> observed = copyAllowListed(
                contrastEvidence.observed(),
                List.of(
                        "discriminating_feature",
                        "failure_sample_count",
                        "failure_match_count",
                        "success_sample_count",
                        "success_match_count"));
        return safeCopy(
                contrastEvidence,
                "bounded comparison aggregates; source queries and rows withheld",
                observed,
                contrastEvidence.status());
    }

    private Map<String, Object> copyAllowListed(
            Map<String, Object> source,
            List<String> fields) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : fields) {
            Object value = source.get(field);
            if (value instanceof Number || value instanceof String text && !text.isBlank()) {
                result.put(field, value);
            }
        }
        return Map.copyOf(result);
    }

    private EvidenceResult safeCopy(
            EvidenceResult source,
            String summary,
            Map<String, Object> observed,
            vip.mate.troubleshooting.model.EvidenceStatus status) {
        return new EvidenceResult(
                source.queryId(),
                source.namespace(),
                "withheld",
                status,
                summary,
                observed,
                source.source(),
                source.collectedAt());
    }

    private String safeEventLabel(LogTraceSkeleton.TimelineEvent event) {
        TreeSet<String> codes = new TreeSet<>();
        Matcher matcher = SAFE_ERROR_CODE.matcher(event.message());
        while (matcher.find() && codes.size() < 8) {
            codes.add(matcher.group(1));
        }
        if (!codes.isEmpty()) {
            return "error_codes=" + String.join(",", codes);
        }
        return event.anomalous()
                ? "anomaly_event"
                : "trace_event_" + event.level().toLowerCase(Locale.ROOT);
    }
}
