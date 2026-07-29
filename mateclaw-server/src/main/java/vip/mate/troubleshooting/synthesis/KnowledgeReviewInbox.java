package vip.mate.troubleshooting.synthesis;

import vip.mate.troubleshooting.model.KnowledgeCandidate;
import vip.mate.troubleshooting.service.SopSummary;

import java.util.List;

/**
 * Read model for the three knowledge lanes that already persist candidates.
 *
 * <p>This is deliberately not a promotion command. Evidence-derived records
 * already own review and qualification fields. Outcome-backed records still
 * use the closure publication contract, so the UI must present that missing
 * review state instead of inventing an approval.</p>
 */
public record KnowledgeReviewInbox(
        List<PlaybookKnowledgeRecord> evidenceDerived,
        List<KnowledgeCandidate> outcomeBacked,
        List<SopSummary> manual,
        List<String> capabilityLimits) {

    public static final List<String> CURRENT_CAPABILITY_LIMITS = List.of(
            "REVIEW_DECISIONS_NOT_IMPLEMENTED",
            "APPROVAL_REQUIRES_ELIGIBILITY_GATE",
            "PROMOTION_MUST_CREATE_NEW_VERSION");

    public KnowledgeReviewInbox {
        evidenceDerived = List.copyOf(
                evidenceDerived == null ? List.of() : evidenceDerived);
        outcomeBacked = List.copyOf(
                outcomeBacked == null ? List.of() : outcomeBacked);
        manual = List.copyOf(manual == null ? List.of() : manual);
        capabilityLimits = List.copyOf(
                capabilityLimits == null ? List.of() : capabilityLimits);
    }
}
