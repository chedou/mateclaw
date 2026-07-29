package vip.mate.troubleshooting.evaluation;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Callable;

/** Production heartbeat for a long evidence/model baseline call. */
@Slf4j
@Component
public final class ScheduledBaselineClaimLeaseKeeper
        implements BaselineClaimLeaseKeeper {

    static final Duration LEASE = Duration.ofMinutes(15);
    static final Duration HEARTBEAT_INTERVAL = Duration.ofMinutes(4);

    private final ScheduledExecutorService scheduler;
    private final Duration lease;
    private final Duration heartbeatInterval;

    public ScheduledBaselineClaimLeaseKeeper() {
        this(
                Executors.newSingleThreadScheduledExecutor(
                        Thread.ofVirtual()
                                .name("t8-baseline-claim-heartbeat-", 0)
                                .factory()),
                LEASE,
                HEARTBEAT_INTERVAL);
    }

    ScheduledBaselineClaimLeaseKeeper(
            ScheduledExecutorService scheduler,
            Duration lease,
            Duration heartbeatInterval) {
        if (scheduler == null || lease == null || heartbeatInterval == null
                || lease.isZero() || lease.isNegative()
                || heartbeatInterval.isZero() || heartbeatInterval.isNegative()
                || heartbeatInterval.compareTo(lease) >= 0) {
            throw new IllegalArgumentException("baseline heartbeat timing is invalid");
        }
        this.scheduler = scheduler;
        this.lease = lease;
        this.heartbeatInterval = heartbeatInterval;
    }

    @Override
    public Duration leaseDuration() {
        return lease;
    }

    @Override
    public LeaseHandle keepAlive(
            long workspaceId,
            BaselineEvaluationRunStore.RunClaim claim,
            BaselineEvaluationRunStore store,
            Clock clock) {
        if (workspaceId <= 0 || claim == null || store == null || clock == null) {
            throw new IllegalArgumentException("baseline heartbeat identity is required");
        }
        AtomicBoolean owned = new AtomicBoolean(true);
        AtomicReference<Thread> activeExternalThread = new AtomicReference<>();
        long intervalMs = heartbeatInterval.toMillis();
        ScheduledFuture<?> heartbeat = scheduler.scheduleAtFixedRate(
                () -> renew(
                        workspaceId,
                        claim,
                        store,
                        clock,
                        owned,
                        activeExternalThread),
                intervalMs,
                intervalMs,
                TimeUnit.MILLISECONDS);
        return new ScheduledLeaseHandle(owned, activeExternalThread, heartbeat);
    }

    private void renew(
            long workspaceId,
            BaselineEvaluationRunStore.RunClaim claim,
            BaselineEvaluationRunStore store,
            Clock clock,
            AtomicBoolean owned,
            AtomicReference<Thread> activeExternalThread) {
        if (!owned.get()) {
            return;
        }
        try {
            Instant now = Instant.now(clock);
            if (!store.renew(workspaceId, claim, now.plus(lease))) {
                loseOwnership(owned, activeExternalThread);
                log.warn("[T8 baseline] claim heartbeat lost ownership: run={}",
                        claim.runId());
            }
        } catch (RuntimeException failure) {
            loseOwnership(owned, activeExternalThread);
            log.warn("[T8 baseline] claim heartbeat failed: run={} error={}",
                    claim.runId(), failure.getClass().getSimpleName());
        }
    }

    private void loseOwnership(
            AtomicBoolean owned,
            AtomicReference<Thread> activeExternalThread) {
        if (!owned.compareAndSet(true, false)) {
            return;
        }
        Thread active = activeExternalThread.get();
        if (active != null) {
            active.interrupt();
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdown();
    }

    private static final class ScheduledLeaseHandle implements LeaseHandle {

        private final AtomicBoolean ownership;
        private final AtomicReference<Thread> activeExternalThread;
        private final ScheduledFuture<?> heartbeat;

        private ScheduledLeaseHandle(
                AtomicBoolean ownership,
                AtomicReference<Thread> activeExternalThread,
                ScheduledFuture<?> heartbeat) {
            this.ownership = ownership;
            this.activeExternalThread = activeExternalThread;
            this.heartbeat = heartbeat;
        }

        @Override
        public boolean owned() {
            return ownership.get();
        }

        @Override
        public <T> T executeExternal(Callable<T> externalCall) {
            if (externalCall == null) {
                throw new IllegalArgumentException("external baseline call is required");
            }
            requireOwnership();
            Thread current = Thread.currentThread();
            boolean interruptedBefore = current.isInterrupted();
            if (!activeExternalThread.compareAndSet(null, current)) {
                throw new IllegalStateException("a baseline claim already has an external call");
            }
            try {
                requireOwnership();
                T result = externalCall.call();
                requireOwnership();
                return result;
            } catch (LeaseOwnershipLostException lost) {
                throw lost;
            } catch (InterruptedException interrupted) {
                if (!owned()) {
                    throw new LeaseOwnershipLostException(interrupted);
                }
                current.interrupt();
                throw new IllegalStateException(
                        "baseline external call was interrupted", interrupted);
            } catch (RuntimeException runtime) {
                if (!owned()) {
                    throw new LeaseOwnershipLostException(runtime);
                }
                throw runtime;
            } catch (Exception checked) {
                if (!owned()) {
                    throw new LeaseOwnershipLostException(checked);
                }
                throw new IllegalStateException("baseline external call failed", checked);
            } finally {
                activeExternalThread.compareAndSet(current, null);
                if (!ownership.get() && !interruptedBefore) {
                    Thread.interrupted();
                }
            }
        }

        @Override
        public void close() {
            heartbeat.cancel(false);
        }
    }
}
