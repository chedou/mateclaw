package vip.mate.troubleshooting.evidence;

import java.util.Map;

public record CollectedEvidence(
        String evidenceType,
        String source,
        String status,
        String title,
        String summary,
        Map<String, Object> content
) {
}
