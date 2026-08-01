package vip.mate.troubleshooting.synthesis;

import org.springframework.stereotype.Component;
import vip.mate.troubleshooting.CanonicalNumberParser;
import vip.mate.troubleshooting.TroubleshootingSecretRedactor;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure, deterministic compression from canonical trace rows to a bounded call
 * chain. This class performs no IO and never calls a model.
 */
@Component
public final class DeterministicLogTraceCompressor {

    private static final int MAX_SOURCE_ENTRIES = 200;
    private static final int MAX_TIMELINE_EVENTS = 64;
    private static final int MAX_SERVICE_TRANSITIONS = 64;
    private static final int MAX_SERVICE_CHARS = 128;
    private static final int MAX_LEVEL_CHARS = 32;
    private static final int MAX_RAW_MESSAGE_CHARS = 8_192;
    private static final int MAX_TOTAL_RAW_CHARS = 128 * 1_024;
    private static final int MAX_MESSAGE_CHARS = 240;
    private static final String TRUNCATION_MARKER = "...[TRUNCATED]";
    private static final Set<String> NORMAL_LEVELS = Set.of("TRACE", "DEBUG", "INFO");
    private static final List<String> ANOMALY_TERMS = List.of(
            "error", "fail", "exception", "timeout", "rejected",
            "unavailable", "denied", "conflict");

    public LogTraceSkeleton compress(EvidenceResult traceBundle) {
        return compress(traceBundle, null);
    }

    public LogTraceSkeleton compress(
            EvidenceResult traceBundle,
            EvidenceResult contrastEvidence) {
        if (traceBundle == null || traceBundle.status() == EvidenceStatus.MISSING) {
            throw new IllegalArgumentException("usable log_trace_bundle evidence is required");
        }
        Object rawPsId = traceBundle.observed().get("ps_id");
        Object rawEntries = traceBundle.observed().get("entries");
        if (!(rawEntries instanceof List<?> entries)
                || entries.isEmpty()
                || entries.size() > MAX_SOURCE_ENTRIES) {
            throw new IllegalArgumentException(
                    "log_trace_bundle must contain 1.." + MAX_SOURCE_ENTRIES + " entries");
        }
        validateRawCharacterBounds(rawPsId, entries);
        String psId = sanitizedText(rawPsId, "ps_id", 128);

        List<TraceEntry> parsed = new ArrayList<>(entries.size());
        for (int index = 0; index < entries.size(); index++) {
            parsed.add(parse(entries.get(index), index));
        }
        parsed.sort(Comparator
                .comparingLong(TraceEntry::timestamp)
                .thenComparing(TraceEntry::service)
                .thenComparing(TraceEntry::message)
                .thenComparingInt(TraceEntry::originalIndex));

        List<String> serviceSequence = serviceSequence(parsed);
        List<Integer> anomalyIndexes = new ArrayList<>();
        for (int index = 0; index < parsed.size(); index++) {
            if (parsed.get(index).anomalous()) {
                anomalyIndexes.add(index);
            }
        }
        Set<Integer> selected = selectedTimelineIndexes(parsed.size(), anomalyIndexes);
        long startedAt = parsed.getFirst().timestamp();
        long endedAt = parsed.getLast().timestamp();
        List<LogTraceSkeleton.TimelineEvent> timeline = selected.stream()
                .sorted()
                .map(index -> timelineEvent(index, parsed.get(index), startedAt))
                .toList();

        return new LogTraceSkeleton(
                psId,
                startedAt,
                endedAt,
                endedAt - startedAt,
                serviceSequence,
                timeline,
                List.copyOf(anomalyIndexes),
                durationSummary(parsed),
                parsed.size(),
                parsed.size() - timeline.size(),
                contrastSummary(contrastEvidence));
    }

    private LogTraceSkeleton.ContrastSummary contrastSummary(EvidenceResult evidence) {
        if (evidence == null || evidence.status() == EvidenceStatus.MISSING) {
            return LogTraceSkeleton.ContrastSummary.unavailable();
        }
        String feature = sanitizedText(
                evidence.observed().get("discriminating_feature"),
                "contrast discriminating_feature", 128);
        long failureSamples = nonNegativeLong(
                evidence.observed().get("failure_sample_count"), "failure_sample_count");
        long failureMatches = nonNegativeLong(
                evidence.observed().get("failure_match_count"), "failure_match_count");
        long successSamples = nonNegativeLong(
                evidence.observed().get("success_sample_count"), "success_sample_count");
        long successMatches = nonNegativeLong(
                evidence.observed().get("success_match_count"), "success_match_count");
        if (failureSamples == 0 || successSamples == 0
                || failureMatches > failureSamples
                || successMatches > successSamples) {
            throw new IllegalArgumentException("contrast counts are mathematically invalid");
        }
        double failureRate = roundedRate(failureMatches, failureSamples);
        double successRate = roundedRate(successMatches, successSamples);
        double delta = Math.round((failureRate - successRate) * 1_000_000d) / 1_000_000d;
        return new LogTraceSkeleton.ContrastSummary(
                true, feature, failureSamples, failureMatches,
                successSamples, successMatches, failureRate, successRate, delta);
    }

    private long nonNegativeLong(Object raw, String field) {
        long value = exactLong(raw, field);
        if (value < 0) {
            throw new IllegalArgumentException("contrast " + field + " must not be negative");
        }
        return value;
    }

    private double roundedRate(long matches, long samples) {
        return Math.round(((double) matches / samples) * 1_000_000d) / 1_000_000d;
    }

    private TraceEntry parse(Object raw, int originalIndex) {
        if (!(raw instanceof Map<?, ?> fields)) {
            throw new IllegalArgumentException("trace entry must be an object");
        }
        long timestamp = exactLong(fields.get("timestamp"), "timestamp");
        if (timestamp < 0) {
            throw new IllegalArgumentException("timestamp must not be negative");
        }
        String service = sanitizedText(fields.get("service"), "service", MAX_SERVICE_CHARS);
        String level = sanitizedText(fields.get("level"), "level", MAX_LEVEL_CHARS)
                .toUpperCase(Locale.ROOT);
        String fullMessage = sanitizedText(
                fields.get("message"), "message", MAX_RAW_MESSAGE_CHARS);
        boolean anomalous = anomalous(level, fullMessage);
        String message = boundedMessage(fullMessage);
        Double duration = optionalDuration(fields.get("duration_ms"));
        return new TraceEntry(
                originalIndex,
                timestamp,
                service,
                level,
                message,
                duration,
                anomalous);
    }

    private void validateRawCharacterBounds(Object rawPsId, List<?> entries) {
        long total = rawTextLength(rawPsId, "ps_id", 128);
        for (Object raw : entries) {
            if (!(raw instanceof Map<?, ?> fields)) {
                throw new IllegalArgumentException("trace entry must be an object");
            }
            total += rawTextLength(fields.get("service"), "service", MAX_SERVICE_CHARS);
            total += rawTextLength(fields.get("level"), "level", MAX_LEVEL_CHARS);
            total += rawTextLength(
                    fields.get("message"), "raw message", MAX_RAW_MESSAGE_CHARS);
            if (total > MAX_TOTAL_RAW_CHARS) {
                throw new IllegalArgumentException(
                        "trace exceeds the total raw character bound");
            }
        }
    }

    private List<String> serviceSequence(List<TraceEntry> entries) {
        List<String> sequence = new ArrayList<>();
        String previous = null;
        for (TraceEntry entry : entries) {
            if (!entry.service().equals(previous)) {
                sequence.add(entry.service());
                previous = entry.service();
            }
        }
        if (sequence.size() > MAX_SERVICE_TRANSITIONS) {
            throw new IllegalArgumentException(
                    "trace has too many service transitions to compress safely");
        }
        return List.copyOf(sequence);
    }

    private Set<Integer> selectedTimelineIndexes(
            int entryCount,
            List<Integer> anomalyIndexes) {
        Set<Integer> selected = new HashSet<>();
        selected.add(0);
        selected.add(entryCount - 1);
        selected.addAll(anomalyIndexes);
        if (selected.size() > MAX_TIMELINE_EVENTS) {
            throw new IllegalArgumentException(
                    "trace has too many anomaly events to compress safely");
        }

        List<Integer> normalIndexes = new ArrayList<>();
        for (int index = 0; index < entryCount; index++) {
            if (!selected.contains(index)) {
                normalIndexes.add(index);
            }
        }
        int remaining = MAX_TIMELINE_EVENTS - selected.size();
        if (normalIndexes.size() <= remaining) {
            selected.addAll(normalIndexes);
            return selected;
        }
        for (int sample = 0; sample < remaining; sample++) {
            int position = (int) ((long) sample * normalIndexes.size() / remaining);
            selected.add(normalIndexes.get(position));
        }
        return selected;
    }

    private LogTraceSkeleton.TimelineEvent timelineEvent(
            int index,
            TraceEntry entry,
            long startedAt) {
        return new LogTraceSkeleton.TimelineEvent(
                index,
                entry.timestamp() - startedAt,
                entry.service(),
                entry.level(),
                entry.message(),
                entry.durationMs(),
                entry.anomalous());
    }

    private Map<String, LogTraceSkeleton.DurationSummary> durationSummary(
            List<TraceEntry> entries) {
        Map<String, DurationAccumulator> accumulators = new LinkedHashMap<>();
        for (TraceEntry entry : entries) {
            if (entry.durationMs() != null) {
                accumulators.computeIfAbsent(
                                entry.service(), ignored -> new DurationAccumulator())
                        .add(entry.durationMs());
            }
        }
        Map<String, LogTraceSkeleton.DurationSummary> summaries = new LinkedHashMap<>();
        accumulators.forEach((service, accumulator) ->
                summaries.put(service, accumulator.summary()));
        return summaries;
    }

    private boolean anomalous(String level, String message) {
        if (!NORMAL_LEVELS.contains(level)) {
            return true;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return ANOMALY_TERMS.stream().anyMatch(normalized::contains);
    }

    private String boundedMessage(String value) {
        if (value.length() <= MAX_MESSAGE_CHARS) {
            return value;
        }
        int prefixLength = MAX_MESSAGE_CHARS - TRUNCATION_MARKER.length();
        if (prefixLength > 0 && Character.isHighSurrogate(value.charAt(prefixLength - 1))) {
            prefixLength--;
        }
        return value.substring(0, prefixLength) + TRUNCATION_MARKER;
    }

    private String sanitizedText(Object raw, String field, int maxChars) {
        String normalized = rawText(raw, field, maxChars);
        String sanitized = TroubleshootingSecretRedactor.redact(normalized);
        if (sanitized.length() > maxChars) {
            throw new IllegalArgumentException(field + " exceeds the safe character bound");
        }
        return sanitized;
    }

    private String rawText(Object raw, String field, int maxChars) {
        rawTextLength(raw, field, maxChars);
        return ((String) raw).trim();
    }

    private int rawTextLength(Object raw, String field, int maxChars) {
        if (!(raw instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-blank string");
        }
        if (value.length() > maxChars) {
            throw new IllegalArgumentException(field + " exceeds the raw character bound");
        }
        return value.length();
    }

    private long exactLong(Object raw, String field) {
        Long value = CanonicalNumberParser.parseExactLong(raw);
        if (value == null) {
            throw new IllegalArgumentException(
                    field + " must be an integer or canonical decimal integer string");
        }
        return value;
    }

    private Double optionalDuration(Object raw) {
        if (raw == null) {
            return null;
        }
        Double duration = CanonicalNumberParser.parseFiniteNonNegativeDouble(raw);
        if (duration == null) {
            throw new IllegalArgumentException(
                    "duration_ms must be finite, non-negative, and canonical");
        }
        return duration;
    }

    private record TraceEntry(
            int originalIndex,
            long timestamp,
            String service,
            String level,
            String message,
            Double durationMs,
            boolean anomalous) {
    }

    private static final class DurationAccumulator {
        private int count;
        private double min = Double.POSITIVE_INFINITY;
        private double max;
        private double sum;

        private void add(double value) {
            count++;
            min = Math.min(min, value);
            max = Math.max(max, value);
            sum += value;
        }

        private LogTraceSkeleton.DurationSummary summary() {
            return new LogTraceSkeleton.DurationSummary(count, min, max, sum / count);
        }
    }
}
