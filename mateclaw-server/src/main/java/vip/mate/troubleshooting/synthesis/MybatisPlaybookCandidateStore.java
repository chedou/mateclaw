package vip.mate.troubleshooting.synthesis;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.model.TroubleshootingPlaybookCandidateEntity;
import vip.mate.troubleshooting.repository.TroubleshootingPlaybookCandidateMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** MyBatis implementation of the generationKey idempotency boundary. */
@Component
public class MybatisPlaybookCandidateStore
        implements PlaybookCandidateStore, PlaybookCandidateReader {

    private final TroubleshootingPlaybookCandidateMapper mapper;
    private final ObjectMapper objectMapper;

    public MybatisPlaybookCandidateStore(
            TroubleshootingPlaybookCandidateMapper mapper,
            ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    // A failed unique-key insert aborts the current transaction on Kingbase/PostgreSQL.
    // Suspend any caller transaction so the recovery SELECT gets a fresh SQL session.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public StoredCandidate saveOrGet(long workspaceId, PlaybookKnowledgeRecord candidate) {
        if (workspaceId <= 0 || candidate == null) {
            throw new IllegalArgumentException("workspaceId and candidate are required");
        }
        TroubleshootingPlaybookCandidateEntity existing = find(
                workspaceId, candidate.draft().generationKey());
        if (existing != null) {
            return new StoredCandidate(read(existing), false);
        }
        TroubleshootingPlaybookCandidateEntity entity = entity(workspaceId, candidate);
        try {
            mapper.insert(entity);
            return new StoredCandidate(candidate, true);
        } catch (DataIntegrityViolationException raced) {
            existing = find(workspaceId, candidate.draft().generationKey());
            if (existing == null) {
                throw raced;
            }
            return new StoredCandidate(read(existing), false);
        }
    }

    @Override
    public java.util.List<PlaybookKnowledgeRecord> list(long workspaceId, int limit) {
        if (workspaceId <= 0) {
            throw new IllegalArgumentException("workspaceId must be positive");
        }
        int capped = Math.min(Math.max(limit, 1), 200);
        return mapper.selectList(
                        new LambdaQueryWrapper<TroubleshootingPlaybookCandidateEntity>()
                                .eq(TroubleshootingPlaybookCandidateEntity::getWorkspaceId,
                                        workspaceId)
                                .eq(TroubleshootingPlaybookCandidateEntity::getDeleted, 0)
                                .orderByDesc(TroubleshootingPlaybookCandidateEntity::getId)
                                .last("LIMIT " + capped))
                .stream()
                .map(this::read)
                .toList();
    }

    private TroubleshootingPlaybookCandidateEntity entity(
            long workspaceId,
            PlaybookKnowledgeRecord candidate) {
        LocalDateTime now = LocalDateTime.ofInstant(candidate.createdAt(), ZoneOffset.UTC);
        TroubleshootingPlaybookCandidateEntity entity =
                new TroubleshootingPlaybookCandidateEntity();
        entity.setWorkspaceId(workspaceId);
        entity.setRecordId(candidate.recordId());
        entity.setGenerationKey(candidate.draft().generationKey());
        entity.setSourceIncidentId(candidate.draft().sourceIncident());
        entity.setSystem(candidate.draft().proposedSelector().system());
        entity.setService(candidate.service());
        entity.setScenarioKey(candidate.draft().proposedSelector().scenarioKey());
        entity.setOrigin(candidate.origin());
        entity.setReviewStatus(candidate.reviewStatus());
        entity.setValidationStatus(candidate.validationStatus());
        entity.setContractVersion(PlaybookDraft.CONTRACT_VERSION);
        entity.setFixtureMode(candidate.fixtureMode());
        entity.setAggregateJson(write(candidate));
        entity.setDeleted(0);
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        return entity;
    }

    private TroubleshootingPlaybookCandidateEntity find(long workspaceId, String generationKey) {
        return mapper.selectOne(
                new LambdaQueryWrapper<TroubleshootingPlaybookCandidateEntity>()
                        .eq(TroubleshootingPlaybookCandidateEntity::getWorkspaceId, workspaceId)
                        .eq(TroubleshootingPlaybookCandidateEntity::getGenerationKey, generationKey)
                        .eq(TroubleshootingPlaybookCandidateEntity::getDeleted, 0));
    }

    private String write(PlaybookKnowledgeRecord candidate) {
        try {
            return objectMapper.writeValueAsString(candidate);
        } catch (JsonProcessingException error) {
            throw serialization("serialize", error);
        }
    }

    private PlaybookKnowledgeRecord read(TroubleshootingPlaybookCandidateEntity entity) {
        try {
            return objectMapper.readValue(entity.getAggregateJson(), PlaybookKnowledgeRecord.class);
        } catch (JsonProcessingException error) {
            throw serialization("deserialize", error);
        }
    }

    private MateClawException serialization(String operation, Exception error) {
        return new MateClawException(
                "err.troubleshooting.contract_serialization", 500,
                "failed to " + operation + " Playbook candidate: " + error.getMessage());
    }
}
