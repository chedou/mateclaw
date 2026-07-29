package vip.mate.troubleshooting.synthesis;

import java.util.List;

/** Read-only query port for persisted evidence-derived review candidates. */
public interface PlaybookCandidateReader {

    List<PlaybookKnowledgeRecord> list(long workspaceId, int limit);

    PlaybookKnowledgeRecord find(long workspaceId, String recordId);
}
