package vip.mate.troubleshooting.model;

import java.util.List;
import java.util.Locale;

/** Versionable deterministic SOP contract; Wiki content is not authoritative. */
public record SopEntry(
        String sopId,
        String contractVersion,
        String system,
        String errorCode,
        String service,
        String title,
        String cause,
        String category,
        String ownerTeam,
        String status,
        boolean verified,
        List<EvidenceRequest> evidenceRequests,
        List<AnomalyCriterion> anomalyCriteria,
        List<DiagnosisRule> diagnosisRules,
        List<RecommendedAction> actions) {

    public static final String CURRENT_CONTRACT_VERSION = "sop.v1";

    public SopEntry {
        sopId = required(sopId, "sopId");
        contractVersion = blankDefault(contractVersion, CURRENT_CONTRACT_VERSION);
        system = required(system, "system");
        errorCode = required(errorCode, "errorCode");
        service = required(service, "service");
        title = required(title, "title");
        cause = cause == null ? "" : cause;
        category = category == null ? "" : category;
        ownerTeam = ownerTeam == null || ownerTeam.isBlank() ? null : ownerTeam.trim();
        status = blankDefault(status, "candidate").toLowerCase(Locale.ROOT);
        evidenceRequests = List.copyOf(evidenceRequests == null ? List.of() : evidenceRequests);
        anomalyCriteria = List.copyOf(anomalyCriteria == null ? List.of() : anomalyCriteria);
        diagnosisRules = List.copyOf(diagnosisRules == null ? List.of() : diagnosisRules);
        actions = List.copyOf(actions == null ? List.of() : actions);
    }

    public String routingKey() {
        return system.trim().toLowerCase(Locale.ROOT) + ":" + errorCode.trim();
    }

    public boolean operational() {
        return verified && "approved".equals(status);
    }

    /**
     * 这条 Playbook 会不会自己给出根因结论。
     *
     * <p><b>为什么要单独命名。</b> 规则全部 {@code abstained} 的 Playbook，在引擎里
     * 只可能落到 {@code INSUFFICIENT_EVIDENCE} 或 {@code EXCLUDED}——它选的是
     * 「这个场景该取哪些证据」，而不是「答案是什么」。它和会下结论的 Playbook
     * 承担的风险差着一个量级；凡是按风险设闸门的地方，都得先问这一句，否则闸门
     * 就会指着错误的对象。</p>
     *
     * <p>注意方向：一条规则都没有时也算「不下结论」。契约校验另有一条要求
     * {@code diagnosisRules} 非空，那是那道闸门的事，不该由这里替它回答。</p>
     */
    public boolean concludes() {
        return diagnosisRules.stream().anyMatch(rule -> !rule.abstained());
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static String blankDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
