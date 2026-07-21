package vip.mate.troubleshooting.dto;

import java.util.List;

public record SopEvidenceCollectRequest(
        List<String> evidenceTypes,
        Boolean includeOptional
) {
}
