package vip.mate.troubleshooting.investigation;

import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;

/** Exact, reviewed predicates that may activate incident-specific investigation behavior. */
public final class ReviewedIncidentPolicy {

    public static final String ICARE_PRODUCT_MAPPING_502_TITLE =
            "调用接口异常（HTTP 502 · get_icare_product_mapping）";

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
}
