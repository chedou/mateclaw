package vip.mate.troubleshooting.projection;

import org.springframework.stereotype.Service;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.deployment.DeploymentTopologyScenarioPolicy;
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
import vip.mate.troubleshooting.model.RecommendedAction;
import vip.mate.troubleshooting.model.RouteMode;
import vip.mate.troubleshooting.model.RouteAuthority;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.BusinessSummary;
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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Builds the formal business and developer views from one authoritative aggregate. */
@Service
public class DiagnosisExperienceProjectionService {

    private static final String WRITE_BOUNDARY =
            "MateClaw 只提供只读证据与状态推进；生产变更由授权人员在系统外执行并回填结果。";

    private final TroubleshootingPersistenceService persistence;
    private final DiagnosisDerivationService derivationService;
    private final CanonicalEvidenceViewProjector evidenceProjector;
    private final DeploymentTopologyScenarioPolicy topologyScenarioPolicy;

    public DiagnosisExperienceProjectionService(
            TroubleshootingPersistenceService persistence,
            DiagnosisDerivationService derivationService,
            CanonicalEvidenceViewProjector evidenceProjector,
            DeploymentTopologyScenarioPolicy topologyScenarioPolicy) {
        this.persistence = persistence;
        this.derivationService = derivationService;
        this.evidenceProjector = evidenceProjector;
        this.topologyScenarioPolicy = topologyScenarioPolicy;
    }

    public DiagnosisExperienceProjection project(long workspaceId, String diagnosisId) {
        StoredDiagnosis stored = persistence.get(workspaceId, diagnosisId);
        Diagnosis diagnosis = stored.diagnosis();
        List<String> capabilityLimits = new ArrayList<>();
        DiagnosisDerivation derivation = derivation(diagnosis, workspaceId, capabilityLimits);

        ConclusionType conclusionType = diagnosis.conclusionType();
        RouteAuthority authority = diagnosis.routeAuthority();
        Confidence confidence = projectedConfidence(diagnosis, conclusionType, authority);
        CanonicalEvidenceViewProjector.ProjectionFacts evidenceFacts =
                evidenceProjector.project(diagnosis);
        ImpactView impact = evidenceFacts.impact();
        capabilityLimits.addAll(evidenceFacts.capabilityLimits());

        BusinessSummary business = new BusinessSummary(
                diagnosis.diagnosisId(),
                conclusionType,
                headline(conclusionType),
                narrative(diagnosis, conclusionType),
                confidence,
                problem(diagnosis),
                impact,
                nextStep(diagnosis, conclusionType),
                diagnosis.status(),
                diagnosis.timings(),
                diagnosis.fixtureMode());

        capabilityLimits.add(WRITE_BOUNDARY);
        if (!diagnosis.timings().recorded()) {
            capabilityLimits.add("该旧记录创建时尚未采集 D14 阶段时间戳，不用 0 或当前时间回填。");
        }
        capabilityLimits.addAll(diagnosis.warnings());

        DeveloperEvidenceView developer = new DeveloperEvidenceView(
                diagnosis.diagnosisId(),
                diagnosis.investigationMode(),
                authority,
                playbookRef(diagnosis),
                scenarioAffordances(workspaceId, diagnosis),
                evidenceFacts.callChain(),
                evidenceSteps(diagnosis, derivation),
                evidenceFacts.contrast(),
                draft(diagnosis),
                deduplicate(capabilityLimits),
                diagnosis.fixtureMode());

        return new DiagnosisExperienceProjection(business, developer);
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
        if (diagnosis.routeMode() != RouteMode.DETERMINISTIC || diagnosis.sopKey() == null) {
            capabilityLimits.add("开放调查路径没有可复算的确定性 SOP 判据链。 ");
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
        if (authority == RouteAuthority.MODEL_PROPOSED
                && diagnosis.confidence() == Confidence.HIGH) {
            return Confidence.MEDIUM;
        }
        return diagnosis.confidence();
    }

    private String headline(ConclusionType conclusionType) {
        return switch (conclusionType) {
            case LOCATED -> "已通过受控证据定位到异常环节";
            case EXCLUDED -> "现有证据已排除当前假设";
            case HYPOTHESIS -> "已形成需要人工确认的根因假设";
            case INSUFFICIENT_EVIDENCE -> "证据不足，系统已停止自动判断";
        };
    }

    private String narrative(Diagnosis diagnosis, ConclusionType conclusionType) {
        return switch (conclusionType) {
            case LOCATED -> "经过审核的排障规则命中，当前定位为："
                    + fallback(diagnosis.rootCause(), diagnosis.summary(), "已定位异常")
                    + "。请结合开发证据复核后推进处置。";
            case EXCLUDED -> "判据不支持当前假设。这是排除结论，不代表已经定位根因。";
            case HYPOTHESIS -> "只读证据支持以下待确认方向："
                    + fallback(diagnosis.rootCause(), diagnosis.summary(), "待人工确认")
                    + "。该结论未经过确定性 SOP 判据裁决。";
            case INSUFFICIENT_EVIDENCE -> "关键证据缺失或互相矛盾，系统没有给出根因。"
                    + "请先补齐开发证据台列出的缺口，再重新调查。";
        };
    }

    private String problem(Diagnosis diagnosis) {
        return fallback(diagnosis.incident().title(), diagnosis.summary(), "待确认故障现象");
    }

    private NextStep nextStep(Diagnosis diagnosis, ConclusionType conclusionType) {
        String team = fallback(diagnosis.routeToTeam(), "责任开发");
        return switch (conclusionType) {
            case LOCATED -> {
                RecommendedAction action = diagnosis.recommendedActions().stream().findFirst().orElse(null);
                if (action == null) {
                    yield new NextStep(
                            "定位结果",
                            "请 " + team + " 复核定位结果并决定系统外处置方式。",
                            WRITE_BOUNDARY);
                }
                yield new NextStep(
                        "解决方案",
                        action.title() + (action.description().isBlank()
                                ? "" : "：" + action.description()),
                        WRITE_BOUNDARY);
            }
            case EXCLUDED -> new NextStep(
                    "排除结论",
                    "换用其他假设继续调查，不要把本结论作为根因处置。",
                    "这是排除不是定位；" + WRITE_BOUNDARY);
            case HYPOTHESIS -> new NextStep(
                    "下一步",
                    "请 " + team + " 沿当前证据方向确认或证伪根因假设。",
                    "当前仍是假设，需要人工确认；" + WRITE_BOUNDARY);
            case INSUFFICIENT_EVIDENCE -> new NextStep(
                    "下一步",
                    "补齐缺失的日志、调用链或指标证据后重新调查。",
                    "证据不足，系统已弃权且没有给出根因；" + WRITE_BOUNDARY);
        };
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
                        fallback(criterion.expression(), "未提供表达式")
                                + "；" + fallback(criterion.substitution(), "未取得观测值"),
                        criterion.signal(),
                        criterionTone(criterion.outcome())));
            }
        }
        return List.copyOf(steps);
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
