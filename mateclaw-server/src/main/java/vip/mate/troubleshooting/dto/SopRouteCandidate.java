package vip.mate.troubleshooting.dto;

import vip.mate.troubleshooting.model.SopDefinition;

import java.util.List;

public record SopRouteCandidate(
        Long skillId,
        String name,
        String domain,
        String scenario,
        String version,
        double score,
        double confidence,
        String reason,
        List<String> missingSignals,
        List<String> requiredEvidence,
        List<String> optionalEvidence,
        String owner,
        boolean fallback
) {
    public static SopRouteCandidate of(SopDefinition sop, double score, String reason,
                                       List<String> missingSignals, boolean fallback) {
        double confidence = Math.max(0.0d, Math.min(1.0d, score / 100.0d));
        return new SopRouteCandidate(
                sop.skillId(),
                sop.name(),
                sop.domain(),
                sop.scenario(),
                sop.version(),
                score,
                confidence,
                reason,
                missingSignals == null ? List.of() : List.copyOf(missingSignals),
                sop.requiredEvidence(),
                sop.optionalEvidence(),
                sop.owner(),
                fallback
        );
    }
}
