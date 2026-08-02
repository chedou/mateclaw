package vip.mate.troubleshooting.service;

import vip.mate.troubleshooting.model.KnowledgeEvidenceGrade;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Counts with the fixed D1 inventory denominator; deliberately no rate. */
public record KnowledgeEvidenceCoverage(
        int inventoryErrorCodeSelectors,
        int registryErrorCodeSelectors,
        int recordedAggregateSelectors,
        int authoredFixtureSelectors,
        int unverifiedSelectors,
        int outsideInventorySelectors) {

    public KnowledgeEvidenceCoverage {
        if (inventoryErrorCodeSelectors <= 0
                || registryErrorCodeSelectors < 0
                || recordedAggregateSelectors < 0
                || authoredFixtureSelectors < 0
                || unverifiedSelectors < 0
                || outsideInventorySelectors < 0
                || registryErrorCodeSelectors > inventoryErrorCodeSelectors
                || recordedAggregateSelectors + authoredFixtureSelectors
                        + unverifiedSelectors != registryErrorCodeSelectors) {
            throw new IllegalArgumentException(
                    "knowledge evidence coverage counts must partition the CSDP inventory");
        }
    }

    public static KnowledgeEvidenceCoverage from(
            List<SopSummary> summaries,
            KnowledgeEvidenceSelectorInventory inventory) {
        if (inventory == null) {
            throw new IllegalArgumentException("selector inventory is required");
        }
        Map<String, SopSummary> errorCodeRows = new LinkedHashMap<>();
        Set<String> outsideInventory = new LinkedHashSet<>();
        for (SopSummary summary : summaries == null ? List.<SopSummary>of() : summaries) {
            if (summary != null
                    && "CSDP".equalsIgnoreCase(summary.system())
                    && !summary.routeKey().contains(":scenario:")) {
                if (inventory.contains(summary.routeKey())) {
                    errorCodeRows.putIfAbsent(summary.routeKey(), summary);
                } else {
                    outsideInventory.add(summary.routeKey());
                }
            }
        }
        int recorded = count(errorCodeRows, KnowledgeEvidenceGrade.RECORDED_AGGREGATE);
        int authored = count(errorCodeRows, KnowledgeEvidenceGrade.AUTHORED_FIXTURE);
        int unverified = errorCodeRows.size() - recorded - authored;
        return new KnowledgeEvidenceCoverage(
                inventory.size(),
                errorCodeRows.size(),
                recorded,
                authored,
                unverified,
                outsideInventory.size());
    }

    private static int count(
            Map<String, SopSummary> rows,
            KnowledgeEvidenceGrade grade) {
        return (int) rows.values().stream()
                .filter(summary -> summary.knowledgeEvidenceGrade() == grade)
                .count();
    }
}
