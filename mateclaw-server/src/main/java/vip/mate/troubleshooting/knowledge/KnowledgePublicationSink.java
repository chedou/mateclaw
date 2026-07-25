package vip.mate.troubleshooting.knowledge;

import vip.mate.troubleshooting.model.KnowledgeCandidate;

/**
 * Downstream publisher seam. P0 intentionally provides no implementation.
 * Implementations must be idempotent by candidate ID because delivery is at least once.
 */
@FunctionalInterface
public interface KnowledgePublicationSink {
    void publish(long workspaceId, KnowledgeCandidate candidate) throws Exception;
}
