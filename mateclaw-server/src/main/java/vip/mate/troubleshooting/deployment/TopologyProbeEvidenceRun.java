package vip.mate.troubleshooting.deployment;

import java.time.Instant;

/** Immutable, secret-free topology evidence projection owned by one Diagnosis. */
public record TopologyProbeEvidenceRun(
        String runId,
        String diagnosisId,
        String topologyId,
        String scenarioKey,
        String toolKey,
        DeploymentTopologySopResult result,
        Instant startedAt,
        Instant completedAt,
        String actorRef) {
}
