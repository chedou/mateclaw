package vip.mate.troubleshooting.evidence;

import vip.mate.troubleshooting.TroubleshootingSecretRedactor;
import vip.mate.troubleshooting.model.ConclusionType;
import vip.mate.troubleshooting.model.DiagnosisStatus;
import vip.mate.troubleshooting.model.PlaybookVersionRef;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;

/** Immutable, secret-free audit facts for one scenario evidence-plan run. */
public record ScenarioEvidenceRunAudit(
        String runId,
        String diagnosisId,
        PlaybookVersionRef playbookVersionRef,
        DiagnosisStatus diagnosisStatus,
        ConclusionType conclusionType,
        List<String> evidenceRefs,
        Instant startedAt,
        Instant completedAt,
        String actorRef) {

    public ScenarioEvidenceRunAudit {
        runId = safe(runId, "runId", 128);
        diagnosisId = safe(diagnosisId, "diagnosisId", 128);
        if (playbookVersionRef == null || diagnosisStatus == null || conclusionType == null) {
            throw new IllegalArgumentException(
                    "playbookVersionRef, diagnosisStatus and conclusionType are required");
        }
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        for (String ref : evidenceRefs == null ? List.<String>of() : evidenceRefs) {
            refs.add(safe(ref, "evidenceRef", 128));
        }
        evidenceRefs = List.copyOf(refs);
        if (startedAt == null || completedAt == null) {
            throw new IllegalArgumentException("startedAt and completedAt are required");
        }
        if (completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("completedAt cannot precede startedAt");
        }
        actorRef = safe(actorRef, "actorRef", 192);
    }

    public Duration duration() {
        return Duration.between(startedAt, completedAt);
    }

    private static String safe(String value, String field, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()
                || normalized.length() > maxLength
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    field + " must contain 1-" + maxLength + " safe characters");
        }
        if (!TroubleshootingSecretRedactor.redact(normalized).equals(normalized)) {
            throw new IllegalArgumentException(field + " must not contain credentials");
        }
        return normalized;
    }
}
