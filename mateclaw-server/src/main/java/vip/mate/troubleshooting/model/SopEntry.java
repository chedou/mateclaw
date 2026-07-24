package vip.mate.troubleshooting.model;

import java.util.List;
import java.util.Locale;

/** Versionable deterministic SOP contract; Wiki content is not authoritative. */
public record SopEntry(
        String sopId,
        String contractVersion,
        String system,
        String errorCode,
        String service,
        String title,
        String cause,
        String category,
        String ownerTeam,
        String status,
        boolean verified,
        List<EvidenceRequest> evidenceRequests,
        List<AnomalyCriterion> anomalyCriteria,
        List<DiagnosisRule> diagnosisRules,
        List<RecommendedAction> actions) {

    public static final String CURRENT_CONTRACT_VERSION = "sop.v1";

    public SopEntry {
        sopId = required(sopId, "sopId");
        contractVersion = blankDefault(contractVersion, CURRENT_CONTRACT_VERSION);
        system = required(system, "system");
        errorCode = required(errorCode, "errorCode");
        service = required(service, "service");
        title = required(title, "title");
        cause = cause == null ? "" : cause;
        category = category == null ? "" : category;
        ownerTeam = ownerTeam == null || ownerTeam.isBlank() ? null : ownerTeam.trim();
        status = blankDefault(status, "candidate").toLowerCase(Locale.ROOT);
        evidenceRequests = List.copyOf(evidenceRequests == null ? List.of() : evidenceRequests);
        anomalyCriteria = List.copyOf(anomalyCriteria == null ? List.of() : anomalyCriteria);
        diagnosisRules = List.copyOf(diagnosisRules == null ? List.of() : diagnosisRules);
        actions = List.copyOf(actions == null ? List.of() : actions);
    }

    public String routingKey() {
        return system.trim().toLowerCase(Locale.ROOT) + ":" + errorCode.trim();
    }

    public boolean operational() {
        return verified && "approved".equals(status);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static String blankDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
