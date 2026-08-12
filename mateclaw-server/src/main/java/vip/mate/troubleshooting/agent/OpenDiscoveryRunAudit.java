package vip.mate.troubleshooting.agent;

import vip.mate.troubleshooting.TroubleshootingSecretRedactor;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/** Immutable, secret-free facts for one bounded OPEN_DISCOVERY run. */
public record OpenDiscoveryRunAudit(
        String runId,
        String diagnosisId,
        List<String> visibleScenarioKeys,
        String selectedScenarioKey,
        String selectedPlanFingerprint,
        List<String> plannedSignalKinds,
        int maxIterations,
        int maxEvidenceRequests,
        int sourceRequestCount,
        Duration timeBudget,
        StopReason stopReason,
        List<String> evidenceRefs,
        Instant startedAt,
        Instant completedAt,
        String actorRef) {

    public OpenDiscoveryRunAudit {
        runId = safe(runId, "runId", 128);
        diagnosisId = safe(diagnosisId, "diagnosisId", 128);
        visibleScenarioKeys = safeList(visibleScenarioKeys, "visibleScenarioKey", 128);
        selectedScenarioKey = nullableSafe(
                selectedScenarioKey, "selectedScenarioKey", 128);
        selectedPlanFingerprint = nullableFingerprint(selectedPlanFingerprint);
        plannedSignalKinds = safeList(plannedSignalKinds, "plannedSignalKind", 64);
        evidenceRefs = safeList(evidenceRefs, "evidenceRef", 128);
        if (selectedScenarioKey != null
                && !visibleScenarioKeys.contains(selectedScenarioKey)) {
            throw new IllegalArgumentException(
                    "selectedScenarioKey must be one of the visible scenario keys");
        }
        if (selectedScenarioKey == null && !plannedSignalKinds.isEmpty()) {
            throw new IllegalArgumentException(
                    "a plan cannot be recorded without a selected scenario key");
        }
        if (selectedScenarioKey != null && plannedSignalKinds.isEmpty()) {
            throw new IllegalArgumentException(
                    "a selected scenario key requires planned signal kinds");
        }
        if (selectedScenarioKey == null && selectedPlanFingerprint != null) {
            throw new IllegalArgumentException(
                    "a plan fingerprint cannot be recorded without a selected scenario key");
        }
        if (maxIterations <= 0 || maxEvidenceRequests <= 0
                || sourceRequestCount < 0
                || sourceRequestCount > maxEvidenceRequests) {
            throw new IllegalArgumentException("discovery run budgets and counts are invalid");
        }
        if (timeBudget == null || timeBudget.isZero() || timeBudget.isNegative()) {
            throw new IllegalArgumentException("timeBudget must be positive");
        }
        if (stopReason == null || startedAt == null || completedAt == null) {
            throw new IllegalArgumentException(
                    "stopReason, startedAt and completedAt are required");
        }
        if (completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("completedAt cannot precede startedAt");
        }
        actorRef = safe(actorRef, "actorRef", 192);
    }

    /** Compatibility reader for V197 rows created before plan fingerprints existed. */
    public OpenDiscoveryRunAudit(
            String runId,
            String diagnosisId,
            List<String> visibleScenarioKeys,
            String selectedScenarioKey,
            List<String> plannedSignalKinds,
            int maxIterations,
            int maxEvidenceRequests,
            int sourceRequestCount,
            Duration timeBudget,
            StopReason stopReason,
            List<String> evidenceRefs,
            Instant startedAt,
            Instant completedAt,
            String actorRef) {
        this(
                runId,
                diagnosisId,
                visibleScenarioKeys,
                selectedScenarioKey,
                null,
                plannedSignalKinds,
                maxIterations,
                maxEvidenceRequests,
                sourceRequestCount,
                timeBudget,
                stopReason,
                evidenceRefs,
                startedAt,
                completedAt,
                actorRef);
    }

    public Duration duration() {
        return Duration.between(startedAt, completedAt);
    }

    public enum StopReason {
        VERIFIABLE_HYPOTHESIS,
        AGENT_ABSTAINED,
        NO_VERIFIABLE_CITATIONS,
        CORE_EVIDENCE_INCOMPLETE,
        INVALID_AGENT_OUTPUT,
        AGENT_INVOCATION_FAILED,
        TIME_BUDGET_EXHAUSTED
    }

    private static List<String> safeList(
            List<String> values,
            String field,
            int maxLength) {
        LinkedHashSet<String> safe = new LinkedHashSet<>();
        for (String value : values == null ? List.<String>of() : values) {
            safe.add(OpenDiscoveryRunAudit.safe(value, field, maxLength));
        }
        return List.copyOf(safe);
    }

    private static String nullableSafe(String value, String field, int maxLength) {
        return value == null || value.isBlank() ? null : safe(value, field, maxLength);
    }

    private static String nullableFingerprint(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!Pattern.matches("[a-f0-9]{64}", normalized)) {
            throw new IllegalArgumentException(
                    "selectedPlanFingerprint must be a SHA-256 hex digest");
        }
        return normalized;
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
