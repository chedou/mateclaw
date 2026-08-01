package vip.mate.troubleshooting.synthesis;

/** Idempotent persistence port keyed by workspace and generationKey. */
public interface PlaybookCandidateStore {

    StoredCandidate saveOrGet(long workspaceId, PlaybookKnowledgeRecord candidate);

    record StoredCandidate(PlaybookKnowledgeRecord candidate, boolean created) {
    }
}
