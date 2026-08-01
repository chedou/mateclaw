package vip.mate.troubleshooting.evidence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.model.TroubleshootingGuanceEvidenceAcceptanceEntity;
import vip.mate.troubleshooting.repository.TroubleshootingGuanceEvidenceAcceptanceMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/** MyBatis implementation of the immutable scope + binding fingerprint boundary. */
@Component
public class MybatisGuanceEvidenceAcceptanceStore
        implements GuanceEvidenceAcceptanceStore {

    private final TroubleshootingGuanceEvidenceAcceptanceMapper mapper;
    private final ObjectMapper objectMapper;

    public MybatisGuanceEvidenceAcceptanceStore(
            TroubleshootingGuanceEvidenceAcceptanceMapper mapper,
            ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<GuanceEvidenceAcceptance> findByFingerprint(
            long workspaceId,
            String scopeKey,
            String bindingFingerprint) {
        validateWorkspace(workspaceId);
        return Optional.ofNullable(mapper.selectOne(
                        base(workspaceId, scopeKey)
                                .eq(
                                        TroubleshootingGuanceEvidenceAcceptanceEntity
                                                ::getBindingFingerprint,
                                        required(bindingFingerprint, "bindingFingerprint"))))
                .map(this::read);
    }

    @Override
    public Optional<GuanceEvidenceAcceptance> findLatest(
            long workspaceId,
            String scopeKey) {
        validateWorkspace(workspaceId);
        return Optional.ofNullable(mapper.selectOne(
                        base(workspaceId, scopeKey)
                                .orderByDesc(
                                        TroubleshootingGuanceEvidenceAcceptanceEntity::getId)
                                .last("LIMIT 1")))
                .map(this::read);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public StoredAcceptance saveOrGet(
            long workspaceId,
            String scopeKey,
            GuanceEvidenceAcceptance acceptance) {
        validateWorkspace(workspaceId);
        String normalizedScope = required(scopeKey, "scopeKey");
        if (acceptance == null) {
            throw new IllegalArgumentException("acceptance is required");
        }
        Optional<GuanceEvidenceAcceptance> existing = findByFingerprint(
                workspaceId,
                normalizedScope,
                acceptance.bindingFingerprint());
        if (existing.isPresent()) {
            return new StoredAcceptance(existing.orElseThrow(), false);
        }
        TroubleshootingGuanceEvidenceAcceptanceEntity entity =
                entity(workspaceId, normalizedScope, acceptance);
        try {
            mapper.insert(entity);
            return new StoredAcceptance(acceptance, true);
        } catch (DataIntegrityViolationException raced) {
            existing = findByFingerprint(
                    workspaceId,
                    normalizedScope,
                    acceptance.bindingFingerprint());
            if (existing.isEmpty()) {
                throw raced;
            }
            return new StoredAcceptance(existing.orElseThrow(), false);
        }
    }

    private LambdaQueryWrapper<TroubleshootingGuanceEvidenceAcceptanceEntity> base(
            long workspaceId,
            String scopeKey) {
        return new LambdaQueryWrapper<TroubleshootingGuanceEvidenceAcceptanceEntity>()
                .eq(
                        TroubleshootingGuanceEvidenceAcceptanceEntity::getWorkspaceId,
                        workspaceId)
                .eq(
                        TroubleshootingGuanceEvidenceAcceptanceEntity::getScopeKey,
                        required(scopeKey, "scopeKey"))
                .eq(
                        TroubleshootingGuanceEvidenceAcceptanceEntity::getDeleted,
                        0);
    }

    private TroubleshootingGuanceEvidenceAcceptanceEntity entity(
            long workspaceId,
            String scopeKey,
            GuanceEvidenceAcceptance acceptance) {
        TroubleshootingGuanceEvidenceAcceptanceEntity entity =
                new TroubleshootingGuanceEvidenceAcceptanceEntity();
        entity.setWorkspaceId(workspaceId);
        entity.setAcceptanceId(acceptance.acceptanceId());
        entity.setScopeKey(scopeKey);
        entity.setBindingFingerprint(acceptance.bindingFingerprint());
        entity.setSystem(acceptance.system());
        entity.setService(acceptance.service());
        entity.setAggregateJson(write(acceptance));
        entity.setVersion(0);
        entity.setDeleted(0);
        LocalDateTime acceptedAt = LocalDateTime.ofInstant(
                acceptance.acceptedAt(), ZoneOffset.UTC);
        entity.setCreateTime(acceptedAt);
        entity.setUpdateTime(acceptedAt);
        return entity;
    }

    private String write(GuanceEvidenceAcceptance acceptance) {
        try {
            return objectMapper.writeValueAsString(acceptance);
        } catch (JsonProcessingException error) {
            throw serialization("serialize", error);
        }
    }

    private GuanceEvidenceAcceptance read(
            TroubleshootingGuanceEvidenceAcceptanceEntity entity) {
        try {
            return objectMapper.readValue(
                    entity.getAggregateJson(), GuanceEvidenceAcceptance.class);
        } catch (JsonProcessingException error) {
            throw serialization("deserialize", error);
        }
    }

    private void validateWorkspace(long workspaceId) {
        if (workspaceId <= 0) {
            throw new IllegalArgumentException("workspaceId must be positive");
        }
    }

    private String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private MateClawException serialization(String operation, Exception error) {
        return new MateClawException(
                "err.troubleshooting.contract_serialization",
                500,
                "failed to " + operation
                        + " Guance acceptance: " + error.getMessage());
    }
}
