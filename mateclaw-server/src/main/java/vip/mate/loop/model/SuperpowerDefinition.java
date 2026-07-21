package vip.mate.loop.model;

import vip.mate.skill.manifest.SkillManifest;

/**
 * Discoverable Loop Engineering superpower projected from a {@code mate_skill}
 * row. The SKILL.md body remains the instruction contract.
 */
public record SuperpowerDefinition(
        Long skillId,
        String name,
        String description,
        String version,
        boolean builtin,
        Long workspaceId,
        SkillManifest.SuperpowerBinding binding,
        String skillContent,
        String body
) {
    public String domain() {
        return binding == null ? null : binding.getDomain();
    }

    public String scenario() {
        return binding == null ? null : binding.getScenario();
    }
}
