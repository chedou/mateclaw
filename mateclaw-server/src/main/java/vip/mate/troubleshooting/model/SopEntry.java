package vip.mate.troubleshooting.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
        List<RecommendedAction> actions,
        List<String> symptomTriggers) {

    public static final String CURRENT_CONTRACT_VERSION = "sop.v1";

    /** A trigger list long enough to hide a mistake stops being reviewable. */
    private static final int MAX_SYMPTOM_TRIGGERS = 16;

    /**
     * Short enough to be a phrase, long enough to be specific. Single characters
     * are excluded because one CJK character matches far too much text.
     */
    private static final int MIN_TRIGGER_LENGTH = 2;
    private static final int MAX_TRIGGER_LENGTH = 64;

    private static final String SCENARIO_PREFIX = "scenario:";

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
        symptomTriggers = normalizeTriggers(symptomTriggers);
    }

    /**
     * Back-compatible shape for the contract before symptom triggers existed.
     *
     * <p>Every Playbook written before this field defaults to declaring no
     * trigger, which is the safe direction: it stays unreachable by symptom
     * routing until someone reviews and declares one.
     */
    public SopEntry(
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
        this(sopId, contractVersion, system, errorCode, service, title, cause,
                category, ownerTeam, status, verified, evidenceRequests,
                anomalyCriteria, diagnosisRules, actions, List.of());
    }

    public String routingKey() {
        return system.trim().toLowerCase(Locale.ROOT) + ":" + errorCode.trim();
    }

    /** True when this Playbook is selected by scenario key rather than error code. */
    public boolean scenarioScoped() {
        return errorCode.toLowerCase(Locale.ROOT).startsWith(SCENARIO_PREFIX);
    }

    /** The scenario key this Playbook answers for, or null when error-code scoped. */
    public String scenarioKey() {
        return scenarioScoped()
                ? errorCode.substring(SCENARIO_PREFIX.length()).trim()
                : null;
    }

    /**
     * Whether this Playbook has declared that it covers the reported symptom.
     *
     * <p>Containment of a declared phrase, not similarity: the Playbook states
     * the exact wording it answers for, so a reviewer can read the trigger and
     * predict every alert it will catch. Scoring the closest match would make
     * routing depend on what other Playbooks happen to exist, which is not a
     * property anyone can review one Playbook at a time.
     *
     * <p>Restricted to scenario-scoped Playbooks because an error-code Playbook
     * is already selected by its code; letting it also match on text would give
     * one contract two different ways to be chosen.
     */
    public boolean coversSymptom(String symptom) {
        if (!scenarioScoped() || symptom == null || symptomTriggers.isEmpty()) {
            return false;
        }
        String haystack = symptom.toLowerCase(Locale.ROOT);
        return symptomTriggers.stream().anyMatch(haystack::contains);
    }

    private static List<String> normalizeTriggers(List<String> triggers) {
        if (triggers == null || triggers.isEmpty()) {
            return List.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String trigger : triggers) {
            if (trigger == null) {
                continue;
            }
            String cleaned = trigger.trim().toLowerCase(Locale.ROOT);
            if (cleaned.length() < MIN_TRIGGER_LENGTH) {
                continue;
            }
            if (cleaned.length() > MAX_TRIGGER_LENGTH) {
                throw new IllegalArgumentException(
                        "symptom trigger must not exceed "
                                + MAX_TRIGGER_LENGTH + " characters");
            }
            normalized.add(cleaned);
        }
        if (normalized.size() > MAX_SYMPTOM_TRIGGERS) {
            throw new IllegalArgumentException(
                    "a Playbook must not declare more than "
                            + MAX_SYMPTOM_TRIGGERS + " symptom triggers");
        }
        return List.copyOf(normalized);
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
