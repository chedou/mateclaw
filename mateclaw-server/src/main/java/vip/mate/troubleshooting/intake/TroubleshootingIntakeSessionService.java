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
import vip.mate.troubleshooting.repository.TroubleshootingIntakeSessionMapper;

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
    private final ObjectMapper objectMapper;
    private final IntakeSessionReducer reducer;
    private final TransactionTemplate transactionTemplate;
    private final ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];

    @Autowired
    public TroubleshootingIntakeSessionService(
            TroubleshootingIntakeSessionMapper sessionMapper,
            TroubleshootingIntakeMessageReceiptMapper receiptMapper,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this(
                sessionMapper,
                receiptMapper,
                objectMapper,
                new IntakeSessionReducer(),
                new TransactionTemplate(transactionManager));
    }

    TroubleshootingIntakeSessionService(
            TroubleshootingIntakeSessionMapper sessionMapper,
            TroubleshootingIntakeMessageReceiptMapper receiptMapper,
            ObjectMapper objectMapper,
            IntakeSessionReducer reducer) {
        this(sessionMapper, receiptMapper, objectMapper, reducer, null);
    }

    private TroubleshootingIntakeSessionService(
            TroubleshootingIntakeSessionMapper sessionMapper,
            TroubleshootingIntakeMessageReceiptMapper receiptMapper,
            ObjectMapper objectMapper,
            IntakeSessionReducer reducer,
            TransactionTemplate transactionTemplate) {
        this.sessionMapper = sessionMapper;
        this.receiptMapper = receiptMapper;
        this.objectMapper = objectMapper;
        this.reducer = reducer;
        this.transactionTemplate = transactionTemplate;
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new ReentrantLock();
        }
    }

    public IntakeDecision accept(IntakeMessageEnvelope envelope) {
        String routingKey = routingKey(envelope);
        ReentrantLock lock = lock(routingKey);
        lock.lock();
        try {
            try {
                return inTransaction(() -> acceptOnce(envelope, routingKey));
            } catch (DuplicateKeyException concurrentWinner) {
                // The first transaction is fully rolled back before execute()
                // rethrows. A single fresh transaction can now observe the
                // committed winner and either return the duplicate decision or
                // apply this distinct message to the winner's active session.
                return inTransaction(() -> acceptOnce(envelope, routingKey));
            }
        } finally {
            // TransactionTemplate commits/rolls back before returning, so the
            // striped lock covers the actual database transaction boundary.
            lock.unlock();
        }
    }

    private IntakeDecision acceptOnce(
            IntakeMessageEnvelope envelope,
            String routingKey) {
        TroubleshootingIntakeMessageReceiptEntity existingReceipt = findReceipt(envelope);
        if (existingReceipt != null) {
            return IntakeDecision.from(
                    read(requiredSession(
                            envelope.workspaceId(), existingReceipt.getIntakeSessionId())),
                    true,
                    false);
        }

        TroubleshootingIntakeSessionEntity latest = findLatest(
                envelope.workspaceId(), routingKey);
        if (latest == null) {
            return create(envelope, routingKey);
        }

        IntakeSession newest = read(latest);
        if (!envelope.receivedAt().isBefore(newest.reportedAt())) {
            if (newest.status() != IntakeSessionStatus.READY) {
                claimMessage(envelope, newest.intakeSessionId());
                return applyToExisting(latest, envelope);
            }
            if (!envelope.receivedAt().isAfter(newest.lastMessageAt())) {
                claimMessage(envelope, newest.intakeSessionId());
                return IntakeDecision.from(newest, false, true);
            }
            return create(envelope, routingKey);
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
        IntakeSession owner = read(historical);
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
        if (next.equals(current)) {
            return IntakeDecision.from(current, false, false);
        }
        update(entity, next);
        return IntakeDecision.from(next, false, false);
    }

    private void insert(
            String routingKey,
            IntakeSession session) {
        TroubleshootingIntakeSessionEntity entity = entity(routingKey, session);
        sessionMapper.insert(entity);
    }

    private IntakeDecision create(
            IntakeMessageEnvelope envelope,
            String routingKey) {
        String sessionId = newSessionId();
        claimMessage(envelope, sessionId);
        IntakeSession created = reducer.start(sessionId, envelope);
        insert(routingKey, created);
        return IntakeDecision.from(created, false, false);
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

    private void update(TroubleshootingIntakeSessionEntity current, IntakeSession next) {
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
            IntakeSession session) {
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
        return receiptMapper.selectOne(
                new LambdaQueryWrapper<TroubleshootingIntakeMessageReceiptEntity>()
                        .eq(TroubleshootingIntakeMessageReceiptEntity::getWorkspaceId,
                                envelope.workspaceId())
                        .eq(TroubleshootingIntakeMessageReceiptEntity::getSource,
                                envelope.source())
                        .eq(TroubleshootingIntakeMessageReceiptEntity::getSourceMessageId,
                                envelope.sourceMessageId())
                        .eq(TroubleshootingIntakeMessageReceiptEntity::getDeleted, 0));
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
        return sha256(envelope.workspaceId() + "\u0000"
                + envelope.source() + "\u0000"
                + envelope.conversationRef() + "\u0000"
                + envelope.reporterRef());
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
}
