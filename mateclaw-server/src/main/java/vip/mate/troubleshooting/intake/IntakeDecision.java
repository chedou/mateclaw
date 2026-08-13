package vip.mate.troubleshooting.intake;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Immediate, deterministic reply to an intake message. */
public record IntakeDecision(
        String intakeSessionId,
        IntakeSessionStatus status,
        List<String> missingFields,
        String prompt,
        boolean duplicate,
        boolean outOfOrder) {

    private static final Map<String, String> FIELD_LABELS = labels();

    public IntakeDecision {
        missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
        if (intakeSessionId == null || intakeSessionId.isBlank()
                || status == null || prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("intake decision is incomplete");
        }
    }

    public static IntakeDecision from(
            IntakeSession session,
            boolean duplicate,
            boolean outOfOrder) {
        String prompt;
        if (session.status() == IntakeSessionStatus.READY) {
            prompt = "资料已齐，正在生成排障单并进入只读取证。"
                    + "\nIntake ID: " + session.intakeSessionId()
                    + "\n结论会回到本对话或排障详情；MateClaw 不会执行任何生产变更。";
        } else {
            String recognized = recognizedSummary(session);
            String labels = session.missingFields().stream()
                    .map(field -> FIELD_LABELS.getOrDefault(field, field))
                    .reduce((left, right) -> left + "、" + right)
                    .orElse("必要信息");
            prompt = (recognized.isBlank() ? "已收到报障" : ("已从告警中识别：" + recognized))
                    + "。还需要：" + labels + "。"
                    + "\n请按以下格式补充（未知必须明确写“未知”，系统不会猜测）："
                    + "\n系统: 深信服新ICare系统"
                    + "\n服务: sf-icare-app"
                    + "\n客户ID: 未知"
                    + "\n发生时间: 2026-08-12 16:36:00";
        }
        if (outOfOrder) {
            prompt = "已收到较早的补充消息，为防止覆盖新信息，本次未改写当前 Intake。\n" + prompt;
        }
        return new IntakeDecision(
                session.intakeSessionId(),
                session.status(),
                session.missingFields(),
                prompt,
                duplicate,
                outOfOrder);
    }

    private static String recognizedSummary(IntakeSession session) {
        Map<String, String> recognized = new LinkedHashMap<>();
        if (notBlank(session.system())) {
            recognized.put("系统", session.system());
        }
        if (notBlank(session.service())) {
            recognized.put("服务", session.service());
        }
        if (notBlank(session.customerRef())) {
            recognized.put("客户ID", session.customerRef());
        }
        if (notBlank(session.errorCode())) {
            recognized.put("错误码", session.errorCode());
        }
        if (session.occurredAt() != null) {
            recognized.put("发生时间", session.occurredAt().toString());
        }
        if (notBlank(session.symptom())) {
            String symptom = session.symptom();
            if (symptom.length() > 40) {
                symptom = symptom.substring(0, 40) + "…";
            }
            recognized.put("现象", symptom);
        }
        return recognized.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("；"));
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static Map<String, String> labels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("symptom", "问题现象");
        labels.put("system", "系统");
        labels.put("service", "服务");
        labels.put("customerRef", "客户 ID/影响对象");
        labels.put("occurredAt", "发生时间");
        return Map.copyOf(labels);
    }
}
