package vip.mate.troubleshooting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
        if (!"candidate".equals(sop.status()) || sop.verified()) {
            throw new MateClawException(
                    "err.troubleshooting.sop_initial_state", 409,
                    "a new SOP must start as candidate with verified=false; "
                            + "promotion is a separate reviewed transition");
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

    /**
     * Lists the route registry, newest first.
     *
     * <p>Returns indexed columns rather than parsed aggregates: curating 146
     * error codes means paging through them constantly, and deserializing every
     * SOP to render a list would make browsing the knowledge base the slowest
     * screen in the console.</p>
     */
    public java.util.List<SopSummary> list(
            long workspaceId, String status, String system, int limit) {
        if (workspaceId <= 0) {
            throw new IllegalArgumentException("workspaceId must be positive");
        }
        int capped = Math.min(Math.max(limit, 1), 500);
        LambdaQueryWrapper<TroubleshootingSopEntity> query =
                new LambdaQueryWrapper<TroubleshootingSopEntity>()
                        .eq(TroubleshootingSopEntity::getWorkspaceId, workspaceId)
                        .eq(TroubleshootingSopEntity::getDeleted, 0)
                        .orderByDesc(TroubleshootingSopEntity::getId)
                        .last("LIMIT " + capped);
        if (status != null && !status.isBlank()) {
            query.eq(TroubleshootingSopEntity::getStatus, status.trim());
        }
        if (system != null && !system.isBlank()) {
            query.eq(TroubleshootingSopEntity::getSystem, system.trim());
        }
        return mapper.selectList(query).stream().map(SopSummary::from).toList();
    }

    /**
     * Moves a SOP along its review lifecycle.
     *
     * <p>Only {@code candidate → approved} and {@code approved → deprecated},
     * and only forwards. The deterministic path acts on an approved SOP without
     * a human in the loop, so promotion has to be a deliberate review decision
     * rather than a flag anyone can flip back and forth. Deprecation leaves the
     * review trail intact; publishing a replacement for the same route requires
     * a separate version model because the current registry keeps route keys unique.</p>
     *
     * <p>Approving also sets {@code verified}, because {@link SopEntry#operational()}
     * requires both — a half-promoted SOP would keep abstaining while looking
     * approved, which is the most confusing failure available here.</p>
     */
    @Transactional
    public SopEntry updateStatus(
            long workspaceId, String system, String errorCode, String targetStatus) {
        SopEntry current = find(workspaceId, system, errorCode);
        if (current == null) {
            throw new MateClawException(
                    "err.troubleshooting.sop_not_found", 404,
                    "no SOP registered for " + system + ":" + errorCode);
        }
        String target = targetStatus == null ? "" : targetStatus.trim().toLowerCase(Locale.ROOT);
        boolean legal = ("approved".equals(target) && "candidate".equals(current.status()))
                || ("deprecated".equals(target) && "approved".equals(current.status()));
        if (!legal) {
            throw new MateClawException(
                    "err.troubleshooting.sop_status_transition", 409,
                    "illegal SOP transition " + current.status() + " -> " + target
                            + "; only candidate->approved and approved->deprecated are allowed");
        }

        SopEntry updated = new SopEntry(
                current.sopId(), current.contractVersion(), current.system(), current.errorCode(),
                current.service(), current.title(), current.cause(), current.category(),
                current.ownerTeam(), target, "approved".equals(target),
                current.evidenceRequests(), current.anomalyCriteria(),
                current.diagnosisRules(), current.actions());

        TroubleshootingSopEntity patch = new TroubleshootingSopEntity();
        patch.setStatus(updated.status());
        patch.setVerified(updated.verified());
        patch.setAggregateJson(json(updated));
        patch.setUpdateTime(LocalDateTime.now(ZoneOffset.UTC));
        int changed = mapper.update(patch,
                new LambdaUpdateWrapper<TroubleshootingSopEntity>()
                        .eq(TroubleshootingSopEntity::getWorkspaceId, workspaceId)
                        .eq(TroubleshootingSopEntity::getRouteKey, current.routingKey())
                        .eq(TroubleshootingSopEntity::getDeleted, 0));
        if (changed != 1) {
            throw new MateClawException(
                    "err.troubleshooting.sop_status_transition", 409,
                    "SOP changed concurrently; reload before promoting it");
        }
        return updated;
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
