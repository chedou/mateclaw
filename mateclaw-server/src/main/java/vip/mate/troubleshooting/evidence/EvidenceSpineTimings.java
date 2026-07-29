package vip.mate.troubleshooting.evidence;

/**
 * Application-side wall-clock measurements for one Evidence Spine collection.
 *
 * <p>Source durations are Router round trips observed by MateClaw, not source-side
 * query execution latency. Compression duration is the sum of deterministic local
 * compression passes. Null means that a stage was not measured or not reached;
 * zero is a valid sub-millisecond measurement.</p>
 */
public record EvidenceSpineTimings(
        Long logSearchDurationMs,
        Long logTraceDurationMs,
        Long contrastDurationMs,
        Long compressionDurationMs) {

    public EvidenceSpineTimings {
        nonNegative(logSearchDurationMs, "logSearchDurationMs");
        nonNegative(logTraceDurationMs, "logTraceDurationMs");
        nonNegative(contrastDurationMs, "contrastDurationMs");
        nonNegative(compressionDurationMs, "compressionDurationMs");
        if (logTraceDurationMs != null && logSearchDurationMs == null) {
            throw new IllegalArgumentException(
                    "log trace timing requires a preceding log search timing");
        }
        if (compressionDurationMs != null && logTraceDurationMs == null) {
            throw new IllegalArgumentException(
                    "compression timing requires a preceding trace timing");
        }
        if (contrastDurationMs != null
                && (logTraceDurationMs == null || compressionDurationMs == null)) {
            throw new IllegalArgumentException(
                    "contrast timing requires the measured core trace and compression");
        }
    }

    public static EvidenceSpineTimings unmeasured() {
        return new EvidenceSpineTimings(null, null, null, null);
    }

    /** True only when all three source round trips and deterministic compression were measured. */
    public boolean complete() {
        return logSearchDurationMs != null
                && logTraceDurationMs != null
                && contrastDurationMs != null
                && compressionDurationMs != null;
    }

    /** Sum of the three source round trips, or null when the full source chain was not measured. */
    public Long evidenceAcquisitionDurationMs() {
        if (logSearchDurationMs == null
                || logTraceDurationMs == null
                || contrastDurationMs == null) {
            return null;
        }
        return Math.addExact(
                Math.addExact(logSearchDurationMs, logTraceDurationMs),
                contrastDurationMs);
    }

    /** Measured source round trips plus deterministic compression, excluding outer overhead. */
    public Long measuredWorkDurationMs() {
        Long acquisitionDurationMs = evidenceAcquisitionDurationMs();
        if (acquisitionDurationMs == null || compressionDurationMs == null) {
            return null;
        }
        return Math.addExact(acquisitionDurationMs, compressionDurationMs);
    }

    /** Sum of every stage actually measured, including a degraded or missing contrast lookup. */
    public long observedWorkDurationMs() {
        long total = 0L;
        for (Long value : new Long[] {
                logSearchDurationMs,
                logTraceDurationMs,
                contrastDurationMs,
                compressionDurationMs}) {
            if (value != null) {
                total = Math.addExact(total, value);
            }
        }
        return total;
    }

    private static void nonNegative(Long value, String name) {
        if (value != null && value < 0L) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
