package vip.mate.troubleshooting.synthesis;

import java.util.Optional;

/** Resolves a routeable artifact only from the reviewed server-owned source. */
public interface KnowledgePromotionMaterialReader {

    Optional<KnowledgePromotionMaterial> find(
            long workspaceId,
            KnowledgeOrigin origin,
            String sourceRecordId);
}
