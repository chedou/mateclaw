package vip.mate.troubleshooting.synthesis;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded, platform-neutral call-chain skeleton produced before any model is
 * allowed to see a trace bundle.
 */
public record LogTraceSkeleton(
        String psId,
        long startedAtEpochMs,
        long endedAtEpochMs,
        long elapsedMs,
        List<String> serviceSequence,
        List<TimelineEvent> timeline,
        List<Integer> anomalySequenceIndexes,
        Map<String, DurationSummary> durationByService,
        int sourceEntryCount,
        int omittedEntryCount) {

    public LogTraceSkeleton {
        psId = required(psId, "psId");
        serviceSequence = List.copyOf(serviceSequence == null ? List.of() : serviceSequence);
        timeline = List.copyOf(timeline == null ? List.of() : timeline);
        anomalySequenceIndexes = List.copyOf(
                anomalySequenceIndexes == null ? List.of() : anomalySequenceIndexes);
        durationByService = Collections.unmodifiableMap(
                new LinkedHashMap<>(durationByService == null ? Map.of() : durationByService));
        if (startedAtEpochMs < 0 || endedAtEpochMs < startedAtEpochMs || elapsedMs < 0) {
            throw new IllegalArgumentException("trace timestamps must be chronological");
        }
        if (sourceEntryCount <= 0
                || omittedEntryCount < 0
                || timeline.size() + omittedEntryCount != sourceEntryCount) {
            throw new IllegalArgumentException("trace skeleton counts are inconsistent");
        }
    }

    public record TimelineEvent(
            int sequenceIndex,
            long offsetMs,
            String service,
            String level,
            String message,
            Double durationMs,
            boolean anomalous) {

        public TimelineEvent {
            service = required(service, "service");
            level = required(level, "level");
            message = required(message, "message");
            if (sequenceIndex < 0 || offsetMs < 0) {
                throw new IllegalArgumentException("timeline position must not be negative");
            }
            if (durationMs != null && (!Double.isFinite(durationMs) || durationMs < 0)) {
                throw new IllegalArgumentException("durationMs must be finite and non-negative");
            }
        }
    }

    public record DurationSummary(
            int sampleCount,
            double minMs,
            double maxMs,
            double averageMs) {

        public DurationSummary {
            if (sampleCount <= 0
                    || !Double.isFinite(minMs)
                    || !Double.isFinite(maxMs)
                    || !Double.isFinite(averageMs)
                    || minMs < 0
                    || maxMs < minMs
                    || averageMs < minMs
                    || averageMs > maxMs) {
                throw new IllegalArgumentException("duration summary is invalid");
            }
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
