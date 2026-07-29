package vip.mate.troubleshooting.synthesis;

import java.util.Optional;

/** Resolves a source only inside the caller's workspace and returns safe review facts. */
public interface KnowledgeReviewSourceReader {

    Optional<KnowledgeReviewSource> find(
            long workspaceId,
            KnowledgeOrigin origin,
            String sourceRecordId);
}
