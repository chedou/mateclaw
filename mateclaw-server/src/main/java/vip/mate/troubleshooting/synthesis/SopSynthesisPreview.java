package vip.mate.troubleshooting.synthesis;

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
        LogTraceSkeleton skeleton,
        boolean fixtureMode,
        List<String> warnings) {

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
