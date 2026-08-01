package vip.mate.troubleshooting.synthesis;

import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.engine.Criterion;
import vip.mate.troubleshooting.engine.CriterionEvaluator;
import vip.mate.troubleshooting.engine.DiagnosisRuleEvaluator;
import vip.mate.troubleshooting.model.AnomalyCriterion;
import vip.mate.troubleshooting.model.Confidence;
import vip.mate.troubleshooting.model.DiagnosisRule;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.SopEntry;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ManualPlaybookReplayEvaluatorTest {

    private final ManualPlaybookReplayEvaluator evaluator =
            new ManualPlaybookReplayEvaluator(
                    new CriterionEvaluator(), new DiagnosisRuleEvaluator());

    @Test
    void passesOnlyWhenTheExactCandidateSeparatesPositiveNegativeAndAbstainCases() {
        ManualPlaybookReplayEvaluation result = evaluator.evaluate(
                candidate(new Criterion.NumericGte("failed_probe_count", 1),
                        requiredTarget()),
                topologySuite());

        assertThat(result.passed()).isTrue();
        assertThat(result.positiveTotal()).isEqualTo(1);
        assertThat(result.positivePassed()).isEqualTo(1);
        assertThat(result.negativeOrAbstainTotal()).isEqualTo(2);
        assertThat(result.negativeOrAbstainPassed()).isEqualTo(2);
        assertThat(result.failureCodes()).isEmpty();
    }

    @Test
    void failsWhenTheCandidateTurnsTheHealthyNegativeIntoAPositiveMatch() {
        ManualPlaybookReplayEvaluation result = evaluator.evaluate(
                candidate(new Criterion.NumericGte("failed_probe_count", 0),
                        requiredTarget()),
                topologySuite());

        assertThat(result.passed()).isFalse();
        assertThat(result.positivePassed()).isEqualTo(1);
        assertThat(result.negativeOrAbstainPassed()).isEqualTo(1);
        assertThat(result.failureCodes())
                .containsExactly("NEGATIVE_OR_ABSTAIN_EXPECTATION_MISMATCH");
    }

    @Test
    void failsBeforeCasesWhenTheCandidateDoesNotOwnTheServerRequiredToolContract() {
        ManualPlaybookReplayEvaluation result = evaluator.evaluate(
                candidate(new Criterion.NumericGte("failed_probe_count", 1),
                        Map.of("assetType", "deployment_topology",
                                "toolKey", "browser_supplied_tool")),
                topologySuite());

        assertThat(result.passed()).isFalse();
        assertThat(result.positivePassed()).isZero();
        assertThat(result.negativeOrAbstainPassed()).isZero();
        assertThat(result.failureCodes()).containsExactly("SUITE_CONTRACT_MISMATCH");
    }

    @Test
    void failsWhenAnyCandidateEvidenceRequestIsNotCoveredByEveryReplayCase() {
        SopEntry base = candidate(
                new Criterion.NumericGte("failed_probe_count", 1), requiredTarget());
        SopEntry candidateWithUncoveredRequiredEvidence = new SopEntry(
                base.sopId(), base.contractVersion(), base.system(), base.errorCode(),
                base.service(), base.title(), base.cause(), base.category(),
                base.ownerTeam(), base.status(), base.verified(),
                List.of(
                        base.evidenceRequests().getFirst(),
                        new EvidenceRequest(
                                "EV-UNTESTED", "log_search", "额外必需证据",
                                Map.of("toolKey", "log_search"), "-15m", true)),
                base.anomalyCriteria(), base.diagnosisRules(), base.actions());

        ManualPlaybookReplayEvaluation result = evaluator.evaluate(
                candidateWithUncoveredRequiredEvidence, topologySuite());

        assertThat(result.passed()).isFalse();
        assertThat(result.failureCodes()).containsExactly("SUITE_CONTRACT_MISMATCH");
    }

    private SopEntry candidate(Criterion criterion, Map<String, Object> target) {
        return new SopEntry(
                "manual-deployment-topology-probe-v1",
                SopEntry.CURRENT_CONTRACT_VERSION,
                "CSDP",
                "scenario:deployment_topology_probe",
                "csp-prm-miniapp",
                "部署拓扑拨测",
                "网络路径待核查",
                "network",
                "网络平台组",
                "candidate",
                false,
                List.of(new EvidenceRequest(
                        "EV-TOPOLOGY",
                        "synthetic_probe",
                        "执行服务端授权的部署拓扑拨测",
                        target,
                        "-15m",
                        true)),
                List.of(new AnomalyCriterion(
                        "failed_probe_present",
                        "EV-TOPOLOGY",
                        "存在失败拨测节点",
                        criterion)),
                List.of(new DiagnosisRule(
                        "RULE-TOPOLOGY-FAILURE",
                        List.of("failed_probe_present"),
                        "部署拓扑存在失败拨测节点",
                        "沿失败节点相邻链路继续核查",
                        Confidence.MEDIUM,
                        false)),
                List.of());
    }

    private ManualPlaybookReplaySuite topologySuite() {
        return new ManualPlaybookReplaySuite(
                ManualPlaybookReplaySuite.CONTRACT_VERSION,
                "deployment-topology-probe/v1",
                1,
                "csdp:scenario:deployment_topology_probe",
                new ManualPlaybookReplaySuite.RequiredEvidenceRequest(
                        "EV-TOPOLOGY",
                        "synthetic_probe",
                        true,
                        requiredTarget()),
                List.of(
                        new ManualPlaybookReplaySuite.ReplayCase(
                                "failed-probe-positive",
                                ManualPlaybookReplaySuite.Cohort.POSITIVE,
                                ManualPlaybookReplaySuite.Disposition.MATCHED,
                                "RULE-TOPOLOGY-FAILURE",
                                List.of(evidence(
                                        EvidenceStatus.ANOMALY,
                                        Map.of("failed_probe_count", 1,
                                                "observed_probe_count", 1)))),
                        new ManualPlaybookReplaySuite.ReplayCase(
                                "healthy-probe-negative",
                                ManualPlaybookReplaySuite.Cohort.NEGATIVE_OR_ABSTAIN,
                                ManualPlaybookReplaySuite.Disposition.EXCLUDED,
                                null,
                                List.of(evidence(
                                        EvidenceStatus.NORMAL,
                                        Map.of("failed_probe_count", 0,
                                                "observed_probe_count", 1)))),
                        new ManualPlaybookReplaySuite.ReplayCase(
                                "probe-unavailable-abstain",
                                ManualPlaybookReplaySuite.Cohort.NEGATIVE_OR_ABSTAIN,
                                ManualPlaybookReplaySuite.Disposition.ABSTAINED,
                                null,
                                List.of(evidence(EvidenceStatus.MISSING, Map.of())))));
    }

    private ManualPlaybookReplaySuite.ReplayEvidence evidence(
            EvidenceStatus status,
            Map<String, Object> observed) {
        return new ManualPlaybookReplaySuite.ReplayEvidence(
                "EV-TOPOLOGY", status, observed);
    }

    private Map<String, Object> requiredTarget() {
        return Map.of(
                "assetType", "deployment_topology",
                "toolKey", "topology_synthetic_probe");
    }
}
