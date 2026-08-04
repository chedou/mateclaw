package vip.mate.troubleshooting.knowledge;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
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
import java.util.Objects;

/** Writes deterministic case pages through MateClaw's existing Wiki chunk/embedding pipeline. */
@Service
@RequiredArgsConstructor
public class WikiTroubleshootingCaseKnowledgeSink implements TroubleshootingCaseKnowledgeSink {

    private final WikiKnowledgeBaseService knowledgeBases;
    private final WikiPageService pages;
    private final WikiRawMaterialService rawMaterials;
    private final WikiProcessingService processing;
    private final WikiChunkService chunks;

    @Override
    public Receipt publish(
            long workspaceId,
            long knowledgeBaseId,
            TroubleshootingCaseKnowledgeDocument document) {
        WikiKnowledgeBaseEntity kb = knowledgeBases.getById(knowledgeBaseId);
        if (kb == null || kb.getWorkspaceId() == null
                || kb.getWorkspaceId() != workspaceId) {
            throw new MateClawException(
                    "err.troubleshooting.knowledge_target_forbidden",
                    403,
                    "knowledge base does not belong to the current workspace");
        }

        WikiPageEntity page = pages.getBySlug(knowledgeBaseId, document.slug());
        boolean reused = page != null;
        if (page == null) {
            page = pages.createPage(
                    knowledgeBaseId,
                    document.slug(),
                    document.title(),
                    document.markdown(),
                    document.summary(),
                    null,
                    "event");
        } else if (!Objects.equals(document.title(), page.getTitle())
                || !Objects.equals(document.markdown(), page.getContent())
                || !Objects.equals(document.summary(), page.getSummary())) {
            throw new MateClawException(
                    "err.troubleshooting.knowledge_snapshot_conflict",
                    409,
                    "immutable diagnosis snapshot content drifted for the same version");
        }

        WikiRawMaterialEntity raw = rawMaterials.addAgentAuthored(
                knowledgeBaseId, document.title(), document.markdown());
        processing.linkAgentPageToRaw(
                page.getId(), knowledgeBaseId, raw.getId(), raw.getTitle(), "event");
        pages.setKnowledgeLayer(page.getId(), "experience");

        List<WikiChunkEntity> persistedChunks = chunks.listByRawId(raw.getId());
        int embedded = (int) persistedChunks.stream()
                .filter(chunk -> chunk.getEmbedding() != null && chunk.getEmbedding().length > 0)
                .count();
        return new Receipt(reused, persistedChunks.size(), embedded);
    }
}
