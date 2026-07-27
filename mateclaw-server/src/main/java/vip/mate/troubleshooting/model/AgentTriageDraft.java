package vip.mate.troubleshooting.model;

import java.util.List;

/** Validated read-only Agent output accepted by the diagnosis state machine. */
public record AgentTriageDraft(
        String diagnosisId,
        String caseId,
        String runId,
        IncidentContext incident,
        List<EvidenceResult> evidence,
        List<String> evidenceCitations,
        String summary,
        String hypothesis,
        Confidence confidence,
        boolean abstained,
        boolean rehearsal,
        boolean fixtureMode,
        List<String> warnings) {

    public AgentTriageDraft {
        diagnosisId = required(diagnosisId, "diagnosisId");
        caseId = required(caseId, "caseId");
        runId = required(runId, "runId");
        if (incident == null || confidence == null) {
            throw new IllegalArgumentException("incident and confidence are required");
        }
        evidence = List.copyOf(evidence == null ? List.of() : evidence);
        evidenceCitations = List.copyOf(
                evidenceCitations == null ? List.of() : evidenceCitations);
        summary = summary == null ? "" : summary.trim();
        hypothesis = hypothesis == null ? "" : hypothesis.trim();
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
        if (!abstained && evidenceCitations.isEmpty()) {
            throw new IllegalArgumentException(
                    "non-abstained Agent triage requires evidence citations");
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
