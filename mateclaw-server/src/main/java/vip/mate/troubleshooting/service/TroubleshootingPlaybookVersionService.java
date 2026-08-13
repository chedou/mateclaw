package vip.mate.troubleshooting.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.model.PlaybookVersionRef;
import vip.mate.troubleshooting.model.KnowledgeEvidenceGrade;
import vip.mate.troubleshooting.model.SopEntry;
import vip.mate.troubleshooting.model.TroubleshootingPlaybookVersionEntity;
import vip.mate.troubleshooting.repository.TroubleshootingPlaybookVersionMapper;
import vip.mate.troubleshooting.synthesis.ApprovedPlaybookVersion;
import vip.mate.troubleshooting.synthesis.KnowledgePromotionMaterial;
import vip.mate.troubleshooting.synthesis.KnowledgeReviewSnapshot;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Version store for knowledge that may drive deterministic diagnosis.
 *
 * <p>Promotion never edits a candidate into authority. It retires the exact
 * authority frozen when review started, inserts a new immutable version, and
 * relies on database uniqueness for the final single-active guarantee.</p>
 */
@Service
public class TroubleshootingPlaybookVersionService {

    private final TroubleshootingPlaybookVersionMapper mapper;
    private final ObjectMapper objectMapper;

    public TroubleshootingPlaybookVersionService(
            TroubleshootingPlaybookVersionMapper mapper,
            ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public Optional<PlaybookVersionRef> activeRef(
            long workspaceId,
            String selectorKey) {
        validateWorkspace(workspaceId);
        String selector = required(selectorKey, "selectorKey");
        TroubleshootingPlaybookVersionEntity active =
                mapper.findActive(workspaceId, selector);
        return active == null
                ? Optional.empty()
                : Optional.of(new PlaybookVersionRef(
                        active.getPlaybookId(), active.getPlaybookVersion()));
    }

    /**
     * Selectors that are live right now under one prefix, for use in a refusal.
     *
     * <p>「没有这条路」如果不同时说出「有哪些路」，读者只能去猜，而猜的时候最省事
     * 的做法是把闸门放宽。这个方法存在的唯一理由，就是让拒绝可以自带下一步。</p>
     *
     * <p>{@code %} 与 {@code _} 必须转义：selector 的前半段是租户自己填的 system，
     * 一个叫 {@code a_b} 的系统会连带匹配到 {@code axb}。范围本来就锁在同一个
     * workspace 内，泄不出去，但一条错的清单会把作者带向错的下一步。</p>
     */
    public List<String> activeSelectorsWithPrefix(
            long workspaceId,
            String prefix,
            int limit) {
        validateWorkspace(workspaceId);
        String safePrefix = required(prefix, "prefix")
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return mapper.listActiveSelectorsLike(
                workspaceId, safePrefix + "%", Math.min(Math.max(limit, 1), 50));
    }

    /**
     * Resolves the system dimension only when one live authority exactly
     * matches service + error code in the same workspace.
     */
    public Optional<String> uniqueActiveSystemForExactRoute(
            long workspaceId,
            String service,
            String errorCode) {
        validateWorkspace(workspaceId);
        List<String> systems = mapper.listActiveSystemsForExactRoute(
                workspaceId,
                required(service, "service"),
                required(errorCode, "errorCode"));
        return systems.size() == 1
                ? Optional.of(systems.getFirst())
                : Optional.empty();
    }

    /**
     * Reads one immutable version by its own identity, at whatever version it is.
     *
     * <p>Unlike {@link #findByRef} this does not demand a known version number:
     * it answers「这个 playbookId 是什么」for a caller that was handed the id and
     * nothing else — which is exactly what the registry listing hands out.</p>
     */
    public Optional<ApprovedPlaybookVersion> findByPlaybookId(
            long workspaceId,
            String playbookId) {
        validateWorkspace(workspaceId);
        TroubleshootingPlaybookVersionEntity entity = mapper.findByPlaybookId(
                workspaceId, required(playbookId, "playbookId"));
        return entity == null ? Optional.empty() : Optional.of(read(entity));
    }

    /** Reads one immutable authority by the identity frozen into a Diagnosis. */
    public Optional<ApprovedPlaybookVersion> findByRef(
            long workspaceId,
            PlaybookVersionRef ref) {
        validateWorkspace(workspaceId);
        if (ref == null) {
            throw new IllegalArgumentException("Playbook version reference is required");
        }
        TroubleshootingPlaybookVersionEntity entity = mapper.findByPlaybookId(
                workspaceId, ref.playbookId());
        if (entity == null || entity.getPlaybookVersion() != ref.playbookVersion()) {
            return Optional.empty();
        }
        return Optional.of(read(entity));
    }

    /**
     * Locks the exact active-approved authority for a persisted Diagnosis.
     * The caller must keep a surrounding transaction open through insertion.
     */
    public Optional<ApprovedPlaybookVersion> lockActiveApprovedByPlaybookId(
            long workspaceId,
            String playbookId) {
        validateWorkspace(workspaceId);
        TroubleshootingPlaybookVersionEntity entity =
                mapper.lockActiveApprovedByPlaybookId(
                        workspaceId, required(playbookId, "playbookId"));
        return entity == null ? Optional.empty() : Optional.of(read(entity));
    }

    public Optional<ApprovedPlaybookVersion> findByReview(
            long workspaceId,
            String reviewId) {
        validateWorkspace(workspaceId);
        TroubleshootingPlaybookVersionEntity entity = mapper.findByReview(
                workspaceId, required(reviewId, "reviewId"));
        return entity == null ? Optional.empty() : Optional.of(read(entity));
    }

    public Optional<ApprovedPlaybookVersion> findCurrent(
            long workspaceId,
            String selectorKey) {
        validateWorkspace(workspaceId);
        TroubleshootingPlaybookVersionEntity entity = mapper.findCurrent(
                workspaceId, required(selectorKey, "selectorKey"));
        return entity == null ? Optional.empty() : Optional.of(read(entity));
    }

    /** Latest immutable version per selector for the formal registry. */
    public List<SopSummary> listLatest(
            long workspaceId,
            String status,
            String system,
            int limit) {
        validateWorkspace(workspaceId);
        int capped = Math.min(Math.max(limit, 1), 500);
        String normalizedStatus = status == null || status.isBlank()
                ? null
                : status.trim().toUpperCase(Locale.ROOT);
        String normalizedSystem = system == null || system.isBlank()
                ? null
                : system.trim();
        return mapper.listLatest(
                        workspaceId, normalizedStatus, normalizedSystem, capped)
                .stream()
                .map(SopSummary::from)
                .toList();
    }

    /** Retires only the active authority created by the specified review. */
    @Transactional
    public ApprovedPlaybookVersion deprecateByReview(
            long workspaceId,
            String reviewId,
            String actor,
            String reason) {
        validateWorkspace(workspaceId);
        String normalizedReviewId = required(reviewId, "reviewId");
        TroubleshootingPlaybookVersionEntity reviewed = mapper.findByReview(
                workspaceId, normalizedReviewId);
        if (reviewed == null) {
            throw conflict("review has no persisted Playbook version");
        }
        if (!"APPROVED".equals(reviewed.getStatus())
                || reviewed.getActiveSelectorKey() == null) {
            throw conflict("reviewed Playbook version is not the active authority");
        }
        return retire(workspaceId, reviewed, actor, reason);
    }

    /** Audited escape hatch for V186 backfilled authority without a review row. */
    @Transactional
    public ApprovedPlaybookVersion deprecateLegacy(
            long workspaceId,
            String playbookId,
            int expectedPlaybookVersion,
            String actor,
            String reason) {
        validateWorkspace(workspaceId);
        TroubleshootingPlaybookVersionEntity legacy = mapper.findByPlaybookId(
                workspaceId, required(playbookId, "playbookId"));
        if (legacy == null) {
            throw conflict("legacy Playbook version does not exist");
        }
        if (!"LEGACY".equals(legacy.getSourceOrigin())
                || legacy.getReviewId() != null
                || legacy.getPlaybookVersion() != expectedPlaybookVersion) {
            throw conflict("only the exact active legacy migration version may use this command");
        }
        String reviewer = required(actor, "reviewer");
        String auditReason = required(reason, "reason");
        if ("DEPRECATED".equals(legacy.getStatus())
                && legacy.getActiveSelectorKey() == null
                && reviewer.equals(legacy.getDeprecatedBy())
                && auditReason.equals(legacy.getDeprecationReason())) {
            return read(legacy);
        }
        if (!"APPROVED".equals(legacy.getStatus())
                || legacy.getActiveSelectorKey() == null) {
            throw conflict("legacy Playbook version is not the active authority");
        }
        return retire(workspaceId, legacy, reviewer, auditReason);
    }

    @Transactional
    public ApprovedPlaybookVersion promote(
            long workspaceId,
            KnowledgePromotionMaterial material,
            String reviewId,
            int reviewVersion,
            boolean activeBaselineKnown,
            String basePlaybookId,
            Integer basePlaybookVersion,
            String actor,
            String reason,
            KnowledgeReviewSnapshot approvalSnapshot) {
        validateWorkspace(workspaceId);
        if (material == null || approvalSnapshot == null) {
            throw new IllegalArgumentException(
                    "promotion material and approval snapshot are required");
        }
        String normalizedReviewId = required(reviewId, "reviewId");
        if (reviewVersion < 1) {
            throw conflict("promotion requires an exact IN_REVIEW version");
        }
        String reviewer = required(actor, "reviewer");
        String auditReason = required(reason, "reason");
        String selector = required(material.selectorKey(), "selectorKey");
        if (!selector.equals(material.playbook().routingKey())) {
            throw conflict("promotion artifact selector does not match its routeable contract");
        }
        if (!"candidate".equals(material.playbook().status())
                || material.playbook().verified()) {
            throw conflict("promotion material must remain candidate and unverified");
        }
        if (!"ELIGIBLE_FOR_APPROVAL".equals(
                approvalSnapshot.approvalEligibility())
                || !approvalSnapshot.eligibilityReasons().isEmpty()) {
            throw conflict("current source is not eligible for approval");
        }

        TroubleshootingPlaybookVersionEntity existing = mapper.findByReview(
                workspaceId, normalizedReviewId);
        if (existing != null) {
            ApprovedPlaybookVersion prior = read(existing);
            if (prior.reviewVersion() != null
                    && prior.reviewVersion() == reviewVersion
                    && prior.sourceOrigin().equals(material.origin().name())
                    && prior.sourceRecordId().equals(material.sourceRecordId())
                    && prior.knowledgeEvidenceGrade() == material.evidenceGrade()
                    && prior.selectorKey().equals(selector)
                    && prior.approvedBy().equals(reviewer)
                    && prior.approvalReason().equals(auditReason)) {
                return prior;
            }
            throw conflict("review already produced a different Playbook version");
        }
        if (!activeBaselineKnown) {
            throw conflict(
                    "review has no versioned authority baseline; restart review before approval");
        }

        TroubleshootingPlaybookVersionEntity active =
                mapper.findActive(workspaceId, selector);
        requireSameBaseline(active, basePlaybookId, basePlaybookVersion);

        Integer currentMax = mapper.maxPlaybookVersion(workspaceId, selector);
        int nextVersion = (currentMax == null ? 0 : currentMax) + 1;
        LocalDateTime now = utcNow();
        if (active != null) {
            SopEntry retired = withIdentityAndStatus(
                    readSop(active), active.getPlaybookId(), "deprecated", false);
            int changed = mapper.retireActive(
                    workspaceId,
                    active.getId(),
                    selector,
                    active.getVersion(),
                    write(retired),
                    reviewer,
                    "superseded by approved review " + normalizedReviewId,
                    now);
            if (changed != 1) {
                throw conflict(
                        "active Playbook changed concurrently; reload before approval");
            }
        }

        String playbookId = "playbook-" + UUID.randomUUID();
        SopEntry approved = withIdentityAndStatus(
                material.playbook(), playbookId, "approved", true);
        TroubleshootingPlaybookVersionEntity entity =
                new TroubleshootingPlaybookVersionEntity();
        entity.setWorkspaceId(workspaceId);
        entity.setPlaybookId(playbookId);
        entity.setSelectorKey(selector);
        entity.setPlaybookVersion(nextVersion);
        entity.setActiveSelectorKey(selector);
        entity.setSystem(approved.system());
        entity.setErrorCode(approved.errorCode());
        entity.setService(approved.service());
        entity.setStatus("APPROVED");
        entity.setSourceOrigin(material.origin().name());
        entity.setSourceRecordId(material.sourceRecordId());
        entity.setKnowledgeEvidenceGrade(material.evidenceGrade().name());
        entity.setReviewId(normalizedReviewId);
        entity.setReviewVersion(reviewVersion);
        entity.setApprovedBy(reviewer);
        entity.setApprovalReason(auditReason);
        entity.setApprovalSnapshotJson(write(approvalSnapshot));
        entity.setContractVersion(approved.contractVersion());
        entity.setAggregateJson(write(approved));
        entity.setVersion(0);
        entity.setDeleted(0);
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        try {
            mapper.insert(entity);
        } catch (DataIntegrityViolationException raced) {
            throw conflict(
                    "selector authority changed concurrently; reload before approval");
        }
        return read(entity);
    }

    private ApprovedPlaybookVersion retire(
            long workspaceId,
            TroubleshootingPlaybookVersionEntity reviewed,
            String actor,
            String reason) {
        String reviewer = required(actor, "reviewer");
        String auditReason = required(reason, "reason");
        TroubleshootingPlaybookVersionEntity active = mapper.findActive(
                workspaceId, reviewed.getSelectorKey());
        if (active == null || !reviewed.getId().equals(active.getId())) {
            throw conflict(
                    "another Playbook version owns this selector; reload before retiring");
        }
        LocalDateTime now = utcNow();
        SopEntry retired = withIdentityAndStatus(
                readSop(reviewed), reviewed.getPlaybookId(), "deprecated", false);
        String aggregate = write(retired);
        int changed = mapper.retireActive(
                workspaceId,
                reviewed.getId(),
                reviewed.getSelectorKey(),
                reviewed.getVersion(),
                aggregate,
                reviewer,
                auditReason,
                now);
        if (changed != 1) {
            throw conflict("active Playbook changed concurrently; reload before retiring it");
        }
        reviewed.setStatus("DEPRECATED");
        reviewed.setActiveSelectorKey(null);
        reviewed.setAggregateJson(aggregate);
        reviewed.setDeprecatedBy(reviewer);
        reviewed.setDeprecationReason(auditReason);
        reviewed.setDeprecatedAt(now);
        reviewed.setVersion(reviewed.getVersion() + 1);
        reviewed.setUpdateTime(now);
        return read(reviewed);
    }

    private void requireSameBaseline(
            TroubleshootingPlaybookVersionEntity active,
            String expectedPlaybookId,
            Integer expectedPlaybookVersion) {
        String expectedId = normalize(expectedPlaybookId);
        if (expectedId == null) {
            if (expectedPlaybookVersion != null || active != null) {
                throw conflict(
                        "selector authority changed since review started; reload before approval");
            }
            return;
        }
        if (expectedPlaybookVersion == null
                || active == null
                || !expectedId.equals(active.getPlaybookId())
                || !expectedPlaybookVersion.equals(active.getPlaybookVersion())) {
            throw conflict(
                    "selector authority changed since review started; reload before approval");
        }
    }

    private ApprovedPlaybookVersion read(
            TroubleshootingPlaybookVersionEntity entity) {
        KnowledgeReviewSnapshot approvalSnapshot = null;
        if (entity.getApprovalSnapshotJson() != null
                && !entity.getApprovalSnapshotJson().isBlank()) {
            approvalSnapshot = read(
                    entity.getApprovalSnapshotJson(),
                    KnowledgeReviewSnapshot.class,
                    "approval snapshot");
        }
        return new ApprovedPlaybookVersion(
                entity.getPlaybookId(),
                entity.getPlaybookVersion(),
                entity.getSelectorKey(),
                entity.getStatus(),
                entity.getSourceOrigin(),
                entity.getSourceRecordId(),
                KnowledgeEvidenceGrade.fromStored(entity.getKnowledgeEvidenceGrade()),
                entity.getReviewId(),
                entity.getReviewVersion(),
                entity.getApprovedBy(),
                entity.getApprovalReason(),
                approvalSnapshot,
                entity.getDeprecatedBy(),
                entity.getDeprecationReason(),
                entity.getDeprecatedAt() == null
                        ? null
                        : entity.getDeprecatedAt().toInstant(ZoneOffset.UTC),
                readSop(entity),
                entity.getCreateTime().toInstant(ZoneOffset.UTC),
                entity.getUpdateTime().toInstant(ZoneOffset.UTC));
    }

    private SopEntry readSop(TroubleshootingPlaybookVersionEntity entity) {
        return read(entity.getAggregateJson(), SopEntry.class, "Playbook aggregate");
    }

    private <T> T read(String json, Class<T> type, String label) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException error) {
            throw serialization("failed to deserialize " + label, error);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw serialization("failed to serialize Playbook version", error);
        }
    }

    private SopEntry withIdentityAndStatus(
            SopEntry source,
            String sopId,
            String status,
            boolean verified) {
        return new SopEntry(
                sopId,
                source.contractVersion(),
                source.system(),
                source.errorCode(),
                source.service(),
                source.title(),
                source.cause(),
                source.category(),
                source.ownerTeam(),
                status,
                verified,
                source.evidenceRequests(),
                source.anomalyCriteria(),
                source.diagnosisRules(),
                source.actions());
    }

    private void validateWorkspace(long workspaceId) {
        if (workspaceId <= 0) {
            throw new IllegalArgumentException("workspaceId must be positive");
        }
    }

    private String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private MateClawException conflict(String message) {
        return new MateClawException(
                "err.troubleshooting.knowledge_promotion_conflict", 409, message);
    }

    private MateClawException serialization(
            String message,
            JsonProcessingException error) {
        return new MateClawException(
                "err.troubleshooting.contract_serialization",
                500,
                message + ": " + error.getMessage());
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
