package vip.mate.troubleshooting.model;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Duration;
import java.time.Instant;

/**
 * Four observable D14 timestamps and three deliberately separate human-cost intervals.
 *
 * <p>Legacy diagnoses use {@link #unrecorded()}; a partially fabricated timing is rejected.
 * A real investigation records report, readiness and conclusion together, then adds the first
 * human handoff later.</p>
 */
public record NorthStarTimings(
        Instant reportedAt,
        Instant readyAt,
        Instant conclusionAt,
        Instant handoffAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Duration intakeCost,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Duration investigateCost,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Duration adoptCost) {

    public NorthStarTimings {
        if (reportedAt == null && readyAt == null && conclusionAt == null && handoffAt == null
                && intakeCost == null && investigateCost == null && adoptCost == null) {
            // A truthful legacy state: no timing was recorded at all.
        } else {
            if (reportedAt == null || readyAt == null || conclusionAt == null) {
                throw new IllegalArgumentException(
                        "reportedAt, readyAt and conclusionAt must be recorded together");
            }
            if (readyAt.isBefore(reportedAt) || conclusionAt.isBefore(readyAt)) {
                throw new IllegalArgumentException("north-star timestamps must be chronological");
            }
            if (handoffAt != null && handoffAt.isBefore(conclusionAt)) {
                throw new IllegalArgumentException("handoffAt cannot precede conclusionAt");
            }
            Duration expectedIntake = Duration.between(reportedAt, readyAt);
            Duration expectedInvestigate = Duration.between(readyAt, conclusionAt);
            Duration expectedAdopt = handoffAt == null
                    ? null : Duration.between(conclusionAt, handoffAt);
            if (!expectedIntake.equals(intakeCost)
                    || !expectedInvestigate.equals(investigateCost)
                    || (expectedAdopt == null
                            ? adoptCost != null : !expectedAdopt.equals(adoptCost))) {
                throw new IllegalArgumentException(
                        "north-star interval values must be derivable from timestamps");
            }
        }
    }

    public static NorthStarTimings unrecorded() {
        return new NorthStarTimings(null, null, null, null, null, null, null);
    }

    public static NorthStarTimings concluded(
            Instant reportedAt,
            Instant readyAt,
            Instant conclusionAt) {
        return new NorthStarTimings(
                reportedAt,
                readyAt,
                conclusionAt,
                null,
                Duration.between(reportedAt, readyAt),
                Duration.between(readyAt, conclusionAt),
                null);
    }

    public boolean recorded() {
        return reportedAt != null;
    }

    /** The first human transition owns handoff; later transitions preserve it. */
    public NorthStarTimings withHandoff(Instant firstHandoffAt) {
        if (!recorded() || handoffAt != null) {
            return this;
        }
        if (firstHandoffAt == null) {
            throw new IllegalArgumentException("firstHandoffAt is required");
        }
        return new NorthStarTimings(
                reportedAt,
                readyAt,
                conclusionAt,
                firstHandoffAt,
                intakeCost,
                investigateCost,
                Duration.between(conclusionAt, firstHandoffAt));
    }
}
