package vip.mate.troubleshooting.followup;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.model.ConclusionType;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.BusinessSummary;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.EvidenceStep;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.EvidenceStepKind;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.StepTone;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjectionService;
import vip.mate.troubleshooting.service.StoredDiagnosis;
import vip.mate.troubleshooting.service.TroubleshootingPersistenceService;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** Deterministic, Diagnosis-bound follow-up answers with no model invocation. */
@Service
public class DiagnosisFollowUpService {

    private static final Pattern END = Pattern.compile("^(结束|退出|关闭)(本次)?(排障|调查)[。.!！]?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUPPLEMENT = Pattern.compile("^(补充|新增)(证据|材料|信息)\\s*[:：]?", Pattern.CASE_INSENSITIVE);
    private static final Pattern WHY = Pattern.compile("为什么|什么原因|原因是什么|怎么(得出|判断|确定)");
    private static final Pattern EVIDENCE = Pattern.compile("哪些证据|什么证据|依据|数据支撑|凭什么");
    private static final Pattern UNKNOWNS = Pattern.compile("还不知道|未知|缺什么|缺少什么|不确定|证据缺口");
    private static final Pattern NEXT = Pattern.compile("下一步|接下来|怎么处理|怎么办|查什么|做什么");

    private final TroubleshootingPersistenceService persistence;
    private final DiagnosisExperienceProjectionService projections;
    private final DiagnosisFollowUpRunStore runs;
    private final Clock clock;

    @Autowired
    public DiagnosisFollowUpService(
            TroubleshootingPersistenceService persistence,
            DiagnosisExperienceProjectionService projections,
            DiagnosisFollowUpRunStore runs) {
        this(persistence, projections, runs, Clock.systemUTC());
    }

    DiagnosisFollowUpService(
            TroubleshootingPersistenceService persistence,
            DiagnosisExperienceProjectionService projections,
            DiagnosisFollowUpRunStore runs,
            Clock clock) {
        this.persistence = persistence;
        this.projections = projections;
        this.runs = runs;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public DiagnosisFollowUpResult respond(
            long workspaceId,
            String diagnosisId,
            String text,
            String actorRef) {
        if (workspaceId <= 0 || diagnosisId == null || diagnosisId.isBlank()
                || text == null || text.isBlank()
                || actorRef == null || actorRef.isBlank()) {
            throw invalid("workspaceId, diagnosisId, text and actor are required");
        }
        String normalizedDiagnosisId = diagnosisId.trim();
        String normalizedText = text.trim();
        StoredDiagnosis stored = persistence.get(workspaceId, normalizedDiagnosisId);
        DiagnosisFollowUpIntent intent = classify(normalizedText);

        if (intent == DiagnosisFollowUpIntent.END) {
            BusinessSummary summary =
                    projections.project(workspaceId, normalizedDiagnosisId).businessSummary();
            return result(normalizedDiagnosisId, DiagnosisFollowUpStatus.ENDED, intent,
                    summary.conclusionType(), summary.evidenceBasis(), summary.fixtureMode(),
                    "已结束本次排障追问。原排障单和调查记录仍保留，后续可从详情页重新查看。", null);
        }
        if (intent == DiagnosisFollowUpIntent.SUPPLEMENTAL_EVIDENCE) {
            BusinessSummary summary =
                    projections.project(workspaceId, normalizedDiagnosisId).businessSummary();
            return recordSupplement(
                    workspaceId, stored, summary, normalizedText, actorRef.trim());
        }

        DiagnosisExperienceProjection projection =
                projections.project(workspaceId, normalizedDiagnosisId);
        String answer = switch (intent) {
            case WHY -> why(projection.businessSummary());
            case EVIDENCE -> evidence(projection);
            case UNKNOWNS -> unknowns(projection);
            case NEXT_STEP -> nextStep(projection.businessSummary());
            case HELP -> help();
            default -> throw new IllegalStateException("unsupported follow-up intent " + intent);
        };
        return result(
                normalizedDiagnosisId,
                DiagnosisFollowUpStatus.ACTIVE,
                intent,
                projection.businessSummary().conclusionType(),
                projection.businessSummary().evidenceBasis(),
                projection.businessSummary().fixtureMode(),
                answer,
                null);
    }

    public List<DiagnosisFollowUpRun> runs(long workspaceId, String diagnosisId) {
        persistence.get(workspaceId, diagnosisId);
        return runs.list(workspaceId, diagnosisId);
    }

    private DiagnosisFollowUpResult recordSupplement(
            long workspaceId,
            StoredDiagnosis stored,
            BusinessSummary summary,
            String text,
            String actorRef) {
        String payload = SUPPLEMENT.matcher(text).replaceFirst("").trim();
        if (payload.isBlank()) {
            return result(
                    stored.diagnosis().diagnosisId(),
                    DiagnosisFollowUpStatus.ACTIVE,
                    DiagnosisFollowUpIntent.SUPPLEMENTAL_EVIDENCE,
                    summary.conclusionType(),
                    summary.evidenceBasis(),
                    summary.fixtureMode(),
                    "请按“补充证据：事实摘要”填写。不要粘贴密钥或整段原始日志。",
                    null);
        }
        Diagnosis diagnosis = stored.diagnosis();
        DiagnosisFollowUpRun run = runs.insert(workspaceId, new DiagnosisFollowUpRun(
                "follow-up-run-" + UUID.randomUUID().toString().replace("-", ""),
                diagnosis.diagnosisId(),
                stored.version(),
                diagnosis.conclusionType(),
                DiagnosisFollowUpIntent.SUPPLEMENTAL_EVIDENCE,
                payload.length(),
                DiagnosisFollowUpDisposition.RECORDED_NOT_VERIFIED,
                actorRef,
                clock.instant()));
        return result(
                diagnosis.diagnosisId(),
                DiagnosisFollowUpStatus.ACTIVE,
                DiagnosisFollowUpIntent.SUPPLEMENTAL_EVIDENCE,
                summary.conclusionType(),
                summary.evidenceBasis(),
                summary.fixtureMode(),
                "已创建新的不可变调查记录 " + run.runId()
                        + "。补充材料当前标记为“待验证”，没有改写原结论；"
                        + "如需重新判断，应基于该记录发起新的只读取证。",
                run);
    }

    private DiagnosisFollowUpIntent classify(String text) {
        if (END.matcher(text).matches()) return DiagnosisFollowUpIntent.END;
        if (SUPPLEMENT.matcher(text).find()) return DiagnosisFollowUpIntent.SUPPLEMENTAL_EVIDENCE;
        if (WHY.matcher(text).find()) return DiagnosisFollowUpIntent.WHY;
        if (EVIDENCE.matcher(text).find()) return DiagnosisFollowUpIntent.EVIDENCE;
        if (UNKNOWNS.matcher(text).find()) return DiagnosisFollowUpIntent.UNKNOWNS;
        if (NEXT.matcher(text).find()) return DiagnosisFollowUpIntent.NEXT_STEP;
        return DiagnosisFollowUpIntent.HELP;
    }

    private String why(BusinessSummary summary) {
        if (summary.conclusionType() == ConclusionType.INSUFFICIENT_EVIDENCE
                || summary.rootCause() == null || summary.rootCause().isBlank()) {
            return "当前排障单还没有形成明确原因。" + sentence(summary.narrative())
                    + "你可以继续问“还缺什么”或“下一步查什么”。";
        }
        String basis = evidenceBasis(summary);
        if (summary.conclusionType() == ConclusionType.HYPOTHESIS) {
            return "当前候选方向是：" + summary.rootCause()
                    + "。它还不是已确认根因。支持这个方向的" + basis + "是："
                    + firstNonBlank(summary.keyEvidence(), summary.narrative(), "未记录") + "。";
        }
        if (summary.conclusionType() == ConclusionType.EXCLUDED) {
            return "当前已经排除这个方向：" + summary.rootCause() + "。排除依据来自"
                    + basis + "：" + firstNonBlank(summary.keyEvidence(), summary.narrative(), "未记录") + "。";
        }
        String label = summary.fixtureMode()
                || summary.evidenceBasis() == DiagnosisExperienceProjection.EvidenceBasis.RECORDED_REPLAY
                ? "本次演练定位到"
                : "当前定位到";
        return label + "：" + summary.rootCause() + "。系统依据的" + basis + "是："
                + firstNonBlank(summary.keyEvidence(), summary.narrative(), "未记录") + "。";
    }

    private String evidence(DiagnosisExperienceProjection projection) {
        BusinessSummary summary = projection.businessSummary();
        String keyEvidence = summary.keyEvidence();
        List<String> anomalies = projection.developerEvidence().steps().stream()
                .filter(step -> step.kind() == EvidenceStepKind.EVIDENCE)
                .filter(step -> step.tone() == StepTone.ANOMALY)
                .map(step -> step.title() + "（" + step.detail() + "）")
                .filter(detail -> detail != null && !detail.isBlank())
                .distinct()
                .limit(3)
                .toList();
        if (keyEvidence == null && anomalies.isEmpty()) {
            return "本次排障没有记录足以支撑明确结论的证据；详情页中的缺口会保持“未记录”，不会用推测补齐。";
        }
        if (summary.conclusionType() == ConclusionType.INSUFFICIENT_EVIDENCE) {
            return "当前证据仍不足，下面只能列出已经取得的事实，不能据此确认根因。"
                    + evidenceFacts(summary, keyEvidence, anomalies);
        }
        String authority = switch (summary.conclusionType()) {
            case LOCATED -> "已定位原因使用的主要";
            case HYPOTHESIS -> "待确认候选方向使用的主要";
            case EXCLUDED -> "排除该方向使用的主要";
            case INSUFFICIENT_EVIDENCE -> throw new IllegalStateException("handled above");
        };
        StringBuilder answer = new StringBuilder(authority)
                .append(evidenceBasis(summary)).append("：");
        if (keyEvidence != null && !keyEvidence.isBlank()) {
            answer.append(keyEvidence).append('。');
        }
        if (!anomalies.isEmpty()) {
            answer.append("取到的异常证据包括：").append(String.join("；", anomalies)).append('。');
        }
        return answer.toString();
    }

    private String evidenceFacts(
            BusinessSummary summary,
            String keyEvidence,
            List<String> anomalies) {
        StringBuilder facts = new StringBuilder(" 已取得的")
                .append(evidenceBasis(summary)).append("：");
        if (keyEvidence != null && !keyEvidence.isBlank()) {
            facts.append(keyEvidence).append('。');
        }
        if (!anomalies.isEmpty()) {
            facts.append(String.join("；", anomalies)).append('。');
        }
        return facts.toString();
    }

    private String unknowns(DiagnosisExperienceProjection projection) {
        List<String> limits = projection.developerEvidence().capabilityLimits().stream()
                .filter(item -> item != null && !item.isBlank())
                .distinct()
                .limit(4)
                .toList();
        if (limits.isEmpty()) {
            return "本次投影没有记录额外未知项；这只表示已登记流程没有更多缺口，不代表系统外不存在其他原因。";
        }
        return "当前还没有确认：" + String.join("；", limits) + "。这些内容不能被当前结论替代。";
    }

    private String nextStep(BusinessSummary summary) {
        var next = summary.nextStep();
        String boundary = next.capabilityBoundary() == null
                ? ""
                : " 能力边界：" + next.capabilityBoundary() + "。";
        return "建议下一步（" + next.label() + "）：" + next.text() + "。" + boundary;
    }

    private String help() {
        return "我会继续围绕当前排障单回答。你可以问：“为什么是这个原因”“有哪些证据”"
                + "“还缺什么”“下一步查什么”；也可以输入“补充证据：事实摘要”创建新的待验证调查记录。"
                + "输入“结束排障”才会退出。";
    }

    private String evidenceBasis(BusinessSummary summary) {
        if (summary.evidenceBasis() == DiagnosisExperienceProjection.EvidenceBasis.REPORTED) {
            return "告警中已规范化记录的事实";
        }
        if (summary.fixtureMode()) return "演练数据（不能替代正式真源证据）";
        return switch (summary.evidenceBasis()) {
            case OBSERVED -> "本次只读取证结果";
            case REPORTED -> throw new IllegalStateException("handled above");
            case RECORDED_REPLAY -> "录制回放数据（仅用于演练）";
        };
    }

    private String sentence(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isEmpty() ? "" : normalized + "。";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "未记录";
    }

    private DiagnosisFollowUpResult result(
            String diagnosisId,
            DiagnosisFollowUpStatus status,
            DiagnosisFollowUpIntent intent,
            ConclusionType conclusionType,
            DiagnosisExperienceProjection.EvidenceBasis evidenceBasis,
            boolean fixtureMode,
            String answer,
            DiagnosisFollowUpRun run) {
        return new DiagnosisFollowUpResult(
                diagnosisId, status, intent, conclusionType, evidenceBasis,
                fixtureMode, answer, run);
    }

    private MateClawException invalid(String message) {
        return new MateClawException("err.troubleshooting.follow_up_invalid", 400, message);
    }
}
