package vip.mate.troubleshooting.engine;

import vip.mate.troubleshooting.model.Confidence;
import vip.mate.troubleshooting.model.ConclusionType;
import vip.mate.troubleshooting.model.DiagnosisRule;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.KnowledgeEvidenceGrade;
import vip.mate.troubleshooting.model.SopEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What one Playbook concludes from one set of evidence. Pure, zero LLM.
 *
 * <p><b>Why it is its own type.</b> This judgement was private to the
 * error-code hit path, which is why a scenario Diagnosis had no way to reach
 * it: naming a scenario created a Diagnosis that waited for evidence, and
 * nothing could turn evidence into a revised conclusion. Copying the logic into
 * a second place would have been worse than the gap — two evaluators drifting
 * apart is how a system starts giving two answers to the same evidence (A9).</p>
 *
 * <p><b>It answers, it does not decide the lifecycle.</b> Whether a Diagnosis
 * may advance is the state machine's business; this only reports what the
 * evidence supports, including when the honest answer is "not enough".</p>
 */
public record PlaybookEvidenceAssessment(
        ConclusionType conclusionType,
        String rootCause,
        String summary,
        Confidence confidence,
        List<String> activeSignals,
        List<String> missingRequestIds,
        List<String> missingRequiredRequestIds,
        List<String> warnings,
        /**
         * 产生这条结论的那条规则；只有 {@code LOCATED} 时才有值。
         *
         * <p><b>为什么要记下来。</b> 引擎本来就算出了它，然后扔掉。一次已结案且结论
         * 被人确认的调查，正是一份**答案由世界给出**的回放案例，而回放案例的期望值
         * 必须精确到规则 id（{@code ReplayCase} 的合同要求 MATCHED 必须指名规则）。
         * 事后靠 rootCause 文本反查是猜——rootCause 并不保证唯一。</p>
         *
         * <p><b>为什么只在 LOCATED 时有值。</b> 规则匹配之后，缺必需证据、Playbook
         * 仍是草案这两种情况都会把结论降级；那时结论不是那条规则给出的。弃权规则
         * 匹配也不算——它落到 INSUFFICIENT_EVIDENCE，而回放合同里只有 MATCHED 才
         * 允许指名规则。让这个字段与「这条规则确实产出了这条结论」严格对齐。</p>
         */
        String matchedRuleId) {

    /** Compatibility shape for callers that do not name a producing rule. */
    public PlaybookEvidenceAssessment(
            ConclusionType conclusionType,
            String rootCause,
            String summary,
            Confidence confidence,
            List<String> activeSignals,
            List<String> missingRequestIds,
            List<String> missingRequiredRequestIds,
            List<String> warnings) {
        this(conclusionType, rootCause, summary, confidence, activeSignals,
                missingRequestIds, missingRequiredRequestIds, warnings, null);
    }

    public PlaybookEvidenceAssessment {
        activeSignals = List.copyOf(activeSignals == null ? List.of() : activeSignals);
        missingRequestIds = List.copyOf(missingRequestIds == null ? List.of() : missingRequestIds);
        missingRequiredRequestIds = List.copyOf(
                missingRequiredRequestIds == null ? List.of() : missingRequiredRequestIds);
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
        if (conclusionType == null || rootCause == null || confidence == null) {
            throw new IllegalArgumentException(
                    "assessment requires a conclusion type, root cause and confidence");
        }
        matchedRuleId = matchedRuleId == null || matchedRuleId.isBlank()
                ? null : matchedRuleId.trim();
        if (matchedRuleId != null && conclusionType != ConclusionType.LOCATED) {
            // 只有 LOCATED 才是「某条规则产出了这条结论」。别处带上规则 id，会让
            // 回放期望指向一条其实没有裁决过这次调查的规则。
            throw new IllegalArgumentException(
                    "only a LOCATED assessment may name the rule that produced it");
        }
    }

    /**
     * 未标定的知识不得声称 HIGH。
     *
     * <p><b>为什么必须有这一格。</b> 证据成色（真源还是夹具）已经会自己推导；接上真源
     * 那一刻它自动变真。但**知识成色不会跟着变**：那 8 条已审核 Playbook 的阈值是人
     * 手写的，从没被任何一次真实故障检验过。少了这一格，真源接通的第一天，系统就会拿
     * 没人验证过的阈值输出 {@code LOCATED / HIGH}——服务经理看到 HIGH 会当成系统有
     * 把握，而系统只是在执行一句没人验证过的判断。</p>
     *
     * <p><b>这不是新发明的谨慎，是把已有的一条纪律补齐。</b> 未命中路对**模型**的建议
     * 早就封顶到 MEDIUM 并附警告。我们给模型的猜测封了顶，却没给一条从没被检验过的
     * 阈值封顶——这个不对称没有道理。</p>
     *
     * <p>只压 {@code LOCATED}：{@code EXCLUDED} 说的是「候选根因都被反证」，那是判据
     * 没成立，不依赖阈值标定得准不准；{@code INSUFFICIENT_EVIDENCE} 本来就不声称。</p>
     */
    private static Confidence cap(
            Confidence confidence,
            ConclusionType conclusionType,
            KnowledgeEvidenceGrade knowledgeGrade) {
        boolean calibrated = knowledgeGrade == KnowledgeEvidenceGrade.RECORDED_AGGREGATE;
        if (calibrated
                || conclusionType != ConclusionType.LOCATED
                || confidence != Confidence.HIGH) {
            return confidence;
        }
        return Confidence.MEDIUM;
    }

    /** A conclusion strong enough for a human to act on, rather than to keep investigating. */
    public boolean actionable() {
        return conclusionType == ConclusionType.LOCATED
                || conclusionType == ConclusionType.EXCLUDED;
    }

    /**
     * Compatibility shape: knowledge whose grade is unknown is treated as
     * uncalibrated, which is the conservative side.
     */
    public static PlaybookEvidenceAssessment assess(
            SopEntry playbook,
            List<EvidenceResult> evidence,
            CriterionEvaluator criteria,
            DiagnosisRuleEvaluator rules,
            boolean fixtureMode) {
        return assess(playbook, evidence, criteria, rules, fixtureMode,
                KnowledgeEvidenceGrade.UNVERIFIED);
    }

    /**
     * @param knowledgeGrade 这份 Playbook 的判据与阈值是怎么来的。它决定结论**最高
     *                       能声称到什么程度**——见 {@link #cap}。
     */
    public static PlaybookEvidenceAssessment assess(
            SopEntry playbook,
            List<EvidenceResult> evidence,
            CriterionEvaluator criteria,
            DiagnosisRuleEvaluator rules,
            boolean fixtureMode,
            KnowledgeEvidenceGrade knowledgeGrade) {
        if (playbook == null || criteria == null || rules == null) {
            throw new IllegalArgumentException("playbook and evaluators are required");
        }
        List<EvidenceResult> collected = List.copyOf(evidence == null ? List.of() : evidence);
        Map<String, EvidenceResult> byRequest = new HashMap<>();
        for (EvidenceResult result : collected) {
            // Two answers to one request is not extra evidence, it is an
            // unresolved contradiction. Silently keeping the last one would let
            // ordering decide the conclusion.
            if (byRequest.putIfAbsent(result.queryId(), result) != null) {
                throw new IllegalArgumentException(
                        "duplicate evidence queryId: " + result.queryId());
            }
        }
        List<String> missing = missing(playbook, byRequest, false);
        List<String> missingRequired = missing(playbook, byRequest, true);

        DiagnosisRuleEvaluator.Evaluation evaluation = rules.evaluate(
                playbook.diagnosisRules(),
                criteria.outcomesBySignal(playbook.anomalyCriteria(), collected));

        ConclusionType conclusionType;
        String rootCause;
        String summary;
        Confidence confidence;
        DiagnosisRule matched = evaluation.matchedRule();
        if (matched != null) {
            conclusionType = matched.abstained()
                    ? ConclusionType.INSUFFICIENT_EVIDENCE
                    : ConclusionType.LOCATED;
            rootCause = matched.rootCause();
            summary = matched.summary();
            confidence = matched.confidence();
        } else if (evaluation.disposition() == DiagnosisRuleEvaluator.Disposition.EXCLUDED) {
            conclusionType = ConclusionType.EXCLUDED;
            rootCause = "当前 Playbook 候选根因均被反证。";
            summary = "现有证据已排除当前 Playbook 中的候选结论。";
            confidence = Confidence.MEDIUM;
        } else {
            conclusionType = ConclusionType.INSUFFICIENT_EVIDENCE;
            rootCause = "证据不足，暂不能确认根因。";
            summary = "Playbook 未提供与当前信号匹配的结论规则。";
            confidence = Confidence.LOW;
        }

        List<String> warnings = new ArrayList<>();
        // A required request that never answered outranks whatever the rules
        // said: rules evaluated over absent evidence are not a conclusion, they
        // are a conclusion drawn from silence.
        if (!missingRequired.isEmpty()) {
            conclusionType = ConclusionType.INSUFFICIENT_EVIDENCE;
            rootCause = "自动取证不完整，当前不能确认根因。";
            summary = "必需证据未取得，已降级为人工取证指引。";
            confidence = Confidence.LOW;
        }
        if (!playbook.operational()) {
            conclusionType = ConclusionType.INSUFFICIENT_EVIDENCE;
            rootCause = "Playbook 尚未审核，当前仅完成影子取证，不输出正式根因。";
            summary = "命中草案 Playbook；结果仅用于离线比对，不能作为处置建议。";
            confidence = Confidence.LOW;
            warnings.add("Playbook 仍为草案，禁止越过影子模式或输出恢复动作。");
        }
        if (fixtureMode) {
            warnings.add("当前仅使用 fixture 证据；指标名与阈值尚未联调核实。");
        }
        if (!missing.isEmpty()) {
            warnings.add("自动取证失败（" + String.join(", ", missing) + "）；请按 Playbook 进行人工取证。");
        }
        if (conclusionType == ConclusionType.EXCLUDED) {
            warnings.add("当前 Playbook 的所有候选结论都被已取得证据反证；这是排除，不是定位。");
        }

        // 阈值从没被真实历史故障标定过时，结论最高只能到 MEDIUM。放在所有降级之后，
        // 这样它只可能把置信度往下压，不会把别处压低的再抬回来。
        Confidence capped = cap(confidence, conclusionType, knowledgeGrade);
        if (capped != confidence) {
            warnings.add("这份 Playbook 的判据与阈值从未用真实历史故障标定过，"
                    + "置信度已封顶为 MEDIUM，需人工确认。");
            confidence = capped;
        }

        return new PlaybookEvidenceAssessment(
                conclusionType, rootCause, summary, confidence,
                evaluation.activeSignals(), missing, missingRequired, warnings,
                // 在所有降级判断**之后**才定：上面任何一条降级都意味着结论不再是
                // 那条规则给出的，这时它不该留名。
                conclusionType == ConclusionType.LOCATED && matched != null
                        ? matched.ruleId()
                        : null);
    }

    private static List<String> missing(
            SopEntry playbook,
            Map<String, EvidenceResult> byRequest,
            boolean requiredOnly) {
        return playbook.evidenceRequests().stream()
                .filter(request -> !requiredOnly || request.required())
                .filter(request -> {
                    EvidenceResult result = byRequest.get(request.requestId());
                    return result == null || result.status() == EvidenceStatus.MISSING;
                })
                .map(EvidenceRequest::requestId)
                .toList();
    }
}
