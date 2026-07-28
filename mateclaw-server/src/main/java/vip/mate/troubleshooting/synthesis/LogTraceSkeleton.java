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
        int omittedEntryCount,
        ContrastSummary contrast) {

    public LogTraceSkeleton {
        psId = required(psId, "psId");
        serviceSequence = List.copyOf(serviceSequence == null ? List.of() : serviceSequence);
        timeline = List.copyOf(timeline == null ? List.of() : timeline);
        anomalySequenceIndexes = List.copyOf(
                anomalySequenceIndexes == null ? List.of() : anomalySequenceIndexes);
        durationByService = Collections.unmodifiableMap(
                new LinkedHashMap<>(durationByService == null ? Map.of() : durationByService));
        contrast = contrast == null ? ContrastSummary.unavailable() : contrast;
        if (startedAtEpochMs < 0 || endedAtEpochMs < startedAtEpochMs || elapsedMs < 0) {
            throw new IllegalArgumentException("trace timestamps must be chronological");
        }
        if (sourceEntryCount <= 0
                || omittedEntryCount < 0
                || timeline.size() + omittedEntryCount != sourceEntryCount) {
            throw new IllegalArgumentException("trace skeleton counts are inconsistent");
        }
    }

    /** Backward-compatible constructor for callers that have no control sample. */
    public LogTraceSkeleton(
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
        this(psId, startedAtEpochMs, endedAtEpochMs, elapsedMs, serviceSequence,
                timeline, anomalySequenceIndexes, durationByService, sourceEntryCount,
                omittedEntryCount, ContrastSummary.unavailable());
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

    /** Deterministic failed-versus-successful sample delta exposed to the model. */
    public record ContrastSummary(
            boolean available,
            String discriminatingFeature,
            long failureSampleCount,
            long failureMatchCount,
            long successSampleCount,
            long successMatchCount,
            double failureRate,
            double successRate,
            double rateDelta) {

        public ContrastSummary {
            discriminatingFeature = discriminatingFeature == null
                    ? ""
                    : discriminatingFeature.trim();
            if (!available) {
                if (failureSampleCount != 0 || failureMatchCount != 0
                        || successSampleCount != 0 || successMatchCount != 0
                        || failureRate != 0 || successRate != 0 || rateDelta != 0) {
                    throw new IllegalArgumentException(
                            "unavailable contrast must not contain invented measurements");
                }
            } else if (discriminatingFeature.isBlank()
                    || failureSampleCount <= 0
                    || successSampleCount <= 0
                    || failureMatchCount < 0
                    || successMatchCount < 0
                    || failureMatchCount > failureSampleCount
                    || successMatchCount > successSampleCount
                    || !unit(failureRate)
                    || !unit(successRate)
                    || !Double.isFinite(rateDelta)
                    || rateDelta < -1
                    || rateDelta > 1) {
                throw new IllegalArgumentException("contrast summary is invalid");
            }
        }

        public static ContrastSummary unavailable() {
            return new ContrastSummary(false, "", 0, 0, 0, 0, 0, 0, 0);
        }

        private static boolean unit(double value) {
            return Double.isFinite(value) && value >= 0 && value <= 1;
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
