package vip.mate.troubleshooting.knowledge;

/** Adapter seam from troubleshooting case snapshots to a searchable knowledge store. */
@FunctionalInterface
public interface TroubleshootingCaseKnowledgeSink {

    Receipt publish(
            long workspaceId,
            long knowledgeBaseId,
            TroubleshootingCaseKnowledgeDocument document);

    record Receipt(boolean reusedPage, int chunkCount, int embeddedChunkCount) {
        public Receipt {
            if (chunkCount < 0 || embeddedChunkCount < 0 || embeddedChunkCount > chunkCount) {
                throw new IllegalArgumentException("invalid chunk counts");
            }
        }

        public boolean vectorReady() {
            return chunkCount > 0 && embeddedChunkCount == chunkCount;
        }
    }
}
