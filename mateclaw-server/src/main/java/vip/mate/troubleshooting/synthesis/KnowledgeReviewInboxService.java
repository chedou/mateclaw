package vip.mate.troubleshooting.synthesis;

import org.springframework.stereotype.Service;
import vip.mate.troubleshooting.model.KnowledgeCandidate;
import vip.mate.troubleshooting.service.SopRegistryRecord;
import vip.mate.troubleshooting.service.SopSummary;
import vip.mate.troubleshooting.service.TroubleshootingPersistenceService;
import vip.mate.troubleshooting.service.TroubleshootingSopPersistenceService;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds one exact, workspace-scoped knowledge-review projection.
 *
 * <p>Candidate payloads, current qualification and persisted review decisions
 * are loaded together. This keeps eligibility on the server and avoids a
 * browser re-implementing origin-specific gates from partial DTOs.</p>
 */
@Service
public class KnowledgeReviewInboxService {

    private final PlaybookCandidateReader evidenceCandidates;
    private final TroubleshootingPersistenceService outcomeCandidates;
    private final TroubleshootingSopPersistenceService manualCandidates;
    private final KnowledgeReviewWorkflowService reviewWorkflow;
    private final KnowledgeReviewQualificationPolicy qualification =
            new KnowledgeReviewQualificationPolicy();

    public KnowledgeReviewInboxService(
            PlaybookCandidateReader evidenceCandidates,
            TroubleshootingPersistenceService outcomeCandidates,
            TroubleshootingSopPersistenceService manualCandidates,
            KnowledgeReviewWorkflowService reviewWorkflow) {
        this.evidenceCandidates = evidenceCandidates;
        this.outcomeCandidates = outcomeCandidates;
        this.manualCandidates = manualCandidates;
        this.reviewWorkflow = reviewWorkflow;
    }

    public KnowledgeReviewInbox read(long workspaceId, int limit) {
        if (workspaceId <= 0) {
            throw new IllegalArgumentException("workspaceId must be positive");
        }
        List<PlaybookKnowledgeRecord> evidenceDerived =
                evidenceCandidates.list(workspaceId, limit);
        List<KnowledgeCandidate> outcomeBacked =
                outcomeCandidates.listKnowledgeCandidates(workspaceId, limit);
        List<SopRegistryRecord> manualRecords =
                manualCandidates.listRecords(
                        workspaceId, "candidate", null, limit);
        List<SopSummary> manual = manualRecords.stream()
                .map(SopRegistryRecord::summary)
                .toList();

        List<KnowledgeReviewSource> sourceStates = new ArrayList<>(
                evidenceDerived.size() + outcomeBacked.size() + manual.size());
        evidenceDerived.stream()
                .map(qualification::evidence)
                .forEach(sourceStates::add);
        outcomeBacked.stream()
                .map(qualification::outcome)
                .forEach(sourceStates::add);
        manualRecords.stream()
                .map(SopRegistryRecord::entry)
                .map(qualification::manual)
                .forEach(sourceStates::add);

        List<KnowledgeReviewSourceKey> sourceKeys = sourceStates.stream()
                .map(source -> new KnowledgeReviewSourceKey(
                        source.origin(), source.sourceRecordId()))
                .toList();
        return new KnowledgeReviewInbox(
                evidenceDerived,
                outcomeBacked,
                manual,
                List.copyOf(sourceStates),
                reviewWorkflow.listForSources(workspaceId, sourceKeys),
                KnowledgeReviewInbox.CURRENT_CAPABILITY_LIMITS);
    }
}
