package vip.mate.troubleshooting.synthesis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.TroubleshootingBusinessTextPolicy;
import vip.mate.troubleshooting.TroubleshootingSecretRedactor;
import vip.mate.troubleshooting.model.TroubleshootingKnowledgeReviewEntity;
import vip.mate.troubleshooting.repository.TroubleshootingKnowledgeReviewMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Audited, optimistic knowledge-review workflow.
 *
 * <p>No row means the source is still a virtual {@code CANDIDATE} at version
 * zero. Starting review creates {@code IN_REVIEW/v1}; rejecting advances the
 * exact reviewed version. Approval is intentionally absent until eligibility
 * gates and versioned promotion are implemented.</p>
 */
@Service
public class KnowledgeReviewWorkflowService {

    private static final int MAX_PAGE_SIZE = 500;
    private static final int MAX_ACTOR_LENGTH = 192;
    private static final int MAX_REASON_LENGTH = 1000;

    private final TroubleshootingKnowledgeReviewMapper mapper;
    private final KnowledgeReviewSourceReader sources;
    private final ObjectMapper objectMapper;

    public KnowledgeReviewWorkflowService(
            TroubleshootingKnowledgeReviewMapper mapper,
            KnowledgeReviewSourceReader sources,
            ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.sources = sources;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public KnowledgeReviewState start(
            long workspaceId,
            KnowledgeOrigin origin,
            String sourceRecordId,
            int expectedVersion,
            String actor,
            String reason) {
        validateWorkspace(workspaceId);
        requireOrigin(origin);
        String sourceId = required(sourceRecordId, "sourceRecordId", 128);
        String reviewer = required(actor, "reviewer", MAX_ACTOR_LENGTH);
        String auditReason = safeReason(reason);
        if (expectedVersion != 0) {
            throw conflict("a new review must start from candidate version 0");
        }

        TroubleshootingKnowledgeReviewEntity existing = mapper.findBySource(
                workspaceId, origin.name(), sourceId);
        if (existing != null) {
            return idempotentStartOrConflict(existing, reviewer, auditReason);
        }

        KnowledgeReviewSource source = sources.find(workspaceId, origin, sourceId)
                .orElseThrow(() -> new MateClawException(
                        "err.troubleshooting.knowledge_review_source_not_found",
                        404,
                        "knowledge candidate does not exist in this workspace"));
        if (source.origin() != origin || !source.sourceRecordId().equals(sourceId)) {
            throw new MateClawException(
                    "err.troubleshooting.knowledge_review_source_mismatch",
                    500,
                    "knowledge review source resolver returned a mismatched identity");
        }

        LocalDateTime now = utcNow();
        TroubleshootingKnowledgeReviewEntity entity =
                new TroubleshootingKnowledgeReviewEntity();
        entity.setWorkspaceId(workspaceId);
        entity.setReviewId("review-" + UUID.randomUUID());
        entity.setOrigin(origin.name());
        entity.setSourceRecordId(sourceId);
        entity.setSelectorKey(source.selectorKey());
        entity.setStatus(KnowledgeReviewStatus.IN_REVIEW.name());
        entity.setReviewer(reviewer);
        entity.setReason(auditReason);
        entity.setSnapshotJson(write(source.snapshot()));
        entity.setVersion(1);
        entity.setDeleted(0);
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        try {
            mapper.insert(entity);
            return read(entity);
        } catch (DataIntegrityViolationException raced) {
            existing = mapper.findBySource(workspaceId, origin.name(), sourceId);
            if (existing == null) {
                throw raced;
            }
            return idempotentStartOrConflict(existing, reviewer, auditReason);
        }
    }

    @Transactional
    public KnowledgeReviewState reject(
            long workspaceId,
            KnowledgeOrigin origin,
            String sourceRecordId,
            int expectedVersion,
            String actor,
            String reason) {
        validateWorkspace(workspaceId);
        requireOrigin(origin);
        String sourceId = required(sourceRecordId, "sourceRecordId", 128);
        String reviewer = required(actor, "reviewer", MAX_ACTOR_LENGTH);
        String auditReason = safeReason(reason);
        if (expectedVersion < 1) {
            throw conflict("reject requires an IN_REVIEW version");
        }

        TroubleshootingKnowledgeReviewEntity entity = mapper.findBySource(
                workspaceId, origin.name(), sourceId);
        if (entity == null) {
            throw new MateClawException(
                    "err.troubleshooting.knowledge_review_not_found",
                    404,
                    "start review before recording a decision");
        }
        KnowledgeReviewState current = read(entity);
        if (current.status() == KnowledgeReviewStatus.REJECTED
                && current.version() == expectedVersion + 1
                && current.reviewer().equals(reviewer)
                && current.reason().equals(auditReason)) {
            return current;
        }
        if (current.status() != KnowledgeReviewStatus.IN_REVIEW
                || current.version() != expectedVersion) {
            throw conflict("review changed concurrently; reload before deciding");
        }

        LocalDateTime now = utcNow();
        int changed = mapper.transition(
                workspaceId,
                current.reviewId(),
                KnowledgeReviewStatus.IN_REVIEW.name(),
                KnowledgeReviewStatus.REJECTED.name(),
                expectedVersion,
                reviewer,
                auditReason,
                now);
        if (changed != 1) {
            throw conflict("review changed concurrently; reload before deciding");
        }
        return new KnowledgeReviewState(
                current.reviewId(),
                current.origin(),
                current.sourceRecordId(),
                current.selectorKey(),
                KnowledgeReviewStatus.REJECTED,
                reviewer,
                auditReason,
                current.snapshot(),
                expectedVersion + 1,
                current.createdAt(),
                now.toInstant(ZoneOffset.UTC));
    }

    public List<KnowledgeReviewState> list(long workspaceId, int limit) {
        validateWorkspace(workspaceId);
        int capped = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
        return mapper.listByWorkspace(workspaceId, capped).stream()
                .map(this::read)
                .toList();
    }

    private KnowledgeReviewState idempotentStartOrConflict(
            TroubleshootingKnowledgeReviewEntity entity,
            String reviewer,
            String reason) {
        KnowledgeReviewState current = read(entity);
        if (current.status() == KnowledgeReviewStatus.IN_REVIEW
                && current.version() == 1
                && current.reviewer().equals(reviewer)
                && current.reason().equals(reason)) {
            return current;
        }
        throw conflict("candidate already has a review state; reload before continuing");
    }

    private KnowledgeReviewState read(TroubleshootingKnowledgeReviewEntity entity) {
        try {
            KnowledgeReviewSnapshot snapshot = objectMapper.readValue(
                    entity.getSnapshotJson(), KnowledgeReviewSnapshot.class);
            return new KnowledgeReviewState(
                    entity.getReviewId(),
                    KnowledgeOrigin.valueOf(entity.getOrigin()),
                    entity.getSourceRecordId(),
                    entity.getSelectorKey(),
                    KnowledgeReviewStatus.valueOf(entity.getStatus()),
                    entity.getReviewer(),
                    entity.getReason(),
                    snapshot,
                    entity.getVersion(),
                    entity.getCreateTime().toInstant(ZoneOffset.UTC),
                    entity.getUpdateTime().toInstant(ZoneOffset.UTC));
        } catch (JsonProcessingException | IllegalArgumentException error) {
            throw new MateClawException(
                    "err.troubleshooting.contract_serialization",
                    500,
                    "failed to deserialize knowledge review: " + error.getMessage());
        }
    }

    private String write(KnowledgeReviewSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException error) {
            throw new MateClawException(
                    "err.troubleshooting.contract_serialization",
                    500,
                    "failed to serialize knowledge review snapshot: " + error.getMessage());
        }
    }

    private void validateWorkspace(long workspaceId) {
        if (workspaceId <= 0) {
            throw new MateClawException(
                    "err.troubleshooting.workspace_required",
                    400,
                    "workspaceId must be positive");
        }
    }

    private void requireOrigin(KnowledgeOrigin origin) {
        if (origin == null) {
            throw new MateClawException(
                    "err.troubleshooting.knowledge_origin_invalid",
                    400,
                    "knowledge origin is required");
        }
    }

    private String required(String value, String name, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new MateClawException(
                    "err.troubleshooting.knowledge_review_input",
                    400,
                    name + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new MateClawException(
                    "err.troubleshooting.knowledge_review_input",
                    400,
                    name + " exceeds " + maxLength + " characters");
        }
        return normalized;
    }

    private String safeReason(String value) {
        String normalized = required(value, "reason", MAX_REASON_LENGTH);
        try {
            if (!TroubleshootingSecretRedactor.redact(normalized).equals(normalized)) {
                throw new IllegalArgumentException("credential-shaped content is forbidden");
            }
            TroubleshootingBusinessTextPolicy.requireNoDeveloperEvidence(
                    normalized, "knowledgeReview.reason");
            return normalized;
        } catch (IllegalArgumentException unsafe) {
            throw new MateClawException(
                    "err.troubleshooting.knowledge_review_reason_unsafe",
                    400,
                    "review reason must not contain credentials, DQL, raw logs, "
                            + "stack traces or unsafe control characters");
        }
    }

    private MateClawException conflict(String message) {
        return new MateClawException(
                "err.troubleshooting.knowledge_review_conflict",
                409,
                message);
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
