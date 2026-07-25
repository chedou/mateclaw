package vip.mate.troubleshooting.model;

import java.util.List;
import java.util.Locale;

/** Engine output accepted by the state machine to create a deterministic diagnosis. */
public record DeterministicDiagnosisDraft(
        String diagnosisId,
        String caseId,
        String runId,
        IncidentContext incident,
        SopEntry sop,
        List<EvidenceResult> evidence,
        List<String> triggeredSignals,
        List<RecommendedAction> recommendedActions,
        String summary,
        String rootCause,
        Confidence confidence,
        boolean abstained,
        String routeToTeam,
        boolean rehearsal,
        boolean fixtureMode,
        List<String> warnings) {

    public DeterministicDiagnosisDraft {
        diagnosisId = required(diagnosisId, "diagnosisId");
        caseId = required(caseId, "caseId");
        runId = required(runId, "runId");
        if (incident == null || sop == null || confidence == null) {
            throw new IllegalArgumentException("incident, sop and confidence are required");
        }
        String routeKey = incident.system().trim().toLowerCase(Locale.ROOT)
                + ":" + required(incident.errorCode(), "incident.errorCode");
        if (!routeKey.equals(sop.routingKey())) {
            throw new IllegalArgumentException("SOP does not match incident route: " + routeKey);
        }
        evidence = List.copyOf(evidence == null ? List.of() : evidence);
        triggeredSignals = List.copyOf(triggeredSignals == null ? List.of() : triggeredSignals);
        recommendedActions = List.copyOf(
                recommendedActions == null ? List.of() : recommendedActions);
        summary = summary == null ? "" : summary;
        rootCause = rootCause == null ? "" : rootCause;
        routeToTeam = routeToTeam == null || routeToTeam.isBlank()
                ? null : routeToTeam.trim();
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
        if (abstained && !recommendedActions.isEmpty()) {
            throw new IllegalArgumentException("abstained diagnosis must not recommend actions");
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
