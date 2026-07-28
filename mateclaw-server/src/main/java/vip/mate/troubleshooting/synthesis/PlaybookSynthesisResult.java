package vip.mate.troubleshooting.synthesis;

import java.util.List;

/** Observable P1 outcome; rejected and abstained attempts never carry a candidate. */
public record PlaybookSynthesisResult(
        Stage stage,
        SopSynthesisPreview evidencePreview,
        PlaybookKnowledgeRecord candidate,
        PlaybookDraft rejectedDraft,
        NorthStarTimings timings,
        List<String> errors,
        List<String> warnings) {

    public PlaybookSynthesisResult {
        errors = List.copyOf(errors == null ? List.of() : errors);
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
        if (stage == null || evidencePreview == null || timings == null) {
            throw new IllegalArgumentException("synthesis result core fields are required");
        }
        boolean candidateStage = stage == Stage.CANDIDATE_CREATED
                || stage == Stage.CANDIDATE_REUSED;
        if (candidateStage != (candidate != null)) {
            throw new IllegalArgumentException("candidate presence must match synthesis stage");
        }
        if (candidate != null && rejectedDraft != null) {
            throw new IllegalArgumentException("successful result cannot contain a rejected draft");
        }
    }

    public enum Stage {
        CANDIDATE_CREATED,
        CANDIDATE_REUSED,
        ABSTAINED,
        MODEL_REJECTED,
        VALIDATION_REJECTED
    }
}
