package vip.mate.troubleshooting.synthesis;

import vip.mate.troubleshooting.model.KnowledgeCandidate;
import vip.mate.troubleshooting.service.SopSummary;

import java.util.List;

/**
 * Read model for the three knowledge lanes that already persist candidates.
 *
 * <p>This is deliberately not a promotion command. Evidence-derived records
 * already own generation and qualification fields. {@code reviewStates} is an
 * independent audit ledger shared by all three origins; missing state means a
 * virtual CANDIDATE/v0 rather than a hidden publication decision.</p>
 */
public record KnowledgeReviewInbox(
        List<PlaybookKnowledgeRecord> evidenceDerived,
        List<KnowledgeCandidate> outcomeBacked,
        List<SopSummary> manual,
        List<KnowledgeReviewSource> sourceStates,
        List<KnowledgeReviewState> reviewStates,
        List<String> capabilityLimits) {

    public static final List<String> CURRENT_CAPABILITY_LIMITS = List.of(
            "REVIEW_START_AND_REJECT_ONLY",
            "APPROVAL_REQUIRES_ELIGIBILITY_GATE",
            "PROMOTION_MUST_CREATE_NEW_VERSION");

    public KnowledgeReviewInbox {
        evidenceDerived = List.copyOf(
                evidenceDerived == null ? List.of() : evidenceDerived);
        outcomeBacked = List.copyOf(
                outcomeBacked == null ? List.of() : outcomeBacked);
        manual = List.copyOf(manual == null ? List.of() : manual);
        sourceStates = List.copyOf(
                sourceStates == null ? List.of() : sourceStates);
        reviewStates = List.copyOf(
                reviewStates == null ? List.of() : reviewStates);
        capabilityLimits = List.copyOf(
                capabilityLimits == null ? List.of() : capabilityLimits);
    }
}
