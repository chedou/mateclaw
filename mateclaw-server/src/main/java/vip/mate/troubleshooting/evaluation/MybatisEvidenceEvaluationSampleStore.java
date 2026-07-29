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
import vip.mate.troubleshooting.model.TroubleshootingEvidenceEvaluationSampleEntity;
import vip.mate.troubleshooting.repository.TroubleshootingEvidenceEvaluationSampleMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** MyBatis implementation of the workspace + sampleKey idempotency boundary. */
@Component
public class MybatisEvidenceEvaluationSampleStore
        implements EvidenceEvaluationSampleStore {

    private final TroubleshootingEvidenceEvaluationSampleMapper mapper;
    private final ObjectMapper objectMapper;

    public MybatisEvidenceEvaluationSampleStore(
            TroubleshootingEvidenceEvaluationSampleMapper mapper,
            ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<EvidenceEvaluationSample> findBySampleKey(
            long workspaceId,
            String sampleKey) {
        validateWorkspace(workspaceId);
        if (sampleKey == null || sampleKey.isBlank()) {
            throw new IllegalArgumentException("sampleKey is required");
        }
        return Optional.ofNullable(findByKey(workspaceId, sampleKey.trim()))
                .map(this::read);
    }

    @Override
    public Optional<EvidenceEvaluationSample> get(long workspaceId, String sampleId) {
        validateWorkspace(workspaceId);
        if (sampleId == null || sampleId.isBlank()) {
            throw new IllegalArgumentException("sampleId is required");
        }
        TroubleshootingEvidenceEvaluationSampleEntity entity = mapper.selectOne(
                new LambdaQueryWrapper<TroubleshootingEvidenceEvaluationSampleEntity>()
                        .eq(TroubleshootingEvidenceEvaluationSampleEntity::getWorkspaceId, workspaceId)
                        .eq(TroubleshootingEvidenceEvaluationSampleEntity::getSampleId, sampleId.trim())
                        .eq(TroubleshootingEvidenceEvaluationSampleEntity::getDeleted, 0));
        return Optional.ofNullable(entity).map(this::read);
    }

    @Override
    // A unique-key violation aborts the current transaction on Kingbase/PostgreSQL.
    // Suspend caller transactions so the recovery SELECT runs in a fresh SQL session.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public StoredSample saveOrGet(long workspaceId, EvidenceEvaluationSample sample) {
        validateWorkspace(workspaceId);
        if (sample == null) {
            throw new IllegalArgumentException("sample is required");
        }
        TroubleshootingEvidenceEvaluationSampleEntity existing =
                findByKey(workspaceId, sample.sampleKey());
        if (existing != null) {
            return new StoredSample(read(existing), false);
        }

        TroubleshootingEvidenceEvaluationSampleEntity entity = entity(workspaceId, sample);
        try {
            mapper.insert(entity);
            return new StoredSample(sample, true);
        } catch (DataIntegrityViolationException raced) {
            existing = findByKey(workspaceId, sample.sampleKey());
            if (existing == null) {
                throw raced;
            }
            return new StoredSample(read(existing), false);
        }
    }

    @Override
    @Transactional
    public EvidenceEvaluationSample finalizeReference(
            long workspaceId,
            EvidenceEvaluationSample sample,
            int expectedVersion) {
        validateWorkspace(workspaceId);
        if (sample == null
                || sample.referenceStatus()
                != EvidenceEvaluationSample.ReferenceStatus.READY_FOR_EVALUATION) {
            throw new IllegalArgumentException("a finalized evaluation sample is required");
        }
        if (expectedVersion < 0 || sample.version() != expectedVersion + 1) {
            throw new IllegalArgumentException("sample version must advance by exactly one");
        }
        LocalDateTime updatedAt = LocalDateTime.ofInstant(
                sample.finalizedAt(), ZoneOffset.UTC);
        int changed = mapper.update(
                null,
                new LambdaUpdateWrapper<TroubleshootingEvidenceEvaluationSampleEntity>()
                        .eq(TroubleshootingEvidenceEvaluationSampleEntity::getWorkspaceId, workspaceId)
                        .eq(TroubleshootingEvidenceEvaluationSampleEntity::getSampleId, sample.sampleId())
                        .eq(TroubleshootingEvidenceEvaluationSampleEntity::getVersion, expectedVersion)
                        .eq(TroubleshootingEvidenceEvaluationSampleEntity::getDeleted, 0)
                        .set(TroubleshootingEvidenceEvaluationSampleEntity::getReferenceStatus,
                                sample.referenceStatus().name())
                        .set(TroubleshootingEvidenceEvaluationSampleEntity::getAggregateJson,
                                write(sample))
                        .set(TroubleshootingEvidenceEvaluationSampleEntity::getVersion,
                                sample.version())
                        .set(TroubleshootingEvidenceEvaluationSampleEntity::getUpdateTime,
                                updatedAt));
        if (changed != 1) {
            throw new MateClawException(
                    "err.troubleshooting.evaluation_sample_conflict",
                    409,
                    "evaluation sample version conflict");
        }
        return sample;
    }

    @Override
    public List<EvidenceEvaluationSample> list(
            long workspaceId,
            String diagnosisId,
            int limit) {
        validateWorkspace(workspaceId);
        int capped = Math.min(Math.max(limit, 1), 200);
        LambdaQueryWrapper<TroubleshootingEvidenceEvaluationSampleEntity> query =
                new LambdaQueryWrapper<TroubleshootingEvidenceEvaluationSampleEntity>()
                        .eq(TroubleshootingEvidenceEvaluationSampleEntity::getWorkspaceId, workspaceId)
                        .eq(TroubleshootingEvidenceEvaluationSampleEntity::getDeleted, 0)
                        .orderByDesc(TroubleshootingEvidenceEvaluationSampleEntity::getId)
                        .last("LIMIT " + capped);
        if (diagnosisId != null && !diagnosisId.isBlank()) {
            query.eq(
                    TroubleshootingEvidenceEvaluationSampleEntity::getDiagnosisId,
                    diagnosisId.trim());
        }
        return mapper.selectList(query).stream().map(this::read).toList();
    }

    private TroubleshootingEvidenceEvaluationSampleEntity findByKey(
            long workspaceId,
            String sampleKey) {
        return mapper.selectOne(
                new LambdaQueryWrapper<TroubleshootingEvidenceEvaluationSampleEntity>()
                        .eq(TroubleshootingEvidenceEvaluationSampleEntity::getWorkspaceId, workspaceId)
                        .eq(TroubleshootingEvidenceEvaluationSampleEntity::getSampleKey, sampleKey)
                        .eq(TroubleshootingEvidenceEvaluationSampleEntity::getDeleted, 0));
    }

    private TroubleshootingEvidenceEvaluationSampleEntity entity(
            long workspaceId,
            EvidenceEvaluationSample sample) {
        TroubleshootingEvidenceEvaluationSampleEntity entity =
                new TroubleshootingEvidenceEvaluationSampleEntity();
        entity.setWorkspaceId(workspaceId);
        entity.setSampleId(sample.sampleId());
        entity.setSampleKey(sample.sampleKey());
        entity.setDiagnosisId(sample.diagnosisId());
        entity.setSystem(sample.system());
        entity.setService(sample.service());
        entity.setScenarioKey(sample.scenarioKey());
        entity.setSourcePlatform(sample.sourcePlatform().name());
        entity.setEvidenceStage(sample.evidence().stage().name());
        entity.setReferenceStatus(sample.referenceStatus().name());
        entity.setFixtureMode(sample.evidence().fixtureMode());
        entity.setDiagnosisFixtureMode(sample.diagnosisFixtureMode());
        entity.setAggregateJson(write(sample));
        entity.setVersion(sample.version());
        entity.setDeleted(0);
        LocalDateTime createdAt = LocalDateTime.ofInstant(
                sample.capturedAt(), ZoneOffset.UTC);
        entity.setCreateTime(createdAt);
        entity.setUpdateTime(createdAt);
        return entity;
    }

    private String write(EvidenceEvaluationSample sample) {
        try {
            return objectMapper.writeValueAsString(sample);
        } catch (JsonProcessingException error) {
            throw serialization("serialize", error);
        }
    }

    private EvidenceEvaluationSample read(
            TroubleshootingEvidenceEvaluationSampleEntity entity) {
        try {
            return objectMapper.readValue(
                    entity.getAggregateJson(), EvidenceEvaluationSample.class);
        } catch (JsonProcessingException error) {
            throw serialization("deserialize", error);
        }
    }

    private void validateWorkspace(long workspaceId) {
        if (workspaceId <= 0) {
            throw new IllegalArgumentException("workspaceId must be positive");
        }
    }

    private MateClawException serialization(String operation, Exception error) {
        return new MateClawException(
                "err.troubleshooting.contract_serialization",
                500,
                "failed to " + operation + " evaluation sample: " + error.getMessage());
    }
}
