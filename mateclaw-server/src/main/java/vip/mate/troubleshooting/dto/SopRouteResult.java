package vip.mate.troubleshooting.dto;

import java.util.List;

public record SopRouteResult(
        SopRouteCandidate selected,
        List<SopRouteCandidate> candidates,
        boolean lowConfidence,
        boolean usedFallback,
        List<String> missingSignals,
        String inputSummary
) {
}
