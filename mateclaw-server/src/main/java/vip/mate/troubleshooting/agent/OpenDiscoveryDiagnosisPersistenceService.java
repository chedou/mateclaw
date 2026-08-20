package vip.mate.troubleshooting.agent;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.model.InvestigationMode;
import vip.mate.troubleshooting.service.IncidentDeduplicationKey;
import vip.mate.troubleshooting.service.FormalOpenDiscoveryAdmission;
import vip.mate.troubleshooting.service.StoredDiagnosis;
import vip.mate.troubleshooting.service.TroubleshootingPersistenceService;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** Atomic, short database boundary for an OPEN_DISCOVERY Diagnosis and its run audit. */
@Service
public class OpenDiscoveryDiagnosisPersistenceService {

    private final TroubleshootingPersistenceService diagnoses;
    private final OpenDiscoveryRunAuditService runAudits;
    private final OpenDiscoveryRunClaimService claims;

    public OpenDiscoveryDiagnosisPersistenceService(
            TroubleshootingPersistenceService diagnoses,
            OpenDiscoveryRunAuditService runAudits,
            OpenDiscoveryRunClaimService claims) {
        this.diagnoses = diagnoses;
        this.runAudits = runAudits;
        this.claims = claims;
    }

    public OpenDiscoveryRunReservation reserve(
            long workspaceId,
            IncidentContext incident,
            boolean rehearsal,
            Instant receivedAt,
            String intakeSessionId,
            Duration lease) {
        if (intakeSessionId != null) {
            return diagnoses.findByIntakeSessionId(workspaceId, intakeSessionId)
                    .map(OpenDiscoveryRunReservation::completed)
                    .orElseGet(OpenDiscoveryRunReservation::unclaimed);
        }
        Optional<StoredDiagnosis> existing = diagnoses.findByIncident(
                workspaceId, incident, rehearsal, receivedAt);
        if (existing.isPresent()) {
            return OpenDiscoveryRunReservation.completed(existing.get());
        }
        Optional<String> dedupKey = IncidentDeduplicationKey.create(
                incident, rehearsal, receivedAt);
        if (dedupKey.isEmpty()) {
            if (rehearsal) {
                return OpenDiscoveryRunReservation.unclaimed();
            }
            throw new MateClawException(
                    "err.troubleshooting.open_discovery_claim_conflict",
                    409,
                    "open discovery needs a stable symptom or trace identity");
        }
        OpenDiscoveryRunClaimService.ClaimResult claimed = claims.claim(
                workspaceId, dedupKey.orElseThrow(), receivedAt, lease);
        return switch (claimed.state()) {
            case ACQUIRED -> OpenDiscoveryRunReservation.acquired(claimed.claim());
            case COMPLETED -> OpenDiscoveryRunReservation.completed(
                    diagnoses.get(workspaceId, claimed.diagnosisId()));
            case IN_PROGRESS -> throw new MateClawException(
                    "err.troubleshooting.open_discovery_in_progress",
                    409,
                    "the same open discovery incident is already in progress");
        };
    }

    public void release(long workspaceId, OpenDiscoveryRunClaim claim) {
        claims.release(workspaceId, claim);
    }

    /**
     * Reuses a completed formal run only when its immutable audit still proves
     * the same pilot and Guance authority. Historical OPEN_DISCOVERY rows are
     * deliberately not upgraded into formal results by a later retry.
     */
    public StoredDiagnosis requireCompletedFormal(
            long workspaceId,
            StoredDiagnosis stored,
            FormalOpenDiscoveryAdmission admission) {
        if (workspaceId <= 0 || stored == null || admission == null) {
            throw new IllegalArgumentException(
                    "workspaceId, stored diagnosis and formal admission are required");
        }
        OpenDiscoveryRunAudit audit = runAudits.latest(
                        workspaceId, stored.diagnosis().diagnosisId())
                .orElseThrow(() -> formalConflict(
                        "the completed diagnosis has no frozen formal authority"));
        if (stored.diagnosis().rehearsal()
                || stored.diagnosis().investigationMode() != InvestigationMode.OPEN_DISCOVERY
                || stored.pilotPlanVersion() == null
                || stored.pilotPlanVersion() != admission.pilotPlanVersion()
                || audit.formalPilotPlanVersion() == null
                || audit.formalPilotPlanVersion() != admission.pilotPlanVersion()
                || !admission.guanceAcceptanceId().equals(audit.sourceAcceptanceId())
                || !admission.guanceBindingFingerprint()
                        .equals(audit.sourceBindingFingerprint())) {
            throw formalConflict(
                    "the completed diagnosis does not match the current frozen formal authority");
        }
        return stored;
    }

    @Transactional
    public StoredDiagnosis persist(
            long workspaceId,
            Diagnosis diagnosis,
            Instant receivedAt,
            String intakeSessionId,
            OpenDiscoveryRunClaim claim,
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
        boolean verifiableHypothesis = runAudit.hasVerifiableHypothesis();
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
            if (claim != null) {
                claims.complete(
                        workspaceId,
                        claim,
                        diagnosis.diagnosisId(),
                        runAudit.completedAt());
            }
        } else if (claim != null) {
            throw new IllegalStateException(
                    "a claimed open discovery run cannot resolve to an existing diagnosis");
        }
        return stored;
    }

    /** Persists a formally admitted bounded run and its frozen source authority. */
    @Transactional
    public StoredDiagnosis persistFormal(
            long workspaceId,
            Diagnosis diagnosis,
            Instant receivedAt,
            String intakeSessionId,
            OpenDiscoveryRunClaim claim,
            OpenDiscoveryRunAudit runAudit,
            FormalOpenDiscoveryAdmission admission,
            Instant claimCompletedAt) {
        if (workspaceId <= 0
                || diagnosis == null
                || receivedAt == null
                || runAudit == null
                || admission == null
                || claimCompletedAt == null) {
            throw new IllegalArgumentException(
                    "workspaceId, diagnosis, receivedAt, runAudit, formal admission and claim completion time are required");
        }
        if (intakeSessionId != null) {
            throw new MateClawException(
                    "err.troubleshooting.formal_open_discovery_conflict",
                    409,
                    "formal generic IntakeSession persistence is not available yet");
        }
        validateRunIdentity(diagnosis, runAudit);
        if (diagnosis.rehearsal()
                || runAudit.formalPilotPlanVersion() == null
                || runAudit.formalPilotPlanVersion() != admission.pilotPlanVersion()
                || !admission.guanceAcceptanceId().equals(runAudit.sourceAcceptanceId())
                || !admission.guanceBindingFingerprint()
                        .equals(runAudit.sourceBindingFingerprint())) {
            throw new IllegalArgumentException(
                    "formal open-discovery audit must match its admission");
        }
        // This must be the transaction's first database write. On Kingbase,
        // CURRENT_TIMESTAMP is the transaction start time; completing the CAS
        // first both validates the lease at the transaction boundary and locks
        // the owner row. Any later Diagnosis/audit failure rolls this update back.
        claims.complete(
                workspaceId,
                claim,
                diagnosis.diagnosisId(),
                claimCompletedAt);
        StoredDiagnosis stored = diagnoses.createOrGet(
                workspaceId,
                diagnosis,
                receivedAt,
                admission.pilotPlanVersion(),
                claim);
        if (!stored.created()) {
            throw new IllegalStateException(
                    "a claimed formal open-discovery run cannot resolve to an existing diagnosis");
        }
        runAudits.insert(workspaceId, runAudit);
        return stored;
    }

    private void validateRunIdentity(
            Diagnosis diagnosis,
            OpenDiscoveryRunAudit runAudit) {
        if (diagnosis.investigationMode() != InvestigationMode.OPEN_DISCOVERY
                || !diagnosis.diagnosisId().equals(runAudit.diagnosisId())
                || !diagnosis.runId().equals(runAudit.runId())) {
            throw new IllegalArgumentException(
                    "runAudit must belong to the OPEN_DISCOVERY diagnosis");
        }
        boolean verifiableHypothesis = runAudit.hasVerifiableHypothesis();
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
    }

    private String required(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("intakeSessionId must not be blank");
        }
        return value.trim();
    }

    private MateClawException formalConflict(String message) {
        return new MateClawException(
                "err.troubleshooting.formal_open_discovery_conflict", 409, message);
    }
}
