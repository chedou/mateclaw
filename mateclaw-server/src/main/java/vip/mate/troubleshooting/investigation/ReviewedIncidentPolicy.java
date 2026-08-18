package vip.mate.troubleshooting.investigation;

import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;

/** Exact, reviewed predicates that may activate incident-specific investigation behavior. */
public final class ReviewedIncidentPolicy {

    public static final String ICARE_PRODUCT_MAPPING_502_TITLE =
            "调用接口异常（HTTP 502 · get_icare_product_mapping）";
    public static final String ICARE_MOBILE_CHANGE_ORDER_FINISH_REJECTED_TITLE =
            "工单涉及变更单，iCare 禁止在移动端完结";

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

}
