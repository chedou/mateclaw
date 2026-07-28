package vip.mate.troubleshooting.intake;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vip.mate.channel.ChannelManager;
import vip.mate.channel.DeliveryOptions;
import vip.mate.troubleshooting.TroubleshootingSecretRedactor;
import vip.mate.troubleshooting.model.ClosureRecord;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.DiagnosisStatus;
import vip.mate.troubleshooting.model.TroubleshootingDiagnosisEntity;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.BusinessSummary;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjectionService;
import vip.mate.troubleshooting.repository.TroubleshootingDiagnosisMapper;
import vip.mate.troubleshooting.service.StoredDiagnosis;
import vip.mate.troubleshooting.service.TroubleshootingPersistenceService;

import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Durable CLOSED -&gt; original channel outcome delivery.
 *
 * <p>The Diagnosis update schedules delivery in its own transaction. Only the
 * node that owns the exact workspace channel route claims the row, and a
 * platform ACK must arrive before completion. Failures remain retryable with
 * no hard attempt cap; closing the incident is never rolled back merely
 * because the IM platform is temporarily unavailable.</p>
 */
@Slf4j
@Component
public class ClosureNotificationPoller {

    private static final int BATCH_SIZE = 20;
    private static final int LEASE_SECONDS = 120;
    private static final int BASE_RETRY_SECONDS = 5;

    private final TroubleshootingDiagnosisMapper mapper;
    private final TroubleshootingIntakeSessionService sessions;
    private final TroubleshootingPersistenceService persistence;
    private final DiagnosisExperienceProjectionService projectionService;
    private final ChannelManager channelManager;
    private final TroubleshootingClosureNotificationRenderer renderer;
    private final Clock clock;
    private final String workerId;

    @Autowired
    public ClosureNotificationPoller(
            TroubleshootingDiagnosisMapper mapper,
            TroubleshootingIntakeSessionService sessions,
            TroubleshootingPersistenceService persistence,
            DiagnosisExperienceProjectionService projectionService,
            ChannelManager channelManager,
            TroubleshootingClosureNotificationRenderer renderer) {
        this(
                mapper,
                sessions,
                persistence,
                projectionService,
                channelManager,
                renderer,
                Clock.systemUTC(),
                defaultWorkerId());
    }

    ClosureNotificationPoller(
            TroubleshootingDiagnosisMapper mapper,
            TroubleshootingIntakeSessionService sessions,
            TroubleshootingPersistenceService persistence,
            DiagnosisExperienceProjectionService projectionService,
            ChannelManager channelManager,
            TroubleshootingClosureNotificationRenderer renderer,
            Clock clock,
            String workerId) {
        this.mapper = mapper;
        this.sessions = sessions;
        this.persistence = persistence;
        this.projectionService = projectionService;
        this.channelManager = channelManager;
        this.renderer = renderer;
        this.clock = clock;
        this.workerId = workerId;
    }

    @Scheduled(
            fixedDelayString = "${mateclaw.troubleshooting.closure-notification.poll-ms:1000}",
            initialDelayString = "${mateclaw.troubleshooting.closure-notification.initial-delay-ms:3000}")
    public void dispatchClosures() {
        LocalDateTime selectionTime = now();
        for (TroubleshootingDiagnosisEntity candidate : selectCandidates(selectionTime)) {
            dispatch(candidate);
        }
    }

    private List<TroubleshootingDiagnosisEntity> selectCandidates(LocalDateTime selectionTime) {
        return mapper.selectList(
                new LambdaQueryWrapper<TroubleshootingDiagnosisEntity>()
                        .eq(TroubleshootingDiagnosisEntity::getDeleted, 0)
                        .isNotNull(TroubleshootingDiagnosisEntity::getSourceIntakeSessionId)
                        .and(state -> state
                                .and(due -> due
                                        .in(
                                                TroubleshootingDiagnosisEntity::getClosureNotificationStatus,
                                                ClosureNotificationStatus.PENDING.name(),
                                                ClosureNotificationStatus.FAILED.name())
                                        .and(schedule -> schedule
                                                .isNull(TroubleshootingDiagnosisEntity::getClosureNotificationNextAttemptAt)
                                                .or()
                                                .le(
                                                        TroubleshootingDiagnosisEntity::getClosureNotificationNextAttemptAt,
                                                        selectionTime)))
                                .or(expired -> expired
                                        .eq(
                                                TroubleshootingDiagnosisEntity::getClosureNotificationStatus,
                                                ClosureNotificationStatus.PROCESSING.name())
                                        .and(lease -> lease
                                                .isNull(TroubleshootingDiagnosisEntity::getClosureNotificationLeaseExpiresAt)
                                                .or()
                                                .lt(
                                                        TroubleshootingDiagnosisEntity::getClosureNotificationLeaseExpiresAt,
                                                        selectionTime))))
                        .orderByAsc(TroubleshootingDiagnosisEntity::getUpdateTime)
                        .last("LIMIT " + BATCH_SIZE));
    }

    private void dispatch(TroubleshootingDiagnosisEntity candidate) {
        Optional<ReadyIntakeDispatch> routable = routable(candidate);
        if (routable.isEmpty()) {
            return;
        }
        LocalDateTime claimTime = now();
        if (mapper.claimClosureNotification(
                candidate.getId(),
                workerId,
                claimTime.plusSeconds(LEASE_SECONDS),
                claimTime) != 1) {
            return;
        }
        TroubleshootingDiagnosisEntity claimed = mapper.selectById(candidate.getId());
        if (claimed == null) {
            return;
        }
        try {
            StoredDiagnosis stored = persistence.get(
                    claimed.getWorkspaceId(), claimed.getDiagnosisId());
            Diagnosis diagnosis = stored.diagnosis();
            ClosureRecord closure = diagnosis.closure();
            if (diagnosis.status() != DiagnosisStatus.CLOSED || closure == null) {
                throw new IllegalStateException(
                        "closure notification requires a closed diagnosis with outcome");
            }
            BusinessSummary summary = projectionService
                    .project(claimed.getWorkspaceId(), claimed.getDiagnosisId())
                    .businessSummary();
            ReadyIntakeDispatch dispatch = routable.get();
            send(
                    dispatch,
                    renderer.render(summary, closure),
                    DeliveryOptions.mentioningUsers(
                            List.of(dispatch.session().reporterRef())));
            if (mapper.markClosureNotificationCompleted(
                    claimed.getId(), workerId, now()) != 1) {
                log.warn(
                        "[ts-closure] notification ACKed but completion lease was lost: diagnosis={}",
                        claimed.getDiagnosisId());
            }
        } catch (Exception error) {
            fail(claimed, error);
        }
    }

    private Optional<ReadyIntakeDispatch> routable(TroubleshootingDiagnosisEntity candidate) {
        String intakeSessionId = candidate.getSourceIntakeSessionId();
        if (intakeSessionId == null || intakeSessionId.isBlank()) {
            return Optional.empty();
        }
        try {
            ReadyIntakeDispatch dispatch = sessions.getReadyDispatch(
                    candidate.getWorkspaceId(), intakeSessionId);
            IntakeSession session = dispatch.session();
            if (!channelManager.canSendToWorkspaceConversation(
                    session.workspaceId(), session.source(), dispatch.routeRef())) {
                return Optional.empty();
            }
            return Optional.of(dispatch);
        } catch (RuntimeException error) {
            log.warn(
                    "[ts-closure] notification left unclaimed because its route is unavailable: diagnosis={} error={}",
                    candidate.getDiagnosisId(),
                    safeError(error));
            return Optional.empty();
        }
    }

    private void send(
            ReadyIntakeDispatch dispatch,
            String content,
            DeliveryOptions options) {
        IntakeSession session = dispatch.session();
        channelManager.sendToWorkspaceConversation(
                session.workspaceId(),
                session.source(),
                dispatch.routeRef(),
                content,
                options);
    }

    private void fail(TroubleshootingDiagnosisEntity claimed, Exception error) {
        String message = safeError(error);
        int attempts = claimed.getClosureNotificationAttempts() == null
                ? 1
                : claimed.getClosureNotificationAttempts();
        LocalDateTime retryAt = now().plusSeconds(retryDelaySeconds(attempts));
        if (mapper.markClosureNotificationFailed(
                claimed.getId(), workerId, message, retryAt, now()) != 1) {
            log.warn(
                    "[ts-closure] failed to persist notification retry: diagnosis={} attempt={}",
                    claimed.getDiagnosisId(), attempts);
        }
        log.warn(
                "[ts-closure] outcome notification will retry: diagnosis={} attempt={} error={}",
                claimed.getDiagnosisId(), attempts, message);
    }

    private long retryDelaySeconds(int attempts) {
        int exponent = Math.max(0, Math.min(attempts - 1, 6));
        return BASE_RETRY_SECONDS * (1L << exponent);
    }

    private String safeError(Exception error) {
        String message = error.getMessage() == null
                ? error.getClass().getSimpleName()
                : error.getMessage();
        String redacted = TroubleshootingSecretRedactor.redact(message);
        return redacted.length() <= 2000 ? redacted : redacted.substring(0, 2000);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static String defaultWorkerId() {
        return "troubleshooting-closure-" + ManagementFactory.getRuntimeMXBean().getName();
    }
}
