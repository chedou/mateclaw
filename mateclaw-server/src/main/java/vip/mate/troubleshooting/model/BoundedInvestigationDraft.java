package vip.mate.troubleshooting.model;

import java.util.List;

/** Validated output of the server-owned bounded investigation policy. */
public record BoundedInvestigationDraft(
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
        boolean located,
        NorthStarTimings timings,
        boolean rehearsal,
        boolean fixtureMode,
        List<String> warnings) {

    public BoundedInvestigationDraft {
        diagnosisId = required(diagnosisId, "diagnosisId");
        caseId = required(caseId, "caseId");
        runId = required(runId, "runId");
        if (incident == null || confidence == null || timings == null) {
            throw new IllegalArgumentException("incident, confidence and timings are required");
        }
        if (confidence == Confidence.HIGH) {
            throw new IllegalArgumentException("bounded investigation confidence cannot be HIGH");
        }
        if (abstained && located) {
            throw new IllegalArgumentException(
                    "bounded investigation cannot be both located and abstained");
        }
        if (abstained && confidence != Confidence.LOW) {
            throw new IllegalArgumentException("abstained bounded investigation confidence must be LOW");
        }
        evidence = List.copyOf(evidence == null ? List.of() : evidence);
        evidenceCitations = List.copyOf(
                evidenceCitations == null ? List.of() : evidenceCitations);
        if (!abstained && evidenceCitations.isEmpty()) {
            throw new IllegalArgumentException(
                    "bounded investigation hypothesis requires evidence citations");
        }
        summary = required(summary, "summary");
        hypothesis = abstained
                ? (hypothesis == null ? "" : hypothesis.trim())
                : required(hypothesis, "hypothesis");
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
