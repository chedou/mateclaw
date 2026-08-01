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
        Instant createdAt,
        OutcomeProof outcomeProof,
        String ownerTeam) {

    public static final String CURRENT_CONTRACT_VERSION = "knowledge-candidate.v2";
    public static final String LEGACY_CONTRACT_VERSION = "knowledge-candidate.v1";

    public KnowledgeCandidate {
        candidateId = required(candidateId, "candidateId");
        contractVersion = contractVersion == null || contractVersion.isBlank()
                ? CURRENT_CONTRACT_VERSION : contractVersion.trim();
        if (!CURRENT_CONTRACT_VERSION.equals(contractVersion)
                && !LEGACY_CONTRACT_VERSION.equals(contractVersion)) {
            throw new IllegalArgumentException(
                    "unsupported knowledge candidate contract: " + contractVersion);
        }
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
        ownerTeam = ownerTeam == null || ownerTeam.isBlank()
                ? null : ownerTeam.trim();
        if (LEGACY_CONTRACT_VERSION.equals(contractVersion)) {
            if (outcomeProof != null || ownerTeam != null) {
                throw new IllegalArgumentException(
                        "knowledge-candidate.v1 cannot carry v2 proof fields");
            }
        } else if (outcomeProof == null) {
            throw new IllegalArgumentException(
                    "knowledge-candidate.v2 requires a frozen outcome proof");
        }
        if (outcomeProof != null
                && (!createdBy.equals(outcomeProof.registeredBy())
                || !createdAt.equals(outcomeProof.registeredAt()))) {
            throw new IllegalArgumentException(
                    "outcome proof must be frozen by the candidate closure transition");
        }
    }

    /**
     * Source-compatible constructor for v1-shaped Java callers.
     *
     * <p>Only {@link #LEGACY_CONTRACT_VERSION} is valid through this shape;
     * current rows must use the canonical constructor and supply their frozen
     * closure proof explicitly.</p>
     */
    public KnowledgeCandidate(
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
        this(
                candidateId,
                contractVersion,
                sourceDiagnosisId,
                sourceCaseId,
                sourceRunId,
                system,
                errorCode,
                sopKey,
                rootCause,
                evidenceIds,
                recommendedActions,
                actionOutcomes,
                resolutionSummary,
                feedback,
                createdBy,
                createdAt,
                null,
                null);
    }

    /** Server-owned outcome fact captured atomically with the Diagnosis closure. */
    public record OutcomeProof(
            ClosureOutcome outcome,
            boolean recoveryVerified,
            String registeredBy,
            Instant registeredAt) {

        public OutcomeProof {
            if (outcome == null) {
                throw new IllegalArgumentException("outcome must not be null");
            }
            if ((outcome == ClosureOutcome.RECOVERED) != recoveryVerified) {
                throw new IllegalArgumentException(
                        "recovery verification must agree with a recovered outcome");
            }
            registeredBy = required(registeredBy, "registeredBy");
            if (registeredAt == null) {
                throw new IllegalArgumentException("registeredAt must not be null");
            }
        }

        public static OutcomeProof from(ClosureRecord closure) {
            if (closure == null) {
                throw new IllegalArgumentException("closure must not be null");
            }
            return new OutcomeProof(
                    closure.outcome(),
                    closure.recoveryVerified(),
                    closure.actor(),
                    closure.closedAt());
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
