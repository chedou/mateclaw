package vip.mate.troubleshooting.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Explicit target for a bounded historical-case Wiki import. */
public record HistoricalCaseKnowledgeImportRequest(
        @NotNull @Min(1) Long knowledgeBaseId,
        @Min(1) @Max(200) Integer limit) {

    public int resolvedLimit() {
        return limit == null ? 100 : limit;
    }
}
