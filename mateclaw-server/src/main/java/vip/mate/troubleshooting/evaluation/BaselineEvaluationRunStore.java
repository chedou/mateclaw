package vip.mate.troubleshooting.evaluation;

import java.time.Instant;
import java.util.List;

/**
 * Workspace-scoped persistence port for single-Agent baseline runs.
 *
 * <p>A caller must atomically claim the deterministic run key before collecting
 * evidence or calling a model. The bounded lease allows a failed worker to be
 * retried without allowing two active workers to call the same model version.</p>
 */
public interface BaselineEvaluationRunStore {

    ClaimResult claim(long workspaceId, RunClaim claim);

    StoredRun complete(long workspaceId, RunClaim claim, BaselineEvaluationRun run);

    boolean renew(long workspaceId, RunClaim claim, Instant expiresAt);

    void release(long workspaceId, RunClaim claim);

    List<BaselineEvaluationRun> list(long workspaceId, String diagnosisId, int limit);

    enum ClaimState {
        ACQUIRED,
        COMPLETED,
        IN_PROGRESS
    }

    record RunClaim(
            String runId,
            String runKey,
            String sampleId,
            String diagnosisId,
            int sampleVersion,
            EvidenceEvaluationSample.SourcePlatform sourcePlatform,
            boolean evidenceFixtureMode,
            boolean diagnosisFixtureMode,
            String modelProvider,
            String modelName,
            String modelConfigVersion,
            String claimToken,
            Instant claimedAt,
            Instant expiresAt) {

        public RunClaim {
            runId = required(runId, "runId");
            runKey = required(runKey, "runKey");
            sampleId = required(sampleId, "sampleId");
            diagnosisId = required(diagnosisId, "diagnosisId");
            if (sampleVersion < 1 || sourcePlatform == null) {
                throw new IllegalArgumentException(
                        "sample version and source platform are required");
            }
            modelProvider = required(modelProvider, "modelProvider");
            modelName = required(modelName, "modelName");
            modelConfigVersion = required(modelConfigVersion, "modelConfigVersion");
            claimToken = required(claimToken, "claimToken");
            if (claimedAt == null || expiresAt == null || !expiresAt.isAfter(claimedAt)) {
                throw new IllegalArgumentException("a positive claim lease is required");
            }
        }

        private static String required(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " is required");
            }
            return value.trim();
        }
    }

    record ClaimResult(
            ClaimState state,
            BaselineEvaluationRun completedRun) {

        public ClaimResult {
            if (state == null
                    || (state == ClaimState.COMPLETED) != (completedRun != null)) {
                throw new IllegalArgumentException("baseline claim result is inconsistent");
            }
        }

        public static ClaimResult acquired() {
            return new ClaimResult(ClaimState.ACQUIRED, null);
        }

        public static ClaimResult completed(BaselineEvaluationRun run) {
            return new ClaimResult(ClaimState.COMPLETED, run);
        }

        public static ClaimResult inProgress() {
            return new ClaimResult(ClaimState.IN_PROGRESS, null);
        }
    }

    record StoredRun(BaselineEvaluationRun run, boolean created) {
        public StoredRun {
            if (run == null) {
                throw new IllegalArgumentException("run is required");
            }
        }
    }
}
