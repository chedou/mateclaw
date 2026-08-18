package vip.mate.troubleshooting.investigation;

import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IncidentReportReadOnlyToolTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-08-17T11:20:40Z");

    @Test
    void turnsOnlyTheReviewedNormalizedAlertIntoBoundedReportedEvidence() {
        IncidentReportReadOnlyTool tool = new IncidentReportReadOnlyTool();

        EvidenceResult result = tool.collect(
                context(icareAlert()),
                request());

        assertThat(result.status()).isEqualTo(EvidenceStatus.ANOMALY);
        assertThat(result.source()).isEqualTo("incident-report:normalized");
        assertThat(result.observed()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "failure_count", 1,
                "http_status", "502",
                "operation", "get_icare_product_mapping",
                "evidence_grade", "REPORTED"));
        assertThat(result.observed().toString())
                .doesNotContain("csdp-applet.sangfor.com", "req", "url", "AF");
    }

    @Test
    void unrelatedOrIncompleteAlertsFailClosedInsteadOfInventingAReportedFailure() {
        IncidentReportReadOnlyTool tool = new IncidentReportReadOnlyTool();
        IncidentContext unrelated = new IncidentContext(
                "incident-other", "CSDP", "csdp-task", null,
                "调用接口异常（HTTP 502 · get_icare_product_mapping）",
                "P1", "待确认", null, OCCURRED_AT, null, "web",
                IncidentCompleteness.STRUCTURED, null);

        EvidenceResult result = tool.collect(context(unrelated), request());

        assertThat(result.status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(result.observed()).isEmpty();
    }

    @Test
    void turnsTheReviewedMobileChangeOrderRejectionIntoPolicyEvidenceOnly() {
        IncidentReportReadOnlyTool tool = new IncidentReportReadOnlyTool();
        IncidentContext incident = new IncidentContext(
                "incident-mobile-finish", "CSDP", "sf-icare-openapi", null,
                ReviewedIncidentPolicy.ICARE_MOBILE_CHANGE_ORDER_FINISH_REJECTED_TITLE,
                "P2", "待确认", null, OCCURRED_AT, null, "web",
                IncidentCompleteness.STRUCTURED, null);
        EvidenceRequest request = new EvidenceRequest(
                "open-discovery-icare-mobile-finish-reported",
                IncidentReportReadOnlyTool.BUSINESS_POLICY_SIGNAL_KIND,
                "读取规范化告警中已经明确的业务拒绝原因",
                Map.of(), "-15m", true);

        EvidenceResult result = tool.collect(context(incident), request);

        assertThat(result.status()).isEqualTo(EvidenceStatus.ANOMALY);
        assertThat(result.observed()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "failure_count", 1,
                "operation", "updateFinish",
                "policy_code", "mobile_change_order_finish_forbidden",
                "client_surface", "MOBILE",
                "change_order_linked", true,
                "recommended_channel", "PC",
                "evidence_grade", "REPORTED"));
        assertThat(result.observed().toString())
                .doesNotContain("token", "Authorization", "workOrder", "loginPrm");
    }

    @Test
    void turnsTheReviewedMissingRevisitResultIntoPolicyEvidenceOnly() {
        IncidentReportReadOnlyTool tool = new IncidentReportReadOnlyTool();
        IncidentContext incident = new IncidentContext(
                "incident-revisit-required", "CSDP", "sf-icare-openapi", null,
                ReviewedIncidentPolicy.ICARE_REQUIRED_REVISIT_RESULT_MISSING_TITLE,
                "P2", "待确认", null, OCCURRED_AT, null, "web",
                IncidentCompleteness.STRUCTURED, null);
        EvidenceRequest request = new EvidenceRequest(
                "open-discovery-icare-revisit-result-reported",
                IncidentReportReadOnlyTool.BUSINESS_POLICY_SIGNAL_KIND,
                "读取规范化告警中已经明确的必填信息拒绝原因",
                Map.of(), "-15m", true);

        EvidenceResult result = tool.collect(context(incident), request);

        assertThat(result.status()).isEqualTo(EvidenceStatus.ANOMALY);
        assertThat(result.observed()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "failure_count", 1,
                "operation", "updateFinish",
                "policy_code", "required_revisit_result_missing",
                "required_information", "REVISIT_RESULT",
                "required_information_missing", true,
                "recommended_action", "COMPLETE_REVISIT_FORM",
                "evidence_grade", "REPORTED"));
        assertThat(result.observed().toString())
                .doesNotContain("token", "Authorization", "workOrder", "loginPrm", "customer");
    }

    private static ReadOnlyToolRegistry.Context context(IncidentContext incident) {
        return new ReadOnlyToolRegistry.Context(
                1L, incident, Set.of("guance"), Instant.MAX);
    }

    private static EvidenceRequest request() {
        return new EvidenceRequest(
                "open-discovery-icare-product-mapping-reported",
                "incident_reported_external_http_failure",
                "读取规范化告警中已经明确的失败点",
                Map.of(), "-15m", true);
    }

    private static IncidentContext icareAlert() {
        return new IncidentContext(
                "incident-502", "CSDP", "csdp-wechat", null,
                "调用接口异常（HTTP 502 · get_icare_product_mapping）",
                "P1", "待确认", null, OCCURRED_AT, null, "channel:web:conversation",
                IncidentCompleteness.STRUCTURED, null);
    }
}
