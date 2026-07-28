package vip.mate.troubleshooting.synthesis;

import java.time.Duration;
import java.time.Instant;

/** Four observable timestamps and three deliberately separate human-cost intervals. */
public record NorthStarTimings(
        Instant reportedAt,
        Instant readyAt,
        Instant conclusionAt,
        Instant handoffAt,
        Duration intakeCost,
        Duration investigateCost,
        Duration adoptCost) {

    public NorthStarTimings {
        if (reportedAt == null || readyAt == null || conclusionAt == null) {
            throw new IllegalArgumentException(
                    "reportedAt, readyAt and conclusionAt are required for a synthesis result");
        }
        if (readyAt.isBefore(reportedAt) || conclusionAt.isBefore(readyAt)) {
            throw new IllegalArgumentException("north-star timestamps must be chronological");
        }
        if (handoffAt != null && handoffAt.isBefore(conclusionAt)) {
            throw new IllegalArgumentException("handoffAt cannot precede conclusionAt");
        }
        Duration expectedIntake = Duration.between(reportedAt, readyAt);
        Duration expectedInvestigate = Duration.between(readyAt, conclusionAt);
        Duration expectedAdopt = handoffAt == null ? null : Duration.between(conclusionAt, handoffAt);
        if (!expectedIntake.equals(intakeCost)
                || !expectedInvestigate.equals(investigateCost)
                || (expectedAdopt == null ? adoptCost != null : !expectedAdopt.equals(adoptCost))) {
            throw new IllegalArgumentException("north-star interval values must be derivable from timestamps");
        }
    }

    public static NorthStarTimings concluded(
            Instant reportedAt,
            Instant readyAt,
            Instant conclusionAt) {
        return new NorthStarTimings(
                reportedAt, readyAt, conclusionAt, null,
                Duration.between(reportedAt, readyAt),
                Duration.between(readyAt, conclusionAt),
                null);
    }
}
