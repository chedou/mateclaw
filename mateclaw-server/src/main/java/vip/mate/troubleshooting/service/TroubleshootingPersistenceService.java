package vip.mate.troubleshooting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.KnowledgeCandidate;
import vip.mate.troubleshooting.model.KnowledgePublicationStatus;
import vip.mate.troubleshooting.model.TroubleshootingDiagnosisEntity;
import vip.mate.troubleshooting.model.TroubleshootingKnowledgeOutboxEntity;
import vip.mate.troubleshooting.repository.TroubleshootingDiagnosisMapper;
import vip.mate.troubleshooting.repository.TroubleshootingKnowledgeOutboxMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Transaction boundary for diagnosis aggregates and knowledge-candidate outbox rows.
 * The rule engine never depends on this service and remains database-free.
 */
@Service
public class TroubleshootingPersistenceService {

    private final TroubleshootingDiagnosisMapper diagnosisMapper;
    private final TroubleshootingKnowledgeOutboxMapper outboxMapper;
    private final ObjectMapper objectMapper;

    public TroubleshootingPersistenceService(
            TroubleshootingDiagnosisMapper diagnosisMapper,
            TroubleshootingKnowledgeOutboxMapper outboxMapper,
            ObjectMapper objectMapper) {
        this.diagnosisMapper = diagnosisMapper;
        this.outboxMapper = outboxMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public StoredDiagnosis createOrGet(
            long workspaceId,
            Diagnosis diagnosis,
            Instant receivedAt) {
        validateWorkspace(workspaceId);
        Optional<String> dedupKey = IncidentDeduplicationKey.create(
                diagnosis.incident(), diagnosis.rehearsal(), receivedAt);
        if (dedupKey.isPresent()) {
            TroubleshootingDiagnosisEntity existing = findByDedupKey(workspaceId, dedupKey.get());
            if (existing != null) {
                return stored(existing, false);
            }
        }

        TroubleshootingDiagnosisEntity entity = entity(workspaceId, diagnosis, dedupKey.orElse(null));
        try {
            diagnosisMapper.insert(entity);
            return new StoredDiagnosis(diagnosis, 0, true);
        } catch (DuplicateKeyException collision) {
            if (dedupKey.isEmpty()) {
                throw collision;
            }
            TroubleshootingDiagnosisEntity existing = findByDedupKey(workspaceId, dedupKey.get());
            if (existing == null) {
                throw collision;
            }
            return stored(existing, false);
        }
    }

    public StoredDiagnosis get(long workspaceId, String diagnosisId) {
        validateWorkspace(workspaceId);
        TroubleshootingDiagnosisEntity entity = diagnosisMapper.selectOne(
                new LambdaQueryWrapper<TroubleshootingDiagnosisEntity>()
                        .eq(TroubleshootingDiagnosisEntity::getWorkspaceId, workspaceId)
                        .eq(TroubleshootingDiagnosisEntity::getDiagnosisId, diagnosisId)
                        .eq(TroubleshootingDiagnosisEntity::getDeleted, 0));
        if (entity == null) {
            throw new MateClawException(
                    "err.troubleshooting.diagnosis_not_found",
                    404,
                    "troubleshooting diagnosis not found: " + diagnosisId);
        }
        return stored(entity, false);
    }

    @Transactional
    public StoredDiagnosis update(long workspaceId, Diagnosis diagnosis, int expectedVersion) {
        updateAggregate(workspaceId, diagnosis, expectedVersion);
        return new StoredDiagnosis(diagnosis, expectedVersion + 1, false);
    }

    /**
     * Lists queue rows for one workspace, newest first.
     *
     * <p>Reads indexed columns only — the stored aggregate is never parsed here,
     * so rendering a queue costs the same whether a diagnosis carries three
     * pieces of evidence or thirty. {@code status} and {@code system} narrow the
     * list when supplied; a blank value means "no filter" rather than "match
     * blank", because that is what an empty console filter box means.</p>
     */
    public java.util.List<DiagnosisSummary> list(
            long workspaceId, String status, String system, int limit) {
        validateWorkspace(workspaceId);
        int capped = Math.min(Math.max(limit, 1), 200);
        LambdaQueryWrapper<TroubleshootingDiagnosisEntity> query =
                new LambdaQueryWrapper<TroubleshootingDiagnosisEntity>()
                        .eq(TroubleshootingDiagnosisEntity::getWorkspaceId, workspaceId)
                        .eq(TroubleshootingDiagnosisEntity::getDeleted, 0)
                        .orderByDesc(TroubleshootingDiagnosisEntity::getId)
                        .last("LIMIT " + capped);
        if (status != null && !status.isBlank()) {
            query.eq(TroubleshootingDiagnosisEntity::getStatus, status.trim());
        }
        if (system != null && !system.isBlank()) {
            query.eq(TroubleshootingDiagnosisEntity::getSystem, system.trim());
        }
        return diagnosisMapper.selectList(query).stream()
                .map(DiagnosisSummary::from)
                .toList();
    }

    @Transactional
    public StoredDiagnosis updateAndEnqueue(
            long workspaceId,
            Diagnosis diagnosis,
            int expectedVersion,
            KnowledgeCandidate candidate) {
        if (candidate == null) {
            throw new IllegalArgumentException("candidate must not be null");
        }
        if (!candidate.sourceDiagnosisId().equals(diagnosis.diagnosisId())) {
            throw new IllegalArgumentException("candidate must belong to the diagnosis being persisted");
        }
        if (diagnosis.knowledgeCandidates().stream()
                .noneMatch(item -> item.candidateId().equals(candidate.candidateId()))) {
            throw new IllegalArgumentException("candidate must already be part of the diagnosis aggregate");
        }
        updateAggregate(workspaceId, diagnosis, expectedVersion);
        enqueueIfAbsent(workspaceId, candidate);
        return new StoredDiagnosis(diagnosis, expectedVersion + 1, false);
    }

    private void updateAggregate(long workspaceId, Diagnosis diagnosis, int expectedVersion) {
        validateWorkspace(workspaceId);
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        LocalDateTime now = utcNow();
        int changed = diagnosisMapper.update(
                null,
                new LambdaUpdateWrapper<TroubleshootingDiagnosisEntity>()
                        .eq(TroubleshootingDiagnosisEntity::getWorkspaceId, workspaceId)
                        .eq(TroubleshootingDiagnosisEntity::getDiagnosisId, diagnosis.diagnosisId())
                        .eq(TroubleshootingDiagnosisEntity::getVersion, expectedVersion)
                        .eq(TroubleshootingDiagnosisEntity::getDeleted, 0)
                        .set(TroubleshootingDiagnosisEntity::getStatus, diagnosis.status().name())
                        .set(TroubleshootingDiagnosisEntity::getContractVersion, diagnosis.contractVersion())
                        .set(TroubleshootingDiagnosisEntity::getAggregateJson, json(diagnosis))
                        .set(TroubleshootingDiagnosisEntity::getVersion, expectedVersion + 1)
                        .set(TroubleshootingDiagnosisEntity::getUpdateTime, now));
        if (changed != 1) {
            throw new MateClawException(
                    "err.troubleshooting.optimistic_lock_conflict",
                    409,
                    "diagnosis changed concurrently; reload before applying the transition");
        }
    }

    private void enqueueIfAbsent(long workspaceId, KnowledgeCandidate candidate) {
        String publicationId = "publication-" + candidate.candidateId();
        TroubleshootingKnowledgeOutboxEntity existing = outboxMapper.selectOne(
                new LambdaQueryWrapper<TroubleshootingKnowledgeOutboxEntity>()
                        .eq(TroubleshootingKnowledgeOutboxEntity::getWorkspaceId, workspaceId)
                        .eq(TroubleshootingKnowledgeOutboxEntity::getPublicationId, publicationId)
                        .eq(TroubleshootingKnowledgeOutboxEntity::getDeleted, 0));
        if (existing != null) {
            return;
        }
        LocalDateTime now = utcNow();
        TroubleshootingKnowledgeOutboxEntity outbox = new TroubleshootingKnowledgeOutboxEntity();
        outbox.setWorkspaceId(workspaceId);
        outbox.setPublicationId(publicationId);
        outbox.setDiagnosisId(candidate.sourceDiagnosisId());
        outbox.setCandidateId(candidate.candidateId());
        outbox.setEventType("KNOWLEDGE_CANDIDATE_CREATED");
        outbox.setContractVersion(candidate.contractVersion());
        outbox.setPayloadJson(json(candidate));
        outbox.setStatus(KnowledgePublicationStatus.PENDING);
        outbox.setAttempts(0);
        outbox.setDeleted(0);
        outbox.setCreateTime(now);
        outbox.setUpdateTime(now);
        outboxMapper.insert(outbox);
    }

    private TroubleshootingDiagnosisEntity findByDedupKey(long workspaceId, String dedupKey) {
        return diagnosisMapper.selectOne(
                new LambdaQueryWrapper<TroubleshootingDiagnosisEntity>()
                        .eq(TroubleshootingDiagnosisEntity::getWorkspaceId, workspaceId)
                        .eq(TroubleshootingDiagnosisEntity::getDedupKey, dedupKey)
                        .eq(TroubleshootingDiagnosisEntity::getDeleted, 0));
    }

    private TroubleshootingDiagnosisEntity entity(
            long workspaceId,
            Diagnosis diagnosis,
            String dedupKey) {
        LocalDateTime now = utcNow();
        TroubleshootingDiagnosisEntity entity = new TroubleshootingDiagnosisEntity();
        entity.setWorkspaceId(workspaceId);
        entity.setDiagnosisId(diagnosis.diagnosisId());
        entity.setCaseId(diagnosis.caseId());
        entity.setRunId(diagnosis.runId());
        entity.setSystem(diagnosis.incident().system());
        entity.setErrorCode(diagnosis.incident().errorCode());
        entity.setService(diagnosis.incident().service());
        entity.setDedupKey(dedupKey);
        entity.setRehearsal(diagnosis.rehearsal());
        entity.setStatus(diagnosis.status().name());
        entity.setContractVersion(diagnosis.contractVersion());
        entity.setAggregateJson(json(diagnosis));
        entity.setVersion(0);
        entity.setDeleted(0);
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        return entity;
    }

    private StoredDiagnosis stored(TroubleshootingDiagnosisEntity entity, boolean created) {
        try {
            Diagnosis diagnosis = objectMapper.readValue(entity.getAggregateJson(), Diagnosis.class);
            return new StoredDiagnosis(diagnosis, entity.getVersion(), created);
        } catch (JsonProcessingException error) {
            throw serializationError("deserialize diagnosis", error);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw serializationError("serialize troubleshooting aggregate", error);
        }
    }

    private MateClawException serializationError(String operation, Exception error) {
        return new MateClawException(
                "err.troubleshooting.contract_serialization",
                500,
                "failed to " + operation + ": " + error.getMessage());
    }

    private void validateWorkspace(long workspaceId) {
        if (workspaceId <= 0) {
            throw new IllegalArgumentException("workspaceId must be positive");
        }
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
