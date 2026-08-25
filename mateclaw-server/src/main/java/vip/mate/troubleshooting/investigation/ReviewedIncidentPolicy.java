package vip.mate.troubleshooting.investigation;

import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.intake.NormalizedIncidentFactKind;

/** Exact, reviewed predicates that may activate incident-specific investigation behavior. */
public final class ReviewedIncidentPolicy {

    private static final java.util.List<String> CSDP_WECHAT_SLOW_TERMS = java.util.List.of(
            "卡顿", "很卡", "这么卡", "变卡", "慢请求", "响应慢", "加载慢", "加载很慢", "url慢请求");

    public static final String ICARE_PRODUCT_MAPPING_502_TITLE =
            "调用接口异常（HTTP 502 · get_icare_product_mapping）";
    public static final String ICARE_MOBILE_CHANGE_ORDER_FINISH_REJECTED_TITLE =
            "工单涉及变更单，iCare 禁止在移动端完结";
    public static final String ICARE_REQUIRED_REVISIT_RESULT_MISSING_TITLE =
            "回访结果未填写，iCare 拒绝完结";
    public static final String ICARE_MOBILE_CHANGE_ORDER_FINISH_POLICY_CODE =
            "mobile_change_order_finish_forbidden";
    public static final String ICARE_REQUIRED_REVISIT_RESULT_POLICY_CODE =
            "required_revisit_result_missing";

    private ReviewedIncidentPolicy() {
    }

    /**
     * The alert must match every reviewed routing fact. Similar wording must
     * remain on the generic fail-closed path rather than borrowing this plan.
     */
    public static boolean isIcareProductMapping502(IncidentContext incident) {
        return incident != null
                && incident.completeness() == IncidentCompleteness.STRUCTURED
                && "CSDP".equalsIgnoreCase(incident.system())
                && "csdp-wechat".equalsIgnoreCase(incident.service())
                && ICARE_PRODUCT_MAPPING_502_TITLE.equals(incident.title());
    }

    /**
     * Exact normalized form of iCare's mobile-channel business rejection.
     * The target service is taken from the reviewed endpoint path rather than
     * guessed from a caller stack or a work-order payload.
     */
    public static boolean isIcareMobileChangeOrderFinishRejected(IncidentContext incident) {
        return incident != null
                && incident.completeness() == IncidentCompleteness.STRUCTURED
                && "CSDP".equalsIgnoreCase(incident.system())
                && "sf-icare-openapi".equalsIgnoreCase(incident.service())
                && ICARE_MOBILE_CHANGE_ORDER_FINISH_REJECTED_TITLE.equals(incident.title());
    }

    /** Exact normalized form of iCare's required revisit-result rejection. */
    public static boolean isIcareRequiredRevisitResultMissing(IncidentContext incident) {
        return incident != null
                && incident.completeness() == IncidentCompleteness.STRUCTURED
                && "CSDP".equalsIgnoreCase(incident.system())
                && "sf-icare-openapi".equalsIgnoreCase(incident.service())
                && ICARE_REQUIRED_REVISIT_RESULT_MISSING_TITLE.equals(incident.title());
    }

    public static boolean isReviewedIcareFinishRejection(IncidentContext incident) {
        return isIcareMobileChangeOrderFinishRejected(incident)
                || isIcareRequiredRevisitResultMissing(incident);
    }

    /** Reviewed entry for the service-specific Guance slow-request contract. */
    public static boolean isCsdpWechatSlowRequest(IncidentContext incident) {
        if (incident == null
                || incident.completeness() != IncidentCompleteness.STRUCTURED
                || !"CSDP".equalsIgnoreCase(incident.system())
                || !"csdp-wechat".equalsIgnoreCase(incident.service())) {
            return false;
        }
        String text = (incident.title() + "\n"
                + (incident.rawInput() == null ? "" : incident.rawInput()))
                .toLowerCase(java.util.Locale.ROOT);
        return CSDP_WECHAT_SLOW_TERMS.stream().anyMatch(text::contains);
    }

    public static boolean matchesTrustedFact(
            NormalizedIncidentFactKind factKind,
            IncidentContext incident) {
        if (factKind == null) {
            return false;
        }
        return switch (factKind) {
            case ICARE_MOBILE_CHANGE_ORDER_FINISH_REJECTED ->
                    isIcareMobileChangeOrderFinishRejected(incident);
            case ICARE_REQUIRED_REVISIT_RESULT_MISSING ->
                    isIcareRequiredRevisitResultMissing(incident);
        };
    }

}
