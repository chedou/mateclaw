package vip.mate.troubleshooting.projection;

import org.springframework.stereotype.Component;
import vip.mate.troubleshooting.TroubleshootingSecretRedactor;
import vip.mate.troubleshooting.evidence.CanonicalEvidenceSchema;
import vip.mate.troubleshooting.model.ConclusionType;
import vip.mate.troubleshooting.model.CriterionOutcome;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.DiagnosisDerivation;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.InvestigationMode;
import vip.mate.troubleshooting.model.RouteSemanticsProvenance;
import vip.mate.troubleshooting.model.SopEntry;
import vip.mate.troubleshooting.projection.InvestigationTraceView.AdapterAttemptView;
import vip.mate.troubleshooting.projection.InvestigationTraceView.AttemptHistoryStatus;
import vip.mate.troubleshooting.projection.InvestigationTraceView.EvidenceContractView;
import vip.mate.troubleshooting.projection.InvestigationTraceView.EvidenceRelationView;
import vip.mate.troubleshooting.projection.InvestigationTraceView.RelationEdge;
import vip.mate.troubleshooting.projection.InvestigationTraceView.RelationNode;
import vip.mate.troubleshooting.projection.InvestigationTraceView.RelationNodeKind;
import vip.mate.troubleshooting.projection.InvestigationTraceView.RelationType;
import vip.mate.troubleshooting.projection.InvestigationTraceView.StageKey;
import vip.mate.troubleshooting.projection.InvestigationTraceView.StageStatus;
import vip.mate.troubleshooting.projection.InvestigationTraceView.StageView;
import vip.mate.troubleshooting.projection.InvestigationTraceView.StopReasonCode;
import vip.mate.troubleshooting.projection.InvestigationTraceView.StopReasonView;
import vip.mate.troubleshooting.projection.InvestigationTraceView.TraceField;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds a truthful seven-stage trace from already persisted troubleshooting facts. */
@Component
public final class InvestigationTraceProjector {

    private static final Map<String, Set<String>> SAFE_OBSERVED_FIELDS = Map.ofEntries(
            Map.entry("synthetic_probe", Set.of("status_code", "probe_name")),
            Map.entry("log_count", Set.of("count", "trace_id")),
            Map.entry("metric", Set.of(
                    "reachable", "connections_current", "connections_available",
                    "slow_query_count", "baseline_slow")),
            Map.entry("trace", Set.of("failed_hop", "status", "duration_ms")),
            Map.entry("log_search", Set.of("match_count", "ps_id")),
            Map.entry("contrast_sample", Set.of(
                    "discriminating_feature", "failure_sample_count",
                    "failure_match_count", "success_sample_count", "success_match_count")),
            Map.entry("error_log_scan", Set.of(
                    "error_count", "affected_trace_count", "latest_trace_id")),
            Map.entry("monitor_event_scan", Set.of(
                    "event_count", "latest_status", "latest_checker")),
            Map.entry("k8s_workload_health", Set.of(
                    "pod_count", "container_count", "running_container_count",
                    "unhealthy_container_count", "max_cpu_percent", "max_memory_percent")),
            Map.entry("incident_impact", Set.of(
                    "function_scope", "affected_customers", "affected_users",
                    "blast_radius", "observed_at")),
            Map.entry("log_trace_bundle", Set.of("ps_id")));

    public InvestigationTraceView project(
            Diagnosis diagnosis,
            SopEntry frozenPlaybook,
            DiagnosisDerivation derivation) {
        if (diagnosis == null) {
            throw new IllegalArgumentException("diagnosis is required");
        }

        List<EvidenceContractView> contracts = evidenceContracts(frozenPlaybook);
        List<AdapterAttemptView> attempts = adapterAttempts(diagnosis, frozenPlaybook);
        List<String> missingRequired = missingRequiredEvidence(contracts, diagnosis.evidence());
        StopReasonView stopReason = stopReason(diagnosis, derivation, missingRequired);
        EvidenceRelationView evidenceRelation = evidenceRelation(diagnosis, derivation);

        return new InvestigationTraceView(
                diagnosis.diagnosisId(),
                diagnosis.timings().investigateCost(),
                stages(
                        diagnosis,
                        frozenPlaybook,
                        derivation,
                        contracts,
                        attempts,
                        missingRequired,
                        stopReason),
                contracts,
                attempts,
                stopReason,
                evidenceRelation);
    }

    private List<EvidenceContractView> evidenceContracts(SopEntry frozenPlaybook) {
        if (frozenPlaybook == null) {
            return List.of();
        }
        return frozenPlaybook.evidenceRequests().stream()
                .map(request -> new EvidenceContractView(
                        request.requestId(),
                        request.signalKind(),
                        safe(request.purpose()),
                        safeMap(request.target()),
                        request.window(),
                        request.required()))
                .toList();
    }

    private List<AdapterAttemptView> adapterAttempts(
            Diagnosis diagnosis,
            SopEntry frozenPlaybook) {
        Map<String, EvidenceRequest> requestsById = new LinkedHashMap<>();
        if (frozenPlaybook != null) {
            for (EvidenceRequest request : frozenPlaybook.evidenceRequests()) {
                requestsById.putIfAbsent(request.requestId(), request);
            }
        }

        List<AdapterAttemptView> attempts = new ArrayList<>();
        for (EvidenceResult original : diagnosis.evidence()) {
            EvidenceResult evidence = TroubleshootingSecretRedactor.redact(original);
            EvidenceRequest request = requestsById.get(evidence.queryId());
            String signalKind = signalKind(request, evidence);
            attempts.add(new AdapterAttemptView(
                    evidence.queryId(),
                    request == null ? null : request.requestId(),
                    signalKind,
                    evidence.source(),
                    evidence.status(),
                    evidenceLabel(signalKind, evidence.status()),
                    null,
                    safeObserved(signalKind, evidence),
                    evidence.collectedAt(),
                    null,
                    AttemptHistoryStatus.FINAL_RESULT_ONLY));
        }
        return List.copyOf(attempts);
    }

    private List<StageView> stages(
            Diagnosis diagnosis,
            SopEntry frozenPlaybook,
            DiagnosisDerivation derivation,
            List<EvidenceContractView> contracts,
            List<AdapterAttemptView> attempts,
            List<String> missingRequired,
            StopReasonView stopReason) {
        List<String> evidenceRefs = diagnosis.evidence().stream()
                .map(EvidenceResult::queryId)
                .distinct()
                .toList();
        boolean hasMissingEvidence = diagnosis.evidence().stream()
                .anyMatch(evidence -> evidence.status() == EvidenceStatus.MISSING);
        String frozenRef = frozenPlaybookRef(diagnosis);

        List<StageView> stages = new ArrayList<>();
        stages.add(new StageView(
                1,
                StageKey.INCIDENT,
                "排障事件",
                StageStatus.COMPLETED,
                safe(diagnosis.incident().title()),
                diagnosis.timings().reportedAt(),
                diagnosis.timings().readyAt(),
                diagnosis.timings().intakeCost(),
                List.of(
                        field("事件 ID", diagnosis.incident().incidentId()),
                        field("系统", diagnosis.incident().system()),
                        field("服务", diagnosis.incident().service()),
                        field("错误码", diagnosis.incident().errorCode()),
                        field("严重级别", diagnosis.incident().severity()),
                        field("信息完整度", diagnosis.incident().completeness()),
                        field("上报来源", diagnosis.incident().intakeSource()),
                        field("发生时间", diagnosis.incident().occurredAt()),
                        field("Trace / PS ID", diagnosis.incident().traceId()),
                        field("原始输入", "不进入该投影（仅展示规范化 Incident 字段）")),
                diagnosis.incident().impact().evidenceRefs()));

        boolean legacyRoute = diagnosis.routeSemanticsProvenance()
                == RouteSemanticsProvenance.LEGACY_DERIVED;
        StageStatus routeStatus = legacyRoute
                || diagnosis.investigationMode() == InvestigationMode.OPEN_DISCOVERY
                        ? StageStatus.PARTIAL
                        : StageStatus.COMPLETED;
        stages.add(new StageView(
                2,
                StageKey.PLAYBOOK_ROUTE,
                "调查路径 / Playbook",
                routeStatus,
                legacyRoute
                        ? "旧合同未持久化调查模式与路由权威"
                        : diagnosis.investigationMode() == InvestigationMode.OPEN_DISCOVERY
                                ? "开放调查，未命中已审核 Playbook"
                                : safe(diagnosis.sopTitle()),
                null,
                null,
                null,
                List.of(
                        field("调查模式", legacyRoute ? null : diagnosis.investigationMode()),
                        field("路由权威", legacyRoute ? null : diagnosis.routeAuthority()),
                        field("兼容路由模式", diagnosis.routeMode()),
                        field("路由语义来源", diagnosis.routeSemanticsProvenance()),
                        field("SOP 选择键", diagnosis.sopKey()),
                        field("冻结 Playbook", frozenRef),
                        field("Playbook 责任方", diagnosis.sourcePlaybookOwner())),
                List.of()));

        StageStatus contractStatus;
        if (frozenPlaybook != null) {
            contractStatus = StageStatus.COMPLETED;
        } else if (diagnosis.sourcePlaybookVersionRef() != null) {
            contractStatus = StageStatus.PARTIAL;
        } else {
            contractStatus = StageStatus.UNRECORDED;
        }
        stages.add(new StageView(
                3,
                StageKey.EVIDENCE_CONTRACT,
                "证据合同",
                contractStatus,
                contracts.isEmpty() ? null : contracts.size() + " 份冻结证据请求",
                null,
                null,
                null,
                List.of(
                        field("合同版本", frozenPlaybook == null
                                ? null : frozenPlaybook.contractVersion()),
                        field("请求数量", contracts.isEmpty() ? null : contracts.size()),
                        field("必需请求", contracts.isEmpty() ? null : contracts.stream()
                                .filter(EvidenceContractView::required).count())),
                List.of()));

        Set<String> sources = new LinkedHashSet<>();
        attempts.forEach(attempt -> sources.add(attempt.adapterSource()));
        stages.add(new StageView(
                4,
                StageKey.ADAPTER_SELECTION,
                "选择适配器",
                attempts.isEmpty() ? StageStatus.UNRECORDED : StageStatus.PARTIAL,
                attempts.isEmpty()
                        ? null
                        : "仅记录最终证据来源；候选顺序、失败重试与逐次耗时未记录",
                null,
                null,
                null,
                List.of(
                        field("最终结果来源", sources.isEmpty() ? null : String.join(", ", sources)),
                        field("候选适配器顺序", null),
                        field("失败与重试次数", null),
                        field("逐次尝试耗时", null)),
                evidenceRefs));

        StageStatus collectionStatus = diagnosis.evidence().isEmpty()
                ? StageStatus.UNRECORDED
                : hasMissingEvidence || !missingRequired.isEmpty()
                        ? StageStatus.PARTIAL : StageStatus.COMPLETED;
        stages.add(new StageView(
                5,
                StageKey.EVIDENCE_COLLECTION,
                "获取只读证据",
                collectionStatus,
                diagnosis.evidence().isEmpty()
                        ? null
                        : diagnosis.evidence().size() + " 份规范化只读证据，"
                                + diagnosis.evidence().stream()
                                        .filter(item -> item.status() == EvidenceStatus.MISSING)
                                        .count()
                                + " 份缺失",
                null,
                null,
                null,
                List.of(
                        field("证据数量", diagnosis.evidence().isEmpty()
                                ? null : diagnosis.evidence().size()),
                        field("缺失必需请求", missingRequired.isEmpty()
                                ? null : String.join(", ", missingRequired)),
                        field("单次采集耗时", null),
                        field("写操作", "禁用")),
                evidenceRefs));

        boolean criteriaMissing = derivation == null || derivation.criteria().isEmpty();
        boolean criteriaPartial = derivation != null && (!derivation.faithful()
                || derivation.criteria().stream()
                        .anyMatch(item -> item.outcome() == CriterionOutcome.UNEVALUATED));
        StageStatus criterionStatus = criteriaMissing
                ? StageStatus.UNRECORDED
                : criteriaPartial ? StageStatus.PARTIAL : StageStatus.COMPLETED;
        stages.add(new StageView(
                6,
                StageKey.CRITERION_EVALUATION,
                "判据计算",
                criterionStatus,
                criteriaMissing
                        ? null
                        : derivation.criteria().size() + " 条判据，"
                                + derivation.criteria().stream()
                                        .filter(item -> item.outcome() == CriterionOutcome.SATISFIED)
                                        .count()
                                + " 条命中",
                null,
                null,
                null,
                List.of(
                        field("可复算", derivation == null ? null : derivation.faithful()),
                        field("判据数量", criteriaMissing ? null : derivation.criteria().size()),
                        field("规则数量", derivation == null || derivation.rules().isEmpty()
                                ? null : derivation.rules().size()),
                        field("判据计算耗时", null),
                        field("完整性说明", derivation == null ? null : derivation.note())),
                derivation == null ? List.of() : derivation.criteria().stream()
                        .map(DiagnosisDerivation.CriterionEvaluation::sourceRequestId)
                        .filter(value -> value != null && !value.isBlank())
                        .distinct()
                        .toList()));

        boolean stopped = diagnosis.conclusionType() == ConclusionType.INSUFFICIENT_EVIDENCE;
        stages.add(new StageView(
                7,
                StageKey.CONCLUSION,
                "结论或弃权",
                stopped ? StageStatus.STOPPED : StageStatus.COMPLETED,
                safe(diagnosis.summary()),
                null,
                diagnosis.timings().conclusionAt(),
                null,
                List.of(
                        field("结论类型", diagnosis.conclusionType()),
                        field("诊断状态", diagnosis.status()),
                        field("置信度", diagnosis.confidence()),
                        field("已弃权", diagnosis.abstained()),
                        field("根因", diagnosis.rootCause()),
                        field("转交团队", diagnosis.routeToTeam()),
                        field("停止原因", stopReason.message()),
                        field("计时口径", diagnosis.timings().investigateCost() == null
                                ? null : "进入调查（readyAt）到形成结论（conclusionAt）的总耗时，跨越第 2—7 阶段")),
                relatedConclusionEvidence(diagnosis, derivation)));
        return List.copyOf(stages);
    }

    private StopReasonView stopReason(
            Diagnosis diagnosis,
            DiagnosisDerivation derivation,
            List<String> missingRequired) {
        StopReasonCode code;
        String message;
        if (diagnosis.conclusionType() != ConclusionType.INSUFFICIENT_EVIDENCE) {
            code = StopReasonCode.CONCLUSION_RECORDED;
            message = "已记录可复核的“" + diagnosis.conclusionType() + "”结论";
        } else if (!missingRequired.isEmpty()) {
            code = StopReasonCode.EVIDENCE_MISSING;
            message = "冻结证据合同中的必需请求未取得非缺失结果："
                    + String.join(", ", missingRequired);
        } else if (diagnosis.abstained()) {
            code = StopReasonCode.ABSTAINED;
            message = "Diagnosis 已记录弃权，但更细停止原因未记录";
        } else {
            code = StopReasonCode.UNRECORDED;
            message = null;
        }
        return new StopReasonView(
                code,
                message,
                diagnosis.timings().conclusionAt(),
                code == StopReasonCode.EVIDENCE_MISSING
                        ? missingRequired
                        : relatedConclusionEvidence(diagnosis, derivation));
    }

    private EvidenceRelationView evidenceRelation(
            Diagnosis diagnosis,
            DiagnosisDerivation derivation) {
        Map<String, RelationNode> nodes = new LinkedHashMap<>();
        List<RelationEdge> edges = new ArrayList<>();
        Map<String, DiagnosisDerivation.CriterionEvaluation> criteriaBySignal =
                new LinkedHashMap<>();

        for (EvidenceResult original : diagnosis.evidence()) {
            EvidenceResult evidence = TroubleshootingSecretRedactor.redact(original);
            String signalKind = signalKind(null, evidence);
            nodes.putIfAbsent(evidenceNodeId(evidence.queryId()), new RelationNode(
                    evidenceNodeId(evidence.queryId()),
                    RelationNodeKind.EVIDENCE,
                    evidenceLabel(signalKind, evidence.status()),
                    safe(evidence.source()) + " · " + evidence.collectedAt(),
                    evidence.status().name(),
                    evidence.queryId()));
        }

        if (derivation != null) {
            for (DiagnosisDerivation.CriterionEvaluation criterion : derivation.criteria()) {
                criteriaBySignal.putIfAbsent(criterion.signal(), criterion);
                String evidenceId = evidenceNodeId(criterion.sourceRequestId());
                nodes.putIfAbsent(evidenceId, new RelationNode(
                        evidenceId,
                        RelationNodeKind.EVIDENCE,
                        "未记录证据 · " + InvestigationTraceView.display(criterion.sourceRequestId()),
                        null,
                        EvidenceStatus.MISSING.name(),
                        criterion.sourceRequestId()));
                String criterionId = criterionNodeId(criterion.signal());
                nodes.putIfAbsent(criterionId, new RelationNode(
                        criterionId,
                        RelationNodeKind.CRITERION,
                        safe(criterion.description()),
                        criterionDetail(criterion),
                        criterion.outcome().name(),
                        criterion.signal()));
                addEdge(
                        edges,
                        evidenceId,
                        criterionId,
                        relation(criterion.outcome()),
                        criterionEdgeLabel(criterion.outcome()));
            }

            for (DiagnosisDerivation.RuleEvaluation rule : derivation.rules()) {
                String ruleId = ruleNodeId(rule.ruleId());
                nodes.putIfAbsent(ruleId, new RelationNode(
                        ruleId,
                        RelationNodeKind.RULE,
                        safe(rule.rootCause()),
                        ruleDetail(rule),
                        rule.fired() ? "FIRED" : "NOT_FIRED",
                        rule.ruleId()));
                for (String signal : rule.requiredSignals()) {
                    DiagnosisDerivation.CriterionEvaluation criterion = criteriaBySignal.get(signal);
                    if (criterion == null) {
                        continue;
                    }
                    addEdge(
                            edges,
                            criterionNodeId(signal),
                            ruleId,
                            relation(criterion.outcome()),
                            ruleEdgeLabel(criterion.outcome()));
                }
            }
        }

        String conclusionId = conclusionNodeId(diagnosis.diagnosisId());
        nodes.put(conclusionId, new RelationNode(
                conclusionId,
                RelationNodeKind.CONCLUSION,
                safe(diagnosis.summary()),
                safe(diagnosis.rootCause()),
                diagnosis.conclusionType().name(),
                diagnosis.diagnosisId()));

        boolean linkedConclusion = false;
        if (derivation != null && derivation.faithful()) {
            for (DiagnosisDerivation.RuleEvaluation rule : derivation.rules()) {
                if (rule.fired()) {
                    addEdge(
                            edges,
                            ruleNodeId(rule.ruleId()),
                            conclusionId,
                            RelationType.SUPPORTS,
                            "命中规则产生结论");
                    linkedConclusion = true;
                }
            }
        }

        if (!linkedConclusion && (derivation == null || derivation.faithful())) {
            linkedConclusion = addFallbackConclusionEdges(
                    diagnosis, derivation, nodes, edges, conclusionId);
        }
        String emptyReason = derivation != null && !derivation.faithful()
                ? safe(derivation.note() == null
                        ? "冻结 Playbook 重算与当时 Diagnosis 记录不一致"
                        : derivation.note())
                : null;
        return new EvidenceRelationView(
                linkedConclusion,
                List.copyOf(nodes.values()),
                List.copyOf(edges),
                emptyReason);
    }

    private boolean addFallbackConclusionEdges(
            Diagnosis diagnosis,
            DiagnosisDerivation derivation,
            Map<String, RelationNode> nodes,
            List<RelationEdge> edges,
            String conclusionId) {
        boolean linked = false;
        for (String citation : diagnosis.evidenceCitations()) {
            String evidenceId = evidenceNodeId(citation);
            if (nodes.containsKey(evidenceId)) {
                addEdge(edges, evidenceId, conclusionId, RelationType.CITES, "Diagnosis 引用证据");
                linked = true;
            }
        }
        if (linked || derivation == null) {
            return linked;
        }
        Set<String> triggered = new LinkedHashSet<>(diagnosis.triggeredSignals());
        for (DiagnosisDerivation.CriterionEvaluation criterion : derivation.criteria()) {
            boolean connect = switch (diagnosis.conclusionType()) {
                case LOCATED -> triggered.contains(criterion.signal())
                        && criterion.outcome() == CriterionOutcome.SATISFIED;
                case EXCLUDED -> criterion.outcome() == CriterionOutcome.EXCLUDED;
                case INSUFFICIENT_EVIDENCE -> criterion.outcome() == CriterionOutcome.UNEVALUATED;
                case HYPOTHESIS -> false;
            };
            if (!connect) {
                continue;
            }
            RelationType relation = diagnosis.conclusionType() == ConclusionType.INSUFFICIENT_EVIDENCE
                    ? RelationType.BLOCKS : RelationType.SUPPORTS;
            String label = switch (diagnosis.conclusionType()) {
                case LOCATED -> "记录命中信号支持结论（规则节点未记录）";
                case EXCLUDED -> "反证支持排除结论";
                case INSUFFICIENT_EVIDENCE -> "证据缺口导致弃权";
                case HYPOTHESIS -> "Diagnosis 引用";
            };
            addEdge(
                    edges,
                    criterionNodeId(criterion.signal()),
                    conclusionId,
                    relation,
                    label);
            linked = true;
        }
        return linked;
    }

    private List<String> relatedConclusionEvidence(
            Diagnosis diagnosis,
            DiagnosisDerivation derivation) {
        LinkedHashSet<String> refs = new LinkedHashSet<>(diagnosis.evidenceCitations());
        if (derivation != null) {
            derivation.criteria().stream()
                    .map(DiagnosisDerivation.CriterionEvaluation::sourceRequestId)
                    .filter(value -> value != null && !value.isBlank())
                    .forEach(refs::add);
        }
        if (refs.isEmpty()) {
            diagnosis.evidence().stream().map(EvidenceResult::queryId).forEach(refs::add);
        }
        return List.copyOf(refs);
    }

    private String ruleDetail(DiagnosisDerivation.RuleEvaluation rule) {
        if (rule.fired()) {
            return "所需信号全部满足";
        }
        List<String> reasons = new ArrayList<>();
        if (!rule.unsatisfiedByExclusion().isEmpty()) {
            reasons.add("已排除：" + String.join(", ", rule.unsatisfiedByExclusion()));
        }
        if (!rule.unsatisfiedByGap().isEmpty()) {
            reasons.add("证据缺口：" + String.join(", ", rule.unsatisfiedByGap()));
        }
        if (!rule.undefinedSignals().isEmpty()) {
            reasons.add("未定义信号：" + String.join(", ", rule.undefinedSignals()));
        }
        return reasons.isEmpty() ? null : String.join("；", reasons);
    }

    private RelationType relation(CriterionOutcome outcome) {
        return switch (outcome) {
            case SATISFIED -> RelationType.SUPPORTS;
            case EXCLUDED -> RelationType.REFUTES;
            case UNEVALUATED -> RelationType.BLOCKS;
        };
    }

    private String criterionEdgeLabel(CriterionOutcome outcome) {
        return switch (outcome) {
            case SATISFIED -> "观测值满足判据";
            case EXCLUDED -> "观测值反证判据";
            case UNEVALUATED -> "证据缺失，判据未求值";
        };
    }

    private String ruleEdgeLabel(CriterionOutcome outcome) {
        return switch (outcome) {
            case SATISFIED -> "命中所需信号";
            case EXCLUDED -> "反证候选规则";
            case UNEVALUATED -> "缺口阻断候选规则";
        };
    }

    private void addEdge(
            List<RelationEdge> edges,
            String from,
            String to,
            RelationType relation,
            String label) {
        edges.add(new RelationEdge(
                "edge-" + (edges.size() + 1), from, to, relation, label));
    }

    private String frozenPlaybookRef(Diagnosis diagnosis) {
        if (diagnosis.sourcePlaybookVersionRef() == null) {
            return null;
        }
        return diagnosis.sourcePlaybookVersionRef().playbookId()
                + "@v"
                + diagnosis.sourcePlaybookVersionRef().playbookVersion();
    }

    private List<String> missingRequiredEvidence(
            List<EvidenceContractView> contracts,
            List<EvidenceResult> evidence) {
        Set<String> present = new LinkedHashSet<>();
        evidence.stream()
                .filter(item -> item.status() != EvidenceStatus.MISSING)
                .map(EvidenceResult::queryId)
                .forEach(present::add);
        return contracts.stream()
                .filter(EvidenceContractView::required)
                .map(EvidenceContractView::requestId)
                .filter(requestId -> !present.contains(requestId))
                .toList();
    }

    private String signalKind(EvidenceRequest request, EvidenceResult evidence) {
        String detected = CanonicalEvidenceSchema.detectSignalKind(evidence.observed());
        if (detected != null) {
            return detected;
        }
        if (request == null) {
            return null;
        }
        String requested = request.signalKind() == null
                ? null : request.signalKind().trim().toLowerCase(java.util.Locale.ROOT);
        return evidence.status() == EvidenceStatus.MISSING
                || CanonicalEvidenceSchema.isValid(requested, evidence.observed())
                        ? requested : null;
    }

    private Map<String, Object> safeObserved(
            String signalKind,
            EvidenceResult evidence) {
        if (signalKind == null
                || evidence.status() == EvidenceStatus.MISSING
                || !CanonicalEvidenceSchema.isValid(signalKind, evidence.observed())) {
            return Map.of();
        }
        Set<String> permitted = SAFE_OBSERVED_FIELDS.getOrDefault(signalKind, Set.of());
        Map<String, Object> facts = new LinkedHashMap<>();
        permitted.stream().sorted().forEach(field -> {
            if (evidence.observed().containsKey(field)) {
                facts.put(field, safeFact(evidence.observed().get(field)));
            }
        });
        if ("log_trace_bundle".equals(signalKind)
                && evidence.observed().get("entries") instanceof List<?> entries) {
            facts.put("entry_count", entries.size());
        }
        return facts;
    }

    private Object safeFact(Object value) {
        if (value instanceof String text) {
            String sanitized = TroubleshootingSecretRedactor.redact(text.trim());
            return sanitized.length() <= 512
                    ? sanitized : sanitized.substring(0, 500) + "...[TRUNCATED]";
        }
        return value;
    }

    private String evidenceLabel(String signalKind, EvidenceStatus status) {
        return (signalKind == null ? "unknown" : signalKind)
                + " 证据 · " + status.name();
    }

    /**
     * Criterion substitutions may contain arbitrary observed strings (for example
     * {@code log_search.sample_message}). The relation projection therefore keeps
     * the authored expression and deterministic outcome, while the linked evidence
     * node exposes only canonical whitelisted facts.
     */
    private String criterionDetail(DiagnosisDerivation.CriterionEvaluation criterion) {
        return safe(criterion.expression())
                + "；判据结果=" + criterion.outcome().name()
                + "；实际观测值请沿证据引用查看安全字段";
    }

    private TraceField field(String label, Object value) {
        return new TraceField(label, value == null ? null : safe(String.valueOf(value)));
    }

    private String safe(String value) {
        return InvestigationTraceView.display(TroubleshootingSecretRedactor.redact(value));
    }

    private Map<String, Object> safeMap(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        values.forEach((key, value) -> sanitized.put(safe(key), safeValue(key, value)));
        return sanitized;
    }

    private Object safeValue(String key, Object value) {
        if (value == null) {
            return null;
        }
        String keyProbe = key + "=placeholder";
        if (!keyProbe.equals(TroubleshootingSecretRedactor.redact(keyProbe))) {
            return TroubleshootingSecretRedactor.REDACTED;
        }
        if (value instanceof Map<?, ?> nested) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            nested.forEach((nestedKey, nestedValue) -> {
                String displayKey = String.valueOf(nestedKey);
                sanitized.put(safe(displayKey), safeValue(displayKey, nestedValue));
            });
            return sanitized;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> sanitized = new ArrayList<>();
            iterable.forEach(item -> sanitized.add(safeValue(key, item)));
            return sanitized;
        }
        if (value.getClass().isArray()) {
            List<Object> sanitized = new ArrayList<>();
            for (int index = 0; index < Array.getLength(value); index++) {
                sanitized.add(safeValue(key, Array.get(value, index)));
            }
            return sanitized;
        }
        return value instanceof String text
                ? TroubleshootingSecretRedactor.redact(text)
                : value;
    }

    private String evidenceNodeId(String ref) {
        return "evidence:" + InvestigationTraceView.display(ref);
    }

    private String criterionNodeId(String signal) {
        return "criterion:" + InvestigationTraceView.display(signal);
    }

    private String ruleNodeId(String ruleId) {
        return "rule:" + InvestigationTraceView.display(ruleId);
    }

    private String conclusionNodeId(String diagnosisId) {
        return "conclusion:" + diagnosisId;
    }
}
