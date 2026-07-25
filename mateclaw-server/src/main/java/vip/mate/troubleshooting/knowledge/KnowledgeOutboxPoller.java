package vip.mate.troubleshooting.knowledge;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vip.mate.troubleshooting.model.KnowledgeCandidate;
import vip.mate.troubleshooting.model.KnowledgePublicationStatus;
import vip.mate.troubleshooting.model.TroubleshootingKnowledgeOutboxEntity;
import vip.mate.troubleshooting.repository.TroubleshootingKnowledgeOutboxMapper;

import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** Lease-based, retryable knowledge outbox poller. No sink means fail-closed no-op. */
@Slf4j
@Component
public class KnowledgeOutboxPoller {

    private static final int BATCH_SIZE = 20;
    private static final int LEASE_SECONDS = 60;

    private final TroubleshootingKnowledgeOutboxMapper mapper;
    private final ObjectProvider<KnowledgePublicationSink> sinkProvider;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String workerId;

    @Autowired
    public KnowledgeOutboxPoller(
            TroubleshootingKnowledgeOutboxMapper mapper,
            ObjectProvider<KnowledgePublicationSink> sinkProvider,
            ObjectMapper objectMapper) {
        this(mapper, sinkProvider, objectMapper, Clock.systemUTC(), defaultWorkerId());
    }

    public KnowledgeOutboxPoller(
            TroubleshootingKnowledgeOutboxMapper mapper,
            ObjectProvider<KnowledgePublicationSink> sinkProvider,
            ObjectMapper objectMapper,
            Clock clock,
            String workerId) {
        this.mapper = mapper;
        this.sinkProvider = sinkProvider;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.workerId = workerId;
    }

    @Scheduled(
            fixedDelayString = "${mateclaw.troubleshooting.outbox.poll-ms:5000}",
            initialDelayString = "${mateclaw.troubleshooting.outbox.initial-delay-ms:30000}")
    public void publishPending() {
        KnowledgePublicationSink sink = sinkProvider.getIfAvailable();
        if (sink == null) {
            return;
        }
        LocalDateTime selectionTime = now();
        List<TroubleshootingKnowledgeOutboxEntity> candidates = mapper.selectList(
                new LambdaQueryWrapper<TroubleshootingKnowledgeOutboxEntity>()
                        .eq(TroubleshootingKnowledgeOutboxEntity::getDeleted, 0)
                        .and(status -> status
                                .in(
                                        TroubleshootingKnowledgeOutboxEntity::getStatus,
                                        KnowledgePublicationStatus.PENDING,
                                        KnowledgePublicationStatus.FAILED)
                                .or(expired -> expired
                                        .eq(
                                                TroubleshootingKnowledgeOutboxEntity::getStatus,
                                                KnowledgePublicationStatus.PROCESSING)
                                        .lt(
                                                TroubleshootingKnowledgeOutboxEntity::getLeaseExpiresAt,
                                                selectionTime)))
                        .orderByAsc(TroubleshootingKnowledgeOutboxEntity::getCreateTime)
                        .last("LIMIT " + BATCH_SIZE));
        for (TroubleshootingKnowledgeOutboxEntity candidate : candidates) {
            publishOne(candidate, sink);
        }
    }

    private void publishOne(
            TroubleshootingKnowledgeOutboxEntity candidate,
            KnowledgePublicationSink sink) {
        LocalDateTime claimTime = now();
        LocalDateTime leaseExpiresAt = claimTime.plusSeconds(LEASE_SECONDS);
        if (mapper.claim(candidate.getId(), workerId, leaseExpiresAt, claimTime) != 1) {
            return;
        }
        TroubleshootingKnowledgeOutboxEntity claimed = mapper.selectById(candidate.getId());
        if (claimed == null) {
            return;
        }
        try {
            KnowledgeCandidate payload = objectMapper.readValue(
                    claimed.getPayloadJson(), KnowledgeCandidate.class);
            sink.publish(claimed.getWorkspaceId(), payload);
            mapper.markPublished(claimed.getId(), workerId, now());
        } catch (Exception error) {
            String message = error.getMessage() == null
                    ? error.getClass().getSimpleName()
                    : error.getMessage();
            mapper.markFailed(claimed.getId(), workerId, truncate(message), now());
            log.warn("Troubleshooting knowledge publication {} failed: {}",
                    claimed.getPublicationId(), message);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private String truncate(String message) {
        return message.length() <= 2000 ? message : message.substring(0, 2000);
    }

    private static String defaultWorkerId() {
        return "troubleshooting-outbox-" + ManagementFactory.getRuntimeMXBean().getName();
    }
}
