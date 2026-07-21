package vip.mate.troubleshooting.service;

import org.springframework.stereotype.Service;
import vip.mate.troubleshooting.dto.SopRouteCandidate;
import vip.mate.troubleshooting.dto.SopRouteRequest;
import vip.mate.troubleshooting.dto.SopRouteResult;
import vip.mate.troubleshooting.dto.SopStepResult;
import vip.mate.troubleshooting.model.SopDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class SopRunDraftBuilder {

    public List<SopStepResult> buildStepDrafts(SopDefinition sop, SopRouteRequest alert, SopRouteResult route) {
        List<String> required = sop == null || sop.requiredEvidence() == null
                ? List.of()
                : sop.requiredEvidence();
        if (required.isEmpty()) {
            return List.of(new SopStepResult(
                    "collect-general-evidence",
                    "inconclusive",
                    List.of("DRAFT-GENERAL-001"),
                    List.of("general"),
                    "待采集通用排障证据。告警摘要：" + inputSummary(alert, route),
                    "当前仅生成执行草稿，不能作为确定根因。",
                    "need_human"
            ));
        }

        List<SopStepResult> out = new ArrayList<>();
        for (String evidence : required) {
            out.add(new SopStepResult(
                    "collect-" + slug(evidence),
                    "inconclusive",
                    List.of("DRAFT-" + slug(evidence).toUpperCase(Locale.ROOT) + "-" + String.format("%03d", out.size() + 1)),
                    List.of(evidence),
                    observationFor(evidence, alert, route),
                    interpretationFor(evidence, sop),
                    "continue"
            ));
        }
        return out;
    }

    public Map<String, Object> buildFinalReportDraft(SopDefinition sop, SopRouteRequest alert, SopRouteResult route) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("conclusion", "证据不足：已创建 SOP 执行草稿，待按 checklist 采集真实证据后更新结论");
        report.put("confidence", "low");
        report.put("nextAction", "按 Agent 执行合同采集必查证据，替换 DRAFT evidenceIds 后重新提交");
        report.put("domain", sop == null ? null : sop.domain());
        report.put("scenario", sop == null ? null : sop.scenario());
        report.put("alertSummary", inputSummary(alert, route));
        SopRouteCandidate selected = route == null ? null : route.selected();
        if (selected != null && selected.missingSignals() != null && !selected.missingSignals().isEmpty()) {
            report.put("missingSignals", selected.missingSignals());
        }
        return report;
    }

    private static String observationFor(String evidence, SopRouteRequest alert, SopRouteResult route) {
        String target = target(alert);
        String window = defaultWindow(alert);
        return switch (slug(evidence)) {
            case "metrics" -> "待采集监控证据：确认 " + target + " 在 " + window + " 的错误率、延迟、QPS、成功率曲线。告警摘要：" + inputSummary(alert, route);
            case "logs" -> "待采集日志证据：检索 " + target + " 在 " + window + " 的错误栈、上游/下游状态码、timeout 关键字。";
            case "release" -> "待采集发布证据：核对 " + target + " 在 " + window + " 是否存在发布、配置、灰度、回滚或依赖版本变化。";
            case "synthetics" -> "待采集观测云拨测证据：确认 " + target + " 在 " + window + " 的探测成功率、失败地域、HTTP 状态码和响应耗时。";
            case "k8s" -> "待采集 K8s 证据：检查 Pod restart、探针失败、OOMKilled、调度和事件。目标：" + target;
            case "host" -> "待采集观测云主机证据：检查主机 CPU、内存、磁盘、网络、在线状态和异常状态。目标：" + target;
            case "container" -> "待采集观测云容器证据：检查 Pod/容器状态、restart count、last state、namespace 和 cluster 维度。目标：" + target;
            case "gateway" -> "待采集网关证据：确认网关状态码、upstream latency、重试和限流情况。目标：" + target;
            case "runbook" -> "待查阅 runbook：匹配历史处置记录和业务降级预案。目标：" + target;
            default -> "待采集 " + evidence + " 证据。目标：" + target + "；窗口：" + window + "；告警摘要：" + inputSummary(alert, route);
        };
    }

    private static String interpretationFor(String evidence, SopDefinition sop) {
        String domain = sop == null ? "unknown" : sop.domain() + "/" + sop.scenario();
        return switch (slug(evidence)) {
            case "metrics" -> "如果指标异常与告警窗口一致，再继续关联日志和发布；如果指标正常，应考虑告警误报或局部实例问题。";
            case "logs" -> "如果日志出现集中错误签名，应用该签名继续定位代码路径、依赖或数据问题；没有日志证据时不能宣称确定根因。";
            case "release" -> "如果异常与发布窗口强相关，优先判断发布/配置变更影响；否则继续检查容量、依赖和外部链路。";
            case "synthetics" -> "如果拨测失败与告警窗口、地域或 endpoint 高度一致，可优先判断真实用户入口受影响；如果拨测正常，应继续区分内部调用、局部实例或告警误报。";
            case "k8s" -> "如果存在重启、探针失败或资源驱逐，应把运行态事件作为高优先级原因链；否则继续检查应用层证据。";
            case "host" -> "如果主机资源或状态异常与告警窗口一致，应继续关联容器和应用指标；如果主机正常，优先回到应用、网关或依赖层。";
            case "container" -> "如果容器重启、探针失败或状态异常，应把运行态事件纳入原因链；如果容器正常，继续检查应用日志和业务指标。";
            case "gateway" -> "如果网关侧存在 upstream 错误或超时，应区分网关自身、上游服务和网络链路问题。";
            default -> "该证据用于支撑 " + domain + " 的 SOP 判断；缺少真实观测时只能输出证据不足。";
        };
    }

    private static String inputSummary(SopRouteRequest alert, SopRouteResult route) {
        if (route != null && route.inputSummary() != null && !route.inputSummary().isBlank()) {
            return route.inputSummary();
        }
        if (alert == null) return "empty alert";
        return List.of(alert.severity(), alert.serviceName(), alert.alertName(), alert.message()).stream()
                .filter(v -> v != null && !v.isBlank())
                .reduce((a, b) -> a + " / " + b)
                .orElse("empty alert");
    }

    private static String target(SopRouteRequest alert) {
        if (alert == null) return "unknown target";
        List<String> parts = new ArrayList<>();
        add(parts, alert.serviceName());
        add(parts, alert.env());
        add(parts, alert.cluster());
        add(parts, alert.namespace());
        add(parts, alert.endpoint());
        return parts.isEmpty() ? "unknown target" : String.join(" / ", parts);
    }

    private static String defaultWindow(SopRouteRequest alert) {
        if (alert != null && alert.status() != null && alert.status().equalsIgnoreCase("resolved")) {
            return "告警开始到恢复后 15 分钟";
        }
        return "告警触发前后各 15 分钟";
    }

    private static void add(List<String> values, String value) {
        if (value != null && !value.isBlank()) values.add(value);
    }

    private static String slug(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }
}
