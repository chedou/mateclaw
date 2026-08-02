package vip.mate.troubleshooting.synthesis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.engine.Criterion;
import vip.mate.troubleshooting.model.AnomalyCriterion;
import vip.mate.troubleshooting.model.Confidence;
import vip.mate.troubleshooting.model.DiagnosisRule;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.SopEntry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ManualPlaybookReplayFingerprintTest {

    private final ManualPlaybookReplayFingerprint fingerprints =
            new ManualPlaybookReplayFingerprint(
                    new ObjectMapper().findAndRegisterModules());

    @Test
    void candidateFingerprintIgnoresMapInsertionOrderButChangesWithContractContent() {
        Map<String, Object> leftTarget = new LinkedHashMap<>();
        leftTarget.put("assetType", "deployment_topology");
        leftTarget.put("toolKey", "topology_synthetic_probe");
        Map<String, Object> rightTarget = new LinkedHashMap<>();
        rightTarget.put("toolKey", "topology_synthetic_probe");
        rightTarget.put("assetType", "deployment_topology");

        String left = fingerprints.candidate(candidate(leftTarget, 1));
        String right = fingerprints.candidate(candidate(rightTarget, 1));
        String changed = fingerprints.candidate(candidate(rightTarget, 2));

        assertThat(left).matches("[a-f0-9]{64}").isEqualTo(right);
        assertThat(changed).isNotEqualTo(left);
    }

    @Test
    void evidenceRequestFingerprintIsCanonicalAndChangesWithTheExactRequestContract() {
        Map<String, Object> leftTarget = new LinkedHashMap<>();
        leftTarget.put("search_term", "SendMsg");
        leftTarget.put("component", "producer");
        Map<String, Object> rightTarget = new LinkedHashMap<>();
        rightTarget.put("component", "producer");
        rightTarget.put("search_term", "SendMsg");

        EvidenceRequest left = new EvidenceRequest(
                "EV-SEARCH", "log_search", "find failed samples",
                leftTarget, "-15m", true);
        EvidenceRequest right = new EvidenceRequest(
                "EV-SEARCH", "log_search", "find failed samples",
                rightTarget, "-15m", true);
        EvidenceRequest changed = new EvidenceRequest(
                "EV-SEARCH", "log_search", "find failed samples",
                rightTarget, "-30m", true);

        assertThat(fingerprints.evidenceRequest(left))
                .matches("[a-f0-9]{64}")
                .isEqualTo(fingerprints.evidenceRequest(right));
        assertThat(fingerprints.evidenceRequest(changed))
                .isNotEqualTo(fingerprints.evidenceRequest(left));
    }

    private SopEntry candidate(Map<String, Object> target, double threshold) {
        return new SopEntry(
                "manual-topology-v1", "sop.v1", "CSDP",
                "scenario:deployment_topology_probe", "network-path",
                "部署拓扑拨测", "网络路径待核查", "network", "网络平台组",
                "candidate", false,
                List.of(new EvidenceRequest(
                        "EV-TOPOLOGY", "synthetic_probe", "只读拨测",
                        target, "-15m", true)),
                List.of(new AnomalyCriterion(
                        "failed_probe_present", "EV-TOPOLOGY", "失败拨测",
                        new Criterion.NumericGte("failed_probe_count", threshold))),
                List.of(new DiagnosisRule(
                        "RULE-TOPOLOGY-FAILURE", List.of("failed_probe_present"),
                        "存在失败拨测", "继续核查相邻链路", Confidence.MEDIUM, false)),
                List.of());
    }
}
