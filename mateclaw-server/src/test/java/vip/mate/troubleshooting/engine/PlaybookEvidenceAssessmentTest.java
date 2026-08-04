package vip.mate.troubleshooting.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.model.AnomalyCriterion;
import vip.mate.troubleshooting.model.ConclusionType;
import vip.mate.troubleshooting.model.Confidence;
import vip.mate.troubleshooting.model.DiagnosisRule;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.SopEntry;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 「是哪条规则给出的这条结论」——引擎一直算得出来，此前算完就扔。
 *
 * <p>要它，是因为一次已结案且结论被人确认的调查，正是一份**答案由世界给出**的回放
 * 案例；而回放案例的期望值必须精确到规则 id（{@code ReplayCase} 合同要求 MATCHED
 * 必须指名规则）。事后拿 rootCause 文本反查是猜——rootCause 并不保证唯一。</p>
 */
class PlaybookEvidenceAssessmentTest {

    @Test
    @DisplayName("定位成立时，记下产出这条结论的规则")
    void namesTheRuleThatProducedALocatedConclusion() {
        PlaybookEvidenceAssessment assessment = assess(playbook(), anomaly());

        assertThat(assessment.conclusionType()).isEqualTo(ConclusionType.LOCATED);
        assertThat(assessment.matchedRuleId()).isEqualTo("R-1");
    }

    /**
     * 规则匹配了，但必需证据没取到——结论被降级成「证据不足」。这时结论**不是**那条
     * 规则给出的，它就不该留名，否则回放期望会指向一条其实没裁决过这次调查的规则。
     */
    @Test
    @DisplayName("必需证据缺失把结论降级时，规则不留名")
    void doesNotNameARuleWhenMissingRequiredEvidenceDowngradesTheConclusion() {
        PlaybookEvidenceAssessment assessment = assess(playbook(), List.of());

        assertThat(assessment.conclusionType())
                .isEqualTo(ConclusionType.INSUFFICIENT_EVIDENCE);
        assertThat(assessment.matchedRuleId()).isNull();
    }

    /** 草案 Playbook 只做影子比对，同样不是那条规则在裁决。 */
    @Test
    @DisplayName("Playbook 仍是草案时，规则不留名")
    void doesNotNameARuleWhileThePlaybookIsStillADraft() {
        PlaybookEvidenceAssessment assessment = assess(draftPlaybook(), anomaly());

        assertThat(assessment.conclusionType())
                .isEqualTo(ConclusionType.INSUFFICIENT_EVIDENCE);
        assertThat(assessment.matchedRuleId()).isNull();
    }

    /** 弃权规则匹配落到 INSUFFICIENT_EVIDENCE；回放合同里只有 MATCHED 才允许指名规则。 */
    @Test
    @DisplayName("弃权规则匹配不算「产出了结论」")
    void anAbstainingRuleDoesNotCountAsProducingAConclusion() {
        PlaybookEvidenceAssessment assessment = assess(abstainingPlaybook(), anomaly());

        assertThat(assessment.conclusionType())
                .isEqualTo(ConclusionType.INSUFFICIENT_EVIDENCE);
        assertThat(assessment.matchedRuleId()).isNull();
    }

    /** 合同自己也挡住这种不一致，而不是只靠调用方守规矩。 */
    @Test
    @DisplayName("非 LOCATED 的判定不许带规则 id")
    void theContractRefusesARuleIdOnANonLocatedAssessment() {
        assertThatThrownBy(() -> new PlaybookEvidenceAssessment(
                ConclusionType.INSUFFICIENT_EVIDENCE, "证据不足", "", Confidence.LOW,
                List.of(), List.of(), List.of(), List.of(), "R-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only a LOCATED assessment");
    }

    private PlaybookEvidenceAssessment assess(SopEntry playbook, List<EvidenceResult> evidence) {
        return PlaybookEvidenceAssessment.assess(
                playbook, evidence,
                new CriterionEvaluator(), new DiagnosisRuleEvaluator(), true);
    }

    private List<EvidenceResult> anomaly() {
        return List.of(new EvidenceResult(
                "EV-1", "L", "q", EvidenceStatus.ANOMALY, "命中",
                Map.of("count", 9), "recorded-replay:test",
                Instant.parse("2026-08-03T10:00:00Z")));
    }

    private SopEntry playbook() {
        return sop("approved", true, new DiagnosisRule(
                "R-1", List.of("error_present"), "连接池打满", "连接不可用",
                Confidence.HIGH, false));
    }

    private SopEntry draftPlaybook() {
        return sop("candidate", false, new DiagnosisRule(
                "R-1", List.of("error_present"), "连接池打满", "连接不可用",
                Confidence.HIGH, false));
    }

    private SopEntry abstainingPlaybook() {
        return sop("approved", true, new DiagnosisRule(
                "R-1", List.of("error_present"), "证据已收齐，根因待人工判定",
                "只取证，不下结论", Confidence.LOW, true));
    }

    private SopEntry sop(String status, boolean verified, DiagnosisRule rule) {
        return new SopEntry(
                "sop-1", SopEntry.CURRENT_CONTRACT_VERSION, "CSDP", "903001",
                "order-svc", "连接池耗尽", "连接池打满", "database", "DBA 组",
                status, verified,
                List.of(new EvidenceRequest(
                        "EV-1", "log_count", "确认发生",
                        Map.of("service", "order-svc"), "-15m", true)),
                List.of(new AnomalyCriterion(
                        "error_present", "EV-1", "错误出现",
                        new Criterion.NumericGte("count", 1))),
                List.of(rule),
                List.of());
    }
}
