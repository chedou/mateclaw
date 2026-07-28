package vip.mate.troubleshooting.synthesis;

import java.time.Instant;
import java.util.List;

/**
 * Model-proposed troubleshooting knowledge that has not crossed a human review
 * boundary. A draft is never an approved or routeable playbook.
 */
public record PlaybookDraft(
        String draftId,
        String generationKey,
        String sourceIncident,
        String proposedType,
        ProposedSelector proposedSelector,
        String title,
        List<EvidencePlanStep> evidencePlan,
        List<Criterion> criteria,
        List<DiagnosisHypothesis> diagnosisHypotheses,
        List<HumanAction> humanActions,
        List<String> evidenceCitations,
        ModelProvenance modelProvenance,
        boolean contrastAvailable,
        List<ValidationError> validationErrors) {

    public static final String CONTRACT_VERSION = "playbook-draft/v1";

    public PlaybookDraft {
        evidencePlan = immutable(evidencePlan);
        criteria = immutable(criteria);
        diagnosisHypotheses = immutable(diagnosisHypotheses);
        humanActions = immutable(humanActions);
        evidenceCitations = immutable(evidenceCitations);
        validationErrors = immutable(validationErrors);
    }

    public PlaybookDraft withValidationErrors(List<ValidationError> errors) {
        return new PlaybookDraft(
                draftId, generationKey, sourceIncident, proposedType, proposedSelector,
                title, evidencePlan, criteria, diagnosisHypotheses, humanActions,
                evidenceCitations, modelProvenance, contrastAvailable, errors);
    }

    public record ProposedSelector(String system, String scenarioKey, String errorCode) {
    }

    public record EvidencePlanStep(
            String intentKey,
            String signalKind,
            String purpose,
            boolean required) {
    }

    public record Criterion(
            String criterionKey,
            String description,
            List<String> evidenceKinds,
            List<String> evidenceCitations) {

        public Criterion {
            evidenceKinds = immutable(evidenceKinds);
            evidenceCitations = immutable(evidenceCitations);
        }
    }

    public record DiagnosisHypothesis(
            String hypothesisKey,
            String summary,
            List<String> evidenceCitations) {

        public DiagnosisHypothesis {
            evidenceCitations = immutable(evidenceCitations);
        }
    }

    public record HumanAction(
            String intentKey,
            String instruction,
            String executionMode,
            List<String> evidenceCitations) {

        public HumanAction {
            evidenceCitations = immutable(evidenceCitations);
        }
    }

    public record ModelProvenance(
            String provider,
            String modelName,
            String modelConfigVersion,
            String draftContractVersion,
            Instant generatedAt,
            int invocationCount) {
    }

    public record ValidationError(String code, String fieldPath, String message) {
    }

    private static <T> List<T> immutable(List<T> values) {
        return List.copyOf(values == null ? List.of() : values);
    }
}
