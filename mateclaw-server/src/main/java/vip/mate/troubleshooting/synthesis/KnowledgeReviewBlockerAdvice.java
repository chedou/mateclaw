package vip.mate.troubleshooting.synthesis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 把「为什么不能批准」的代码，翻成「那我该做什么」。
 *
 * <p><b>为什么值得单独存在。</b> 资格代码是给机器读的，短、稳定、可比对，这很对。
 * 但它同时是作者唯一看得见的东西：{@code REPLAY_SUITE_UNAVAILABLE} 说清楚了检查
 * 是什么，完全没说清楚出路在哪。一个只报代码的拒绝，等于逼人去猜；而猜的时候最省
 * 事的做法，是把校验放宽——那恰恰是这些闸门要挡住的事。</p>
 *
 * <p><b>只加一句话，不改任何判定。</b> 这里不参与是否放行，只负责把已经做出的拒绝
 * 说完整。没有登记建议的代码就不说话：编一条听起来合理但其实走不通的下一步，比
 * 沉默更糟。</p>
 */
public final class KnowledgeReviewBlockerAdvice {

    private static final Map<String, String> NEXT_STEP = Map.ofEntries(
            Map.entry("REPLAY_SUITE_UNAVAILABLE",
                    "这个 selector 没有随包回放套件，会下结论的 Playbook 无法在此被证明。"
                            + "先把 diagnosisRules 全部标成 abstained（只声明取哪些证据、"
                            + "不给根因）即可批准，成色记为 UNVERIFIED；等它跑出真实案例，"
                            + "再回来补结论规则。"),
            Map.entry("POSITIVE_AND_NEGATIVE_REPLAY_REQUIRED",
                    "还没有跑过回放：POST /api/v1/troubleshooting/sops/review-inbox/"
                            + "manual/{sopId}/replay"),
            Map.entry("POSITIVE_AND_NEGATIVE_REPLAY_FAILED",
                    "回放跑了但没通过：这条 Playbook 没能复现已知答案，改内容而不是改闸门"),
            Map.entry("REPLAY_PROOF_STALE",
                    "候选或套件在回放之后改过，重新跑一次 .../replay"),
            Map.entry("CONTRACT_VALIDATION_FAILED",
                    "契约校验未通过，逐条看 validationErrors"),
            Map.entry("OWNER_REQUIRED",
                    "缺 ownerTeam：知识要有人认领，评审人不会被顺手当成负责人"),
            Map.entry("CITATIONS_REQUIRED",
                    "缺证据引用：结论必须指得出它依据的是哪条证据（A1）"),
            Map.entry("FIXTURE_ONLY",
                    "这条候选只在夹具上成立过，还没有真源证据支撑"),
            Map.entry("SELECTOR_REQUIRED",
                    "缺 selector：没有路由键的知识无法被任何一条报障找到"),
            Map.entry("OUTCOME_VERIFICATION_NOT_PROJECTED",
                    "这条候选没有冻结的结案证明，只有 v2 记录会在结案同一笔事务里写下它"),
            Map.entry("REFERENCE_SOLUTION_DELTA",
                    "与参考解存在差异，先看 referenceComparison"));

    private KnowledgeReviewBlockerAdvice() {
    }

    /** @return 逐条下一步；认不出的代码不产生条目，也绝不编一条。 */
    public static List<String> nextSteps(Collection<String> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return List.of();
        }
        List<String> steps = new ArrayList<>();
        for (String reason : new LinkedHashSet<>(reasons)) {
            String step = NEXT_STEP.get(reason);
            if (step != null) {
                steps.add(reason + " → " + step);
            }
        }
        return List.copyOf(steps);
    }
}
