package vip.mate.troubleshooting.evaluation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.model.TroubleshootingBaselineEvaluationRunEntity;
import vip.mate.troubleshooting.repository.TroubleshootingBaselineEvaluationRunMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** MyBatis implementation of the atomic workspace + run-key baseline lease. */
@Component
public class MybatisBaselineEvaluationRunStore
        implements BaselineEvaluationRunStore {

    private static final String RUNNING = "RUNNING";

    private final TroubleshootingBaselineEvaluationRunMapper mapper;
    private final ObjectMapper objectMapper;

    public MybatisBaselineEvaluationRunStore(
            TroubleshootingBaselineEvaluationRunMapper mapper,
            ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ClaimResult claim(long workspaceId, RunClaim claim) {
        validateWorkspace(workspaceId);
        requireClaim(claim);
        TroubleshootingBaselineEvaluationRunEntity existing =
                findByKey(workspaceId, claim.runKey());
        if (existing == null) {
            try {
                mapper.insert(reservation(workspaceId, claim));
                return ClaimResult.acquired();
            } catch (DataIntegrityViolationException raced) {
                existing = findByKey(workspaceId, claim.runKey());
                if (existing == null) {
                    throw raced;
                }
            }
        }
        return recoverOrObserve(workspaceId, claim, existing);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public StoredRun complete(
            long workspaceId,
            RunClaim claim,
            BaselineEvaluationRun run) {
        validateWorkspace(workspaceId);
        requireClaim(claim);
        validateCompletion(claim, run);
        int updated = mapper.update(
                null,
                new LambdaUpdateWrapper<TroubleshootingBaselineEvaluationRunEntity>()
                        .eq(TroubleshootingBaselineEvaluationRunEntity::getWorkspaceId, workspaceId)
                        .eq(TroubleshootingBaselineEvaluationRunEntity::getRunKey, claim.runKey())
                        .eq(TroubleshootingBaselineEvaluationRunEntity::getRunStatus, RUNNING)
                        .eq(TroubleshootingBaselineEvaluationRunEntity::getClaimToken,
                                claim.claimToken())
                        .eq(TroubleshootingBaselineEvaluationRunEntity::getDeleted, 0)
                        .set(TroubleshootingBaselineEvaluationRunEntity::getRunStatus,
                                run.status().name())
                        .set(TroubleshootingBaselineEvaluationRunEntity::getModelDurationMs,
                                run.modelDurationMs())
                        .set(TroubleshootingBaselineEvaluationRunEntity::getComposedTotalMs,
                                run.composedTotalDurationMs())
                        .set(TroubleshootingBaselineEvaluationRunEntity::getResultJson,
                                write(run)));
        if (updated == 1) {
            return new StoredRun(run, true);
        }
        TroubleshootingBaselineEvaluationRunEntity existing =
                findByKey(workspaceId, claim.runKey());
        if (completed(existing)) {
            return new StoredRun(read(existing), false);
        }
        throw conflict("baseline run claim is no longer owned by this worker");
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean renew(
            long workspaceId,
            RunClaim claim,
            Instant expiresAt) {
        validateWorkspace(workspaceId);
        requireClaim(claim);
        if (expiresAt == null || !expiresAt.isAfter(claim.claimedAt())) {
            throw new IllegalArgumentException("a future claim expiration is required");
        }
        int updated = mapper.update(
                null,
                new LambdaUpdateWrapper<TroubleshootingBaselineEvaluationRunEntity>()
                        .eq(TroubleshootingBaselineEvaluationRunEntity::getWorkspaceId, workspaceId)
                        .eq(TroubleshootingBaselineEvaluationRunEntity::getRunKey, claim.runKey())
                        .eq(TroubleshootingBaselineEvaluationRunEntity::getRunStatus, RUNNING)
                        .eq(TroubleshootingBaselineEvaluationRunEntity::getClaimToken,
                                claim.claimToken())
                        .eq(TroubleshootingBaselineEvaluationRunEntity::getDeleted, 0)
                        .set(TroubleshootingBaselineEvaluationRunEntity::getReservationExpiresAt,
                                utc(expiresAt)));
        return updated == 1;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void release(long workspaceId, RunClaim claim) {
        validateWorkspace(workspaceId);
        requireClaim(claim);
        mapper.update(
                null,
                new LambdaUpdateWrapper<TroubleshootingBaselineEvaluationRunEntity>()
                        .eq(TroubleshootingBaselineEvaluationRunEntity::getWorkspaceId, workspaceId)
                        .eq(TroubleshootingBaselineEvaluationRunEntity::getRunKey, claim.runKey())
                        .eq(TroubleshootingBaselineEvaluationRunEntity::getRunStatus, RUNNING)
                        .eq(TroubleshootingBaselineEvaluationRunEntity::getClaimToken,
                                claim.claimToken())
                        .eq(TroubleshootingBaselineEvaluationRunEntity::getDeleted, 0)
                        .set(TroubleshootingBaselineEvaluationRunEntity::getReservationExpiresAt,
                                utc(claim.claimedAt())));
    }

    @Override
    public List<BaselineEvaluationRun> list(
            long workspaceId,
            String diagnosisId,
            int limit) {
        validateWorkspace(workspaceId);
        int capped = Math.min(Math.max(limit, 1), 200);
        LambdaQueryWrapper<TroubleshootingBaselineEvaluationRunEntity> query =
                new LambdaQueryWrapper<TroubleshootingBaselineEvaluationRunEntity>()
                        .eq(TroubleshootingBaselineEvaluationRunEntity::getWorkspaceId, workspaceId)
                        .eq(TroubleshootingBaselineEvaluationRunEntity::getDeleted, 0)
                        .isNotNull(TroubleshootingBaselineEvaluationRunEntity::getResultJson)
                        .orderByDesc(TroubleshootingBaselineEvaluationRunEntity::getId)
                        .last("LIMIT " + capped);
        if (diagnosisId != null && !diagnosisId.isBlank()) {
            query.eq(
                    TroubleshootingBaselineEvaluationRunEntity::getDiagnosisId,
                    diagnosisId.trim());
        }
        return mapper.selectList(query).stream().map(this::read).toList();
    }

    private ClaimResult recoverOrObserve(
            long workspaceId,
            RunClaim claim,
            TroubleshootingBaselineEvaluationRunEntity existing) {
        if (completed(existing)) {
            return ClaimResult.completed(read(existing));
        }
        if (!RUNNING.equals(existing.getRunStatus())) {
            throw serialization("read", new IllegalStateException(
                    "completed baseline row has no bounded result"));
        }
        Instant expiresAt = instant(existing.getReservationExpiresAt());
        if (expiresAt != null && expiresAt.isAfter(claim.claimedAt())) {
            return ClaimResult.inProgress();
        }
        LambdaUpdateWrapper<TroubleshootingBaselineEvaluationRunEntity> takeover =
                new LambdaUpdateWrapper<TroubleshootingBaselineEvaluationRunEntity>()
                        .eq(TroubleshootingBaselineEvaluationRunEntity::getWorkspaceId, workspaceId)
                        .eq(TroubleshootingBaselineEvaluationRunEntity::getRunKey, claim.runKey())
                        .eq(TroubleshootingBaselineEvaluationRunEntity::getRunStatus, RUNNING)
                        .eq(TroubleshootingBaselineEvaluationRunEntity::getDeleted, 0)
                        .le(TroubleshootingBaselineEvaluationRunEntity::getReservationExpiresAt,
                                utc(claim.claimedAt()))
                        .set(TroubleshootingBaselineEvaluationRunEntity::getClaimToken,
                                claim.claimToken())
                        .set(TroubleshootingBaselineEvaluationRunEntity::getReservationExpiresAt,
                                utc(claim.expiresAt()));
        if (existing.getClaimToken() == null) {
            takeover.isNull(TroubleshootingBaselineEvaluationRunEntity::getClaimToken);
        } else {
            takeover.eq(
                    TroubleshootingBaselineEvaluationRunEntity::getClaimToken,
                    existing.getClaimToken());
        }
        int updated = mapper.update(null, takeover);
        if (updated == 1) {
            return ClaimResult.acquired();
        }
        TroubleshootingBaselineEvaluationRunEntity winner =
                findByKey(workspaceId, claim.runKey());
        return completed(winner)
                ? ClaimResult.completed(read(winner))
                : ClaimResult.inProgress();
    }

    private TroubleshootingBaselineEvaluationRunEntity reservation(
            long workspaceId,
            RunClaim claim) {
        TroubleshootingBaselineEvaluationRunEntity entity =
                new TroubleshootingBaselineEvaluationRunEntity();
        entity.setWorkspaceId(workspaceId);
        entity.setRunId(claim.runId());
        entity.setRunKey(claim.runKey());
        entity.setSampleId(claim.sampleId());
        entity.setDiagnosisId(claim.diagnosisId());
        entity.setSampleVersion(claim.sampleVersion());
        entity.setSourcePlatform(claim.sourcePlatform().name());
        entity.setEvidenceFixtureMode(claim.evidenceFixtureMode());
        entity.setDiagnosisFixtureMode(claim.diagnosisFixtureMode());
        entity.setRunStatus(RUNNING);
        entity.setModelProvider(claim.modelProvider());
        entity.setModelName(claim.modelName());
        entity.setModelConfigVersion(claim.modelConfigVersion());
        entity.setClaimToken(claim.claimToken());
        entity.setReservationExpiresAt(utc(claim.expiresAt()));
        entity.setDeleted(0);
        entity.setCreateTime(utc(claim.claimedAt()));
        return entity;
    }

    private TroubleshootingBaselineEvaluationRunEntity findByKey(
            long workspaceId,
            String runKey) {
        return mapper.selectOne(
                new LambdaQueryWrapper<TroubleshootingBaselineEvaluationRunEntity>()
                        .eq(TroubleshootingBaselineEvaluationRunEntity::getWorkspaceId, workspaceId)
                        .eq(TroubleshootingBaselineEvaluationRunEntity::getRunKey, runKey)
                        .eq(TroubleshootingBaselineEvaluationRunEntity::getDeleted, 0));
    }

    private boolean completed(TroubleshootingBaselineEvaluationRunEntity entity) {
        return entity != null
                && entity.getResultJson() != null
                && !entity.getResultJson().isBlank();
    }

    private void validateCompletion(RunClaim claim, BaselineEvaluationRun run) {
        if (run == null
                || !claim.runId().equals(run.runId())
                || !claim.runKey().equals(run.runKey())
                || !claim.sampleId().equals(run.sampleId())
                || !claim.modelConfigVersion().equals(run.model().modelConfigVersion())) {
            throw new IllegalArgumentException(
                    "completed run must match the claimed immutable identity");
        }
    }

    private String write(BaselineEvaluationRun run) {
        try {
            return objectMapper.writeValueAsString(run);
        } catch (JsonProcessingException error) {
            throw serialization("serialize", error);
        }
    }

    private BaselineEvaluationRun read(
            TroubleshootingBaselineEvaluationRunEntity entity) {
        try {
            return objectMapper.readValue(entity.getResultJson(), BaselineEvaluationRun.class);
        } catch (JsonProcessingException error) {
            throw serialization("deserialize", error);
        }
    }

    private void validateWorkspace(long workspaceId) {
        if (workspaceId <= 0) {
            throw new IllegalArgumentException("workspaceId must be positive");
        }
    }

    private void requireClaim(RunClaim claim) {
        if (claim == null) {
            throw new IllegalArgumentException("run claim is required");
        }
    }

    private LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private MateClawException serialization(String operation, Exception error) {
        return new MateClawException(
                "err.troubleshooting.contract_serialization",
                500,
                "failed to " + operation + " baseline evaluation run: " + error.getMessage());
    }

    private MateClawException conflict(String message) {
        return new MateClawException(
                "err.troubleshooting.baseline_evaluation_conflict", 409, message);
    }
}
