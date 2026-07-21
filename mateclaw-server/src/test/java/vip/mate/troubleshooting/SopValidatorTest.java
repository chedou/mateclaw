package vip.mate.troubleshooting;

import org.junit.jupiter.api.Test;
import vip.mate.skill.manifest.SkillManifest;
import vip.mate.troubleshooting.dto.SopStepResult;
import vip.mate.troubleshooting.dto.SopValidationResult;
import vip.mate.troubleshooting.model.SopDefinition;
import vip.mate.troubleshooting.service.SopValidator;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SopValidatorTest {

    private final SopValidator validator = new SopValidator();

    @Test
    void missingRequiredEvidenceBlocksFinalReport() {
        SopDefinition sop = sop(List.of("metrics", "logs", "release"));
        List<SopStepResult> steps = List.of(
                step("check-metrics", "passed", List.of("metrics")),
                step("check-logs", "passed", List.of("logs"))
        );

        SopValidationResult result = validator.validate(sop, steps);

        assertFalse(result.valid());
        assertEquals(List.of("release"), result.missingEvidence());
        assertTrue(result.errors().contains("required_evidence_missing"));
    }

    @Test
    void acceptsCompleteStructuredChecklist() {
        SopDefinition sop = sop(List.of("metrics", "logs"));
        List<SopStepResult> steps = List.of(
                step("check-metrics", "passed", List.of("metrics")),
                step("check-logs", "inconclusive", List.of("logs"))
        );

        SopValidationResult result = validator.validate(sop, steps);

        assertTrue(result.valid());
        assertTrue(result.missingEvidence().isEmpty());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void rejectsInvalidStepContract() {
        SopDefinition sop = sop(List.of());
        SopStepResult invalid = new SopStepResult(
                "",
                "done",
                List.of(),
                List.of(),
                "",
                "",
                "maybe"
        );

        SopValidationResult result = validator.validate(sop, List.of(invalid));

        assertFalse(result.valid());
        assertTrue(result.errors().contains("step[0].stepId_required"));
        assertTrue(result.errors().contains("step[0].status_invalid"));
        assertTrue(result.errors().contains("step[0].observation_required"));
        assertTrue(result.errors().contains("step[0].interpretation_required"));
        assertTrue(result.errors().contains("step[0].nextDecision_invalid"));
    }

    private static SopStepResult step(String stepId, String status, List<String> evidenceTypes) {
        return new SopStepResult(
                stepId,
                status,
                List.of("E-001"),
                evidenceTypes,
                "observed",
                "interpreted",
                "continue"
        );
    }

    private static SopDefinition sop(List<String> requiredEvidence) {
        return new SopDefinition(
                1L,
                "api-service-5xx",
                null,
                "1.0.0",
                true,
                0L,
                "api_service",
                "http_5xx_timeout",
                SkillManifest.TroubleshootingMatch.builder().build(),
                requiredEvidence,
                List.of(),
                "sop-checklist-v1",
                "platform-sre",
                90,
                null,
                false,
                "",
                ""
        );
    }
}
