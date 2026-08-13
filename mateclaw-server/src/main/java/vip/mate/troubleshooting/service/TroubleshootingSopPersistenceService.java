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
import vip.mate.troubleshooting.synthesis.ApprovedPlaybookVersion;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Manual candidate source store plus the deterministic route projection. */
@Service
public class TroubleshootingSopPersistenceService {

    private final TroubleshootingSopMapper mapper;
    private final TroubleshootingPlaybookVersionService playbookVersions;
    private final KnowledgeEvidenceSelectorInventory evidenceSelectorInventory;
    private final ObjectMapper objectMapper;

    public TroubleshootingSopPersistenceService(
            TroubleshootingSopMapper mapper,
            TroubleshootingPlaybookVersionService playbookVersions,
            KnowledgeEvidenceSelectorInventory evidenceSelectorInventory,
            ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.playbookVersions = playbookVersions;
        this.evidenceSelectorInventory = evidenceSelectorInventory;
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
        TroubleshootingSopEntity existing = findEntityBySopId(workspaceId, sop.sopId());
        if (existing != null) {
            throw sourceCollision(sop.sopId());
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
            throw sourceCollision(sop.sopId());
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
        int capped = Math.min(Math.max(limit, 1), 500);
        String normalizedStatus = status == null || status.isBlank()
                ? null
                : status.trim().toLowerCase(Locale.ROOT);
        // Apply status only after version rows shadow their source rows;
        // otherwise filtering "candidate" could resurrect an approved source.
        int scanLimit = normalizedStatus == null ? capped : 500;
        Map<String, SopSummary> currentBySelector = new LinkedHashMap<>();
        listEntities(workspaceId, null, system, scanLimit).stream()
                .map(SopSummary::from)
                .forEach(summary -> currentBySelector.putIfAbsent(
                        summary.routeKey(), summary));
        playbookVersions.listLatest(workspaceId, null, system, scanLimit)
                .forEach(summary -> currentBySelector.put(
                        summary.routeKey(), summary));
        return currentBySelector.values().stream()
                .filter(summary -> normalizedStatus == null
                        || normalizedStatus.equals(summary.status()))
                .sorted(Comparator.comparing(SopSummary::updateTime).reversed())
                .limit(capped)
                .toList();
    }

    /** Workspace registry numerator against the reviewed 146-selector inventory. */
    public KnowledgeEvidenceCoverage knowledgeEvidenceCoverage(long workspaceId) {
        return KnowledgeEvidenceCoverage.from(
                list(workspaceId, null, null, 500), evidenceSelectorInventory);
    }

    /**
     * Reads registry metadata and full contracts in one bounded query.
     *
     * <p>The normal registry endpoint keeps using {@link #list} and returns
     * summaries only. Knowledge qualification needs the complete manual
     * candidate contract, so it consumes this paired internal projection
     * instead of issuing one query per row.</p>
     */
    public java.util.List<SopRegistryRecord> listRecords(
            long workspaceId, String status, String system, int limit) {
        return listEntities(workspaceId, status, system, limit).stream()
                .map(entity -> new SopRegistryRecord(
                        SopSummary.from(entity), read(entity)))
                .toList();
    }

    private java.util.List<TroubleshootingSopEntity> listEntities(
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
        return mapper.selectList(query);
    }

    /**
     * Retires an already approved SOP version.
     *
     * <p>The legacy endpoint used to allow {@code candidate → approved} by
     * flipping this aggregate in place. That bypasses origin-specific
     * eligibility, fixed replay, optimistic review and the v4 invariant that
     * approval creates a new version. Candidate approval therefore fails
     * closed here, and versioned retirement must use the exact review command
     * rather than this compatibility mutation.</p>
     */
    @Transactional
    public SopEntry updateStatus(
            long workspaceId, String system, String errorCode, String targetStatus) {
        String routeKey = system.trim().toLowerCase(Locale.ROOT)
                + ":" + errorCode.trim();
        var versioned = playbookVersions.findCurrent(workspaceId, routeKey);
        SopEntry current = versioned
                .map(v -> v.playbook())
                .orElseGet(() -> {
                    TroubleshootingSopEntity entity = findOperationalEntity(
                            workspaceId, routeKey);
                    if (entity == null) {
                        entity = findLatestEntity(workspaceId, routeKey);
                    }
                    return entity == null ? null : read(entity);
                });
        if (current == null) {
            throw new MateClawException(
                    "err.troubleshooting.sop_not_found", 404,
                    "no SOP registered for " + system + ":" + errorCode);
        }
        String target = targetStatus == null ? "" : targetStatus.trim().toLowerCase(Locale.ROOT);
        if ("approved".equals(target)) {
            throw new MateClawException(
                    "err.troubleshooting.sop_promotion_gate_required", 409,
                    "candidate approval requires the eligibility gate and must create a new version");
        }
        boolean legal = "deprecated".equals(target) && "approved".equals(current.status());
        if (!legal) {
            throw new MateClawException(
                    "err.troubleshooting.sop_status_transition", 409,
                    "illegal SOP transition " + current.status() + " -> " + target
                            + "; the legacy endpoint only allows approved->deprecated");
        }

        if (versioned.isPresent()) {
            throw new MateClawException(
                    "err.troubleshooting.sop_versioned_deprecation_review_required",
                    409,
                    "versioned Playbook retirement requires the audited knowledge-review "
                            + "command with an exact review version and reason");
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
                        .eq(TroubleshootingSopEntity::getSopId, current.sopId())
                        .eq(TroubleshootingSopEntity::getRouteKey, current.routingKey())
                        .eq(TroubleshootingSopEntity::getStatus, "approved")
                        .eq(TroubleshootingSopEntity::getVerified, true)
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
        var versioned = playbookVersions.findCurrent(workspaceId, routeKey);
        if (versioned.isPresent()) {
            SopEntry latest = versioned.get().playbook();
            return latest.operational() ? latest : null;
        }
        TroubleshootingSopEntity entity = findOperationalEntity(workspaceId, routeKey);
        return entity == null ? null : read(entity);
    }

    /**
     * Resolves a missing system only when one operational route matches the
     * already structured service and explicit error code.
     *
     * <p>This is intentionally not fuzzy text routing. Ambiguous/no matches
     * stay empty so Intake can ask the reporter instead of elevating a guess to
     * deterministic Playbook authority.</p>
     */
    public java.util.Optional<String> findUniqueOperationalSystem(
            long workspaceId, String service, String errorCode) {
        if (workspaceId <= 0 || service == null || service.isBlank()
                || errorCode == null || errorCode.isBlank()) {
            return java.util.Optional.empty();
        }
        return playbookVersions.uniqueActiveSystemForExactRoute(
                workspaceId, service.trim(), errorCode.trim());
    }

    /** Reads the latest contract for governance UI, including candidate/deprecated. */
    public SopEntry findLatest(long workspaceId, String system, String errorCode) {
        String routeKey = system.trim().toLowerCase(Locale.ROOT) + ":" + errorCode.trim();
        var versioned = playbookVersions.findCurrent(workspaceId, routeKey);
        if (versioned.isPresent()) {
            return versioned.get().playbook();
        }
        TroubleshootingSopEntity entity = findLatestEntity(workspaceId, routeKey);
        return entity == null ? null : read(entity);
    }

    /**
     * Resolves whichever identity {@link #list} handed out for this row.
     *
     * <p><b>为什么要认两种 id。</b> {@link #list} 会用版本行覆盖同一 selector 的
     * 注册行，于是同一个 {@code sopId} 字段在两种行里装着两个身份空间的值：候选行
     * 装的是人工来源记录号，已生效行装的是版本表的 {@code playbook-*}。而这里此前
     * 只查注册表——**列表把已生效行的 id 发出去，详情接口不认**，浏览知识库时正好
     * 是最重要的那些行（operational 的）打不开。</p>
     *
     * <p>两次查询都锁在同一个 workspace 内。顺序是注册表优先：它是人工来源的稳定
     * 身份，评审那几个接口收的也是它，先认它可以让同一个 id 在两处含义一致。</p>
     */
    public SopEntry findBySopId(long workspaceId, String sopId) {
        if (workspaceId <= 0) {
            throw new IllegalArgumentException("workspaceId must be positive");
        }
        if (sopId == null || sopId.isBlank()) {
            throw new IllegalArgumentException("sopId must not be blank");
        }
        String normalized = sopId.trim();
        TroubleshootingSopEntity entity = findEntityBySopId(workspaceId, normalized);
        if (entity != null) {
            return read(entity);
        }
        return playbookVersions.findByPlaybookId(workspaceId, normalized)
                .map(ApprovedPlaybookVersion::playbook)
                .orElse(null);
    }

    private SopEntry read(TroubleshootingSopEntity entity) {
        try {
            return objectMapper.readValue(entity.getAggregateJson(), SopEntry.class);
        } catch (JsonProcessingException error) {
            throw new MateClawException(
                    "err.troubleshooting.contract_serialization",
                    500,
                    "failed to deserialize SOP: " + error.getMessage());
        }
    }

    private TroubleshootingSopEntity findEntityBySopId(
            long workspaceId,
            String sopId) {
        return mapper.selectOne(
                new LambdaQueryWrapper<TroubleshootingSopEntity>()
                        .eq(TroubleshootingSopEntity::getWorkspaceId, workspaceId)
                        .eq(TroubleshootingSopEntity::getSopId, sopId)
                        .eq(TroubleshootingSopEntity::getDeleted, 0));
    }

    private TroubleshootingSopEntity findOperationalEntity(
            long workspaceId,
            String routeKey) {
        return mapper.selectOne(
                new LambdaQueryWrapper<TroubleshootingSopEntity>()
                        .eq(TroubleshootingSopEntity::getWorkspaceId, workspaceId)
                        .eq(TroubleshootingSopEntity::getRouteKey, routeKey)
                        .eq(TroubleshootingSopEntity::getStatus, "approved")
                        .eq(TroubleshootingSopEntity::getVerified, true)
                        .eq(TroubleshootingSopEntity::getDeleted, 0)
                        .last("LIMIT 1"));
    }

    private TroubleshootingSopEntity findLatestEntity(
            long workspaceId,
            String routeKey) {
        List<TroubleshootingSopEntity> rows = mapper.selectList(
                new LambdaQueryWrapper<TroubleshootingSopEntity>()
                        .eq(TroubleshootingSopEntity::getWorkspaceId, workspaceId)
                        .eq(TroubleshootingSopEntity::getRouteKey, routeKey)
                        .eq(TroubleshootingSopEntity::getDeleted, 0)
                        .orderByDesc(TroubleshootingSopEntity::getId)
                        .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.getFirst();
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

    private MateClawException sourceCollision(String sopId) {
        return new MateClawException(
                "err.troubleshooting.sop_key_collision",
                409,
                "manual SOP source id collision: " + sopId);
    }
}
