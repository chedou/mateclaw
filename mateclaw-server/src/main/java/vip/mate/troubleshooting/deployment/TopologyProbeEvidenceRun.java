package vip.mate.troubleshooting.deployment;

import java.time.Instant;

/**
 * Immutable, secret-free topology evidence projection owned by one Diagnosis.
 *
 * <p>{@code conclusionUpdated} says whether this run's result was fed back into
 * the Diagnosis and re-decided, or whether it was recorded as a look-again on an
 * investigation that had already moved past waiting for evidence. Rows written
 * before the write-back existed deserialize to {@code false}, which is exactly
 * what happened to them.</p>
 */
public record TopologyProbeEvidenceRun(
        String runId,
        String diagnosisId,
        String topologyId,
        String scenarioKey,
        String toolKey,
        DeploymentTopologySopResult result,
        Instant startedAt,
        Instant completedAt,
        String actorRef,
        boolean conclusionUpdated) {
}
