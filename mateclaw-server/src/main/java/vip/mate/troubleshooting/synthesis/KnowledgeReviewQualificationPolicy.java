package vip.mate.troubleshooting.synthesis;

import vip.mate.troubleshooting.model.AnomalyCriterion;
import vip.mate.troubleshooting.model.DiagnosisRule;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.KnowledgeCandidate;
import vip.mate.troubleshooting.model.SopEntry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
        // KnowledgeCandidate v1 is only the closure sediment. It does not carry
        // the authoritative ClosureRecord needed to prove outcome and
        // recovery-verification applicability at the promotion boundary.
        reasons.add("OUTCOME_VERIFICATION_NOT_PROJECTED");
        reasons.add("POSITIVE_REPLAY_REQUIRED");
        reasons.add("OWNER_REQUIRED");
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
        // V172 keeps one row per route, but it cannot yet prove the
        // version-aware single-active-approved invariant needed by promotion.
        reasons.add("VERSIONED_SELECTOR_UNIQUENESS_REQUIRED");
        // Manual registration currently has no server-owned replay
        // attestation. UI input cannot turn this condition into PASS.
        reasons.add("POSITIVE_AND_NEGATIVE_REPLAY_REQUIRED");
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
                        null));
    }

    private List<PlaybookDraft.ValidationError> validateManualContract(SopEntry sop) {
        List<PlaybookDraft.ValidationError> errors = new ArrayList<>();
        Set<String> requestIds = new HashSet<>();
        for (int index = 0; index < sop.evidenceRequests().size(); index++) {
            EvidenceRequest request = sop.evidenceRequests().get(index);
            if (!requestIds.add(request.requestId())) {
                errors.add(error(
                        "DUPLICATE_EVIDENCE_REQUEST",
                        "evidenceRequests[" + index + "].requestId",
                        "evidence request ids must be unique"));
            }
        }
        Set<String> signals = new HashSet<>();
        for (int index = 0; index < sop.anomalyCriteria().size(); index++) {
            AnomalyCriterion criterion = sop.anomalyCriteria().get(index);
            signals.add(criterion.signal());
            if (!requestIds.contains(criterion.sourceRequestId())) {
                errors.add(error(
                        "UNKNOWN_EVIDENCE_REQUEST",
                        "anomalyCriteria[" + index + "].sourceRequestId",
                        "criterion must reference a declared evidence request"));
            }
        }
        for (int ruleIndex = 0; ruleIndex < sop.diagnosisRules().size(); ruleIndex++) {
            DiagnosisRule rule = sop.diagnosisRules().get(ruleIndex);
            for (int signalIndex = 0;
                    signalIndex < rule.requiredSignals().size();
                    signalIndex++) {
                if (!signals.contains(rule.requiredSignals().get(signalIndex))) {
                    errors.add(error(
                            "UNKNOWN_REQUIRED_SIGNAL",
                            "diagnosisRules[" + ruleIndex + "].requiredSignals["
                                    + signalIndex + "]",
                            "diagnosis rule must reference a declared anomaly signal"));
                }
            }
        }
        if (sop.evidenceRequests().isEmpty()) {
            errors.add(error(
                    "EVIDENCE_PLAN_REQUIRED",
                    "evidenceRequests",
                    "manual playbook requires at least one read-only evidence request"));
        }
        if (sop.anomalyCriteria().isEmpty()) {
            errors.add(error(
                    "CRITERIA_REQUIRED",
                    "anomalyCriteria",
                    "manual playbook requires at least one deterministic criterion"));
        }
        if (sop.diagnosisRules().isEmpty()) {
            errors.add(error(
                    "DIAGNOSIS_RULE_REQUIRED",
                    "diagnosisRules",
                    "manual playbook requires at least one deterministic diagnosis rule"));
        }
        if (!"candidate".equals(sop.status()) || sop.verified()) {
            errors.add(error(
                    "SOURCE_STATE_INVALID",
                    "status",
                    "manual review source must remain candidate and unverified"));
        }
        return List.copyOf(errors);
    }

    private KnowledgeReviewSource source(
            KnowledgeOrigin origin,
            String sourceRecordId,
            String selectorKey,
            KnowledgeReviewSnapshot snapshot) {
        return new KnowledgeReviewSource(
                origin, sourceRecordId, selectorKey, snapshot);
    }

    private PlaybookDraft.ValidationError error(
            String code,
            String fieldPath,
            String message) {
        return new PlaybookDraft.ValidationError(code, fieldPath, message);
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
