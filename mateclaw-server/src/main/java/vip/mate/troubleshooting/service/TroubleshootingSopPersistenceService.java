package vip.mate.troubleshooting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.model.SopEntry;
import vip.mate.troubleshooting.model.TroubleshootingSopEntity;
import vip.mate.troubleshooting.repository.TroubleshootingSopMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;

/** Persistent D1 route registry. A route collision fails closed. */
@Service
public class TroubleshootingSopPersistenceService {

    private final TroubleshootingSopMapper mapper;
    private final ObjectMapper objectMapper;

    public TroubleshootingSopPersistenceService(
            TroubleshootingSopMapper mapper,
            ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public SopEntry register(long workspaceId, SopEntry sop) {
        if (workspaceId <= 0) {
            throw new IllegalArgumentException("workspaceId must be positive");
        }
        TroubleshootingSopEntity existing = findEntity(workspaceId, sop.routingKey());
        if (existing != null) {
            throw collision(sop.routingKey());
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        TroubleshootingSopEntity entity = new TroubleshootingSopEntity();
        entity.setWorkspaceId(workspaceId);
        entity.setSopId(sop.sopId());
        entity.setRouteKey(sop.routingKey());
        entity.setSystem(sop.system());
        entity.setErrorCode(sop.errorCode());
        entity.setService(sop.service());
        entity.setStatus(sop.status());
        entity.setVerified(sop.verified());
        entity.setContractVersion(sop.contractVersion());
        entity.setAggregateJson(json(sop));
        entity.setVersion(0);
        entity.setDeleted(0);
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        try {
            mapper.insert(entity);
        } catch (DuplicateKeyException ignored) {
            throw collision(sop.routingKey());
        }
        return sop;
    }

    public SopEntry find(long workspaceId, String system, String errorCode) {
        String routeKey = system.trim().toLowerCase(Locale.ROOT) + ":" + errorCode.trim();
        TroubleshootingSopEntity entity = findEntity(workspaceId, routeKey);
        if (entity == null) {
            return null;
        }
        try {
            return objectMapper.readValue(entity.getAggregateJson(), SopEntry.class);
        } catch (JsonProcessingException error) {
            throw new MateClawException(
                    "err.troubleshooting.contract_serialization",
                    500,
                    "failed to deserialize SOP: " + error.getMessage());
        }
    }

    private TroubleshootingSopEntity findEntity(long workspaceId, String routeKey) {
        return mapper.selectOne(
                new LambdaQueryWrapper<TroubleshootingSopEntity>()
                        .eq(TroubleshootingSopEntity::getWorkspaceId, workspaceId)
                        .eq(TroubleshootingSopEntity::getRouteKey, routeKey)
                        .eq(TroubleshootingSopEntity::getDeleted, 0));
    }

    private String json(SopEntry sop) {
        try {
            return objectMapper.writeValueAsString(sop);
        } catch (JsonProcessingException error) {
            throw new MateClawException(
                    "err.troubleshooting.contract_serialization",
                    500,
                    "failed to serialize SOP: " + error.getMessage());
        }
    }

    private MateClawException collision(String routeKey) {
        return new MateClawException(
                "err.troubleshooting.sop_key_collision",
                409,
                "SOP routing key collision: " + routeKey);
    }
}
