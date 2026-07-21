package vip.mate.troubleshooting.model;

import vip.mate.skill.manifest.SkillManifest;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Routable troubleshooting SOP projected from a {@code mate_skill} row.
 */
public record SopDefinition(
        Long skillId,
        String name,
        String description,
        String version,
        boolean builtin,
        Long workspaceId,
        String domain,
        String scenario,
        SkillManifest.TroubleshootingMatch match,
        List<String> requiredEvidence,
        List<String> optionalEvidence,
        String outputSchema,
        String owner,
        Integer reviewCycleDays,
        LocalDateTime reviewDueAt,
        boolean expired,
        String skillContent,
        String body
) {
    public boolean isFallback() {
        return "generic".equalsIgnoreCase(domain)
                && "systematic_debugging".equalsIgnoreCase(scenario);
    }
}
