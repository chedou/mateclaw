package vip.mate.troubleshooting.engine;

import vip.mate.troubleshooting.model.Confidence;
import vip.mate.troubleshooting.model.ConclusionType;
import vip.mate.troubleshooting.model.DiagnosisRule;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
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
        List<String> warnings) {

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
    }

    /** A conclusion strong enough for a human to act on, rather than to keep investigating. */
    public boolean actionable() {
        return conclusionType == ConclusionType.LOCATED
                || conclusionType == ConclusionType.EXCLUDED;
    }

    public static PlaybookEvidenceAssessment assess(
            SopEntry playbook,
            List<EvidenceResult> evidence,
            CriterionEvaluator criteria,
            DiagnosisRuleEvaluator rules,
            boolean fixtureMode) {
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

        return new PlaybookEvidenceAssessment(
                conclusionType, rootCause, summary, confidence,
                evaluation.activeSignals(), missing, missingRequired, warnings);
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
