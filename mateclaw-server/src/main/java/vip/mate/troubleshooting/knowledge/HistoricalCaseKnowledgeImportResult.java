package vip.mate.troubleshooting.knowledge;

import java.util.List;

/** Bounded import receipt. It exposes identities and vector state, never case payloads. */
public record HistoricalCaseKnowledgeImportResult(
        long knowledgeBaseId,
        int discovered,
        int imported,
        int reused,
        int vectorReady,
        int vectorPending,
        int failed,
        List<Item> items) {

    public HistoricalCaseKnowledgeImportResult {
        items = List.copyOf(items == null ? List.of() : items);
    }

    public record Item(
            String diagnosisId,
            String caseId,
            String slug,
            State state,
            boolean authoritativeResolution,
            int chunkCount,
            int embeddedChunkCount,
            String error) {
    }

    public enum State {
        IMPORTED_VECTOR_READY,
        IMPORTED_VECTOR_PENDING,
        REUSED_VECTOR_READY,
        REUSED_VECTOR_PENDING,
        FAILED
    }
}
