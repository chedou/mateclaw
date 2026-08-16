package vip.mate.troubleshooting.investigation;

import org.springframework.stereotype.Component;
import vip.mate.troubleshooting.engine.Criterion;
import vip.mate.troubleshooting.model.AnomalyCriterion;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.IncidentContext;

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
        return HypothesisGraph.of(List.of(
                new HypothesisGraph.Hypothesis(
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
                                new Criterion.NumericGte("error_count", 1)))),
                new HypothesisGraph.Hypothesis(
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
                                new Criterion.NumericGte("unhealthy_container_count", 1))))));
    }

    private HypothesisGraph.Question question(
            String id,
            int priority,
            String signalKind,
            String purpose,
            String signal,
            String description,
            Criterion criterion) {
        EvidenceRequest request = new EvidenceRequest(
                id,
                signalKind,
                purpose,
                Map.of(),
                "-15m",
                true);
        return new HypothesisGraph.Question(
                id,
                priority,
                EvidenceRouterReadOnlyTool.TOOL_KEY,
                EvidenceRouterReadOnlyTool.VERSION,
                request,
                new AnomalyCriterion(signal, id, description, criterion));
    }
}
