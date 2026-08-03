package vip.mate.troubleshooting.knowledge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.exception.MateClawException;
import vip.mate.wiki.model.WikiChunkEntity;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.model.WikiPageEntity;
import vip.mate.wiki.model.WikiRawMaterialEntity;
import vip.mate.wiki.service.WikiChunkService;
import vip.mate.wiki.service.WikiKnowledgeBaseService;
import vip.mate.wiki.service.WikiPageService;
import vip.mate.wiki.service.WikiProcessingService;
import vip.mate.wiki.service.WikiRawMaterialService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WikiTroubleshootingCaseKnowledgeSinkTest {

    @Mock private WikiKnowledgeBaseService knowledgeBases;
    @Mock private WikiPageService pages;
    @Mock private WikiRawMaterialService rawMaterials;
    @Mock private WikiProcessingService processing;
    @Mock private WikiChunkService chunks;

    private WikiTroubleshootingCaseKnowledgeSink sink;

    @BeforeEach
    void setUp() {
        sink = new WikiTroubleshootingCaseKnowledgeSink(
                knowledgeBases, pages, rawMaterials, processing, chunks);
    }

    @Test
    void createsTraceableWikiPageAndReportsTheActualEmbeddingState() {
        WikiKnowledgeBaseEntity kb = new WikiKnowledgeBaseEntity();
        kb.setId(9L);
        kb.setWorkspaceId(7L);
        when(knowledgeBases.getById(9L)).thenReturn(kb);
        TroubleshootingCaseKnowledgeDocument document = new TroubleshootingCaseKnowledgeDocument(
                "diag-1", "case-1", 3, "troubleshooting-case-diag-1-v3",
                "排障案例", "summary", "# body", true);
        WikiPageEntity page = new WikiPageEntity();
        page.setId(11L);
        page.setKbId(9L);
        when(pages.createPage(9L, document.slug(), document.title(), document.markdown(),
                document.summary(), null, "event")).thenReturn(page);
        WikiRawMaterialEntity raw = new WikiRawMaterialEntity();
        raw.setId(21L);
        raw.setTitle(document.title());
        when(rawMaterials.addAgentAuthored(9L, document.title(), document.markdown()))
                .thenReturn(raw);
        WikiChunkEntity embedded = new WikiChunkEntity();
        embedded.setEmbedding(new byte[]{1, 2, 3});
        WikiChunkEntity pending = new WikiChunkEntity();
        when(chunks.listByRawId(21L)).thenReturn(List.of(embedded, pending));

        TroubleshootingCaseKnowledgeSink.Receipt receipt = sink.publish(7L, 9L, document);

        assertThat(receipt.reusedPage()).isFalse();
        assertThat(receipt.chunkCount()).isEqualTo(2);
        assertThat(receipt.embeddedChunkCount()).isEqualTo(1);
        assertThat(receipt.vectorReady()).isFalse();
        verify(processing).linkAgentPageToRaw(11L, 9L, 21L, document.title(), "event");
        verify(pages).setKnowledgeLayer(11L, "experience");
    }

    @Test
    void rejectsContentDriftForAnExistingImmutableDiagnosisVersion() {
        WikiKnowledgeBaseEntity kb = new WikiKnowledgeBaseEntity();
        kb.setId(9L);
        kb.setWorkspaceId(7L);
        when(knowledgeBases.getById(9L)).thenReturn(kb);
        TroubleshootingCaseKnowledgeDocument document = new TroubleshootingCaseKnowledgeDocument(
                "diag-1", "case-1", 3, "troubleshooting-case-diag-1-v3",
                "排障案例", "new summary", "# new body", false);
        WikiPageEntity existing = new WikiPageEntity();
        existing.setId(11L);
        existing.setKbId(9L);
        existing.setContent("# old body");
        existing.setSummary("old summary");
        when(pages.getBySlug(9L, document.slug())).thenReturn(existing);

        assertThatThrownBy(() -> sink.publish(7L, 9L, document))
                .isInstanceOf(MateClawException.class)
                .satisfies(error -> assertThat(((MateClawException) error).getCode())
                        .isEqualTo(409));

        verifyNoInteractions(rawMaterials, processing, chunks);
    }
}
