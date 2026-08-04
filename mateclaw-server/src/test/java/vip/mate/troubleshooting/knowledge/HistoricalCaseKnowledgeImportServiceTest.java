package vip.mate.troubleshooting.knowledge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.model.InvestigationMode;
import vip.mate.troubleshooting.model.RouteAuthority;
import vip.mate.troubleshooting.model.RouteSemanticsProvenance;
import vip.mate.troubleshooting.service.DiagnosisSummary;
import vip.mate.troubleshooting.service.StoredDiagnosis;
import vip.mate.troubleshooting.service.TroubleshootingPersistenceService;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.service.WikiKnowledgeBaseService;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HistoricalCaseKnowledgeImportServiceTest {

    @Mock private TroubleshootingPersistenceService persistence;
    @Mock private WikiKnowledgeBaseService knowledgeBases;
    @Mock private TroubleshootingCaseKnowledgeDocumentFactory documents;
    @Mock private TroubleshootingCaseKnowledgeSink sink;

    private HistoricalCaseKnowledgeImportService service;

    @BeforeEach
    void setUp() {
        service = new HistoricalCaseKnowledgeImportService(
                persistence, knowledgeBases, documents, sink);
    }

    @Test
    void importsEveryDiscoveredCaseAndReportsVectorReadinessWithoutRawPayloads() {
        WikiKnowledgeBaseEntity kb = knowledgeBase(9L, 7L);
        when(knowledgeBases.getById(9L)).thenReturn(kb);
        when(persistence.list(7L, null, null, null, 20))
                .thenReturn(List.of(summary("diag-1"), summary("diag-2")));
        StoredDiagnosis first = mock(StoredDiagnosis.class);
        StoredDiagnosis second = mock(StoredDiagnosis.class);
        when(persistence.get(7L, "diag-1")).thenReturn(first);
        when(persistence.get(7L, "diag-2")).thenReturn(second);
        TroubleshootingCaseKnowledgeDocument doc1 = document("diag-1", "case-1");
        TroubleshootingCaseKnowledgeDocument doc2 = document("diag-2", "case-2");
        when(documents.create(first)).thenReturn(doc1);
        when(documents.create(second)).thenReturn(doc2);
        when(sink.publish(7L, 9L, doc1)).thenReturn(
                new TroubleshootingCaseKnowledgeSink.Receipt(false, 1, 1));
        when(sink.publish(7L, 9L, doc2)).thenReturn(
                new TroubleshootingCaseKnowledgeSink.Receipt(true, 2, 0));

        HistoricalCaseKnowledgeImportResult result = service.importCases(7L, 9L, 20);

        assertThat(result.discovered()).isEqualTo(2);
        assertThat(result.imported()).isEqualTo(1);
        assertThat(result.reused()).isEqualTo(1);
        assertThat(result.vectorReady()).isEqualTo(1);
        assertThat(result.vectorPending()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(result.items()).extracting(HistoricalCaseKnowledgeImportResult.Item::diagnosisId)
                .containsExactly("diag-1", "diag-2");
        verify(sink).publish(7L, 9L, doc1);
        verify(sink).publish(7L, 9L, doc2);
    }

    @Test
    void refusesToWriteIntoAnotherWorkspacesKnowledgeBase() {
        when(knowledgeBases.getById(9L)).thenReturn(knowledgeBase(9L, 8L));

        assertThatThrownBy(() -> service.importCases(7L, 9L, 20))
                .isInstanceOf(MateClawException.class)
                .satisfies(error -> assertThat(((MateClawException) error).getCode())
                        .isEqualTo(403))
                .hasMessageContaining("current workspace");
    }

    @Test
    void perCaseFailureReceiptDoesNotEchoConnectorOrCredentialDetails() {
        when(knowledgeBases.getById(9L)).thenReturn(knowledgeBase(9L, 7L));
        when(persistence.list(7L, null, null, null, 20))
                .thenReturn(List.of(summary("diag-1")));
        StoredDiagnosis stored = mock(StoredDiagnosis.class);
        TroubleshootingCaseKnowledgeDocument document = document("diag-1", "case-1");
        when(persistence.get(7L, "diag-1")).thenReturn(stored);
        when(documents.create(stored)).thenReturn(document);
        when(sink.publish(7L, 9L, document))
                .thenThrow(new IllegalStateException("Authorization: Bearer secret-token"));

        HistoricalCaseKnowledgeImportResult result = service.importCases(7L, 9L, 20);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.items().getFirst().error()).isEqualTo("CASE_IMPORT_FAILED");
        assertThat(result.items().getFirst().error()).doesNotContain("secret-token");
    }

    private DiagnosisSummary summary(String diagnosisId) {
        return new DiagnosisSummary(
                diagnosisId,
                "case-" + diagnosisId,
                "CSDP",
                null,
                "csdp-session-service",
                "CLOSED",
                InvestigationMode.SCENARIO_PLAYBOOK,
                RouteAuthority.EXPLICIT,
                RouteSemanticsProvenance.PERSISTED,
                true,
                3,
                LocalDateTime.parse("2026-08-03T10:00:00"),
                LocalDateTime.parse("2026-08-03T10:10:00"));
    }

    private WikiKnowledgeBaseEntity knowledgeBase(long id, long workspaceId) {
        WikiKnowledgeBaseEntity kb = new WikiKnowledgeBaseEntity();
        kb.setId(id);
        kb.setWorkspaceId(workspaceId);
        kb.setName("智能排障故障案例");
        kb.setStatus("active");
        return kb;
    }

    private TroubleshootingCaseKnowledgeDocument document(String diagnosisId, String caseId) {
        return new TroubleshootingCaseKnowledgeDocument(
                diagnosisId,
                caseId,
                3,
                "troubleshooting-case-" + diagnosisId + "-v3",
                "排障案例·" + caseId,
                "summary",
                "# body",
                true);
    }
}
