package vip.mate.troubleshooting.service;

import org.springframework.stereotype.Service;
import vip.mate.troubleshooting.dto.SopValidationResult;
import vip.mate.troubleshooting.model.SopDefinition;

import java.util.Map;
import java.util.regex.Pattern;

@Service
public class SopReportRenderer {

    private static final Pattern KEY_VALUE_SECRET = Pattern.compile(
            "(?i)\\b(authorization|cookie|set-cookie|token|access_token|refresh_token|password|passwd|secret|api[_-]?key)\\b\\s*[:=]\\s*([^\\s,;]+)"
    );
    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"
    );
    private static final Pattern PHONE = Pattern.compile(
            "(?<!\\d)(?:\\+?86[- ]?)?1[3-9]\\d{9}(?!\\d)"
    );

    public String renderGroupReport(SopDefinition sop, SopValidationResult validation,
                                    Map<String, Object> finalReport) {
        String title = sop == null ? "排障 SOP" : sop.domain() + "/" + sop.scenario();
        String conclusion = stringValue(finalReport, "conclusion", "暂未形成明确根因");
        String confidence = stringValue(finalReport, "confidence", "unknown");
        String nextAction = stringValue(finalReport, "nextAction", "请值班同学结合案件详情继续确认");

        StringBuilder sb = new StringBuilder();
        sb.append("【排障结论】").append(title).append('\n');
        if (validation != null && !validation.valid()) {
            sb.append("状态：证据不足");
            if (!validation.missingEvidence().isEmpty()) {
                sb.append("（缺少 ").append(String.join(", ", validation.missingEvidence())).append("）");
            }
            sb.append('\n');
        }
        sb.append("结论：").append(conclusion).append('\n');
        sb.append("置信度：").append(confidence).append('\n');
        sb.append("建议：").append(nextAction);
        return redact(sb.toString());
    }

    public String redact(String input) {
        if (input == null || input.isBlank()) return input;
        String redacted = KEY_VALUE_SECRET.matcher(input).replaceAll("$1=<redacted>");
        redacted = EMAIL.matcher(redacted).replaceAll("<email-redacted>");
        redacted = PHONE.matcher(redacted).replaceAll("<phone-redacted>");
        return redacted;
    }

    private static String stringValue(Map<String, Object> map, String key, String fallback) {
        if (map == null) return fallback;
        Object value = map.get(key);
        if (value == null || value.toString().isBlank()) return fallback;
        return value.toString();
    }
}
