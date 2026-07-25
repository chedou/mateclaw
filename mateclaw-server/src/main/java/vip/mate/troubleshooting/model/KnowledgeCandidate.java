package vip.mate.troubleshooting.model;

import java.time.Instant;
import java.util.List;

/** Candidate only: publication never overwrites an approved SOP directly. */
public record KnowledgeCandidate(
        String candidateId,
        String contractVersion,
        String sourceDiagnosisId,
        String sourceCaseId,
        String sourceRunId,
        String system,
        String errorCode,
        String sopKey,
        String rootCause,
        List<String> evidenceIds,
        List<RecommendedAction> recommendedActions,
        List<ActionOutcomeRecord> actionOutcomes,
        String resolutionSummary,
        String feedback,
        String createdBy,
        Instant createdAt) {

    public static final String CURRENT_CONTRACT_VERSION = "knowledge-candidate.v1";

    public KnowledgeCandidate {
        candidateId = required(candidateId, "candidateId");
        contractVersion = contractVersion == null || contractVersion.isBlank()
                ? CURRENT_CONTRACT_VERSION : contractVersion.trim();
        sourceDiagnosisId = required(sourceDiagnosisId, "sourceDiagnosisId");
        sourceCaseId = required(sourceCaseId, "sourceCaseId");
        sourceRunId = required(sourceRunId, "sourceRunId");
        system = required(system, "system");
        rootCause = required(rootCause, "rootCause");
        evidenceIds = List.copyOf(evidenceIds == null ? List.of() : evidenceIds);
        recommendedActions = List.copyOf(recommendedActions == null ? List.of() : recommendedActions);
        actionOutcomes = List.copyOf(actionOutcomes == null ? List.of() : actionOutcomes);
        resolutionSummary = required(resolutionSummary, "resolutionSummary");
        createdBy = required(createdBy, "createdBy");
        createdAt = createdAt == null ? Instant.EPOCH : createdAt;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
