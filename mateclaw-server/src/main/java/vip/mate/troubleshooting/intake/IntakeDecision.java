package vip.mate.troubleshooting.intake;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
            prompt = "资料已齐，已进入异步只读调查队列。"
                    + "\nIntake ID: " + session.intakeSessionId()
                    + "\n调查结果或明确失败原因将原路返回；MateClaw 不会执行任何生产变更。";
        } else {
            String labels = session.missingFields().stream()
                    .map(field -> FIELD_LABELS.getOrDefault(field, field))
                    .reduce((left, right) -> left + "、" + right)
                    .orElse("必要信息");
            prompt = "已收到报障，还需要：" + labels + "。"
                    + "\n请按以下格式补充（未知必须明确写“未知”，系统不会猜测）："
                    + "\n系统: CSDP"
                    + "\n服务: csdp-wechat"
                    + "\n客户ID: tenant-42 或 未知/批量"
                    + "\n发生时间: 2026-07-29 10:05:00";
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
