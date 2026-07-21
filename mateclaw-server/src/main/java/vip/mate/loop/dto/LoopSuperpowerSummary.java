package vip.mate.loop.dto;

import vip.mate.loop.model.SuperpowerDefinition;
import vip.mate.skill.manifest.SkillManifest;

import java.util.List;

public record LoopSuperpowerSummary(
        Long skillId,
        String name,
        String description,
        String version,
        String domain,
        String scenario,
        String triggerType,
        String workspaceIsolation,
        Integer maxIterations,
        Integer maxChangedFiles,
        boolean requireHumanBeforePush,
        List<String> requiredChecks,
        List<String> outputs,
        String owner
) {
    public static LoopSuperpowerSummary from(SuperpowerDefinition definition) {
        SkillManifest.SuperpowerBinding binding = definition.binding();
        SkillManifest.SuperpowerTrigger trigger = binding == null ? null : binding.getTrigger();
        SkillManifest.SuperpowerWorkspace workspace = binding == null ? null : binding.getWorkspace();
        SkillManifest.SuperpowerPolicy policy = binding == null ? null : binding.getPolicy();
        SkillManifest.SuperpowerVerification verification = binding == null ? null : binding.getVerification();
        return new LoopSuperpowerSummary(
                definition.skillId(),
                definition.name(),
                definition.description(),
                definition.version(),
                definition.domain(),
                definition.scenario(),
                trigger == null ? null : trigger.getType(),
                workspace == null ? null : workspace.getIsolation(),
                policy == null ? null : policy.getMaxIterations(),
                policy == null ? null : policy.getMaxChangedFiles(),
                policy == null || policy.isRequireHumanBeforePush(),
                verification == null || verification.getRequired() == null ? List.of() : verification.getRequired(),
                binding == null || binding.getOutputs() == null ? List.of() : binding.getOutputs(),
                binding == null ? null : binding.getOwner()
        );
    }
}
