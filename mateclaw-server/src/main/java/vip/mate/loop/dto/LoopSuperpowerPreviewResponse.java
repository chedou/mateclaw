package vip.mate.loop.dto;

import java.util.List;

public record LoopSuperpowerPreviewResponse(
        LoopSuperpowerSummary selected,
        double confidence,
        List<String> reasons,
        List<String> missingSignals,
        List<LoopSuperpowerSummary> candidates
) {
}
