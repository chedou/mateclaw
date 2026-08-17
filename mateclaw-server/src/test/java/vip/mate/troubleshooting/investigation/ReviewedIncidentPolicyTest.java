package vip.mate.troubleshooting.investigation;

import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewedIncidentPolicyTest {

    @Test
    void acceptsOnlyTheExactReviewedIncident() {
        assertThat(ReviewedIncidentPolicy.isIcareProductMapping502(incident(
                "CSDP", "csdp-wechat",
                ReviewedIncidentPolicy.ICARE_PRODUCT_MAPPING_502_TITLE,
                IncidentCompleteness.STRUCTURED))).isTrue();

        assertThat(ReviewedIncidentPolicy.isIcareProductMapping502(incident(
                "CSDP", "csdp-wechat",
                "调用接口异常（HTTP 502 · get_icare_product_mapping）-重试失败",
                IncidentCompleteness.STRUCTURED))).isFalse();
        assertThat(ReviewedIncidentPolicy.isIcareProductMapping502(incident(
                "OTHER", "csdp-wechat",
                ReviewedIncidentPolicy.ICARE_PRODUCT_MAPPING_502_TITLE,
                IncidentCompleteness.STRUCTURED))).isFalse();
        assertThat(ReviewedIncidentPolicy.isIcareProductMapping502(incident(
                "CSDP", "csdp-task",
                ReviewedIncidentPolicy.ICARE_PRODUCT_MAPPING_502_TITLE,
                IncidentCompleteness.STRUCTURED))).isFalse();
        assertThat(ReviewedIncidentPolicy.isIcareProductMapping502(incident(
                "CSDP", "csdp-wechat",
                ReviewedIncidentPolicy.ICARE_PRODUCT_MAPPING_502_TITLE,
                IncidentCompleteness.SYMPTOM))).isFalse();
    }

    private IncidentContext incident(
            String system, String service, String title, IncidentCompleteness completeness) {
        return new IncidentContext(
                "inc-1", system, service, null, title, "P2", "待确认",
                null, Instant.parse("2026-08-17T11:20:40Z"), null,
                "web", completeness, null);
    }
}
