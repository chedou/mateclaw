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
import vip.mate.troubleshooting.model.KnowledgeEvidenceGrade;
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

    /**
     * 真源接通那一刻最要紧的一格。
     *
     * <p>证据成色会自己推导，接上真源就自动变真；**知识成色不会跟着变**。已审核的那几条
     * Playbook 阈值是人手写的，从没被任何一次真实故障检验过。少了这一格，第一天系统就会
     * 拿没人验证过的阈值输出 {@code LOCATED / HIGH}，而服务经理看到 HIGH 会当成系统有
     * 把握。这不是新发明的谨慎：未命中路对**模型**的建议早就封顶到 MEDIUM，一条从没被
     * 检验过的阈值没有理由比模型的猜测更有底气。</p>
     */
    @Test
    @DisplayName("阈值没被真实故障标定过时，LOCATED 最高只能到 MEDIUM，并说出理由")
    void uncalibratedKnowledgeCannotClaimHighConfidence() {
        for (KnowledgeEvidenceGrade grade : List.of(
                KnowledgeEvidenceGrade.UNVERIFIED, KnowledgeEvidenceGrade.AUTHORED_FIXTURE)) {
            PlaybookEvidenceAssessment assessment = assess(playbook(), anomaly(), grade);

            assertThat(assessment.conclusionType()).isEqualTo(ConclusionType.LOCATED);
            assertThat(assessment.confidence())
                    .as("%s 的阈值没有被真实历史故障标定过", grade)
                    .isEqualTo(Confidence.MEDIUM);
            assertThat(assessment.warnings())
                    .as("封顶必须说出理由，否则读者只看到一个没来由的 MEDIUM")
                    .anyMatch(w -> w.contains("从未用真实历史故障标定过"));
        }
    }

    /** 反过来也要成立，否则「一律封顶」也能让上一条通过，而那会让标定变得毫无意义。 */
    @Test
    @DisplayName("阈值来自录制聚合时，不封顶")
    void knowledgeCalibratedFromRecordedAggregatesKeepsItsConfidence() {
        PlaybookEvidenceAssessment assessment = assess(
                playbook(), anomaly(), KnowledgeEvidenceGrade.RECORDED_AGGREGATE);

        assertThat(assessment.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(assessment.warnings())
                .noneMatch(w -> w.contains("从未用真实历史故障标定过"));
    }

    /** 只压 LOCATED：EXCLUDED 说的是判据没成立，不依赖阈值标定得准不准。 */
    @Test
    @DisplayName("EXCLUDED 不受封顶影响")
    void anExcludedConclusionIsNotCapped() {
        PlaybookEvidenceAssessment assessment = assess(
                playbook(),
                List.of(new EvidenceResult(
                        "EV-1", "L", "q", EvidenceStatus.NORMAL, "未命中",
                        Map.of("count", 0), "recorded-replay:test",
                        Instant.parse("2026-08-03T10:00:00Z"))),
                KnowledgeEvidenceGrade.UNVERIFIED);

        assertThat(assessment.conclusionType()).isEqualTo(ConclusionType.EXCLUDED);
        assertThat(assessment.confidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(assessment.warnings())
                .noneMatch(w -> w.contains("从未用真实历史故障标定过"));
    }

    private PlaybookEvidenceAssessment assess(SopEntry playbook, List<EvidenceResult> evidence) {
        return assess(playbook, evidence, KnowledgeEvidenceGrade.RECORDED_AGGREGATE);
    }

    private PlaybookEvidenceAssessment assess(
            SopEntry playbook, List<EvidenceResult> evidence, KnowledgeEvidenceGrade grade) {
        return PlaybookEvidenceAssessment.assess(
                playbook, evidence,
                new CriterionEvaluator(), new DiagnosisRuleEvaluator(), true, grade);
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
