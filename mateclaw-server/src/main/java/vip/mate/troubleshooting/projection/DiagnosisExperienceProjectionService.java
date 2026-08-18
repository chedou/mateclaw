package vip.mate.troubleshooting.projection;

import org.springframework.stereotype.Service;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.agent.OpenDiscoveryRunAudit;
import vip.mate.troubleshooting.agent.OpenDiscoveryRunAuditService;
import vip.mate.troubleshooting.deployment.DeploymentTopologyScenarioPolicy;
import vip.mate.troubleshooting.evidence.ScenarioEvidenceRunAudit;
import vip.mate.troubleshooting.evidence.ScenarioEvidenceRunAuditService;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.ScenarioAffordance;
import vip.mate.troubleshooting.model.Confidence;
import vip.mate.troubleshooting.model.ConclusionType;
import vip.mate.troubleshooting.model.CriterionOutcome;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.DiagnosisDerivation;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.KnowledgeCandidate;
import vip.mate.troubleshooting.model.InvestigationMode;
import vip.mate.troubleshooting.model.KnowledgeEvidenceGrade;
import vip.mate.troubleshooting.model.RecommendedAction;
import vip.mate.troubleshooting.model.RouteAuthority;
import vip.mate.troubleshooting.model.SopEntry;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.BusinessSummary;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.EvidenceBasis;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.ContrastView;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.DeveloperEvidenceView;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.DraftView;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.EvidenceStep;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.EvidenceStepKind;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.ImpactView;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.NextStep;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.ReviewStatus;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.StepTone;
import vip.mate.troubleshooting.service.DiagnosisDerivationService;
import vip.mate.troubleshooting.service.StoredDiagnosis;
import vip.mate.troubleshooting.service.TroubleshootingPersistenceService;
import vip.mate.troubleshooting.service.TroubleshootingPlaybookVersionService;
import vip.mate.troubleshooting.synthesis.ApprovedPlaybookVersion;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/** Builds the formal business and developer views from one authoritative aggregate. */
@Service
public class DiagnosisExperienceProjectionService {

    private static final String WRITE_BOUNDARY =
            "不改生产环境；处置完成后回来登记。";

    private final TroubleshootingPersistenceService persistence;
    private final DiagnosisDerivationService derivationService;
    private final CanonicalEvidenceViewProjector evidenceProjector;
    private final DeploymentTopologyScenarioPolicy topologyScenarioPolicy;
    private final TroubleshootingPlaybookVersionService playbookVersions;
    private final InvestigationTraceProjector investigationTraceProjector;
    private final ScenarioEvidenceRunAuditService scenarioEvidenceRuns;
    private final OpenDiscoveryRunAuditService openDiscoveryRuns;
    private final SystemOnboardingGapService onboardingGaps;

    public DiagnosisExperienceProjectionService(
            TroubleshootingPersistenceService persistence,
            DiagnosisDerivationService derivationService,
            CanonicalEvidenceViewProjector evidenceProjector,
            DeploymentTopologyScenarioPolicy topologyScenarioPolicy,
            TroubleshootingPlaybookVersionService playbookVersions,
            InvestigationTraceProjector investigationTraceProjector,
            ScenarioEvidenceRunAuditService scenarioEvidenceRuns,
            OpenDiscoveryRunAuditService openDiscoveryRuns,
            SystemOnboardingGapService onboardingGaps) {
        this.persistence = persistence;
        this.derivationService = derivationService;
        this.evidenceProjector = evidenceProjector;
        this.topologyScenarioPolicy = topologyScenarioPolicy;
        this.playbookVersions = playbookVersions;
        this.investigationTraceProjector = investigationTraceProjector;
        this.scenarioEvidenceRuns = scenarioEvidenceRuns;
        this.openDiscoveryRuns = openDiscoveryRuns;
        this.onboardingGaps = onboardingGaps;
    }

    public DiagnosisExperienceProjection project(long workspaceId, String diagnosisId) {
        StoredDiagnosis stored = persistence.get(workspaceId, diagnosisId);
        Diagnosis diagnosis = stored.diagnosis();
        List<String> capabilityLimits = new ArrayList<>();
        DiagnosisDerivation derivation = derivation(diagnosis, workspaceId, capabilityLimits);
        ApprovedPlaybookVersion frozenVersion = frozenPlaybookVersion(workspaceId, diagnosis);
        SopEntry frozenPlaybook = frozenVersion == null ? null : frozenVersion.playbook();
        ScenarioEvidenceRunAudit latestScenarioEvidenceRun =
                diagnosis.investigationMode() == InvestigationMode.SCENARIO_PLAYBOOK
                        ? scenarioEvidenceRuns.latest(workspaceId, diagnosisId).orElse(null)
                        : null;
        OpenDiscoveryRunAudit latestOpenDiscoveryRun =
                diagnosis.investigationMode() == InvestigationMode.OPEN_DISCOVERY
                        ? openDiscoveryRuns.latest(workspaceId, diagnosisId).orElse(null)
                        : null;

        ConclusionType conclusionType = diagnosis.conclusionType();
        RouteAuthority authority = diagnosis.routeAuthority();
        Confidence confidence = projectedConfidence(diagnosis, conclusionType, authority);
        CanonicalEvidenceViewProjector.ProjectionFacts evidenceFacts =
                evidenceProjector.project(diagnosis);
        ImpactView impact = evidenceFacts.impact();
        capabilityLimits.addAll(evidenceFacts.capabilityLimits());

        // Only an abstention can be caused by an unonboarded system; a conclusion
        // that reached a verdict already had the layers it needed.
        List<SystemOnboardingGap> gaps =
                conclusionType == ConclusionType.INSUFFICIENT_EVIDENCE
                        && diagnosis.evidence().stream()
                                .noneMatch(item -> item.status()
                                        != vip.mate.troubleshooting.model.EvidenceStatus.MISSING)
                        ? onboardingGaps.inspect(workspaceId, diagnosis.incident())
                        : List.of();

        BusinessSummary business = new BusinessSummary(
                diagnosis.diagnosisId(),
                conclusionType,
                headline(conclusionType),
                businessRootCause(diagnosis, conclusionType),
                narrative(diagnosis, conclusionType, gaps),
                keyEvidence(diagnosis, evidenceFacts.contrast()),
                confidence,
                problem(diagnosis),
                impact,
                nextStep(diagnosis, conclusionType, gaps),
                diagnosis.status(),
                diagnosis.timings(),
                diagnosis.fixtureMode(),
                evidenceBasis(diagnosis));

        capabilityLimits.add(WRITE_BOUNDARY);
        if (!diagnosis.timings().recorded()) {
            capabilityLimits.add("这是旧记录：当时还没记下各阶段耗时，所以这里不会用 0 或当前时间凑数。");
        }
        capabilityLimits.addAll(diagnosis.warnings());

        DeveloperEvidenceView developer = new DeveloperEvidenceView(
                diagnosis.diagnosisId(),
                diagnosis.investigationMode(),
                authority,
                diagnosis.routeSemanticsProvenance(),
                playbookRef(diagnosis),
                knowledgeEvidenceGrade(diagnosis, frozenVersion),
                scenarioAffordances(workspaceId, diagnosis),
                evidenceFacts.callChain(),
                evidenceSteps(diagnosis, derivation),
                investigationTraceProjector.project(
                        diagnosis,
                        frozenPlaybook,
                        derivation,
                        latestScenarioEvidenceRun,
                        latestOpenDiscoveryRun),
                evidenceFacts.contrast(),
                draft(diagnosis),
                plainCapabilityLimits(capabilityLimits),
                diagnosis.fixtureMode(),
                evidenceFacts.failureBreakdown());

        return new DiagnosisExperienceProjection(business, developer);
    }

    private ApprovedPlaybookVersion frozenPlaybookVersion(
            long workspaceId,
            Diagnosis diagnosis) {
        if (diagnosis.sourcePlaybookVersionRef() == null) {
            return null;
        }
        Optional<ApprovedPlaybookVersion> found = playbookVersions.findByRef(
                workspaceId, diagnosis.sourcePlaybookVersionRef());
        if (found == null || found.isEmpty()) {
            return null;
        }
        ApprovedPlaybookVersion version = found.orElseThrow();
        if (!version.selectorKey().equals(diagnosis.sopKey())
                || !version.selectorKey().equals(version.playbook().routingKey())) {
            return null;
        }
        return version;
    }

    private KnowledgeEvidenceGrade knowledgeEvidenceGrade(
            Diagnosis diagnosis,
            ApprovedPlaybookVersion frozenVersion) {
        if (diagnosis.sopKey() == null) {
            return null;
        }
        if (diagnosis.sourcePlaybookVersionRef() == null) {
            return KnowledgeEvidenceGrade.UNVERIFIED;
        }
        return frozenVersion == null
                ? KnowledgeEvidenceGrade.UNVERIFIED
                : frozenVersion.knowledgeEvidenceGrade();
    }

    private String playbookRef(Diagnosis diagnosis) {
        if (diagnosis.sopKey() == null) {
            return null;
        }
        if (diagnosis.sourcePlaybookVersionRef() == null) {
            return diagnosis.sopKey() + " · 历史记录未冻结版本";
        }
        return diagnosis.sopKey()
                + " · "
                + diagnosis.sourcePlaybookVersionRef().playbookId()
                + "@v"
                + diagnosis.sourcePlaybookVersionRef().playbookVersion();
    }

    private DiagnosisDerivation derivation(
            Diagnosis diagnosis,
            long workspaceId,
            List<String> capabilityLimits) {
        if (diagnosis.investigationMode() == InvestigationMode.OPEN_DISCOVERY
                || diagnosis.sopKey() == null) {
            capabilityLimits.add("这是开放调查：没有套用标准排障方案，所以结论不能按固定步骤复算。");
            return null;
        }
        try {
            DiagnosisDerivation derivation =
                    derivationService.explain(workspaceId, diagnosis.diagnosisId());
            if (!derivation.faithful()) {
                capabilityLimits.add(derivation.note());
            }
            return derivation;
        } catch (MateClawException exception) {
            capabilityLimits.add("判据链暂不可重建：" + exception.getMessage());
            return null;
        }
    }

    private Confidence projectedConfidence(
            Diagnosis diagnosis,
            ConclusionType conclusionType,
            RouteAuthority authority) {
        if (conclusionType == ConclusionType.INSUFFICIENT_EVIDENCE) {
            return Confidence.LOW;
        }
        if ((authority == RouteAuthority.MODEL_PROPOSED
                || authority == RouteAuthority.POLICY_PROPOSED)
                && diagnosis.confidence() == Confidence.HIGH) {
            return Confidence.MEDIUM;
        }
        return diagnosis.confidence();
    }

    private String headline(ConclusionType conclusionType) {
        return switch (conclusionType) {
            case LOCATED -> "已定位到出问题的环节";
            case EXCLUDED -> "现有证据已排除当前假设";
            case HYPOTHESIS -> "已形成需要人工确认的根因假设";
            case INSUFFICIENT_EVIDENCE -> "证据不足，系统已停止自动判断";
        };
    }

    /** Null unless the conclusion actually names a cause the reader can act on. */
    private String businessRootCause(Diagnosis diagnosis, ConclusionType conclusionType) {
        return switch (conclusionType) {
            case LOCATED, HYPOTHESIS -> normalizeNullable(diagnosis.rootCause());
            case EXCLUDED, INSUFFICIENT_EVIDENCE -> null;
        };
    }

    private String narrative(
            Diagnosis diagnosis,
            ConclusionType conclusionType,
            List<SystemOnboardingGap> gaps) {
        if (conclusionType == ConclusionType.INSUFFICIENT_EVIDENCE && !gaps.isEmpty()) {
            return "这个系统还没有接入到可取证的状态，所以本次一条证据都没有采集到，"
                    + "也没有给出根因。下面列出的是配置缺口，不是报障人要补的材料。";
        }
        if (conclusionType == ConclusionType.INSUFFICIENT_EVIDENCE
                && hasSuccessfulZeroMatchEvidence(diagnosis)) {
            return "系统只读查询成功，数据源也正常返回，但没有找到支持当前候选的匹配记录。"
                    + "告警内容只作为报障事实，系统没有把它直接升级成最终根因。";
        }
        return switch (conclusionType) {
            // The Playbook author already wrote the explanation for this exact
            // failure; a generic template that says the rule matched tells the
            // reader about our machinery instead of about their outage.
            case LOCATED -> fallback(
                    diagnosis.summary(),
                    "经过审核的排障规则命中。请结合开发证据复核后推进处置。");
            case EXCLUDED -> "判据不支持当前假设。这是排除结论，不代表已经定位根因。";
            case HYPOTHESIS -> fallback(
                    diagnosis.summary(),
                    "只读证据支持上述待确认方向，该结论未经过确定性 SOP 判据裁决。");
            case INSUFFICIENT_EVIDENCE -> "关键证据缺失或互相矛盾，系统没有给出根因。"
                    + "请先补齐开发证据台列出的缺口，再重新调查。";
        };
    }

    /**
     * States the comparison in counts, so the reader can judge the conclusion
     * instead of trusting it.
     *
     * <p>Reads the already-validated canonical contrast rather than the raw
     * observed map: a second parser here would be a second chance to disagree
     * with the developer view about what the same evidence said. The feature is
     * left unnamed because the root cause line above already names it, and a
     * second code-to-Chinese table would only drift from the one the developer
     * view uses.</p>
     */
    private String keyEvidence(Diagnosis diagnosis, ContrastView contrast) {
        if (contrast == null || !contrast.available()
                || contrast.failedRequests() == null || contrast.normalRequests() == null) {
            if (isIcareRequiredRevisitResultMissing(diagnosis)
                    && hasReportedPolicyEvidence(
                            diagnosis,
                            vip.mate.troubleshooting.investigation.ReviewedIncidentPolicy
                                    .ICARE_REQUIRED_REVISIT_RESULT_POLICY_CODE)) {
                return "iCare 的错误提示与本次请求结构一致：结构化回访结果为空；"
                        + "普通回访文字不能替代完结校验要求的回访结果字段。";
            }
            if (isIcareMobileChangeOrderFinishRejected(diagnosis)
                    && hasReportedPolicyEvidence(
                            diagnosis,
                            vip.mate.troubleshooting.investigation.ReviewedIncidentPolicy
                                    .ICARE_MOBILE_CHANGE_ORDER_FINISH_POLICY_CODE)) {
                return "iCare 返回的业务提示已经明确：工单关联变更单，"
                        + "因此不允许在移动端完结。";
            }
            if (isIcareProductMapping502(diagnosis)
                    && hasReportedEvidence(
                            diagnosis, "incident_reported_external_http_failure")) {
                return "告警已经明确：iCare 产品映射接口调用返回 HTTP 502。"
                        + "这能确认直接失败点，但不能证明上游为什么返回 502。";
            }
            return null;
        }
        return "异常 "
                + contrast.failedRequests().requestsWithFeature()
                + "/" + contrast.failedRequests().totalRequests()
                + " 命中同一特征，正常 "
                + contrast.normalRequests().requestsWithFeature()
                + "/" + contrast.normalRequests().totalRequests()
                + "。";
    }

    private EvidenceBasis evidenceBasis(Diagnosis diagnosis) {
        boolean reported = (isIcareProductMapping502(diagnosis)
                        && hasReportedEvidence(
                                diagnosis, "incident_reported_external_http_failure"))
                || (isIcareMobileChangeOrderFinishRejected(diagnosis)
                        && hasReportedPolicyEvidence(
                                diagnosis,
                                vip.mate.troubleshooting.investigation.ReviewedIncidentPolicy
                                        .ICARE_MOBILE_CHANGE_ORDER_FINISH_POLICY_CODE))
                || (isIcareRequiredRevisitResultMissing(diagnosis)
                        && hasReportedPolicyEvidence(
                                diagnosis,
                                vip.mate.troubleshooting.investigation.ReviewedIncidentPolicy
                                        .ICARE_REQUIRED_REVISIT_RESULT_POLICY_CODE));
        if (reported) {
            return EvidenceBasis.REPORTED;
        }
        return diagnosis.fixtureMode()
                ? EvidenceBasis.RECORDED_REPLAY
                : EvidenceBasis.OBSERVED;
    }

    private String problem(Diagnosis diagnosis) {
        return fallback(diagnosis.incident().title(), diagnosis.summary(), "待确认故障现象");
    }

    private NextStep nextStep(
            Diagnosis diagnosis,
            ConclusionType conclusionType,
            List<SystemOnboardingGap> gaps) {
        if (conclusionType == ConclusionType.INSUFFICIENT_EVIDENCE && !gaps.isEmpty()) {
            return onboardingNextStep(diagnosis, gaps);
        }
        if (isIcareRequiredRevisitResultMissing(diagnosis)
                && hasReportedPolicyEvidence(
                        diagnosis,
                        vip.mate.troubleshooting.investigation.ReviewedIncidentPolicy
                                .ICARE_REQUIRED_REVISIT_RESULT_POLICY_CODE)
                && (conclusionType == ConclusionType.HYPOTHESIS
                        || conclusionType == ConclusionType.LOCATED)) {
            return new NextStep(
                    "补全回访信息后重新完结",
                    "在 iCare 工单的回访区域填写结构化“回访结果”及页面提示的其他必填项，"
                            + "保存后再执行完结。普通备注或回访话术不能代替该字段。",
                    "平台不会代替你填写或提交工单；重新提交前无法确认是否还存在其他必填项。");
        }
        if (isIcareMobileChangeOrderFinishRejected(diagnosis)
                && hasReportedPolicyEvidence(
                        diagnosis,
                        vip.mate.troubleshooting.investigation.ReviewedIncidentPolicy
                                .ICARE_MOBILE_CHANGE_ORDER_FINISH_POLICY_CODE)
                && (conclusionType == ConclusionType.HYPOTHESIS
                        || conclusionType == ConclusionType.LOCATED)) {
            return new NextStep(
                    "改用 PC 端完结",
                    "不要在移动端重试。请由操作人在 PC 端打开同一工单完成完结；"
                            + "如 PC 端仍无法完成，携带脱敏工单号和发生时间联系 iCare 技术支持。",
                    "平台不会代替你提交完结操作；尚未验证 PC 端结果，也未判断变更单状态是否正确。");
        }
        String team = fallback(diagnosis.routeToTeam(), "责任开发");
        return switch (conclusionType) {
            case LOCATED -> {
                List<RecommendedAction> actions = diagnosis.recommendedActions();
                if (actions.isEmpty()) {
                    yield new NextStep(
                            "定位结果",
                            "请 " + team + " 复核定位结果并决定系统外处置方式。",
                            WRITE_BOUNDARY);
                }
                // Not 「解决方案」: the capability boundary on this very field
                // says the system locates and does not hand over a fix.
                yield new NextStep("定位结果", actionChecklist(actions), WRITE_BOUNDARY);
            }
            case EXCLUDED -> new NextStep(
                    "排除结论",
                    "换用其他假设继续调查，不要把本结论作为根因处置。",
                    "这是排除不是定位；" + WRITE_BOUNDARY);
            case HYPOTHESIS -> isIcareProductMapping502(diagnosis)
                    ? new NextStep(
                            "继续核对 502 上游原因",
                            "直接失败点已明确：iCare 产品映射接口返回 HTTP 502。"
                                    + "请 iCare 或网关负责人核对同一时间窗的上游日志与健康状态，"
                                    + "并用关联 ID 将告警请求与上游响应连起来。",
                            "当前只确认了直接失败点，未确认上游根因；" + WRITE_BOUNDARY)
                    : new NextStep(
                            "下一步",
                            "请 " + team + " 沿当前证据方向确认或证伪根因假设。",
                            "当前仍是假设，需要人工确认；" + WRITE_BOUNDARY);
            case INSUFFICIENT_EVIDENCE -> new NextStep(
                    isIcareProductMapping502(diagnosis) ? "继续核对 502 上游原因" : "下一步",
                    isIcareProductMapping502(diagnosis)
                            ? "已知失败点是 iCare 产品映射接口返回 HTTP 502；"
                                    + "请先核对 Guance measurement/字段映射，再由 iCare 或网关负责人"
                                    + "检查同一时间窗的上游日志与健康状态。"
                            : "补齐缺失的日志、调用链或指标证据后重新调查。",
                    "证据不足，系统已弃权且没有给出根因；" + WRITE_BOUNDARY);
        };
    }

    private boolean hasSuccessfulZeroMatchEvidence(Diagnosis diagnosis) {
        List<vip.mate.troubleshooting.model.EvidenceResult> available = diagnosis.evidence().stream()
                .filter(item -> item.status()
                        != vip.mate.troubleshooting.model.EvidenceStatus.MISSING)
                .toList();
        if (available.isEmpty()) {
            return false;
        }
        List<Number> numbers = available.stream()
                .flatMap(item -> item.observed().values().stream())
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .toList();
        return !numbers.isEmpty()
                && numbers.stream().allMatch(value -> value.doubleValue() == 0D);
    }

    private boolean isIcareProductMapping502(Diagnosis diagnosis) {
        return vip.mate.troubleshooting.investigation.ReviewedIncidentPolicy
                .isIcareProductMapping502(diagnosis.incident());
    }

    private boolean isIcareMobileChangeOrderFinishRejected(Diagnosis diagnosis) {
        return vip.mate.troubleshooting.investigation.ReviewedIncidentPolicy
                .isIcareMobileChangeOrderFinishRejected(diagnosis.incident());
    }

    private boolean isIcareRequiredRevisitResultMissing(Diagnosis diagnosis) {
        return vip.mate.troubleshooting.investigation.ReviewedIncidentPolicy
                .isIcareRequiredRevisitResultMissing(diagnosis.incident());
    }

    private boolean hasReportedEvidence(Diagnosis diagnosis, String expectedSignalKind) {
        return diagnosis.evidence().stream()
                .filter(item -> item.status()
                        != vip.mate.troubleshooting.model.EvidenceStatus.MISSING)
                .map(item -> vip.mate.troubleshooting.evidence.CanonicalEvidenceSchema
                        .detectSignalKind(item.observed()))
                .anyMatch(expectedSignalKind::equalsIgnoreCase);
    }

    private boolean hasReportedPolicyEvidence(
            Diagnosis diagnosis,
            String expectedPolicyCode) {
        String signalKind = "incident_reported_business_policy_rejection";
        return diagnosis.evidence().stream()
                .filter(item -> item.status()
                        == vip.mate.troubleshooting.model.EvidenceStatus.ANOMALY)
                .filter(item -> vip.mate.troubleshooting.evidence.CanonicalEvidenceSchema
                        .isValid(signalKind, item.observed()))
                .filter(item -> signalKind.equalsIgnoreCase(
                        vip.mate.troubleshooting.evidence.CanonicalEvidenceSchema
                                .detectSignalKind(item.observed())))
                .anyMatch(item -> expectedPolicyCode.equals(
                                String.valueOf(item.observed().get("policy_code")).trim())
                        && "REPORTED".equals(
                                String.valueOf(item.observed().get("evidence_grade")).trim()));
    }

    /**
     * Every recommended action, each marked with who performs it.
     *
     * <p>Showing only the first one hid the action that actually needed a
     * person: the read-only review the system can run itself came first, so the
     * reader was told to wait for us while the real next move — asking the
     * gateway owner — was never mentioned.</p>
     */
    private String actionChecklist(List<RecommendedAction> actions) {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < actions.size(); index++) {
            RecommendedAction action = actions.get(index);
            if (index > 0) {
                text.append('\n');
            }
            text.append(index + 1).append(". ").append(action.title())
                    .append(' ').append(performerOf(action));
        }
        return text.toString();
    }

    private String performerOf(RecommendedAction action) {
        return switch (action.actionType()) {
            case AUTO_READONLY -> "— 系统可代做";
            case HUMAN_CONTACT -> "— 需人去联系确认";
            case MANUAL_WRITE, MANUAL_UNKNOWN -> "— 需人在平台外完成，再回来登记结果";
        };
    }

    private NextStep onboardingNextStep(
            Diagnosis diagnosis,
            List<SystemOnboardingGap> gaps) {
        StringBuilder text = new StringBuilder("「")
                .append(fallback(diagnosis.incident().system(), "该系统"))
                .append("」还没接入到可取证状态，还差 ")
                .append(gaps.size())
                .append(" 层：");
        for (int index = 0; index < gaps.size(); index++) {
            text.append('\n')
                    .append(index + 1)
                    .append(". ")
                    .append(gaps.get(index).title())
                    .append(" —— ")
                    .append(gaps.get(index).detail());
        }
        text.append("\n这些要").append(gaps.get(0).owner())
                .append("在排障配置里补齐；报障人再补日志也不会改变结果。");
        return new NextStep(
                "先完成系统接入",
                text.toString(),
                "系统尚未接入，本次没有取到任何证据，也没有给出根因；" + WRITE_BOUNDARY);
    }

    private List<EvidenceStep> evidenceSteps(
            Diagnosis diagnosis,
            DiagnosisDerivation derivation) {
        List<EvidenceStep> steps = new ArrayList<>();
        for (EvidenceResult evidence : diagnosis.evidence()) {
            steps.add(new EvidenceStep(
                    EvidenceStepKind.EVIDENCE,
                    evidence.collectedAt(),
                    fallback(evidence.summary(), evidence.namespace() + " 证据"),
                    evidence.namespace() + " · " + evidence.source() + " · " + evidence.status(),
                    evidence.queryId(),
                    evidenceTone(evidence.status())));
        }
        if (derivation != null) {
            for (DiagnosisDerivation.CriterionEvaluation criterion : derivation.criteria()) {
                steps.add(new EvidenceStep(
                        EvidenceStepKind.CRITERION,
                        null,
                        "判据 · " + fallback(criterion.description(), criterion.signal()),
                        safeCriterionDetail(criterion),
                        criterion.signal(),
                        criterionTone(criterion.outcome())));
            }
        }
        return List.copyOf(steps);
    }

    /**
     * A rendered substitution can embed arbitrary observed strings such as a
     * canonical log sample. DeveloperEvidenceView keeps the authored rule and
     * deterministic verdict; operators inspect values through the separately
     * bounded canonical evidence projection.
     */
    private String safeCriterionDetail(
            DiagnosisDerivation.CriterionEvaluation criterion) {
        return fallback(criterion.expression(), "未提供表达式")
                + "；判据结果=" + criterion.outcome().name()
                + "；实际观测值请沿证据引用查看安全字段";
    }

    private StepTone evidenceTone(EvidenceStatus status) {
        return switch (status) {
            case NORMAL -> StepTone.NORMAL;
            case ANOMALY -> StepTone.ANOMALY;
            case MISSING -> StepTone.UNEVALUATED;
        };
    }

    private StepTone criterionTone(CriterionOutcome outcome) {
        return switch (outcome) {
            case SATISFIED -> StepTone.ANOMALY;
            case EXCLUDED -> StepTone.EXCLUDED;
            case UNEVALUATED -> StepTone.UNEVALUATED;
        };
    }

    private DraftView draft(Diagnosis diagnosis) {
        if (diagnosis.knowledgeCandidates().isEmpty()) {
            return new DraftView(
                    null,
                    "尚未形成知识草稿",
                    List.of(),
                    "只有在人工闭环并明确选择沉淀后才会生成候选；不会自动发布为 Playbook。",
                    ReviewStatus.DRAFT,
                    "当前没有知识候选。"
            );
        }
        KnowledgeCandidate candidate = diagnosis.knowledgeCandidates().getLast();
        List<String> steps = candidate.recommendedActions().stream()
                .map(this::describeAction)
                .toList();
        if (steps.isEmpty()) {
            steps = List.of(candidate.resolutionSummary());
        }
        return new DraftView(
                candidate.candidateId(),
                "知识候选 · " + candidate.rootCause(),
                steps,
                null,
                ReviewStatus.CANDIDATE,
                "仅记录为知识候选；当前没有独立审核语义，发布状态不等于已批准 Playbook。"
        );
    }

    private String describeAction(RecommendedAction action) {
        return action.title() + (action.description().isBlank() ? "" : "：" + action.description());
    }

    private List<String> plainCapabilityLimits(List<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            result.add(toPlainCapabilityLimit(value.trim()));
        }
        return List.copyOf(result);
    }

    /** Operator-facing rewrite; keeps unknown strings intact. */
    static String toPlainCapabilityLimit(String raw) {
        String text = raw.trim();
        return switch (text) {
            case "MateClaw 只提供只读证据与状态推进；生产变更由授权人员在系统外执行并回填结果。" ->
                    WRITE_BOUNDARY;
            case "只读 Agent 输出仅供人工确认；未生成或执行任何处置动作。" ->
                    "上面的分析只是建议，需要人确认；系统没有自动改任何东西。";
            case "当前证据链仍处于 fixtureMode，生产数据源联调完成前不得解除。" ->
                    "当前还在演练/演示证据模式；生产数据源联调完成前，不能当成正式验收通过。";
            case "开放调查路径没有可复算的确定性 SOP 判据链。",
                 "开放调查路径没有可复算的确定性 SOP 判据链" ->
                    "这是开放调查：没有套用标准排障方案，所以结论不能按固定步骤复算。";
            case "该旧记录创建时尚未采集 D14 阶段时间戳，不用 0 或当前时间回填。" ->
                    "这是旧记录：当时还没记下各阶段耗时，所以这里不会用 0 或当前时间凑数。";
            default -> plainRouteMissLimit(text);
        };
    }

    private static String plainRouteMissLimit(String text) {
        if (text.startsWith("确定性路由未命中：")) {
            String reason = text.substring("确定性路由未命中：".length()).trim();
            if (reason.contains("no errorCode") || reason.contains("deterministic routing needs one")) {
                return "这单没有错误码，没法自动匹配标准排障方案。";
            }
            if (!reason.isBlank()) {
                return "没法自动匹配标准排障方案：" + reason;
            }
            return "没法自动匹配标准排障方案。";
        }
        if (text.startsWith("只读 Agent ")) {
            return text
                    .replace("只读 Agent 超出", "助手超时（超过")
                    .replace(" 秒服务端时长预算，已停止等待并降级为人工深查。", " 秒），已改为请人继续查。")
                    .replace("只读 Agent 调用失败，已降级为人工深查。", "助手调用失败，已改为请人继续查。")
                    .replace("只读 Agent 输出不可解析，已降级为人工深查。", "助手返回内容读不懂，已改为请人继续查。")
                    .replace("只读 Agent 未提供可验证的证据引用，已强制弃权。", "助手没给出可核对的证据引用，已放弃自动结论。")
                    .replace("只读 Agent 未提供完整的摘要与假设，已强制弃权。", "助手没写清摘要和假设，已放弃自动结论。")
                    .replace("只读 Agent 未能形成可验证结论，等待人工深查。", "助手没形成可核对结论，等人工继续查。");
        }
        return text;
    }

    private List<String> deduplicate(List<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                result.add(value.trim());
            }
        }
        return List.copyOf(result);
    }

    private String fallback(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "待确认";
    }

    private String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Scenario capabilities as a keyed list. Each scenario contributes at most one
     * entry; the projection itself stays scenario-agnostic so shipping a new
     * scenario does not add a field every other diagnosis has to carry.
     */
    private List<ScenarioAffordance> scenarioAffordances(long workspaceId, Diagnosis diagnosis) {
        List<ScenarioAffordance> affordances = new ArrayList<>();
        if (topologyScenarioPolicy.requiresProbe(workspaceId, diagnosis)) {
            affordances.add(new ScenarioAffordance(
                    DeploymentTopologyScenarioPolicy.SCENARIO_KEY, true));
        }
        return List.copyOf(affordances);
    }

}
