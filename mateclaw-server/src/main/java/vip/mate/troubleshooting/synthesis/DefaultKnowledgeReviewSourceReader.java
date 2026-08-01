package vip.mate.troubleshooting.synthesis;

import org.springframework.stereotype.Component;
import vip.mate.troubleshooting.model.KnowledgeCandidate;
import vip.mate.troubleshooting.model.SopEntry;
import vip.mate.troubleshooting.service.TroubleshootingPersistenceService;
import vip.mate.troubleshooting.service.TroubleshootingSopPersistenceService;

import java.util.Optional;

/** Resolves each provenance lane through its own workspace-scoped source store. */
@Component
public class DefaultKnowledgeReviewSourceReader implements KnowledgeReviewSourceReader {

    private final PlaybookCandidateReader evidenceCandidates;
    private final TroubleshootingPersistenceService outcomeCandidates;
    private final TroubleshootingSopPersistenceService manualCandidates;
    private final ManualPlaybookReplayService manualReplays;
    private final KnowledgeReviewQualificationPolicy qualification =
            new KnowledgeReviewQualificationPolicy();

    public DefaultKnowledgeReviewSourceReader(
            PlaybookCandidateReader evidenceCandidates,
            TroubleshootingPersistenceService outcomeCandidates,
            TroubleshootingSopPersistenceService manualCandidates,
            ManualPlaybookReplayService manualReplays) {
        this.evidenceCandidates = evidenceCandidates;
        this.outcomeCandidates = outcomeCandidates;
        this.manualCandidates = manualCandidates;
        this.manualReplays = manualReplays;
    }

    @Override
    public Optional<KnowledgeReviewSource> find(
            long workspaceId,
            KnowledgeOrigin origin,
            String sourceRecordId) {
        if (workspaceId <= 0) {
            throw new IllegalArgumentException("workspaceId must be positive");
        }
        if (origin == null || sourceRecordId == null || sourceRecordId.isBlank()) {
            throw new IllegalArgumentException("origin and sourceRecordId are required");
        }
        String sourceId = sourceRecordId.trim();
        return switch (origin) {
            case EVIDENCE_DERIVED -> evidence(workspaceId, sourceId);
            case OUTCOME_BACKED -> outcome(workspaceId, sourceId);
            case MANUAL -> manual(workspaceId, sourceId);
        };
    }

    private Optional<KnowledgeReviewSource> evidence(long workspaceId, String recordId) {
        PlaybookKnowledgeRecord record = evidenceCandidates.find(workspaceId, recordId);
        if (record == null) {
            return Optional.empty();
        }
        return Optional.of(qualification.evidence(record));
    }

    private Optional<KnowledgeReviewSource> outcome(long workspaceId, String candidateId) {
        KnowledgeCandidate candidate = outcomeCandidates.findKnowledgeCandidate(
                workspaceId, candidateId);
        if (candidate == null) {
            return Optional.empty();
        }
        return Optional.of(qualification.outcome(candidate));
    }

    private Optional<KnowledgeReviewSource> manual(long workspaceId, String sopId) {
        SopEntry sop = manualCandidates.findBySopId(workspaceId, sopId);
        if (sop == null || !"candidate".equals(sop.status())) {
            return Optional.empty();
        }
        return Optional.of(qualification.manual(
                sop, manualReplays.qualification(workspaceId, sop)));
    }
}
