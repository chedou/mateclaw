package vip.mate.troubleshooting.intake;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.repository.TroubleshootingIntakeMessageReceiptMapper;
import vip.mate.troubleshooting.repository.TroubleshootingIntakeInvestigationMapper;
import vip.mate.troubleshooting.repository.TroubleshootingIntakeSessionMapper;
import vip.mate.troubleshooting.service.TroubleshootingSopPersistenceService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Durable, idempotent coordinator for multi-turn channel intake.
 *
 * <p>The message receipt table carries no text; it exists only to prevent a
 * retried webhook from applying the same patch twice. A hashed routing key
 * locates sessions without exposing channel identifiers in an index. The
 * immutable reported-at boundary lets provider event time select the owning
 * session even after a newer session has opened; its nullable active-key copy
 * serializes the open intake. Optimistic version
 * checks protect updates, while striped JVM locks remove the common same-node
 * creation race; the DB unique keys remain the cross-node backstop.</p>
 */
@Service
public class TroubleshootingIntakeSessionService {

    private static final int LOCK_STRIPES = 64;

    private final TroubleshootingIntakeSessionMapper sessionMapper;
    private final TroubleshootingIntakeMessageReceiptMapper receiptMapper;
    private final TroubleshootingIntakeInvestigationMapper investigationMapper;
    private final ObjectMapper objectMapper;
    private final IntakeSessionReducer reducer;
    private final TroubleshootingSopPersistenceService sopPersistence;
    private final TransactionTemplate transactionTemplate;
    private final ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];

    @Autowired
    public TroubleshootingIntakeSessionService(
            TroubleshootingIntakeSessionMapper sessionMapper,
            TroubleshootingIntakeMessageReceiptMapper receiptMapper,
            TroubleshootingIntakeInvestigationMapper investigationMapper,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            TroubleshootingSopPersistenceService sopPersistence) {
        this(
                sessionMapper,
                receiptMapper,
                investigationMapper,
                objectMapper,
                new IntakeSessionReducer(),
                new TransactionTemplate(transactionManager),
                sopPersistence);
    }

    TroubleshootingIntakeSessionService(
            TroubleshootingIntakeSessionMapper sessionMapper,
            TroubleshootingIntakeMessageReceiptMapper receiptMapper,
            TroubleshootingIntakeInvestigationMapper investigationMapper,
            ObjectMapper objectMapper,
            IntakeSessionReducer reducer) {
        this(sessionMapper, receiptMapper, investigationMapper, objectMapper, reducer, null, null);
    }

    TroubleshootingIntakeSessionService(
            TroubleshootingIntakeSessionMapper sessionMapper,
            TroubleshootingIntakeMessageReceiptMapper receiptMapper,
            TroubleshootingIntakeInvestigationMapper investigationMapper,
            ObjectMapper objectMapper,
            IntakeSessionReducer reducer,
            TroubleshootingSopPersistenceService sopPersistence) {
        this(sessionMapper, receiptMapper, investigationMapper, objectMapper, reducer, null,
                sopPersistence);
    }

    private TroubleshootingIntakeSessionService(
            TroubleshootingIntakeSessionMapper sessionMapper,
            TroubleshootingIntakeMessageReceiptMapper receiptMapper,
            TroubleshootingIntakeInvestigationMapper investigationMapper,
            ObjectMapper objectMapper,
            IntakeSessionReducer reducer,
            TransactionTemplate transactionTemplate,
            TroubleshootingSopPersistenceService sopPersistence) {
        this.sessionMapper = sessionMapper;
        this.receiptMapper = receiptMapper;
        this.investigationMapper = investigationMapper;
        this.objectMapper = objectMapper;
        this.reducer = reducer;
        this.sopPersistence = sopPersistence;
        this.transactionTemplate = transactionTemplate;
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new ReentrantLock();
        }
    }

    public IntakeDecision accept(IntakeMessageEnvelope envelope) {
        return accept(envelope, ConversationModeRequest.none(), null);
    }

    /**
     * Accepts a Web conversation turn while freezing its formal/rehearsal mode
     * in the durable aggregate. A missing mode defaults only when creating the
     * very first session; later turns inherit the stored fact.
     */
    public IntakeDecision acceptConversation(
            IntakeMessageEnvelope envelope,
            Boolean rehearsal) {
        return acceptConversation(envelope, rehearsal, null);
    }

    /**
     * Accepts a scoped Web receipt while optionally consulting one legacy
     * unscoped receipt id from the pre-upgrade deployment.
     *
     * <p>The legacy id is lookup-only: it is never claimed, copied or returned.
     * A matching row is reusable only when its persisted aggregate proves the
     * same workspace, source, conversation and authenticated reporter.</p>
     */
    public IntakeDecision acceptConversation(
            IntakeMessageEnvelope envelope,
            Boolean rehearsal,
            String legacyReceiptLookupId) {
        if (!TroubleshootingIntakeSources.WEB_CONVERSATION.equals(envelope.source())) {
            throw new IllegalArgumentException(
                    "conversation mode can only be bound to Web conversation intake");
        }
        return accept(
                envelope,
                ConversationModeRequest.requested(rehearsal),
                normalizeLegacyReceiptLookupId(envelope, legacyReceiptLookupId));
    }

    private IntakeDecision accept(
            IntakeMessageEnvelope envelope,
            ConversationModeRequest modeRequest,
            String legacyReceiptLookupId) {
        String routingKey = routingKey(envelope);
        ReentrantLock lock = lock(routingKey);
        lock.lock();
        try {
            try {
                return inTransaction(() -> acceptOnce(
                        envelope, routingKey, modeRequest, legacyReceiptLookupId));
            } catch (DuplicateKeyException concurrentWinner) {
                // The first transaction is fully rolled back before execute()
                // rethrows. A single fresh transaction can now observe the
                // committed winner and either return the duplicate decision or
                // apply this distinct message to the winner's active session.
                return inTransaction(() -> acceptOnce(
                        envelope, routingKey, modeRequest, legacyReceiptLookupId));
            }
        } finally {
            // TransactionTemplate commits/rolls back before returning, so the
            // striped lock covers the actual database transaction boundary.
            lock.unlock();
        }
    }

    /** Loads the immutable READY hand-off snapshot for the background worker. */
    public IntakeSession getReady(long workspaceId, String intakeSessionId) {
        return getReadyDispatch(workspaceId, intakeSessionId).session();
    }

    /** Loads the current safe aggregate for transcript projection. */
    public IntakeSession get(long workspaceId, String intakeSessionId) {
        return read(requiredSession(workspaceId, intakeSessionId));
    }

    /** Returns the latest server-owned mode fact for one authenticated Web conversation. */
    public IntakeSession getConversationMode(
            long workspaceId,
            String conversationRef,
            String reporterRef) {
        if (conversationRef == null || conversationRef.isBlank()
                || reporterRef == null || reporterRef.isBlank()) {
            throw new MateClawException(
                    "err.troubleshooting.conversation_identity_required",
                    400,
                    "conversationId and authenticated operator are required");
        }
        String routingKey = routingKey(
                workspaceId,
                TroubleshootingIntakeSources.WEB_CONVERSATION,
                conversationRef.trim(),
                reporterRef.trim());
        TroubleshootingIntakeSessionEntity latest = findLatest(workspaceId, routingKey);
        if (latest == null) {
            throw new MateClawException(
                    "err.troubleshooting.conversation_not_found",
                    404,
                    "troubleshooting conversation was not found");
        }
        IntakeSession session = read(latest);
        if (session.rehearsal() == null) {
            throw new MateClawException(
                    "err.troubleshooting.conversation_mode_unavailable",
                    409,
                    "legacy troubleshooting conversation has no locked mode");
        }
        return session;
    }

    /**
     * Loads READY business state together with the separately persisted
     * transport route. Keeping the two fields separate preserves the stable
     * Intake routing key across upgrades and channel configuration changes.
     */
    public ReadyIntakeDispatch getReadyDispatch(
            long workspaceId,
            String intakeSessionId) {
        TroubleshootingIntakeSessionEntity entity =
                requiredSession(workspaceId, intakeSessionId);
        IntakeSession session = read(entity);
        if (session.status() != IntakeSessionStatus.READY) {
            throw new MateClawException(
                    "err.troubleshooting.intake_not_ready",
                    409,
                    "intake session is not READY: " + intakeSessionId);
        }
        return new ReadyIntakeDispatch(session, entity.getDeliveryConversationId());
    }

    /**
     * Adds missing durable tasks for READY rows created before the dispatcher
     * migration. The unique workspace/intake key is the cross-node winner;
     * concurrent reconcilers therefore converge without duplicating work.
     */
    public int reconcileReadyInvestigations() {
        java.util.List<TroubleshootingIntakeSessionEntity> readySessions =
                sessionMapper.selectList(
                        new LambdaQueryWrapper<TroubleshootingIntakeSessionEntity>()
                                .eq(TroubleshootingIntakeSessionEntity::getStatus,
                                        IntakeSessionStatus.READY.name())
                                .eq(TroubleshootingIntakeSessionEntity::getDeleted, 0)
                                .orderByAsc(TroubleshootingIntakeSessionEntity::getCreateTime));
        int created = 0;
        for (TroubleshootingIntakeSessionEntity ready : readySessions) {
            Long existing = investigationMapper.selectCount(
                    new LambdaQueryWrapper<TroubleshootingIntakeInvestigationEntity>()
                            .eq(TroubleshootingIntakeInvestigationEntity::getWorkspaceId,
                                    ready.getWorkspaceId())
                            .eq(TroubleshootingIntakeInvestigationEntity::getIntakeSessionId,
                                    ready.getIntakeSessionId())
                            .eq(TroubleshootingIntakeInvestigationEntity::getDeleted, 0));
            if (existing != null && existing > 0) {
                continue;
            }
            IntakeSession session = read(ready);
            if (session.status() != IntakeSessionStatus.READY) {
                continue;
            }
            try {
                enqueueInvestigation(session);
                created++;
            } catch (DuplicateKeyException concurrentWinner) {
                // Another node inserted the same unique workspace/intake row.
            }
        }
        return created;
    }

    private IntakeDecision acceptOnce(
            IntakeMessageEnvelope envelope,
            String routingKey,
            ConversationModeRequest modeRequest,
            String legacyReceiptLookupId) {
        TroubleshootingIntakeMessageReceiptEntity existingReceipt = findReceipt(envelope);
        if (existingReceipt != null) {
            TroubleshootingIntakeSessionEntity existing = requiredSession(
                    envelope.workspaceId(), existingReceipt.getIntakeSessionId());
            requireSameMessageOwner(read(existing), envelope);
            IntakeSession session = requireConversationMode(existing, modeRequest);
            return IntakeDecision.from(session, true, false);
        }

        TroubleshootingIntakeSessionEntity legacyOwner =
                findOwnedLegacyReceiptSession(envelope, legacyReceiptLookupId);
        if (legacyOwner != null) {
            IntakeSession session = requireConversationMode(legacyOwner, modeRequest);
            return IntakeDecision.from(session, true, false);
        }

        TroubleshootingIntakeSessionEntity latest = findLatest(
                envelope.workspaceId(), routingKey);
        if (latest == null) {
            return create(envelope, routingKey, modeRequest.initialMode());
        }

        IntakeSession newest = requireConversationMode(latest, modeRequest);
        if (!envelope.receivedAt().isBefore(newest.reportedAt())) {
            if (newest.status() != IntakeSessionStatus.READY) {
                claimMessage(envelope, newest.intakeSessionId());
                return applyToExisting(latest, envelope);
            }
            if (!envelope.receivedAt().isAfter(newest.lastMessageAt())) {
                claimMessage(envelope, newest.intakeSessionId());
                return IntakeDecision.from(newest, false, true);
            }
            return create(envelope, routingKey, newest.rehearsal());
        }

        // A callback older than the latest session's immutable start boundary
        // belongs to the preceding event-time interval, not to the currently
        // active session. If it predates every known boundary, attach it to the
        // earliest session so it is still idempotently receipted without
        // rewriting an aggregate.
        TroubleshootingIntakeSessionEntity historical = findLatestStartedAtOrBefore(
                envelope.workspaceId(), routingKey, envelope.receivedAt());
        if (historical == null) {
            historical = findEarliest(envelope.workspaceId(), routingKey);
        }
        IntakeSession owner = requireConversationMode(historical, modeRequest);
        claimMessage(envelope, owner.intakeSessionId());
        return IntakeDecision.from(owner, false, true);
    }

    private IntakeDecision inTransaction(Supplier<IntakeDecision> work) {
        if (transactionTemplate == null) {
            // Narrow unit tests exercise the domain coordinator without a
            // Spring application context; production always uses the public
            // constructor and therefore a real TransactionTemplate.
            return work.get();
        }
        return Objects.requireNonNull(
                transactionTemplate.execute(status -> work.get()),
                "intake transaction returned no decision");
    }

    private IntakeDecision applyToExisting(
            TroubleshootingIntakeSessionEntity entity,
            IntakeMessageEnvelope envelope) {
        IntakeSession current = read(entity);
        boolean outOfOrder = !envelope.receivedAt().isAfter(current.lastMessageAt());
        if (outOfOrder) {
            return IntakeDecision.from(current, false, true);
        }
        IntakeSession next = reducer.accept(current, envelope);
        next = resolveExactOperationalRoute(next, envelope);
        if (next.equals(current)) {
            return IntakeDecision.from(current, false, false);
        }
        update(entity, next, envelope.deliveryConversationId());
        if (current.status() != IntakeSessionStatus.READY
                && next.status() == IntakeSessionStatus.READY) {
            enqueueInvestigation(next);
        }
        return IntakeDecision.from(next, false, false);
    }

    private void insert(
            String routingKey,
            IntakeSession session,
            String deliveryConversationId) {
        TroubleshootingIntakeSessionEntity entity = entity(
                routingKey, session, deliveryConversationId);
        sessionMapper.insert(entity);
    }

    private IntakeDecision create(
            IntakeMessageEnvelope envelope,
            String routingKey,
            Boolean rehearsal) {
        String sessionId = newSessionId();
        claimMessage(envelope, sessionId);
        IntakeSession created = reducer.start(sessionId, envelope, rehearsal);
        created = resolveExactOperationalRoute(created, envelope);
        insert(routingKey, created, envelope.deliveryConversationId());
        if (created.status() == IntakeSessionStatus.READY) {
            enqueueInvestigation(created);
        }
        return IntakeDecision.from(created, false, false);
    }

    /**
     * A source receipt proves only that a message id was seen. It must never be
     * usable as a cross-conversation lookup handle for the session it points
     * to, especially for Web message ids supplied by a browser.
     */
    private void requireSameMessageOwner(
            IntakeSession owner,
            IntakeMessageEnvelope envelope) {
        if (!sameMessageOwner(owner, envelope)) {
            throw new MateClawException(
                    "err.troubleshooting.message_identity_conflict",
                    409,
                    "the message id belongs to a different troubleshooting conversation");
        }
    }

    private boolean sameMessageOwner(
            IntakeSession owner,
            IntakeMessageEnvelope envelope) {
        return owner.workspaceId() == envelope.workspaceId()
                && owner.source().equals(envelope.source())
                && owner.conversationRef().equals(envelope.conversationRef())
                && owner.reporterRef().equals(envelope.reporterRef());
    }

    private TroubleshootingIntakeSessionEntity findOwnedLegacyReceiptSession(
            IntakeMessageEnvelope envelope,
            String legacyReceiptLookupId) {
        if (legacyReceiptLookupId == null) {
            return null;
        }
        TroubleshootingIntakeMessageReceiptEntity legacyReceipt = findReceipt(
                envelope.workspaceId(), envelope.source(), legacyReceiptLookupId);
        if (legacyReceipt == null) {
            return null;
        }
        TroubleshootingIntakeSessionEntity candidate = requiredSession(
                envelope.workspaceId(), legacyReceipt.getIntakeSessionId());
        // Legacy browser ids were globally unscoped. A collision belonging to
        // another actor/conversation is not this turn's duplicate; continue
        // with the scoped receipt rather than letting the old id capture or
        // block the new conversation.
        return sameMessageOwner(read(candidate), envelope) ? candidate : null;
    }

    private IntakeSession requireConversationMode(
            TroubleshootingIntakeSessionEntity entity,
            ConversationModeRequest modeRequest) {
        IntakeSession current = read(entity);
        if (!modeRequest.active()) {
            return current;
        }
        if (current.rehearsal() == null) {
            if (modeRequest.requested() == null) {
                throw new MateClawException(
                        "err.troubleshooting.conversation_mode_unavailable",
                        409,
                        "legacy troubleshooting conversation has no locked mode");
            }
            IntakeSession locked = current.withRehearsal(modeRequest.requested());
            update(entity, locked, null);
            entity.setAggregateJson(json(locked));
            entity.setVersion(entity.getVersion() + 1);
            entity.setStatus(locked.status().name());
            entity.setLastMessageAt(toLocal(locked.lastMessageAt()));
            return locked;
        }
        if (modeRequest.requested() != null
                && !current.rehearsal().equals(modeRequest.requested())) {
            throw new MateClawException(
                    "err.troubleshooting.conversation_mode_conflict",
                    409,
                    "troubleshooting conversation mode is already locked");
        }
        return current;
    }

    private IntakeSession resolveExactOperationalRoute(
            IntakeSession session,
            IntakeMessageEnvelope envelope) {
        if (sopPersistence == null || session == null
                || session.system() != null
                || session.service() == null) {
            return session;
        }
        // A monitoring alert names a service and no error code. Falling back to
        // the service-only lookup keeps the same single-authority rule; it only
        // stops requiring a code the alert never had.
        java.util.Optional<String> resolved = session.errorCode() == null
                ? sopPersistence.findUniqueOperationalSystemForService(
                        session.workspaceId(), session.service())
                : sopPersistence.findUniqueOperationalSystem(
                        session.workspaceId(), session.service(), session.errorCode());
        return resolved
                .map(system -> reducer.acceptResolvedSystem(session, envelope, system))
                .orElse(session);
    }

    private void enqueueInvestigation(IntakeSession session) {
        if (TroubleshootingIntakeSources.isLocalSynchronous(session.source())) {
            // Web conversation intake reports Diagnosis at the HTTP boundary and
            // does not need channel ACK delivery through IntakeInvestigationPoller.
            return;
        }
        LocalDateTime now = utcNow();
        TroubleshootingIntakeInvestigationEntity task =
                new TroubleshootingIntakeInvestigationEntity();
        task.setWorkspaceId(session.workspaceId());
        task.setIntakeSessionId(session.intakeSessionId());
        task.setDiagnosisId(null);
        task.setStatus(IntakeInvestigationStatus.PENDING);
        task.setAttempts(0);
        task.setTerminalAttempts(0);
        task.setNextAttemptAt(now);
        task.setClaimedBy(null);
        task.setLeaseExpiresAt(null);
        task.setLastError(null);
        task.setCompletedAt(null);
        task.setDeleted(0);
        task.setCreateTime(now);
        task.setUpdateTime(now);
        investigationMapper.insert(task);
    }

    private void claimMessage(IntakeMessageEnvelope envelope, String sessionId) {
        TroubleshootingIntakeMessageReceiptEntity receipt =
                new TroubleshootingIntakeMessageReceiptEntity();
        receipt.setWorkspaceId(envelope.workspaceId());
        receipt.setSource(envelope.source());
        receipt.setSourceMessageId(envelope.sourceMessageId());
        receipt.setIntakeSessionId(sessionId);
        receipt.setReceivedAt(toLocal(envelope.receivedAt()));
        receipt.setDeleted(0);
        receipt.setCreateTime(utcNow());
        receiptMapper.insert(receipt);
    }

    private void update(
            TroubleshootingIntakeSessionEntity current,
            IntakeSession next,
            String deliveryConversationId) {
        LocalDateTime now = utcNow();
        int changed = sessionMapper.update(
                null,
                new LambdaUpdateWrapper<TroubleshootingIntakeSessionEntity>()
                        .eq(TroubleshootingIntakeSessionEntity::getWorkspaceId,
                                current.getWorkspaceId())
                        .eq(TroubleshootingIntakeSessionEntity::getIntakeSessionId,
                                current.getIntakeSessionId())
                        .eq(TroubleshootingIntakeSessionEntity::getVersion,
                                current.getVersion())
                        .eq(TroubleshootingIntakeSessionEntity::getDeleted, 0)
                        .set(TroubleshootingIntakeSessionEntity::getStatus,
                                next.status().name())
                        .set(TroubleshootingIntakeSessionEntity::getActiveKey,
                                next.status() == IntakeSessionStatus.READY
                                        ? null
                                        : current.getActiveKey())
                        .set(TroubleshootingIntakeSessionEntity::getLastMessageAt,
                                toLocal(next.lastMessageAt()))
                        .set(current.getDeliveryConversationId() == null
                                        && deliveryConversationId != null,
                                TroubleshootingIntakeSessionEntity::getDeliveryConversationId,
                                deliveryConversationId)
                        .set(TroubleshootingIntakeSessionEntity::getAggregateJson, json(next))
                        .set(TroubleshootingIntakeSessionEntity::getVersion,
                                current.getVersion() + 1)
                        .set(TroubleshootingIntakeSessionEntity::getUpdateTime, now));
        if (changed != 1) {
            throw new MateClawException(
                    "err.troubleshooting.intake_optimistic_lock_conflict",
                    409,
                    "intake session changed concurrently; retry the source message");
        }
    }

    private TroubleshootingIntakeSessionEntity entity(
            String routingKey,
            IntakeSession session,
            String deliveryConversationId) {
        LocalDateTime now = utcNow();
        TroubleshootingIntakeSessionEntity entity = new TroubleshootingIntakeSessionEntity();
        entity.setWorkspaceId(session.workspaceId());
        entity.setIntakeSessionId(session.intakeSessionId());
        entity.setActiveKey(session.status() == IntakeSessionStatus.READY
                ? null
                : routingKey);
        entity.setRoutingKey(routingKey);
        entity.setSource(session.source());
        entity.setConversationRef(session.conversationRef());
        entity.setDeliveryConversationId(deliveryConversationId);
        entity.setReporterRef(session.reporterRef());
        entity.setStatus(session.status().name());
        entity.setReportedAt(toLocal(session.reportedAt()));
        entity.setLastMessageAt(toLocal(session.lastMessageAt()));
        entity.setAggregateJson(json(session));
        entity.setVersion(0);
        entity.setDeleted(0);
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        return entity;
    }

    private TroubleshootingIntakeSessionEntity findLatest(
            long workspaceId,
            String routingKey) {
        return sessionMapper.selectOne(
                new LambdaQueryWrapper<TroubleshootingIntakeSessionEntity>()
                        .eq(TroubleshootingIntakeSessionEntity::getWorkspaceId, workspaceId)
                        .eq(TroubleshootingIntakeSessionEntity::getRoutingKey, routingKey)
                        .eq(TroubleshootingIntakeSessionEntity::getDeleted, 0)
                        .orderByDesc(TroubleshootingIntakeSessionEntity::getReportedAt)
                        .orderByDesc(TroubleshootingIntakeSessionEntity::getId)
                        .last("LIMIT 1"));
    }

    private TroubleshootingIntakeSessionEntity findLatestStartedAtOrBefore(
            long workspaceId,
            String routingKey,
            java.time.Instant eventAt) {
        return sessionMapper.selectOne(
                new LambdaQueryWrapper<TroubleshootingIntakeSessionEntity>()
                        .eq(TroubleshootingIntakeSessionEntity::getWorkspaceId, workspaceId)
                        .eq(TroubleshootingIntakeSessionEntity::getRoutingKey, routingKey)
                        .le(TroubleshootingIntakeSessionEntity::getReportedAt, toLocal(eventAt))
                        .eq(TroubleshootingIntakeSessionEntity::getDeleted, 0)
                        .orderByDesc(TroubleshootingIntakeSessionEntity::getReportedAt)
                        .orderByDesc(TroubleshootingIntakeSessionEntity::getId)
                        .last("LIMIT 1"));
    }

    private TroubleshootingIntakeSessionEntity findEarliest(
            long workspaceId,
            String routingKey) {
        return sessionMapper.selectOne(
                new LambdaQueryWrapper<TroubleshootingIntakeSessionEntity>()
                        .eq(TroubleshootingIntakeSessionEntity::getWorkspaceId, workspaceId)
                        .eq(TroubleshootingIntakeSessionEntity::getRoutingKey, routingKey)
                        .eq(TroubleshootingIntakeSessionEntity::getDeleted, 0)
                        .orderByAsc(TroubleshootingIntakeSessionEntity::getReportedAt)
                        .orderByAsc(TroubleshootingIntakeSessionEntity::getId)
                        .last("LIMIT 1"));
    }

    private TroubleshootingIntakeSessionEntity requiredSession(
            long workspaceId,
            String intakeSessionId) {
        TroubleshootingIntakeSessionEntity entity = sessionMapper.selectOne(
                new LambdaQueryWrapper<TroubleshootingIntakeSessionEntity>()
                        .eq(TroubleshootingIntakeSessionEntity::getWorkspaceId, workspaceId)
                        .eq(TroubleshootingIntakeSessionEntity::getIntakeSessionId, intakeSessionId)
                        .eq(TroubleshootingIntakeSessionEntity::getDeleted, 0));
        if (entity == null) {
            throw persistenceError("intake message points to a missing session", null);
        }
        return entity;
    }

    private TroubleshootingIntakeMessageReceiptEntity findReceipt(
            IntakeMessageEnvelope envelope) {
        return findReceipt(
                envelope.workspaceId(),
                envelope.source(),
                envelope.sourceMessageId());
    }

    private TroubleshootingIntakeMessageReceiptEntity findReceipt(
            long workspaceId,
            String source,
            String sourceMessageId) {
        return receiptMapper.selectOne(
                new LambdaQueryWrapper<TroubleshootingIntakeMessageReceiptEntity>()
                        .eq(TroubleshootingIntakeMessageReceiptEntity::getWorkspaceId,
                                workspaceId)
                        .eq(TroubleshootingIntakeMessageReceiptEntity::getSource,
                                source)
                        .eq(TroubleshootingIntakeMessageReceiptEntity::getSourceMessageId,
                                sourceMessageId)
                        .eq(TroubleshootingIntakeMessageReceiptEntity::getDeleted, 0));
    }

    private String normalizeLegacyReceiptLookupId(
            IntakeMessageEnvelope envelope,
            String legacyReceiptLookupId) {
        if (legacyReceiptLookupId == null || legacyReceiptLookupId.isBlank()) {
            return null;
        }
        String normalized = legacyReceiptLookupId.trim();
        if (normalized.equals(envelope.sourceMessageId())) {
            return null;
        }
        if (!normalized.matches("web-msg-[A-Za-z0-9_-]{8,128}")) {
            // This value exists only to bridge receipts written by the old
            // browser deployment. An unusable compatibility alias must not
            // reject an otherwise valid scoped turn.
            return null;
        }
        return normalized;
    }

    private IntakeSession read(TroubleshootingIntakeSessionEntity entity) {
        try {
            return objectMapper.readValue(entity.getAggregateJson(), IntakeSession.class);
        } catch (JsonProcessingException error) {
            throw persistenceError("deserialize intake session", error);
        }
    }

    private String json(IntakeSession session) {
        try {
            return objectMapper.writeValueAsString(session);
        } catch (JsonProcessingException error) {
            throw persistenceError("serialize intake session", error);
        }
    }

    private String routingKey(IntakeMessageEnvelope envelope) {
        return routingKey(
                envelope.workspaceId(), envelope.source(),
                envelope.conversationRef(), envelope.reporterRef());
    }

    private String routingKey(
            long workspaceId,
            String source,
            String conversationRef,
            String reporterRef) {
        return sha256(workspaceId + "\u0000"
                + source + "\u0000"
                + conversationRef + "\u0000"
                + reporterRef);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private ReentrantLock lock(String routingKey) {
        int index = (routingKey.hashCode() & Integer.MAX_VALUE) % locks.length;
        return locks[index];
    }

    private String newSessionId() {
        return "intake-" + UUID.randomUUID().toString().replace("-", "");
    }

    private LocalDateTime toLocal(java.time.Instant value) {
        return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private MateClawException persistenceError(String operation, Exception error) {
        String suffix = error == null || error.getMessage() == null
                ? ""
                : ": " + error.getMessage();
        return new MateClawException(
                "err.troubleshooting.intake_persistence",
                500,
                "failed to " + operation + suffix);
    }

    private record ConversationModeRequest(boolean active, Boolean requested) {
        private static ConversationModeRequest none() {
            return new ConversationModeRequest(false, null);
        }

        private static ConversationModeRequest requested(Boolean rehearsal) {
            return new ConversationModeRequest(true, rehearsal);
        }

        private Boolean initialMode() {
            if (!active) {
                return null;
            }
            return requested == null ? Boolean.TRUE : requested;
        }
    }
}
