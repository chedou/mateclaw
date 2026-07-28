package vip.mate.troubleshooting.synthesis;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Pure structural comparison; it performs no fuzzy or model-based similarity judgement. */
public final class ReferenceSolutionComparator {

    public Comparison compare(PlaybookDraft draft, ReferenceSolution reference) {
        if (draft == null || reference == null) {
            throw new IllegalArgumentException("draft and reference solution are required");
        }
        List<String> orderedIntents = new ArrayList<>();
        draft.evidencePlan().stream()
                .map(PlaybookDraft.EvidencePlanStep::intentKey)
                .filter(this::present)
                .forEach(orderedIntents::add);
        draft.humanActions().stream()
                .map(PlaybookDraft.HumanAction::intentKey)
                .filter(this::present)
                .forEach(orderedIntents::add);
        Set<String> intents = new LinkedHashSet<>(orderedIntents);

        List<String> missing = reference.requiredStepIntents().stream()
                .filter(required -> !intents.contains(required))
                .toList();
        List<String> forbidden = reference.forbiddenStepIntents().stream()
                .filter(intents::contains)
                .toList();
        List<String> ordering = reference.orderingConstraints().stream()
                .filter(rule -> orderingViolation(orderedIntents, rule))
                .map(rule -> rule.beforeIntent() + " -> " + rule.afterIntent())
                .toList();

        Set<String> actualEvidenceKinds = new LinkedHashSet<>();
        draft.evidencePlan().stream()
                .map(PlaybookDraft.EvidencePlanStep::signalKind)
                .filter(this::present)
                .forEach(actualEvidenceKinds::add);
        draft.criteria().stream()
                .flatMap(criterion -> criterion.evidenceKinds().stream())
                .filter(this::present)
                .forEach(actualEvidenceKinds::add);
        List<String> missingKinds = reference.requiredEvidenceKinds().stream()
                .filter(required -> !actualEvidenceKinds.contains(required))
                .toList();

        int expected = reference.requiredStepIntents().size();
        double coverage = expected == 0 ? 1.0 : (double) (expected - missing.size()) / expected;
        boolean passed = missing.isEmpty()
                && forbidden.isEmpty()
                && ordering.isEmpty()
                && missingKinds.isEmpty();
        return new Comparison(
                reference.referenceId(), passed, coverage,
                missing, forbidden, ordering, missingKinds);
    }

    private boolean orderingViolation(
            List<String> orderedIntents,
            ReferenceSolution.OrderingConstraint rule) {
        int before = orderedIntents.indexOf(rule.beforeIntent());
        int after = orderedIntents.indexOf(rule.afterIntent());
        return before >= 0 && after >= 0 && before >= after;
    }

    private boolean present(String value) {
        return value != null && !value.isBlank();
    }

    public record Comparison(
            String referenceId,
            boolean passed,
            double requiredIntentCoverage,
            List<String> missingStepIntents,
            List<String> forbiddenStepIntentsPresent,
            List<String> orderingViolations,
            List<String> missingEvidenceKinds) {

        public Comparison {
            missingStepIntents = List.copyOf(missingStepIntents);
            forbiddenStepIntentsPresent = List.copyOf(forbiddenStepIntentsPresent);
            orderingViolations = List.copyOf(orderingViolations);
            missingEvidenceKinds = List.copyOf(missingEvidenceKinds);
        }
    }
}
