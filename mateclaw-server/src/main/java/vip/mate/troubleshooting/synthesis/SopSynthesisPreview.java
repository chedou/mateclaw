package vip.mate.troubleshooting.synthesis;

import com.fasterxml.jackson.annotation.JsonProperty;
import vip.mate.troubleshooting.evidence.EvidenceSpineTimings;
import vip.mate.troubleshooting.model.EvidenceStatus;

import java.time.Instant;
import java.util.List;

/** Read-only output after evidence collection and deterministic compression. */
public record SopSynthesisPreview(
        Stage stage,
        String system,
        String service,
        String searchTerm,
        long matchCount,
        String psId,
        EvidenceReference searchEvidence,
        EvidenceReference traceEvidence,
        EvidenceReference contrastEvidence,
        LogTraceSkeleton skeleton,
        boolean fixtureMode,
        int traceEntries,
        int sourceRequestCount,
        long totalDurationMs,
        EvidenceSpineTimings timings,
        Instant completedAt,
        List<String> warnings) {

    /** Backward-compatible constructor for existing preview-only callers. */
    public SopSynthesisPreview(
            Stage stage,
            String system,
            String service,
            String searchTerm,
            long matchCount,
            String psId,
            EvidenceReference searchEvidence,
            EvidenceReference traceEvidence,
            EvidenceReference contrastEvidence,
            LogTraceSkeleton skeleton,
            boolean fixtureMode,
            List<String> warnings) {
        this(
                stage,
                system,
                service,
                searchTerm,
                matchCount,
                psId,
                searchEvidence,
                traceEvidence,
                contrastEvidence,
                skeleton,
                fixtureMode,
                skeleton == null ? 0 : skeleton.sourceEntryCount(),
                3,
                0,
                EvidenceSpineTimings.unmeasured(),
                traceEvidence == null ? null : traceEvidence.collectedAt(),
                warnings);
    }

    public SopSynthesisPreview {
        if (stage == null || searchEvidence == null || traceEvidence == null || skeleton == null) {
            throw new IllegalArgumentException("synthesis preview core fields are required");
        }
        system = required(system, "system");
        service = required(service, "service");
        searchTerm = required(searchTerm, "searchTerm");
        psId = required(psId, "psId");
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
        if (matchCount <= 0 || !psId.equals(skeleton.psId())) {
            throw new IllegalArgumentException("synthesis preview evidence is inconsistent");
        }
        if ((contrastEvidence != null) != skeleton.contrast().available()) {
            throw new IllegalArgumentException("contrast reference and summary must agree");
        }
        if (!fixtureMode
                || traceEntries <= 0
                || traceEntries != skeleton.sourceEntryCount()
                || sourceRequestCount != 3
                || totalDurationMs < 0
                || completedAt == null) {
            throw new IllegalArgumentException(
                    "synthesis preview measurement facts are incomplete");
        }
        timings = timings == null ? EvidenceSpineTimings.unmeasured() : timings;
        if (totalDurationMs < timings.observedWorkDurationMs()) {
            throw new IllegalArgumentException(
                    "synthesis preview total duration is shorter than measured work");
        }
    }

    @JsonProperty("contrastAvailable")
    public boolean contrastAvailable() {
        return skeleton.contrast().available();
    }

    public enum Stage {
        READY_FOR_MODEL
    }

    public record EvidenceReference(
            String queryId,
            EvidenceStatus status,
            String source,
            Instant collectedAt) {

        public EvidenceReference {
            queryId = required(queryId, "queryId");
            source = required(source, "source");
            if (status == null || collectedAt == null) {
                throw new IllegalArgumentException("evidence reference status and time are required");
            }
        }

    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
