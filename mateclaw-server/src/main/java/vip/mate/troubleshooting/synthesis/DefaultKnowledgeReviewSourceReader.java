package vip.mate.troubleshooting.synthesis;

import org.springframework.stereotype.Component;
import vip.mate.troubleshooting.model.KnowledgeCandidate;
import vip.mate.troubleshooting.model.SopEntry;
import vip.mate.troubleshooting.service.TroubleshootingPersistenceService;
import vip.mate.troubleshooting.service.TroubleshootingSopPersistenceService;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Resolves each provenance lane through its own workspace-scoped source store. */
@Component
public class DefaultKnowledgeReviewSourceReader implements KnowledgeReviewSourceReader {

    private final PlaybookCandidateReader evidenceCandidates;
    private final TroubleshootingPersistenceService outcomeCandidates;
    private final TroubleshootingSopPersistenceService manualCandidates;

    public DefaultKnowledgeReviewSourceReader(
            PlaybookCandidateReader evidenceCandidates,
            TroubleshootingPersistenceService outcomeCandidates,
            TroubleshootingSopPersistenceService manualCandidates) {
        this.evidenceCandidates = evidenceCandidates;
        this.outcomeCandidates = outcomeCandidates;
        this.manualCandidates = manualCandidates;
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
        PlaybookDraft draft = record.draft();
        PlaybookDraft.ModelProvenance model = draft.modelProvenance();
        KnowledgeReviewSnapshot snapshot = new KnowledgeReviewSnapshot(
                record.validationStatus(),
                draft.validationErrors(),
                record.referenceComparison(),
                model == null ? null : model.modelConfigVersion(),
                record.approvalEligibility(),
                record.eligibilityReasons(),
                record.fixtureMode());
        return Optional.of(new KnowledgeReviewSource(
                KnowledgeOrigin.EVIDENCE_DERIVED,
                record.recordId(),
                selectorKey(draft),
                snapshot));
    }

    private Optional<KnowledgeReviewSource> outcome(long workspaceId, String candidateId) {
        KnowledgeCandidate candidate = outcomeCandidates.findKnowledgeCandidate(
                workspaceId, candidateId);
        if (candidate == null) {
            return Optional.empty();
        }
        KnowledgeReviewSnapshot snapshot = new KnowledgeReviewSnapshot(
                "NOT_EVALUATED",
                List.of(),
                null,
                null,
                "NOT_ELIGIBLE",
                List.of("OUTCOME_ELIGIBILITY_GATE_NOT_IMPLEMENTED"),
                null);
        return Optional.of(new KnowledgeReviewSource(
                KnowledgeOrigin.OUTCOME_BACKED,
                candidate.candidateId(),
                outcomeSelector(candidate),
                snapshot));
    }

    private Optional<KnowledgeReviewSource> manual(long workspaceId, String sopId) {
        SopEntry sop = manualCandidates.findBySopId(workspaceId, sopId);
        if (sop == null || !"candidate".equals(sop.status())) {
            return Optional.empty();
        }
        KnowledgeReviewSnapshot snapshot = new KnowledgeReviewSnapshot(
                "NOT_EVALUATED",
                List.of(),
                null,
                null,
                "NOT_ELIGIBLE",
                List.of("MANUAL_ELIGIBILITY_GATE_NOT_IMPLEMENTED"),
                null);
        return Optional.of(new KnowledgeReviewSource(
                KnowledgeOrigin.MANUAL,
                sop.sopId(),
                sop.routingKey(),
                snapshot));
    }

    private String selectorKey(PlaybookDraft draft) {
        PlaybookDraft.ProposedSelector selector = draft.proposedSelector();
        if (selector == null || selector.system() == null || selector.system().isBlank()) {
            return null;
        }
        String system = selector.system().trim().toLowerCase(Locale.ROOT);
        if ("ERROR_CODE".equals(draft.proposedType())
                && selector.errorCode() != null && !selector.errorCode().isBlank()) {
            return system + ":" + selector.errorCode().trim();
        }
        if ("SCENARIO".equals(draft.proposedType())
                && selector.scenarioKey() != null && !selector.scenarioKey().isBlank()) {
            return system + ":scenario:" + selector.scenarioKey().trim();
        }
        return null;
    }

    private String outcomeSelector(KnowledgeCandidate candidate) {
        if (candidate.sopKey() != null && !candidate.sopKey().isBlank()) {
            return candidate.sopKey().trim().toLowerCase(Locale.ROOT);
        }
        if (candidate.errorCode() != null && !candidate.errorCode().isBlank()) {
            return candidate.system().trim().toLowerCase(Locale.ROOT)
                    + ":" + candidate.errorCode().trim();
        }
        return null;
    }
}
