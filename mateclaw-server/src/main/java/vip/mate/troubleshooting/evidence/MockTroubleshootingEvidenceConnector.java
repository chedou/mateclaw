package vip.mate.troubleshooting.evidence;

import org.springframework.stereotype.Component;
import vip.mate.troubleshooting.dto.SopRouteRequest;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class MockTroubleshootingEvidenceConnector implements EvidenceConnector {

    @Override
    public boolean supports(String evidenceType) {
        return evidenceType != null && !evidenceType.isBlank();
    }

    @Override
    public List<CollectedEvidence> collect(EvidenceCollectionRequest request) {
        String type = normalize(request.evidenceType());
        SopRouteRequest alert = request.alert();
        String target = target(alert);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("connector", id());
        content.put("status", "collected");
        content.put("mode", "mock");
        content.put("serviceName", value(alert == null ? null : alert.serviceName(), "unknown-service"));
        content.put("env", value(alert == null ? null : alert.env(), "unknown-env"));
        content.put("cluster", value(alert == null ? null : alert.cluster(), "unknown-cluster"));
        content.put("endpoint", value(alert == null ? null : alert.endpoint(), "unknown-endpoint"));
        content.put("collectedAt", LocalDateTime.now().toString());
        content.put("timeWindow", "alert_time +/- 15m");
        content.put("sampleWindow", "alert_time +/- 15m");
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("caseId", request.caseId());
        query.put("runId", request.run() == null ? null : request.run().getId());
        query.put("evidenceType", type);
        content.put("query", query);

        String title;
        String summary;
        switch (type) {
            case "metrics" -> {
                content.put("errorRate", "mock: 5xx_rate elevated from 0.2% to 6.8%");
                content.put("p95Latency", "mock: p95 latency elevated from 180ms to 1.9s");
                content.put("qps", "mock: traffic stable, no traffic-drop signal");
                content.put("rawPreview", "mock metrics: 5xx_rate elevated, p95 latency elevated, traffic stable");
                title = "Mock metrics snapshot";
                summary = "模拟监控显示 " + target + " 在告警窗口内 5xx 和延迟升高，流量基本稳定。";
            }
            case "logs" -> {
                content.put("errorSignature", "mock: upstream timeout calling payment-service");
                content.put("sampleCount", 37);
                content.put("sensitiveFieldsRedacted", true);
                content.put("rawPreview", "mock logs: timeout calling payment-service; sensitive fields redacted");
                title = "Mock log search result";
                summary = "模拟日志显示 " + target + " 存在集中 timeout 错误签名，原始日志已脱敏。";
            }
            case "release" -> {
                content.put("recentChange", "mock: deployment completed 8 minutes before alert");
                content.put("changeOwner", "platform-sre");
                content.put("rollbackAvailable", true);
                content.put("rawPreview", "mock release: deployment completed 8 minutes before alert; rollback available");
                title = "Mock release window";
                summary = "模拟发布记录显示告警前 8 分钟存在一次发布，需结合日志和指标确认相关性。";
            }
            case "synthetics" -> {
                content.put("failedRegions", List.of("华东-上海"));
                content.put("failedCount", 2);
                content.put("successRate", "mock: 92%");
                content.put("rawPreview", "mock synthetics: Shanghai probes saw HTTP 503 and timeout in alert window");
                title = "Mock Guance synthetics";
                summary = "模拟观测云拨测显示 " + target + " 在告警窗口内部分地域探测失败，需结合指标和日志确认内外部影响面。";
            }
            case "host" -> {
                content.put("hostStatus", "mock: one host cpu usage reached 91%");
                content.put("memoryUsage", "mock: memory usage normal");
                content.put("rawPreview", "mock host: cpu high on one node, memory normal");
                title = "Mock Guance host snapshot";
                summary = "模拟观测云主机数据提示 " + target + " 所在主机 CPU 短时升高，需要结合容器和指标确认影响。";
            }
            case "container" -> {
                content.put("restartCount", "mock: container restarted 2 times");
                content.put("podPhase", "mock: running with recent restart");
                content.put("rawPreview", "mock container: recent restart and transient readiness failure");
                title = "Mock Guance container snapshot";
                summary = "模拟观测云容器数据提示 " + target + " 存在近期重启和短暂就绪失败。";
            }
            case "k8s" -> {
                content.put("podRestarts", "mock: 3 pods restarted in alert window");
                content.put("probeFailures", "mock: readiness probe failures observed");
                content.put("rawPreview", "mock k8s: pod restarts and readiness probe failures observed");
                title = "Mock K8s events";
                summary = "模拟 K8s 事件显示告警窗口内存在 Pod 重启和就绪探针失败。";
            }
            case "gateway" -> {
                content.put("upstreamStatus", "mock: upstream 504 increased");
                content.put("gatewayLatency", "mock: gateway overhead normal");
                content.put("rawPreview", "mock gateway: upstream 504 increased; gateway overhead normal");
                title = "Mock gateway trace";
                summary = "模拟网关证据显示 upstream 504 上升，网关自身延迟未见明显异常。";
            }
            case "runbook" -> {
                content.put("matchedRunbook", "mock: systematic-debugging fallback notes");
                content.put("historicalIncident", "mock: similar timeout incident resolved by rollback");
                content.put("rawPreview", "mock runbook: similar timeout incident resolved by rollback");
                title = "Mock runbook match";
                summary = "模拟 runbook 命中相似超时处置记录，建议优先核对发布和依赖状态。";
            }
            default -> {
                content.put("note", "mock evidence placeholder for " + type);
                content.put("rawPreview", "mock evidence placeholder for " + type);
                title = "Mock " + type + " evidence";
                summary = "模拟采集 " + type + " 证据，目标：" + target + "。";
            }
        }

        return List.of(new CollectedEvidence(type, id(), "collected", title, summary, content));
    }

    @Override
    public int order() {
        return 1000;
    }

    @Override
    public String id() {
        return "mock-troubleshooting";
    }

    private static String target(SopRouteRequest alert) {
        if (alert == null) return "unknown target";
        return String.join(" / ", List.of(
                value(alert.serviceName(), "unknown-service"),
                value(alert.env(), "unknown-env"),
                value(alert.cluster(), "unknown-cluster"),
                value(alert.endpoint(), "unknown-endpoint")
        ));
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (List.of("synthetic", "synthetics", "dialtest", "dial-test", "dial_test", "guance-synthetics", "拨测")
                .contains(normalized)) {
            return "synthetics";
        }
        String underscored = normalized.replace('-', '_');
        if (List.of("hosts", "infra_host", "infrastructure_host", "guance_host", "guance_hosts", "主机")
                .contains(underscored)) {
            return "host";
        }
        if (List.of("containers", "pod", "pods", "infra_container", "infrastructure_container",
                "guance_container", "guance_containers", "guance_pod", "guance_pods", "容器")
                .contains(underscored)) {
            return "container";
        }
        if (List.of("kubernetes", "guance_k8s").contains(underscored)) {
            return "k8s";
        }
        return underscored;
    }

    private static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
