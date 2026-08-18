package vip.mate.troubleshooting.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.model.TroubleshootingFormalDiagnosisClaimEntity;
import vip.mate.troubleshooting.repository.TroubleshootingFormalDiagnosisClaimMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** Database lease that gates a formal Incident or IntakeSession before admission and Guance I/O. */
@Service
public class FormalDiagnosisClaimService {

    private static final String PROCESSING = "PROCESSING";
    private static final String COMPLETED = "COMPLETED";

    private final TroubleshootingFormalDiagnosisClaimMapper mapper;

    public FormalDiagnosisClaimService(
            TroubleshootingFormalDiagnosisClaimMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ClaimResult claim(
            long workspaceId,
            String dedupKey,
            Instant claimedAt,
            Duration lease) {
        if (workspaceId <= 0) {
            throw new IllegalArgumentException("workspaceId must be positive");
        }
        FormalDiagnosisClaim claim = claimValue(dedupKey, claimedAt, lease);
        TroubleshootingFormalDiagnosisClaimEntity existing =
                mapper.findByKey(workspaceId, claim.dedupKey());
        if (existing == null) {
            try {
                if (mapper.insert(row(workspaceId, claim)) != 1) {
                    throw conflict("formal diagnosis claim could not be acquired");
                }
                return ClaimResult.acquired(claim);
            } catch (DataIntegrityViolationException raced) {
                existing = mapper.findByKey(workspaceId, claim.dedupKey());
                if (existing == null) {
                    throw raced;
                }
            }
        }
        return recoverOrObserve(workspaceId, claim, existing);
    }

    /** Joins the Diagnosis insert transaction; failure rolls both writes back. */
    @Transactional
    public void complete(
            long workspaceId,
            FormalDiagnosisClaim claim,
            String diagnosisId,
            Instant completedAt) {
        if (claim == null || diagnosisId == null || diagnosisId.isBlank()
                || completedAt == null) {
            throw new IllegalArgumentException(
                    "claim, diagnosisId and completedAt are required");
        }
        if (mapper.complete(
                workspaceId,
                claim.dedupKey(),
                claim.claimToken(),
                diagnosisId.trim(),
                utc(completedAt)) != 1) {
            throw conflict("formal diagnosis claim ownership was lost");
        }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void release(long workspaceId, FormalDiagnosisClaim claim) {
        if (claim != null) {
            mapper.release(workspaceId, claim.dedupKey(), claim.claimToken());
        }
    }

    private ClaimResult recoverOrObserve(
            long workspaceId,
            FormalDiagnosisClaim claim,
            TroubleshootingFormalDiagnosisClaimEntity existing) {
        if (COMPLETED.equals(existing.getStatus())) {
            if (existing.getDiagnosisId() == null || existing.getDiagnosisId().isBlank()) {
                throw conflict("completed formal diagnosis claim has no diagnosis identity");
            }
            return ClaimResult.completed(existing.getDiagnosisId().trim());
        }
        if (!PROCESSING.equals(existing.getStatus())) {
            throw conflict("formal diagnosis claim has an invalid state");
        }
        Instant expiresAt = instant(existing.getLeaseExpiresAt());
        if (expiresAt != null && expiresAt.isAfter(claim.claimedAt())) {
            return ClaimResult.inProgress();
        }
        int updated = mapper.takeOver(
                workspaceId,
                claim.dedupKey(),
                existing.getClaimToken(),
                utc(claim.claimedAt()),
                claim.claimToken(),
                utc(claim.expiresAt()));
        if (updated == 1) {
            return ClaimResult.acquired(claim);
        }
        TroubleshootingFormalDiagnosisClaimEntity winner =
                mapper.findByKey(workspaceId, claim.dedupKey());
        if (winner != null && COMPLETED.equals(winner.getStatus())) {
            if (winner.getDiagnosisId() == null || winner.getDiagnosisId().isBlank()) {
                throw conflict("completed formal diagnosis claim has no diagnosis identity");
            }
            return ClaimResult.completed(winner.getDiagnosisId().trim());
        }
        return ClaimResult.inProgress();
    }

    private FormalDiagnosisClaim claimValue(
            String dedupKey,
            Instant claimedAt,
            Duration lease) {
        if (claimedAt == null || lease == null || lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException(
                    "a positive formal diagnosis claim lease is required");
        }
        return new FormalDiagnosisClaim(
                dedupKey,
                "claim-" + UUID.randomUUID().toString().replace("-", ""),
                claimedAt,
                claimedAt.plus(lease));
    }

    private TroubleshootingFormalDiagnosisClaimEntity row(
            long workspaceId,
            FormalDiagnosisClaim claim) {
        TroubleshootingFormalDiagnosisClaimEntity row =
                new TroubleshootingFormalDiagnosisClaimEntity();
        row.setWorkspaceId(workspaceId);
        row.setDedupKey(claim.dedupKey());
        row.setClaimToken(claim.claimToken());
        row.setStatus(PROCESSING);
        row.setClaimedAt(utc(claim.claimedAt()));
        row.setLeaseExpiresAt(utc(claim.expiresAt()));
        row.setCreateTime(utc(claim.claimedAt()));
        row.setUpdateTime(utc(claim.claimedAt()));
        return row;
    }

    private LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private MateClawException conflict(String message) {
        return new MateClawException(
                "err.troubleshooting.formal_diagnosis_claim_conflict", 409, message);
    }

    public enum ClaimState {
        ACQUIRED,
        IN_PROGRESS,
        COMPLETED
    }

    public record ClaimResult(
            ClaimState state,
            FormalDiagnosisClaim claim,
            String diagnosisId) {

        public ClaimResult {
            if (state == null
                    || (state == ClaimState.ACQUIRED) != (claim != null)
                    || (state == ClaimState.COMPLETED)
                            != (diagnosisId != null && !diagnosisId.isBlank())) {
                throw new IllegalArgumentException(
                        "formal diagnosis claim result is inconsistent");
            }
        }

        public static ClaimResult acquired(FormalDiagnosisClaim claim) {
            return new ClaimResult(ClaimState.ACQUIRED, claim, null);
        }

        public static ClaimResult inProgress() {
            return new ClaimResult(ClaimState.IN_PROGRESS, null, null);
        }

        public static ClaimResult completed(String diagnosisId) {
            return new ClaimResult(ClaimState.COMPLETED, null, diagnosisId);
        }
    }
}
