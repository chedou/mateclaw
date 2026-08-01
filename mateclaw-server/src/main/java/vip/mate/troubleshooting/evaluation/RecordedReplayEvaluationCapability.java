package vip.mate.troubleshooting.evaluation;

/** Server-owned readiness for the fixture-only Recorded Replay T8 capture action. */
public record RecordedReplayEvaluationCapability(
        boolean available,
        String reasonCode,
        String reason,
        String scenarioKey,
        String searchTerm,
        String window) {

    public RecordedReplayEvaluationCapability {
        reasonCode = reasonCode == null ? "UNKNOWN" : reasonCode.trim();
        reason = reason == null ? "" : reason.trim();
        scenarioKey = normalized(scenarioKey);
        searchTerm = normalized(searchTerm);
        window = normalized(window);
        if (available && !"READY".equals(reasonCode)) {
            throw new IllegalArgumentException("available Replay capability must be READY");
        }
        if (available && (scenarioKey == null || searchTerm == null || window == null)) {
            throw new IllegalArgumentException("available Replay capability requires a capture target");
        }
    }

    static RecordedReplayEvaluationCapability ready(
            String scenarioKey,
            String searchTerm,
            String window) {
        return new RecordedReplayEvaluationCapability(
                true,
                "READY",
                "Recorded Replay fixture、路由与登记范围均已就绪",
                scenarioKey,
                searchTerm,
                window);
    }

    static RecordedReplayEvaluationCapability unavailable(
            String reasonCode,
            String reason) {
        return new RecordedReplayEvaluationCapability(
                false, reasonCode, reason, null, null, null);
    }

    private static String normalized(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
