package vip.mate.troubleshooting.intake;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vip.mate.channel.ChannelManager;
import vip.mate.troubleshooting.TroubleshootingSecretRedactor;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.BusinessSummary;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjectionService;
import vip.mate.troubleshooting.repository.TroubleshootingIntakeInvestigationMapper;
import vip.mate.troubleshooting.service.StoredDiagnosis;
import vip.mate.troubleshooting.service.TroubleshootingIntakeService;
import vip.mate.troubleshooting.service.TroubleshootingPersistenceService;

import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lease-based READY -&gt; Diagnosis dispatcher with durable terminal delivery.
 *
 * <p>The channel callback commits only Intake plus a PENDING task. A locally
 * routable channel leader claims the slow read-only investigation, links the
 * idempotent Diagnosis, waits for the platform delivery ACK, and completes the
 * lease. Exhausted investigation attempts transition to a second persistent
 * delivery state so even the explicit fail-closed message is retried until a
 * channel leader can acknowledge it.</p>
 */
@Slf4j
@Component
public class IntakeInvestigationPoller {

    private static final int BATCH_SIZE = 20;
    private static final int MAX_ATTEMPTS = 5;
    private static final int LEASE_SECONDS = 120;
    private static final int BASE_RETRY_SECONDS = 5;

    private final TroubleshootingIntakeInvestigationMapper mapper;
    private final TroubleshootingIntakeSessionService sessions;
    private final TroubleshootingIntakeService intakeService;
    private final TroubleshootingPersistenceService persistence;
    private final DiagnosisExperienceProjectionService projectionService;
    private final ChannelManager channelManager;
    private final TroubleshootingChannelSummaryRenderer renderer;
    private final Clock clock;
    private final String workerId;
    private final AtomicBoolean historicalReadyReconciled = new AtomicBoolean(false);

    @Autowired
    public IntakeInvestigationPoller(
            TroubleshootingIntakeInvestigationMapper mapper,
            TroubleshootingIntakeSessionService sessions,
            TroubleshootingIntakeService intakeService,
            TroubleshootingPersistenceService persistence,
            DiagnosisExperienceProjectionService projectionService,
            ChannelManager channelManager,
            TroubleshootingChannelSummaryRenderer renderer) {
        this(
                mapper,
                sessions,
                intakeService,
                persistence,
                projectionService,
                channelManager,
                renderer,
                Clock.systemUTC(),
                defaultWorkerId());
    }

    IntakeInvestigationPoller(
            TroubleshootingIntakeInvestigationMapper mapper,
            TroubleshootingIntakeSessionService sessions,
            TroubleshootingIntakeService intakeService,
            TroubleshootingPersistenceService persistence,
            DiagnosisExperienceProjectionService projectionService,
            ChannelManager channelManager,
            TroubleshootingChannelSummaryRenderer renderer,
            Clock clock,
            String workerId) {
        this.mapper = mapper;
        this.sessions = sessions;
        this.intakeService = intakeService;
        this.persistence = persistence;
        this.projectionService = projectionService;
        this.channelManager = channelManager;
        this.renderer = renderer;
        this.clock = clock;
        this.workerId = workerId;
    }

    @Scheduled(
            fixedDelayString = "${mateclaw.troubleshooting.intake-dispatch.poll-ms:1000}",
            initialDelayString = "${mateclaw.troubleshooting.intake-dispatch.initial-delay-ms:3000}")
    public void dispatchReady() {
        reconcileHistoricalReadyOnce();
        LocalDateTime selectionTime = now();
        for (TroubleshootingIntakeInvestigationEntity candidate
                : selectNormalCandidates(selectionTime)) {
            dispatchNormal(candidate);
        }
        for (TroubleshootingIntakeInvestigationEntity candidate
                : selectTerminalCandidates(selectionTime)) {
            dispatchTerminal(candidate);
        }
    }

    private void reconcileHistoricalReadyOnce() {
        if (historicalReadyReconciled.get()) {
            return;
        }
        synchronized (historicalReadyReconciled) {
            if (historicalReadyReconciled.get()) {
                return;
            }
            try {
                int created = sessions.reconcileReadyInvestigations();
                historicalReadyReconciled.set(true);
                if (created > 0) {
                    log.info("[ts-intake] reconciled {} historical READY investigation tasks",
                            created);
                }
            } catch (RuntimeException error) {
                log.warn("[ts-intake] historical READY reconciliation will retry: {}",
                        error.getMessage());
            }
        }
    }

    private List<TroubleshootingIntakeInvestigationEntity> selectNormalCandidates(
            LocalDateTime selectionTime) {
        return mapper.selectList(
                new LambdaQueryWrapper<TroubleshootingIntakeInvestigationEntity>()
                        .eq(TroubleshootingIntakeInvestigationEntity::getDeleted, 0)
                        .lt(TroubleshootingIntakeInvestigationEntity::getAttempts, MAX_ATTEMPTS)
                        .and(state -> state
                                .and(due -> due
                                        .in(
                                                TroubleshootingIntakeInvestigationEntity::getStatus,
                                                IntakeInvestigationStatus.PENDING,
                                                IntakeInvestigationStatus.FAILED)
                                        .and(schedule -> schedule
                                                .isNull(TroubleshootingIntakeInvestigationEntity::getNextAttemptAt)
                                                .or()
                                                .le(
                                                        TroubleshootingIntakeInvestigationEntity::getNextAttemptAt,
                                                        selectionTime)))
                                .or(expired -> expired
                                        .eq(
                                                TroubleshootingIntakeInvestigationEntity::getStatus,
                                                IntakeInvestigationStatus.PROCESSING)
                                        .and(lease -> lease
                                                .isNull(TroubleshootingIntakeInvestigationEntity::getLeaseExpiresAt)
                                                .or()
                                                .lt(
                                                        TroubleshootingIntakeInvestigationEntity::getLeaseExpiresAt,
                                                        selectionTime))))
                        .orderByAsc(TroubleshootingIntakeInvestigationEntity::getCreateTime)
                        .last("LIMIT " + BATCH_SIZE));
    }

    private List<TroubleshootingIntakeInvestigationEntity> selectTerminalCandidates(
            LocalDateTime selectionTime) {
        return mapper.selectList(
                new LambdaQueryWrapper<TroubleshootingIntakeInvestigationEntity>()
                        .eq(TroubleshootingIntakeInvestigationEntity::getDeleted, 0)
                        .and(state -> state
                                .and(due -> due
                                        .eq(
                                                TroubleshootingIntakeInvestigationEntity::getStatus,
                                                IntakeInvestigationStatus.TERMINAL_PENDING)
                                        .and(schedule -> schedule
                                                .isNull(TroubleshootingIntakeInvestigationEntity::getNextAttemptAt)
                                                .or()
                                                .le(
                                                        TroubleshootingIntakeInvestigationEntity::getNextAttemptAt,
                                                        selectionTime)))
                                .or(expired -> expired
                                        .eq(
                                                TroubleshootingIntakeInvestigationEntity::getStatus,
                                                IntakeInvestigationStatus.TERMINAL_PROCESSING)
                                        .and(lease -> expiredLease(lease, selectionTime)))
                                .or(abandoned -> abandoned
                                        .eq(
                                                TroubleshootingIntakeInvestigationEntity::getStatus,
                                                IntakeInvestigationStatus.PROCESSING)
                                        .ge(TroubleshootingIntakeInvestigationEntity::getAttempts,
                                                MAX_ATTEMPTS)
                                        .and(lease -> expiredLease(lease, selectionTime))))
                        .orderByAsc(TroubleshootingIntakeInvestigationEntity::getCreateTime)
                        .last("LIMIT " + BATCH_SIZE));
    }

    private void expiredLease(
            LambdaQueryWrapper<TroubleshootingIntakeInvestigationEntity> lease,
            LocalDateTime selectionTime) {
        lease.isNull(TroubleshootingIntakeInvestigationEntity::getLeaseExpiresAt)
                .or()
                .lt(TroubleshootingIntakeInvestigationEntity::getLeaseExpiresAt, selectionTime);
    }

    private void dispatchNormal(TroubleshootingIntakeInvestigationEntity candidate) {
        Optional<ReadyIntakeDispatch> routable = routable(candidate);
        if (routable.isEmpty()) {
            return;
        }
        LocalDateTime claimTime = now();
        if (mapper.claim(
                candidate.getId(),
                workerId,
                claimTime.plusSeconds(LEASE_SECONDS),
                claimTime,
                MAX_ATTEMPTS) != 1) {
            return;
        }
        TroubleshootingIntakeInvestigationEntity claimed = mapper.selectById(candidate.getId());
        if (claimed == null) {
            return;
        }
        try {
            IntakeSession session = routable.get().session();
            String diagnosisId = claimed.getDiagnosisId();
            if (diagnosisId == null || diagnosisId.isBlank()) {
                StoredDiagnosis stored = intakeService.report(session);
                diagnosisId = stored.diagnosis().diagnosisId();
                if (mapper.attachDiagnosis(
                        claimed.getId(), workerId, diagnosisId, now()) != 1) {
                    throw new IllegalStateException(
                            "lost intake investigation lease while linking diagnosis");
                }
                claimed.setDiagnosisId(diagnosisId);
            }
            BusinessSummary summary = projectionService
                    .project(claimed.getWorkspaceId(), diagnosisId)
                    .businessSummary();
            send(routable.get(), renderer.render(summary));
            if (mapper.markCompleted(claimed.getId(), workerId, now()) != 1) {
                log.warn("[ts-intake] notification ACKed but completion lease was lost: intake={}",
                        claimed.getIntakeSessionId());
            }
        } catch (Exception error) {
            failNormal(claimed, error);
        }
    }

    private void failNormal(
            TroubleshootingIntakeInvestigationEntity claimed,
            Exception error) {
        String message = safeError(error);
        int attempts = claimed.getAttempts() == null ? 1 : claimed.getAttempts();
        LocalDateTime retryAt = now().plusSeconds(retryDelaySeconds(attempts));
        int changed = attempts >= MAX_ATTEMPTS
                ? mapper.markTerminalPending(
                        claimed.getId(), workerId, message, retryAt, now())
                : mapper.markFailed(
                        claimed.getId(), workerId, message, retryAt, now());
        if (changed != 1) {
            log.warn("[ts-intake] failed to persist retry transition: intake={} attempt={}",
                    claimed.getIntakeSessionId(), attempts);
        }
        log.warn("[ts-intake] READY dispatch failed: intake={} attempt={} error={}",
                claimed.getIntakeSessionId(), attempts, message);
    }

    private void dispatchTerminal(TroubleshootingIntakeInvestigationEntity candidate) {
        Optional<ReadyIntakeDispatch> routable = routable(candidate);
        if (routable.isEmpty()) {
            return;
        }
        LocalDateTime claimTime = now();
        if (mapper.claimTerminal(
                candidate.getId(),
                workerId,
                claimTime.plusSeconds(LEASE_SECONDS),
                claimTime,
                MAX_ATTEMPTS) != 1) {
            return;
        }
        TroubleshootingIntakeInvestigationEntity claimed = mapper.selectById(candidate.getId());
        if (claimed == null) {
            return;
        }
        try {
            String diagnosisId = findOrAttachPersistedDiagnosis(claimed);
            String content;
            if (diagnosisId == null) {
                content = terminalMessage(claimed);
            } else {
                BusinessSummary summary = projectionService
                        .project(claimed.getWorkspaceId(), diagnosisId)
                        .businessSummary();
                content = renderer.render(summary);
            }
            send(routable.get(), content);
            if (mapper.markTerminalCompleted(claimed.getId(), workerId, now()) != 1) {
                log.warn("[ts-intake] terminal notification ACKed but completion lease was lost: intake={}",
                        claimed.getIntakeSessionId());
            }
        } catch (Exception error) {
            String message = safeError(error);
            int attempts = claimed.getTerminalAttempts() == null
                    ? 1
                    : claimed.getTerminalAttempts();
            LocalDateTime retryAt = now().plusSeconds(retryDelaySeconds(attempts));
            if (mapper.rescheduleTerminal(
                    claimed.getId(), workerId, message, retryAt, now()) != 1) {
                log.warn("[ts-intake] failed to persist terminal retry: intake={} attempt={}",
                        claimed.getIntakeSessionId(), attempts);
            }
            log.warn("[ts-intake] terminal notification will retry: intake={} attempt={} error={}",
                    claimed.getIntakeSessionId(), attempts, message);
        }
    }

    private String findOrAttachPersistedDiagnosis(
            TroubleshootingIntakeInvestigationEntity claimed) {
        String diagnosisId = claimed.getDiagnosisId();
        if (diagnosisId != null && !diagnosisId.isBlank()) {
            return diagnosisId;
        }
        Optional<StoredDiagnosis> persisted = persistence.findByIntakeSessionId(
                claimed.getWorkspaceId(), claimed.getIntakeSessionId());
        if (persisted.isEmpty()) {
            return null;
        }
        diagnosisId = persisted.get().diagnosis().diagnosisId();
        if (mapper.attachDiagnosis(claimed.getId(), workerId, diagnosisId, now()) != 1) {
            throw new IllegalStateException(
                    "lost intake investigation lease while recovering diagnosis link");
        }
        claimed.setDiagnosisId(diagnosisId);
        return diagnosisId;
    }

    private Optional<ReadyIntakeDispatch> routable(
            TroubleshootingIntakeInvestigationEntity candidate) {
        try {
            ReadyIntakeDispatch dispatch = sessions.getReadyDispatch(
                    candidate.getWorkspaceId(), candidate.getIntakeSessionId());
            IntakeSession session = dispatch.session();
            if (!channelManager.canSendToWorkspaceConversation(
                    session.workspaceId(), session.source(), dispatch.routeRef())) {
                return Optional.empty();
            }
            return Optional.of(dispatch);
        } catch (RuntimeException error) {
            log.warn("[ts-intake] task left unclaimed because its READY route is unavailable: intake={} error={}",
                    candidate.getIntakeSessionId(), safeError(error));
            return Optional.empty();
        }
    }

    private void send(ReadyIntakeDispatch dispatch, String content) {
        IntakeSession session = dispatch.session();
        channelManager.sendToWorkspaceConversation(
                session.workspaceId(), session.source(), dispatch.routeRef(), content);
    }

    private String terminalMessage(TroubleshootingIntakeInvestigationEntity claimed) {
        StringBuilder message = new StringBuilder()
                .append("只读调查未能安全完成，系统已停止自动判断。")
                .append("\nIntake ID: ")
                .append(claimed.getIntakeSessionId())
                .append("\n请联系值班人员人工深查；MateClaw 未执行任何生产变更。");
        return message.toString();
    }

    private String safeError(Exception error) {
        String message = error.getMessage() == null
                ? error.getClass().getSimpleName()
                : error.getMessage();
        return truncate(TroubleshootingSecretRedactor.redact(message));
    }

    private long retryDelaySeconds(int attempts) {
        int exponent = Math.max(0, Math.min(attempts - 1, 3));
        return BASE_RETRY_SECONDS * (1L << exponent);
    }

    private String truncate(String message) {
        return message.length() <= 2000 ? message : message.substring(0, 2000);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static String defaultWorkerId() {
        return "troubleshooting-intake-" + ManagementFactory.getRuntimeMXBean().getName();
    }
}
