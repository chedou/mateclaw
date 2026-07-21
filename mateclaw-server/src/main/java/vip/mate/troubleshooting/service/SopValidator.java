package vip.mate.troubleshooting.service;

import org.springframework.stereotype.Service;
import vip.mate.troubleshooting.dto.SopStepResult;
import vip.mate.troubleshooting.dto.SopValidationResult;
import vip.mate.troubleshooting.model.SopDefinition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class SopValidator {

    private static final Set<String> VALID_STATUSES = Set.of(
            "passed", "failed", "inconclusive", "skipped"
    );

    private static final Set<String> VALID_NEXT_DECISIONS = Set.of(
            "continue", "stop", "switch_sop", "need_human"
    );

    public SopValidationResult validate(SopDefinition sop, List<SopStepResult> steps) {
        List<String> errors = new ArrayList<>();
        List<String> missingEvidence = new ArrayList<>();
        if (steps == null || steps.isEmpty()) {
            errors.add("step_results_required");
        }

        Set<String> observedEvidenceTypes = new HashSet<>();
        if (steps != null) {
            for (int i = 0; i < steps.size(); i++) {
                SopStepResult step = steps.get(i);
                String prefix = "step[" + i + "]";
                if (isBlank(step.stepId())) errors.add(prefix + ".stepId_required");
                String status = norm(step.status());
                if (!VALID_STATUSES.contains(status)) errors.add(prefix + ".status_invalid");
                if (isBlank(step.observation())) errors.add(prefix + ".observation_required");
                if (isBlank(step.interpretation())) errors.add(prefix + ".interpretation_required");
                String next = norm(step.nextDecision());
                if (!VALID_NEXT_DECISIONS.contains(next)) errors.add(prefix + ".nextDecision_invalid");
                if (step.evidenceTypes() != null) {
                    step.evidenceTypes().stream()
                            .filter(v -> !isBlank(v))
                            .map(SopValidator::norm)
                            .forEach(observedEvidenceTypes::add);
                }
            }
        }

        if (sop != null && sop.requiredEvidence() != null) {
            for (String required : sop.requiredEvidence()) {
                String normalized = norm(required);
                if (!normalized.isBlank() && !observedEvidenceTypes.contains(normalized)) {
                    missingEvidence.add(required);
                }
            }
        }
        if (!missingEvidence.isEmpty()) {
            errors.add("required_evidence_missing");
        }
        return new SopValidationResult(errors.isEmpty(), missingEvidence, errors);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String norm(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
