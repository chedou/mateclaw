package vip.mate.troubleshooting.synthesis;

import java.util.List;

/** Model-owned portion of a {@link PlaybookDraft}; identity and provenance are server-owned. */
public record PlaybookDraftProposal(
        boolean abstain,
        String abstainReason,
        String proposedType,
        PlaybookDraft.ProposedSelector proposedSelector,
        String title,
        List<PlaybookDraft.EvidencePlanStep> evidencePlan,
        List<PlaybookDraft.Criterion> criteria,
        List<PlaybookDraft.DiagnosisHypothesis> diagnosisHypotheses,
        List<PlaybookDraft.HumanAction> humanActions,
        List<String> evidenceCitations) {

    public PlaybookDraftProposal {
        abstainReason = abstainReason == null ? "" : abstainReason.trim();
        evidencePlan = immutable(evidencePlan);
        criteria = immutable(criteria);
        diagnosisHypotheses = immutable(diagnosisHypotheses);
        humanActions = immutable(humanActions);
        evidenceCitations = immutable(evidenceCitations);
    }

    private static <T> List<T> immutable(List<T> values) {
        return List.copyOf(values == null ? List.of() : values);
    }
}
