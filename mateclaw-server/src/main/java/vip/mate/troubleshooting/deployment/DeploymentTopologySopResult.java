package vip.mate.troubleshooting.deployment;

import java.time.Instant;
import java.util.List;

/** Secret-free, non-persistent result of one deployment-topology probe analysis. */
public record DeploymentTopologySopResult(
        String schemaVersion,
        String system,
        String systemLabel,
        Instant snapshotExportedAt,
        String signalKind,
        AnalysisStatus status,
        Summary summary,
        List<NodeObservation> observations,
        List<SuspectLink> suspectLinks,
        List<String> unconfiguredNodeKeys,
        List<String> warnings,
        Instant completedAt,
        boolean modelCalled,
        boolean persisted) {

    public enum AnalysisStatus {
        NO_PROBES_CONFIGURED,
        INSUFFICIENT_EVIDENCE,
        PARTIAL_OBSERVATION,
        NETWORK_PROBLEM_DETECTED,
        NO_PROBLEM_OBSERVED
    }

    public enum ObservationStatus {
        HEALTHY,
        FAILED,
        UNAVAILABLE,
        IDENTITY_MISMATCH
    }

    public record Summary(
            int nodeCount,
            int linkCount,
            int configuredProbeNodes,
            int observedProbeNodes,
            int healthyProbeNodes,
            int failingProbeNodes,
            int unavailableProbeNodes) {
    }

    public record NodeObservation(
            String nodeKey,
            String label,
            String type,
            String targetUrl,
            String probeName,
            String window,
            ObservationStatus status,
            Integer statusCode,
            String observedTargetUrl,
            String observedProbeName,
            String evidenceRef,
            String detail,
            Instant collectedAt) {
    }

    /** Topology-adjacent hint only; it is not a proven failing hop or root cause. */
    public record SuspectLink(String source, String target, String reason) {
    }
}
