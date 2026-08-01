package vip.mate.troubleshooting.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.TroubleshootingSecretRedactor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IncidentImpactContractTest {

    private static final Instant OBSERVED_AT = Instant.parse("2026-07-20T09:13:05Z");

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void readsLegacyStringImpactAsUnknownStructuredImpact() throws Exception {
        String json = """
                {
                  "incidentId":"incident-legacy-impact",
                  "system":"CSDP",
                  "service":"csdp-session-service",
                  "errorCode":"903001",
                  "title":"会话消息发送失败",
                  "severity":"P2",
                  "impact":"消息发送功能受影响",
                  "traceId":null,
                  "occurredAt":"2026-07-20T09:13:05Z",
                  "slaRemaining":null,
                  "intakeSource":"manual",
                  "completeness":"STRUCTURED",
                  "rawInput":null
                }
                """;

        IncidentContext restored = objectMapper.readValue(json, IncidentContext.class);

        assertThat(restored.impact().functionScope()).isEqualTo("消息发送功能受影响");
        assertThat(restored.impact().affectedCustomers()).isNull();
        assertThat(restored.impact().affectedUsers()).isNull();
        assertThat(restored.impact().blastRadius()).isEqualTo(BlastRadius.UNKNOWN);
        assertThat(restored.impact().evidenceRefs()).isEmpty();
        assertThat(restored.impact().observedAt()).isNull();
    }

    @Test
    void writesAndReadsTheStructuredImpactContract() throws Exception {
        IncidentImpact impact = new IncidentImpact(
                "消息发送功能",
                2,
                15,
                BlastRadius.MULTI_CUSTOMER,
                List.of("IMPACT-CUSTOMERS", "IMPACT-USERS"),
                OBSERVED_AT,
                "同窗口两个客户出现同类失败");
        IncidentContext incident = new IncidentContext(
                "incident-structured-impact",
                "CSDP",
                "csdp-session-service",
                null,
                "会话消息发送失败",
                "P2",
                impact,
                null,
                OBSERVED_AT,
                null,
                "manual",
                IncidentCompleteness.LOG,
                null);

        String json = objectMapper.writeValueAsString(incident);
        IncidentContext restored = objectMapper.readValue(json, IncidentContext.class);
        JsonNode impactJson = objectMapper.readTree(json).path("impact");

        assertThat(impactJson.isObject()).isTrue();
        assertThat(impactJson.path("affectedCustomers").asInt()).isEqualTo(2);
        assertThat(restored.impact()).isEqualTo(impact);
    }

    @Test
    void rejectsMeasuredCountsOrRadiusWithoutEvidenceReferences() {
        assertThatThrownBy(() -> new IncidentImpact(
                "消息发送功能", 2, null, BlastRadius.UNKNOWN,
                List.of(), OBSERVED_AT, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidenceRefs");

        assertThatThrownBy(() -> new IncidentImpact(
                "消息发送功能", null, null, BlastRadius.SINGLE_CUSTOMER,
                List.of(), OBSERVED_AT, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidenceRefs");
    }

    @Test
    void rejectsPreciseCountsWithoutAnObservationTime() {
        assertThatThrownBy(() -> new IncidentImpact(
                "消息发送功能", 2, null, BlastRadius.MULTI_CUSTOMER,
                List.of("IMPACT-COUNT"), null, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("observedAt");
    }

    @Test
    void rejectsUnsafeOrDuplicateEvidenceReferences() {
        assertThatThrownBy(() -> new IncidentImpact(
                "消息发送功能", 2, null, BlastRadius.MULTI_CUSTOMER,
                List.of("Authorization: Bearer production-secret"), OBSERVED_AT, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidenceRefs");

        assertThatThrownBy(() -> new IncidentImpact(
                "消息发送功能", 2, null, BlastRadius.MULTI_CUSTOMER,
                List.of("IMPACT-COUNT", "IMPACT-COUNT"), OBSERVED_AT, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void redactsImpactTextWithoutChangingMeasuredFactsOrSafeReferences() {
        IncidentImpact impact = new IncidentImpact(
                "消息发送 token=production-secret",
                2,
                null,
                BlastRadius.MULTI_CUSTOMER,
                List.of("IMPACT-COUNT"),
                OBSERVED_AT,
                "Authorization: Bearer another-secret");

        IncidentImpact redacted = TroubleshootingSecretRedactor.redact(impact);

        assertThat(redacted.functionScope()).contains(TroubleshootingSecretRedactor.REDACTED)
                .doesNotContain("production-secret");
        assertThat(redacted.note()).contains(TroubleshootingSecretRedactor.REDACTED)
                .doesNotContain("another-secret");
        assertThat(redacted.affectedCustomers()).isEqualTo(2);
        assertThat(redacted.blastRadius()).isEqualTo(BlastRadius.MULTI_CUSTOMER);
        assertThat(redacted.evidenceRefs()).containsExactly("IMPACT-COUNT");
    }
}
