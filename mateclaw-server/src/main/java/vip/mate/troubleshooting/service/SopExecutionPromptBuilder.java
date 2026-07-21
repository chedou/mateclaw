package vip.mate.troubleshooting.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import vip.mate.troubleshooting.dto.SopRouteRequest;
import vip.mate.troubleshooting.dto.SopRouteResult;
import vip.mate.troubleshooting.model.SopDefinition;
import vip.mate.troubleshooting.model.TroubleshootingSopRunEntity;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class SopExecutionPromptBuilder {

    private final ObjectMapper objectMapper;

    public String build(TroubleshootingSopRunEntity run,
                        SopDefinition sop,
                        SopRouteRequest alert,
                        SopRouteResult route) {
        StringBuilder sb = new StringBuilder();
        sb.append("[排障 SOP 执行合同]\n");
        sb.append("你是公司内部智能排障助手。本次任务必须严格按照选中的 SOP 执行，")
                .append("不能凭空切换 SOP，不能发明证据，所有工具默认只读。\n\n");

        sb.append("[案件上下文]\n");
        sb.append("- caseId: ").append(run == null ? "" : safe(run.getCaseId())).append('\n');
        sb.append("- sopRunId: ").append(run == null || run.getId() == null ? "" : run.getId()).append('\n');
        sb.append("- domain/scenario: ")
                .append(sop == null ? "" : safe(sop.domain()) + "/" + safe(sop.scenario())).append('\n');
        sb.append("- confidence: ").append(route == null || route.selected() == null
                ? "unknown" : route.selected().confidence()).append('\n');
        sb.append("- missingSignals: ").append(route == null ? "[]" : toJson(route.missingSignals())).append("\n\n");

        sb.append("[告警输入]\n");
        sb.append(toJson(alert)).append("\n\n");

        sb.append("[证据要求]\n");
        sb.append("- requiredEvidence: ").append(sop == null ? "[]" : toJson(sop.requiredEvidence())).append('\n');
        sb.append("- optionalEvidence: ").append(sop == null ? "[]" : toJson(sop.optionalEvidence())).append("\n\n");

        sb.append("[SOP 正文]\n");
        sb.append(sop == null || sop.body() == null || sop.body().isBlank()
                ? "(SOP body empty)"
                : sop.body().trim()).append("\n\n");

        sb.append("[输出要求]\n");
        sb.append("请最终只输出 JSON，不要输出 Markdown。JSON shape 必须为：\n");
        sb.append("""
                {
                  "stepResults": [
                    {
                      "stepId": "check-release-window",
                      "status": "passed|failed|inconclusive|skipped",
                      "evidenceIds": ["E-001"],
                      "evidenceTypes": ["metrics|logs|release|synthetics|k8s|gateway|runbook"],
                      "observation": "观察到的事实，不包含敏感原文",
                      "interpretation": "基于证据的判断",
                      "nextDecision": "continue|stop|switch_sop|need_human"
                    }
                  ],
                  "finalReport": {
                    "conclusion": "明确根因；若必查证据缺失，必须写证据不足",
                    "confidence": "high|medium|low|unknown",
                    "nextAction": "下一步建议"
                  }
                }
                """);
        sb.append("必查证据未覆盖时，finalReport.conclusion 必须明确写“证据不足”，")
                .append("不能伪装成确定根因。\n");
        return sb.toString();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public Map<String, Object> defaultFinalReportTemplate() {
        return Map.of(
                "conclusion", "证据不足：MVP 验证样例尚未覆盖全部必查证据",
                "confidence", "low",
                "nextAction", "补齐缺失证据后重新提交 SOP stepResults"
        );
    }
}
