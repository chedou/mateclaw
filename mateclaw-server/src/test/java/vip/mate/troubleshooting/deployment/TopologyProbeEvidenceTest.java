package vip.mate.troubleshooting.deployment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The translation from "a tool ran" to "the evidence request was answered".
 * Each case here is a different honest answer, and the ways they could be
 * flattened into each other are the reason the class exists.
 */
class TopologyProbeEvidenceTest {

    private static final Instant NOW = Instant.parse("2026-07-30T12:00:00Z");

    @Test
    @DisplayName("观测到失败节点：ANOMALY，并带上判据要读的计数")
    void aFailingProbeReportsTheCountTheCriterionThresholdsOn() {
        EvidenceResult evidence = translate(
                DeploymentTopologySopResult.AnalysisStatus.NETWORK_PROBLEM_DETECTED,
                new DeploymentTopologySopResult.Summary(2, 1, 2, 2, 1, 1, 0));

        assertThat(evidence.queryId()).isEqualTo("EV-TOPOLOGY");
        assertThat(evidence.status()).isEqualTo(EvidenceStatus.ANOMALY);
        assertThat(evidence.observed())
                .containsEntry(TopologyProbeEvidence.FAILED_PROBE_COUNT, 1);
        assertThat(evidence.source()).isEqualTo("topology_synthetic_probe");
        assertThat(evidence.collectedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("全覆盖且全部正常：NORMAL 且计数为 0，足以反证候选根因")
    void aFullyObservedHealthyProbeAssertsZeroFailures() {
        EvidenceResult evidence = translate(
                DeploymentTopologySopResult.AnalysisStatus.NO_PROBLEM_OBSERVED,
                new DeploymentTopologySopResult.Summary(2, 1, 2, 2, 2, 0, 0));

        assertThat(evidence.status()).isEqualTo(EvidenceStatus.NORMAL);
        assertThat(evidence.observed())
                .containsEntry(TopologyProbeEvidence.FAILED_PROBE_COUNT, 0);
    }

    /**
     * The one that matters. A zero here would be "zero among the ones we
     * reached", and once it is inside a criterion nothing distinguishes it from
     * "zero" — the candidate root cause would come back EXCLUDED on the strength
     * of nodes nobody looked at. So the count is withheld and the criterion
     * stays unevaluated, which the engine already keeps separate from excluded.
     */
    @Test
    @DisplayName("覆盖不完整时不给出失败计数，判据只能是「未求值」而非「已反证」")
    void partialCoverageWithholdsTheCountItDoesNotHonestlyHave() {
        EvidenceResult evidence = translate(
                DeploymentTopologySopResult.AnalysisStatus.PARTIAL_OBSERVATION,
                new DeploymentTopologySopResult.Summary(3, 2, 3, 2, 2, 0, 1));

        assertThat(evidence.observed())
                .as("未覆盖的节点不允许被算作「没有失败」")
                .doesNotContainKey(TopologyProbeEvidence.FAILED_PROBE_COUNT);
        assertThat(evidence.observed())
                .containsEntry(TopologyProbeEvidence.OBSERVED_PROBE_COUNT, 2)
                .containsEntry(TopologyProbeEvidence.CONFIGURED_PROBE_COUNT, 3);
        assertThat(evidence.summary()).contains("不作为「已排除」");
    }

    @Test
    @DisplayName("一个都没观测到 / 没有配置拨测节点：MISSING，这是没取到证据")
    void nothingObservedIsMissingEvidenceRatherThanAQuietNormal() {
        assertThat(translate(
                DeploymentTopologySopResult.AnalysisStatus.INSUFFICIENT_EVIDENCE,
                new DeploymentTopologySopResult.Summary(2, 1, 2, 0, 0, 0, 2)).status())
                .isEqualTo(EvidenceStatus.MISSING);
        assertThat(translate(
                DeploymentTopologySopResult.AnalysisStatus.NO_PROBES_CONFIGURED,
                new DeploymentTopologySopResult.Summary(2, 1, 0, 0, 0, 0, 0)).status())
                .isEqualTo(EvidenceStatus.MISSING);
    }

    private EvidenceResult translate(
            DeploymentTopologySopResult.AnalysisStatus status,
            DeploymentTopologySopResult.Summary summary) {
        EvidenceRequest request = new EvidenceRequest(
                "EV-TOPOLOGY", "synthetic_probe", "执行服务端授权的部署拓扑拨测",
                Map.of(
                        "assetType", DeploymentTopologyScenarioPolicy.ASSET_TYPE,
                        "toolKey", DeploymentTopologyScenarioPolicy.TOOL_KEY),
                "-15m", true);
        DeploymentTopologySopResult result = new DeploymentTopologySopResult(
                "1.0", "csp-deployment", "CSP 部署架构", NOW.minusSeconds(60),
                "synthetic_probe", status, summary,
                List.of(), List.of(), List.of(), List.of(),
                NOW, false, true);
        return TopologyProbeEvidence.from(request, "topology-1", result);
    }
}
