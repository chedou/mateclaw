package vip.mate.troubleshooting.agent;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.InvestigationMode;
import vip.mate.troubleshooting.service.StoredDiagnosis;
import vip.mate.troubleshooting.service.TroubleshootingPersistenceService;

import java.time.Instant;

/** Atomic, short database boundary for an OPEN_DISCOVERY Diagnosis and its run audit. */
@Service
public class OpenDiscoveryDiagnosisPersistenceService {

    private final TroubleshootingPersistenceService diagnoses;
    private final OpenDiscoveryRunAuditService runAudits;

    public OpenDiscoveryDiagnosisPersistenceService(
            TroubleshootingPersistenceService diagnoses,
            OpenDiscoveryRunAuditService runAudits) {
        this.diagnoses = diagnoses;
        this.runAudits = runAudits;
    }

    @Transactional
    public StoredDiagnosis persist(
            long workspaceId,
            Diagnosis diagnosis,
            Instant receivedAt,
            String intakeSessionId,
            OpenDiscoveryRunAudit runAudit) {
        if (workspaceId <= 0 || diagnosis == null || receivedAt == null || runAudit == null) {
            throw new IllegalArgumentException(
                    "workspaceId, diagnosis, receivedAt and runAudit are required");
        }
        if (diagnosis.investigationMode() != InvestigationMode.OPEN_DISCOVERY
                || !diagnosis.diagnosisId().equals(runAudit.diagnosisId())
                || !diagnosis.runId().equals(runAudit.runId())) {
            throw new IllegalArgumentException(
                    "runAudit must belong to the OPEN_DISCOVERY diagnosis");
        }
        boolean verifiableHypothesis = runAudit.stopReason()
                == OpenDiscoveryRunAudit.StopReason.VERIFIABLE_HYPOTHESIS;
        if (diagnosis.abstained() == verifiableHypothesis) {
            throw new IllegalArgumentException(
                    "runAudit stopReason must agree with the diagnosis outcome");
        }
        java.util.Set<String> diagnosisEvidenceRefs = diagnosis.evidence().stream()
                .map(vip.mate.troubleshooting.model.EvidenceResult::queryId)
                .collect(java.util.stream.Collectors.toSet());
        if (!diagnosisEvidenceRefs.containsAll(runAudit.evidenceRefs())) {
            throw new IllegalArgumentException(
                    "runAudit evidenceRefs must belong to the diagnosis evidence");
        }
        StoredDiagnosis stored = intakeSessionId == null
                ? diagnoses.createOrGet(workspaceId, diagnosis, receivedAt)
                : diagnoses.createOrGetForIntake(
                        workspaceId, diagnosis, required(intakeSessionId));
        if (stored.created()) {
            runAudits.insert(workspaceId, runAudit);
        }
        return stored;
    }

    private String required(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("intakeSessionId must not be blank");
        }
        return value.trim();
    }
}
