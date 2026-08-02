package vip.mate.troubleshooting.deployment;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.DiagnosisStatus;
import vip.mate.troubleshooting.model.TroubleshootingTopologyProbeRunEntity;
import vip.mate.troubleshooting.repository.TroubleshootingDiagnosisMapper;
import vip.mate.troubleshooting.repository.TroubleshootingTopologyProbeRunMapper;
import vip.mate.troubleshooting.service.TroubleshootingPersistenceService;

/** Short transaction that serializes a topology-evidence append with case closure. */
@Service
@RequiredArgsConstructor
public class TopologyProbeEvidenceRunPersistenceService {

    private final TroubleshootingDiagnosisMapper diagnosisMapper;
    private final TroubleshootingTopologyProbeRunMapper runMapper;
    private final TroubleshootingPersistenceService persistence;

    /**
     * Locks the Diagnosis row, verifies that it is still open, then appends the
     * immutable run before releasing the lock. A concurrent close therefore
     * either commits first and rejects this append, or waits until this append
     * has committed; it cannot slip between the final check and the insert.
     *
     * <p>When {@code advanced} is supplied, the re-decided aggregate is written
     * under the same lock. The run and the conclusion it produced are one fact;
     * committing the row and losing the conclusion would put the Diagnosis back
     * in the state this write-back exists to end.</p>
     */
    @Transactional
    public void insertIfDiagnosisOpen(
            long workspaceId,
            String diagnosisId,
            TroubleshootingTopologyProbeRunEntity entity,
            Diagnosis advanced,
            int expectedVersion) {
        String status = diagnosisMapper.lockStatusForDependentAppend(
                workspaceId, diagnosisId);
        if (status == null) {
            throw new MateClawException(
                    "err.troubleshooting.diagnosis_not_found",
                    404,
                    "troubleshooting diagnosis not found: " + diagnosisId);
        }
        if (DiagnosisStatus.CLOSED.name().equals(status)) {
            throw new MateClawException(
                    "err.troubleshooting.topology_probe_diagnosis_closed",
                    409,
                    "diagnosis was closed while topology evidence was being collected");
        }
        if (runMapper.insert(entity) != 1) {
            throw new MateClawException(
                    "err.troubleshooting.topology_probe_persistence_failed",
                    500,
                    "topology evidence run could not be persisted");
        }
        if (advanced != null) {
            persistence.update(workspaceId, advanced, expectedVersion);
        }
    }
}
