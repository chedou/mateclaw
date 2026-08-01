package vip.mate.troubleshooting.model;

import java.util.List;

/** Explicit Scenario Playbook selection before its required read-only evidence exists. */
public record ScenarioDiagnosisDraft(
        String diagnosisId,
        String caseId,
        String runId,
        IncidentContext incident,
        String scenarioKey,
        SopEntry playbook,
        PlaybookVersionRef sourcePlaybookVersionRef,
        String actor,
        NorthStarTimings timings,
        boolean rehearsal,
        boolean fixtureMode,
        List<String> warnings) {

    public ScenarioDiagnosisDraft {
        diagnosisId = required(diagnosisId, "diagnosisId");
        caseId = required(caseId, "caseId");
        runId = required(runId, "runId");
        scenarioKey = required(scenarioKey, "scenarioKey");
        actor = required(actor, "actor");
        if (incident == null || playbook == null
                || sourcePlaybookVersionRef == null || timings == null) {
            throw new IllegalArgumentException(
                    "incident, playbook, sourcePlaybookVersionRef and timings are required");
        }
        if (incident.errorCode() != null) {
            throw new IllegalArgumentException(
                    "an explicit Scenario Playbook intake must not masquerade as an error-code route");
        }
        String selector = new ScenarioSelector(
                incident.system(), scenarioKey).routingKey();
        if (!selector.equals(playbook.routingKey())) {
            throw new IllegalArgumentException(
                    "Scenario Playbook does not match incident selector: " + selector);
        }
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
    }

    public String selectorKey() {
        return playbook.routingKey();
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
