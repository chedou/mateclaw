package vip.mate.troubleshooting.deployment;

import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Translates one topology probe analysis into the canonical evidence the rule
 * engine reads. Pure, zero LLM.
 *
 * <p><b>Why the translation is a named thing.</b> Before this, a probe run
 * produced a rich result that lived only in its own table, and the Diagnosis
 * that requested it never learned the answer — it waited in
 * {@code NEEDS_INVESTIGATION} forever. Running a tool is not the same as
 * answering the evidence request that asked for it; this is where the second
 * becomes true.</p>
 *
 * <p><b>The distinction that carries the weight.</b> Partial coverage with no
 * observed failure is not a clean bill of health. If it were reported as
 * {@code failed_probe_count = 0}, the criterion would evaluate false, the
 * candidate root cause would be marked {@code EXCLUDED}, and the console would
 * say the network was ruled out on the strength of nodes nobody looked at. So
 * that case reports the coverage it had and withholds the count it does not
 * honestly have, leaving the criterion {@code UNEVALUATED} — which the engine
 * already keeps separate from {@code EXCLUDED}, precisely for this.</p>
 */
public final class TopologyProbeEvidence {

    /** The criterion field the topology scenario Playbook thresholds on. */
    public static final String FAILED_PROBE_COUNT = "failed_probe_count";
    public static final String OBSERVED_PROBE_COUNT = "observed_probe_count";
    public static final String CONFIGURED_PROBE_COUNT = "configured_probe_count";
    public static final String UNAVAILABLE_PROBE_COUNT = "unavailable_probe_count";

    private static final String NAMESPACE = "deployment_topology";

    private TopologyProbeEvidence() {
    }

    public static EvidenceResult from(
            EvidenceRequest request,
            String topologyId,
            DeploymentTopologySopResult result) {
        if (request == null || result == null) {
            throw new IllegalArgumentException("evidence request and probe result are required");
        }
        DeploymentTopologySopResult.Summary summary = result.summary();
        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put(CONFIGURED_PROBE_COUNT, summary.configuredProbeNodes());
        observed.put(OBSERVED_PROBE_COUNT, summary.observedProbeNodes());
        observed.put(UNAVAILABLE_PROBE_COUNT, summary.unavailableProbeNodes());

        EvidenceStatus status = switch (result.status()) {
            case NO_PROBES_CONFIGURED, INSUFFICIENT_EVIDENCE -> EvidenceStatus.MISSING;
            case NETWORK_PROBLEM_DETECTED -> EvidenceStatus.ANOMALY;
            case PARTIAL_OBSERVATION, NO_PROBLEM_OBSERVED -> EvidenceStatus.NORMAL;
        };
        // Only a complete observation may assert a failure count. Under partial
        // coverage the number would be "zero among the ones we reached", which
        // reads identically to "zero" once it is in a criterion.
        if (result.status() == DeploymentTopologySopResult.AnalysisStatus.NETWORK_PROBLEM_DETECTED
                || result.status() == DeploymentTopologySopResult.AnalysisStatus.NO_PROBLEM_OBSERVED) {
            observed.put(FAILED_PROBE_COUNT, summary.failingProbeNodes());
        }

        return new EvidenceResult(
                request.requestId(),
                NAMESPACE,
                "topologyId=" + (topologyId == null ? "" : topologyId),
                status,
                summarize(result),
                observed,
                String.valueOf(request.target().get("toolKey")),
                result.completedAt());
    }

    private static String summarize(DeploymentTopologySopResult result) {
        DeploymentTopologySopResult.Summary summary = result.summary();
        return switch (result.status()) {
            case NO_PROBES_CONFIGURED -> "拓扑内没有配置拨测节点，本次未取得任何观测。";
            case INSUFFICIENT_EVIDENCE -> "已配置 " + summary.configuredProbeNodes()
                    + " 个拨测节点，但全部未返回观测。";
            case NETWORK_PROBLEM_DETECTED -> "观测到 " + summary.failingProbeNodes()
                    + " 个失败拨测节点（已观测 " + summary.observedProbeNodes() + " 个）。";
            case PARTIAL_OBSERVATION -> "已观测 " + summary.observedProbeNodes() + "/"
                    + summary.configuredProbeNodes()
                    + " 个拨测节点，未见失败；覆盖不完整，不作为「已排除」。";
            case NO_PROBLEM_OBSERVED -> "全部 " + summary.observedProbeNodes()
                    + " 个拨测节点均正常，未观测到网络问题。";
        };
    }
}
