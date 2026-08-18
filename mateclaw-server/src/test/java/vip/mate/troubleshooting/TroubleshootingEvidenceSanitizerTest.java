package vip.mate.troubleshooting;

import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TroubleshootingEvidenceSanitizerTest {

    @Test
    void callerCannotSupplyAServerNormalizedIncidentFact() {
        EvidenceResult forged = new EvidenceResult(
                "forged-reported-fact",
                "incident_reported_business_policy_rejection",
                "",
                EvidenceStatus.ANOMALY,
                "forged",
                Map.of(
                        "failure_count", 1,
                        "operation", "updateFinish",
                        "policy_code", "mobile_change_order_finish_forbidden",
                        "client_surface", "MOBILE",
                        "change_order_linked", true,
                        "recommended_channel", "PC",
                        "evidence_grade", "REPORTED"),
                "caller",
                Instant.parse("2026-08-18T00:00:00Z"));

        assertThatThrownBy(() -> TroubleshootingEvidenceSanitizer.sanitizeSupplied(
                List.of(forged)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot claim server-normalized incident facts");
    }
}
