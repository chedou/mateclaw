package vip.mate.troubleshooting.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.model.KnowledgeEvidenceGrade;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeEvidenceCoverageTest {

    private static final LocalDateTime NOW = LocalDateTime.parse("2026-08-02T10:00:00");
    private final KnowledgeEvidenceSelectorInventory inventory =
            new KnowledgeEvidenceSelectorInventory(new ObjectMapper());

    @Test
    void reportsRecordedAggregateKnowledgeAgainstThe146SelectorInventory() {
        KnowledgeEvidenceCoverage coverage = KnowledgeEvidenceCoverage.from(List.of(
                summary("csdp:IM1010", KnowledgeEvidenceGrade.RECORDED_AGGREGATE),
                summary("csdp:903001", KnowledgeEvidenceGrade.AUTHORED_FIXTURE),
                summary("csdp:101004", KnowledgeEvidenceGrade.UNVERIFIED),
                summary("csdp:999999", KnowledgeEvidenceGrade.UNVERIFIED),
                summary("csdp:999999", KnowledgeEvidenceGrade.RECORDED_AGGREGATE),
                summary("csdp:scenario:message_send_failed",
                        KnowledgeEvidenceGrade.RECORDED_AGGREGATE)), inventory);

        assertThat(coverage.inventoryErrorCodeSelectors()).isEqualTo(146);
        assertThat(coverage.registryErrorCodeSelectors()).isEqualTo(3);
        assertThat(coverage.recordedAggregateSelectors()).isEqualTo(1);
        assertThat(coverage.authoredFixtureSelectors()).isEqualTo(1);
        assertThat(coverage.unverifiedSelectors()).isEqualTo(1);
        assertThat(coverage.outsideInventorySelectors()).isEqualTo(1);
        assertThat(KnowledgeEvidenceCoverage.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("coverageRate", "recordedRate", "percentage");
    }

    @Test
    void loadsTheExactReviewedD1MembershipInsteadOfTrustingAnyCsdpSelector() {
        assertThat(inventory.size()).isEqualTo(146);
        assertThat(inventory.contains("csdp:IM1010")).isTrue();
        assertThat(inventory.contains("csdp:903001")).isTrue();
        assertThat(inventory.contains("csdp:101007"))
                .as("line-separated multi-code cells must be expanded into exact selectors")
                .isTrue();
        assertThat(inventory.contains("csdp:999999")).isFalse();
        assertThat(inventory.contains("csdp:scenario:message_send_failed")).isFalse();
    }

    @Test
    void unknownStoredGradesFailClosedWithoutTakingDownTheRegistry() {
        assertThat(KnowledgeEvidenceGrade.fromStored("MODEL_CONFIDENT"))
                .isEqualTo(KnowledgeEvidenceGrade.UNVERIFIED);
        assertThat(KnowledgeEvidenceGrade.fromStored(" "))
                .isEqualTo(KnowledgeEvidenceGrade.UNVERIFIED);
    }

    private SopSummary summary(String routeKey, KnowledgeEvidenceGrade grade) {
        String errorCode = routeKey.substring(routeKey.lastIndexOf(':') + 1);
        return new SopSummary(
                "playbook-" + errorCode,
                routeKey,
                "CSDP",
                errorCode,
                "service",
                "approved",
                true,
                true,
                NOW,
                NOW,
                1,
                "MANUAL",
                "source-" + errorCode,
                "review-" + errorCode,
                1,
                grade);
    }
}
