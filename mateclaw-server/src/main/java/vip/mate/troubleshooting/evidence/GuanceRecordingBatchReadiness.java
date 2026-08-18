package vip.mate.troubleshooting.evidence;

import java.util.List;

/**
 * Secret-free readiness projection for the workspace's immutable first T7
 * recording batch.
 *
 * <p>The count gate is workspace-wide. Every row still carries the exact
 * system/service/scenario identity and the binding fingerprint that was
 * evaluated for that row; no row authorizes another row or creates an
 * acceptance record.</p>
 */
public record GuanceRecordingBatchReadiness(
        String contractVersion,
        String batchId,
        long workspaceId,
        String catalogContractVersion,
        String catalogFingerprint,
        int frozenTargetCount,
        int executableTargetCount,
        boolean readyForOwnerAcceptance,
        List<TargetReadiness> targets,
        long asOfEpochSeconds,
        List<String> blockers) {

    public GuanceRecordingBatchReadiness {
        targets = List.copyOf(targets == null ? List.of() : targets);
        blockers = List.copyOf(blockers == null ? List.of() : blockers);
    }

    public record TargetReadiness(
            String targetId,
            String system,
            String service,
            String scenarioKey,
            String selectorKey,
            String bindingFingerprint,
            String targetBindingFingerprint,
            boolean executable,
            List<String> blockers) {

        public TargetReadiness {
            blockers = List.copyOf(blockers == null ? List.of() : blockers);
        }
    }
}
