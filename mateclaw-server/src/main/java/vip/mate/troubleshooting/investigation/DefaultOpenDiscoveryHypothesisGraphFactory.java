package vip.mate.troubleshooting.investigation;

import org.springframework.stereotype.Component;
import vip.mate.troubleshooting.engine.Criterion;
import vip.mate.troubleshooting.model.AnomalyCriterion;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.service.FormalOpenDiscoveryPlan;

import java.util.List;
import java.util.Map;

/**
 * Small, server-owned first graph for an unknown incident.
 *
 * <p>It deliberately asks only two broad questions that the current canonical
 * evidence catalog can answer without inventing a source query. A specific
 * mechanism still requires an approved scenario or human investigation.</p>
 */
@Component
public final class DefaultOpenDiscoveryHypothesisGraphFactory {

    public HypothesisGraph create(IncidentContext incident) {
        if (incident == null) {
            throw new IllegalArgumentException("incident is required");
        }
        List<HypothesisGraph.Hypothesis> hypotheses = new java.util.ArrayList<>();
        if (ReviewedIncidentPolicy.isIcareProductMapping502(incident)) {
            hypotheses.add(new HypothesisGraph.Hypothesis(
                    "icare-product-mapping-http-502",
                    "直接失败点：iCare 产品映射外部接口返回 HTTP 502（上游为何返回 502 尚未定位）",
                    140,
                    List.of(questionWithTool(
                            "open-discovery-icare-product-mapping-reported",
                            140,
                            IncidentReportReadOnlyTool.TOOL_KEY,
                            IncidentReportReadOnlyTool.VERSION,
                            IncidentReportReadOnlyTool.SIGNAL_KIND,
                            "读取规范化告警中已经明确的失败点",
                            "icare-product-mapping-502-present",
                            "告警明确记录产品映射接口 HTTP 502",
                            new Criterion.NumericGte("failure_count", 1)))));
        } else {
            hypotheses.add(new HypothesisGraph.Hypothesis(
                        "application-errors",
                        "应用服务自身出现集中错误",
                        100,
                        List.of(question(
                                "open-discovery-error-log-scan",
                                100,
                                "error_log_scan",
                                "检查故障窗口内应用 ERROR 是否集中出现",
                                "application-error-present",
                                "应用 ERROR 数量大于零",
                                new Criterion.NumericGte("error_count", 1)))));
        }
        hypotheses.add(new HypothesisGraph.Hypothesis(
                        "runtime-health",
                        "Kubernetes 工作负载出现异常",
                        80,
                        List.of(question(
                                "open-discovery-k8s-workload-health",
                                80,
                                "k8s_workload_health",
                                "检查服务工作负载是否存在非运行容器",
                                "runtime-unhealthy-container-present",
                                "异常容器数量大于零",
                                new Criterion.NumericGte("unhealthy_container_count", 1)))));
        return HypothesisGraph.of(hypotheses);
    }

    /**
     * Builds the formal generic graph from external observability questions
     * only. Caller text can never promote itself into local reported evidence.
     */
    public HypothesisGraph createFormal(
            IncidentContext incident,
            FormalOpenDiscoveryPlan formalPlan) {
        if (incident == null) {
            throw new IllegalArgumentException("incident is required");
        }
        if (formalPlan == null) {
            throw new IllegalArgumentException(
                    "formal plan does not authorize the reviewed graph");
        }
        if (ReviewedIncidentPolicy.isCsdpWechatSlowRequest(incident)
                && formalPlan.allowedSignalKinds().contains("slow_request_analysis")) {
            return createCsdpWechatSlowRequest();
        }
        List<HypothesisGraph.Hypothesis> hypotheses = new java.util.ArrayList<>();
        if (formalPlan.allowedSignalKinds().contains("error_log_scan")) {
            hypotheses.add(new HypothesisGraph.Hypothesis(
                        "application-errors",
                        "应用服务自身出现集中错误",
                        100,
                        List.of(question(
                                "open-discovery-error-log-scan",
                                100,
                                "error_log_scan",
                                "检查故障窗口内应用 ERROR 是否集中出现",
                                "application-error-present",
                                "应用 ERROR 数量大于零",
                                new Criterion.NumericGte("error_count", 1)))));
        }
        if (formalPlan.allowedSignalKinds().contains("k8s_workload_health")) {
            hypotheses.add(new HypothesisGraph.Hypothesis(
                        "runtime-health",
                        "Kubernetes 工作负载出现异常",
                        80,
                        List.of(question(
                                "open-discovery-k8s-workload-health",
                                80,
                                "k8s_workload_health",
                                "检查服务工作负载是否存在非运行容器",
                                "runtime-unhealthy-container-present",
                                "异常容器数量大于零",
                                new Criterion.NumericGte(
                                        "unhealthy_container_count", 1)))));
        }
        return HypothesisGraph.of(hypotheses);
    }

    private HypothesisGraph createCsdpWechatSlowRequest() {
        Criterion reviewedCriterion = new Criterion.AllOf(List.of(
                new Criterion.RateMultipleGt(
                        "current_slow_request_count", "current_request_count",
                        "baseline_slow_request_count", "baseline_request_count", 5D),
                new Criterion.FractionGte(
                        "partner_user_info_slow_count", "current_slow_request_count", 0.5D),
                new Criterion.MultipleLte(
                        "current_request_count", "baseline_request_count", 1.5D),
                new Criterion.NumericLte("timeout_error_count", 0D)));
        return HypothesisGraph.of(List.of(new HypothesisGraph.Hypothesis(
                "csdp-wechat-partner-user-info-hotspot",
                "明确排障原因：partner_user_info 查询链路出现性能退化；"
                        + "慢请求显著增长且过半集中于该接口，同期流量未增长、未见超时",
                180,
                List.of(question(
                        "open-discovery-csdp-wechat-slow-request-analysis",
                        180,
                        "slow_request_analysis",
                        "对比故障窗口与前一等长窗口，确认慢请求热点及排除流量/超时方向",
                        "csdp-wechat-partner-user-info-hotspot-present",
                        "慢请求率放大、partner_user_info 占比过半、流量无显著增长且无超时错误",
                        reviewedCriterion,
                        "-20m")))));
    }

    /** Builds a local-only graph after IntakeSession provenance has been verified. */
    public HypothesisGraph createReviewedIncidentReport(IncidentContext incident) {
        if (!ReviewedIncidentPolicy.isReviewedIcareFinishRejection(incident)) {
            throw new IllegalArgumentException("incident has no reviewed local report plan");
        }
        if (ReviewedIncidentPolicy.isIcareRequiredRevisitResultMissing(incident)) {
            return HypothesisGraph.of(List.of(new HypothesisGraph.Hypothesis(
                    "icare-required-revisit-result-missing",
                    "明确排障原因：回访结果未填写，iCare 完结校验拒绝提交",
                    160,
                    List.of(questionWithTool(
                            "open-discovery-icare-revisit-result-reported",
                            160,
                            IncidentReportReadOnlyTool.TOOL_KEY,
                            IncidentReportReadOnlyTool.VERSION,
                            IncidentReportReadOnlyTool.BUSINESS_POLICY_SIGNAL_KIND,
                            "读取规范化告警中已经明确的必填信息拒绝原因",
                            "icare-required-revisit-result-missing-present",
                            "告警与请求结构共同表明回访结果字段为空",
                            new Criterion.NumericGte("failure_count", 1))))));
        }
        return HypothesisGraph.of(List.of(new HypothesisGraph.Hypothesis(
                "icare-mobile-change-order-finish-rejected",
                "直接失败原因：工单关联变更单，iCare 禁止在移动端完结",
                160,
                List.of(questionWithTool(
                        "open-discovery-icare-mobile-finish-reported",
                        160,
                        IncidentReportReadOnlyTool.TOOL_KEY,
                        IncidentReportReadOnlyTool.VERSION,
                        IncidentReportReadOnlyTool.BUSINESS_POLICY_SIGNAL_KIND,
                        "读取规范化告警中已经明确的业务拒绝原因",
                        "icare-mobile-change-order-finish-policy-present",
                        "告警明确记录移动端完结被变更单规则拒绝",
                        new Criterion.NumericGte("failure_count", 1))))));
    }

    private HypothesisGraph.Question question(
            String id,
            int priority,
            String signalKind,
            String purpose,
            String signal,
            String description,
            Criterion criterion) {
        return question(
                id, priority, signalKind, purpose, signal, description, criterion, "-15m");
    }

    private HypothesisGraph.Question question(
            String id,
            int priority,
            String signalKind,
            String purpose,
            String signal,
            String description,
            Criterion criterion,
            String window) {
        return questionWithTool(
                id, priority,
                EvidenceRouterReadOnlyTool.TOOL_KEY,
                EvidenceRouterReadOnlyTool.VERSION,
                signalKind, purpose, signal, description, criterion, window);
    }

    private HypothesisGraph.Question questionWithTool(
            String id,
            int priority,
            String toolKey,
            String toolVersion,
            String signalKind,
            String purpose,
            String signal,
            String description,
            Criterion criterion) {
        return questionWithTool(
                id, priority, toolKey, toolVersion, signalKind, purpose,
                signal, description, criterion, "-15m");
    }

    private HypothesisGraph.Question questionWithTool(
            String id,
            int priority,
            String toolKey,
            String toolVersion,
            String signalKind,
            String purpose,
            String signal,
            String description,
            Criterion criterion,
            String window) {
        EvidenceRequest request = new EvidenceRequest(
                id,
                signalKind,
                purpose,
                Map.of(),
                window,
                true);
        return new HypothesisGraph.Question(
                id,
                priority,
                toolKey,
                toolVersion,
                request,
                new AnomalyCriterion(signal, id, description, criterion));
    }
}
