package vip.mate.troubleshooting.dto;

import vip.mate.troubleshooting.model.SopDefinition;

import java.time.LocalDateTime;
import java.util.List;

public record SopSummary(
        Long skillId,
        String name,
        String description,
        String version,
        boolean builtin,
        String domain,
        String scenario,
        List<String> severities,
        List<String> labels,
        List<String> keywords,
        List<String> requiredEvidence,
        List<String> optionalEvidence,
        String outputSchema,
        String owner,
        Integer reviewCycleDays,
        LocalDateTime reviewDueAt,
        boolean expired
) {
    public static SopSummary from(SopDefinition sop) {
        var match = sop.match();
        return new SopSummary(
                sop.skillId(),
                sop.name(),
                sop.description(),
                sop.version(),
                sop.builtin(),
                sop.domain(),
                sop.scenario(),
                match == null || match.getSeverities() == null ? List.of() : match.getSeverities(),
                match == null || match.getLabels() == null ? List.of() : match.getLabels(),
                match == null || match.getKeywords() == null ? List.of() : match.getKeywords(),
                sop.requiredEvidence(),
                sop.optionalEvidence(),
                sop.outputSchema(),
                sop.owner(),
                sop.reviewCycleDays(),
                sop.reviewDueAt(),
                sop.expired()
        );
    }
}
