package vip.mate.troubleshooting.synthesis;

import vip.mate.troubleshooting.model.KnowledgeCandidate;
import vip.mate.troubleshooting.model.SopEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic, source-specific qualification projection for knowledge review.
 *
 * <p>This policy only credits facts already present in a server-owned source
 * contract. Missing replay, ownership or outcome-verification facts remain
 * explicit blockers; callers cannot attest them through a review button.</p>
 */
public final class KnowledgeReviewQualificationPolicy {

    public KnowledgeReviewSource evidence(PlaybookKnowledgeRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("evidence-derived record is required");
        }
        PlaybookDraft draft = record.draft();
        List<String> reasons = new ArrayList<>();
        if (!"VALID".equals(record.validationStatus())
                || !draft.validationErrors().isEmpty()) {
            reasons.add("CONTRACT_VALIDATION_FAILED");
        }
        if (record.referenceComparison() == null
                || !record.referenceComparison().passed()) {
            reasons.add("REFERENCE_SOLUTION_DELTA");
        }
        if (draft.evidenceCitations().isEmpty()) {
            reasons.add("CITATIONS_REQUIRED");
        }
        // PlaybookDraft v1 intentionally has no owner field. A reviewer is not
        // silently promoted into the knowledge owner role.
        reasons.add("OWNER_REQUIRED");
        // Candidate persistence precedes deterministic replay in the v4
        // pipeline. A successful synthesis run is not a replay attestation.
        reasons.add("POSITIVE_REPLAY_REQUIRED");
        // The separately frozen negative/abstain cohort is not yet linked to
        // this exact candidate either.
        reasons.add("NEGATIVE_OR_ABSTAIN_REPLAY_REQUIRED");
        if (record.fixtureMode()) {
            reasons.add("FIXTURE_ONLY");
        }
        PlaybookDraft.ModelProvenance model = draft.modelProvenance();
        return source(
                KnowledgeOrigin.EVIDENCE_DERIVED,
                record.recordId(),
                selectorKey(draft),
                new KnowledgeReviewSnapshot(
                        record.validationStatus(),
                        KnowledgeQualificationPhase.CALIBRATION,
                        draft.validationErrors(),
                        record.referenceComparison(),
                        model == null ? null : model.modelConfigVersion(),
                        eligibility(reasons),
                        reasons,
                        record.fixtureMode()));
    }

    public KnowledgeReviewSource outcome(KnowledgeCandidate candidate) {
        if (candidate == null) {
            throw new IllegalArgumentException("outcome-backed candidate is required");
        }
        List<String> reasons = new ArrayList<>();
        // v1 rows have no authoritative closure projection. New v2 rows freeze
        // this proof in the same transition that closes the Diagnosis; a
        // reviewer-supplied flag is never accepted here.
        if (KnowledgeCandidate.LEGACY_CONTRACT_VERSION.equals(
                candidate.contractVersion())) {
            reasons.add("OUTCOME_VERIFICATION_NOT_PROJECTED");
        } else if (candidate.outcomeProof() == null) {
            // The v2 record constructor rejects this state. Keep the
            // qualification projection fail closed if a future persistence
            // adapter ever bypasses that boundary.
            reasons.add("OUTCOME_VERIFICATION_NOT_PROJECTED");
        }
        reasons.add("POSITIVE_REPLAY_REQUIRED");
        if (candidate.ownerTeam() == null || candidate.ownerTeam().isBlank()) {
            reasons.add("OWNER_REQUIRED");
        }
        if (candidate.evidenceIds().isEmpty()) {
            reasons.add("CITATIONS_REQUIRED");
        }
        String selector = outcomeSelector(candidate);
        if (selector == null) {
            reasons.add("SELECTOR_REQUIRED");
        }
        return source(
                KnowledgeOrigin.OUTCOME_BACKED,
                candidate.candidateId(),
                selector,
                new KnowledgeReviewSnapshot(
                        "NOT_EVALUATED",
                        KnowledgeQualificationPhase.NOT_APPLICABLE,
                        List.of(),
                        null,
                        null,
                        eligibility(reasons),
                        reasons,
                        null));
    }

    public KnowledgeReviewSource manual(SopEntry sop) {
        return manual(sop, null);
    }

    public KnowledgeReviewSource manual(
            SopEntry sop,
            ManualPlaybookReplayQualification replayQualification) {
        if (sop == null) {
            throw new IllegalArgumentException("manual candidate is required");
        }
        List<PlaybookDraft.ValidationError> errors = validateManualContract(sop);
        List<String> reasons = new ArrayList<>();
        if (!errors.isEmpty()) {
            reasons.add("CONTRACT_VALIDATION_FAILED");
        }
        if (sop.ownerTeam() == null || sop.ownerTeam().isBlank()) {
            reasons.add("OWNER_REQUIRED");
        }
        ManualPlaybookReplayAttestation replay = replayQualification == null
                ? null : replayQualification.attestation();
        if (replayQualification == null || !replayQualification.suiteAvailable()) {
            reasons.add(replayQualification == null
                    ? "POSITIVE_AND_NEGATIVE_REPLAY_REQUIRED"
                    : "REPLAY_SUITE_UNAVAILABLE");
        } else if (replay == null) {
            reasons.add("POSITIVE_AND_NEGATIVE_REPLAY_REQUIRED");
        } else if (!sameReplayIdentity(sop, replayQualification, replay)) {
            reasons.add("REPLAY_PROOF_STALE");
        } else if (replay.status() != ManualPlaybookReplayAttestation.Status.PASSED) {
            reasons.add("POSITIVE_AND_NEGATIVE_REPLAY_FAILED");
        }
        return source(
                KnowledgeOrigin.MANUAL,
                sop.sopId(),
                sop.routingKey(),
                new KnowledgeReviewSnapshot(
                        errors.isEmpty() ? "VALID" : "INVALID",
                        KnowledgeQualificationPhase.NOT_APPLICABLE,
                        errors,
                        null,
                        null,
                        eligibility(reasons),
                        reasons,
                        null,
                        replay));
    }

    private boolean sameReplayIdentity(
            SopEntry sop,
            ManualPlaybookReplayQualification qualification,
            ManualPlaybookReplayAttestation replay) {
        return sop.sopId().equals(replay.sourceRecordId())
                && sop.routingKey().equals(replay.selectorKey())
                && qualification.candidateFingerprint()
                        .equals(replay.candidateFingerprint())
                && qualification.suiteFingerprint()
                        .equals(replay.suiteFingerprint());
    }

    private List<PlaybookDraft.ValidationError> validateManualContract(SopEntry sop) {
        return ManualPlaybookContractValidator.validate(sop);
    }

    private KnowledgeReviewSource source(
            KnowledgeOrigin origin,
            String sourceRecordId,
            String selectorKey,
            KnowledgeReviewSnapshot snapshot) {
        return new KnowledgeReviewSource(
                origin, sourceRecordId, selectorKey, snapshot);
    }

    private String eligibility(List<String> reasons) {
        return reasons.isEmpty() ? "ELIGIBLE_FOR_APPROVAL" : "NOT_ELIGIBLE";
    }

    private String selectorKey(PlaybookDraft draft) {
        PlaybookDraft.ProposedSelector selector = draft.proposedSelector();
        if (selector == null || selector.system() == null
                || selector.system().isBlank()) {
            return null;
        }
        String system = selector.system().trim().toLowerCase(Locale.ROOT);
        if ("ERROR_CODE".equals(draft.proposedType())
                && selector.errorCode() != null
                && !selector.errorCode().isBlank()) {
            return system + ":" + selector.errorCode().trim();
        }
        if ("SCENARIO".equals(draft.proposedType())
                && selector.scenarioKey() != null
                && !selector.scenarioKey().isBlank()) {
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
