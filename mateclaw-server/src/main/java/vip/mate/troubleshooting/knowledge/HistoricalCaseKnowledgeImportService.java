package vip.mate.troubleshooting.knowledge;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.service.DiagnosisSummary;
import vip.mate.troubleshooting.service.StoredDiagnosis;
import vip.mate.troubleshooting.service.TroubleshootingPersistenceService;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.service.WikiKnowledgeBaseService;

import java.util.ArrayList;
import java.util.List;

/** Explicit, idempotent backfill from persisted Diagnosis records to Wiki vectors. */
@Service
@RequiredArgsConstructor
@Slf4j
public class HistoricalCaseKnowledgeImportService {

    private static final int MAX_IMPORT = 200;

    private final TroubleshootingPersistenceService persistence;
    private final WikiKnowledgeBaseService knowledgeBases;
    private final TroubleshootingCaseKnowledgeDocumentFactory documents;
    private final TroubleshootingCaseKnowledgeSink sink;

    public HistoricalCaseKnowledgeImportResult importCases(
            long workspaceId,
            long knowledgeBaseId,
            int requestedLimit) {
        if (workspaceId <= 0 || knowledgeBaseId <= 0) {
            throw new MateClawException(
                    "err.troubleshooting.invalid_request",
                    400,
                    "workspaceId and knowledgeBaseId are required");
        }
        int limit = Math.min(Math.max(requestedLimit, 1), MAX_IMPORT);
        WikiKnowledgeBaseEntity kb = knowledgeBases.getById(knowledgeBaseId);
        if (kb == null || kb.getWorkspaceId() == null
                || kb.getWorkspaceId() != workspaceId) {
            throw new MateClawException(
                    "err.troubleshooting.knowledge_target_forbidden",
                    403,
                    "knowledge base does not belong to the current workspace");
        }
        if (!"active".equalsIgnoreCase(kb.getStatus())) {
            throw new MateClawException(
                    "err.troubleshooting.knowledge_target_inactive",
                    409,
                    "knowledge base is not active");
        }

        List<DiagnosisSummary> discovered = persistence.list(
                workspaceId, null, null, null, limit);
        List<HistoricalCaseKnowledgeImportResult.Item> items = new ArrayList<>();
        int imported = 0;
        int reused = 0;
        int vectorReady = 0;
        int vectorPending = 0;
        int failed = 0;

        for (DiagnosisSummary summary : discovered) {
            TroubleshootingCaseKnowledgeDocument document = null;
            try {
                StoredDiagnosis stored = persistence.get(workspaceId, summary.diagnosisId());
                document = documents.create(stored);
                TroubleshootingCaseKnowledgeSink.Receipt receipt = sink.publish(
                        workspaceId, knowledgeBaseId, document);
                if (receipt.reusedPage()) reused++; else imported++;
                if (receipt.vectorReady()) vectorReady++; else vectorPending++;
                items.add(successItem(document, receipt));
            } catch (RuntimeException error) {
                failed++;
                log.warn(
                        "Historical troubleshooting case import failed: diagnosisId={}, type={}",
                        summary.diagnosisId(),
                        error.getClass().getSimpleName());
                items.add(failureItem(summary, document));
            }
        }
        return new HistoricalCaseKnowledgeImportResult(
                knowledgeBaseId,
                discovered.size(),
                imported,
                reused,
                vectorReady,
                vectorPending,
                failed,
                items);
    }

    private HistoricalCaseKnowledgeImportResult.Item successItem(
            TroubleshootingCaseKnowledgeDocument document,
            TroubleshootingCaseKnowledgeSink.Receipt receipt) {
        HistoricalCaseKnowledgeImportResult.State state;
        if (receipt.reusedPage()) {
            state = receipt.vectorReady()
                    ? HistoricalCaseKnowledgeImportResult.State.REUSED_VECTOR_READY
                    : HistoricalCaseKnowledgeImportResult.State.REUSED_VECTOR_PENDING;
        } else {
            state = receipt.vectorReady()
                    ? HistoricalCaseKnowledgeImportResult.State.IMPORTED_VECTOR_READY
                    : HistoricalCaseKnowledgeImportResult.State.IMPORTED_VECTOR_PENDING;
        }
        return new HistoricalCaseKnowledgeImportResult.Item(
                document.diagnosisId(),
                document.caseId(),
                document.slug(),
                state,
                document.authoritativeResolution(),
                receipt.chunkCount(),
                receipt.embeddedChunkCount(),
                null);
    }

    private HistoricalCaseKnowledgeImportResult.Item failureItem(
            DiagnosisSummary summary,
            TroubleshootingCaseKnowledgeDocument document) {
        return new HistoricalCaseKnowledgeImportResult.Item(
                summary.diagnosisId(),
                summary.caseId(),
                document == null ? null : document.slug(),
                HistoricalCaseKnowledgeImportResult.State.FAILED,
                document != null && document.authoritativeResolution(),
                0,
                0,
                "CASE_IMPORT_FAILED");
    }
}
