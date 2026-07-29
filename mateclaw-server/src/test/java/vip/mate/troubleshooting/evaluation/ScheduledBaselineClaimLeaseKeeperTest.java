package vip.mate.troubleshooting.evaluation;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduledBaselineClaimLeaseKeeperTest {

    private static final Instant NOW = Instant.parse("2026-07-29T08:00:00Z");

    @Test
    void renewsAnOwnedClaimBeforeItsLeaseCanExpire() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        ScheduledBaselineClaimLeaseKeeper keeper = new ScheduledBaselineClaimLeaseKeeper(
                scheduler, Duration.ofSeconds(1), Duration.ofMillis(10));
        BaselineEvaluationRunStore store = mock(BaselineEvaluationRunStore.class);
        CountDownLatch renewed = new CountDownLatch(1);
        when(store.renew(eq(7L), eq(claim()), any(Instant.class)))
                .thenAnswer(invocation -> {
                    renewed.countDown();
                    return true;
                });

        try (BaselineClaimLeaseKeeper.LeaseHandle handle = keeper.keepAlive(
                7L,
                claim(),
                store,
                Clock.fixed(NOW, ZoneOffset.UTC))) {
            assertThat(renewed.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(handle.owned()).isTrue();
        } finally {
            keeper.shutdown();
        }

        verify(store).renew(7L, claim(), NOW.plusSeconds(1));
    }

    @Test
    void interruptsABoundedExternalCallAsSoonAsTheClaimLosesOwnership() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ScheduledBaselineClaimLeaseKeeper keeper = new ScheduledBaselineClaimLeaseKeeper(
                scheduler, Duration.ofSeconds(1), Duration.ofMillis(10));
        BaselineEvaluationRunStore store = mock(BaselineEvaluationRunStore.class);
        CountDownLatch externalStarted = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        when(store.renew(eq(7L), eq(claim()), any(Instant.class)))
                .thenReturn(false);

        try (BaselineClaimLeaseKeeper.LeaseHandle handle = keeper.keepAlive(
                7L,
                claim(),
                store,
                Clock.fixed(NOW, ZoneOffset.UTC))) {
            Future<String> result = worker.submit(() -> {
                try {
                    return handle.executeExternal(() -> {
                        externalStarted.countDown();
                        try {
                            new CountDownLatch(1).await();
                            return "unexpected";
                        } catch (InterruptedException lost) {
                            interrupted.countDown();
                            throw lost;
                        }
                    });
                } catch (BaselineClaimLeaseKeeper.LeaseOwnershipLostException lost) {
                    return "lost";
                }
            });

            assertThat(externalStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(result.get(1, TimeUnit.SECONDS)).isEqualTo("lost");
            assertThat(handle.owned()).isFalse();
        } finally {
            worker.shutdownNow();
            keeper.shutdown();
        }
    }

    private BaselineEvaluationRunStore.RunClaim claim() {
        return new BaselineEvaluationRunStore.RunClaim(
                "baseline-012345678901234567890123",
                "a".repeat(64),
                "eval-012345678901234567890123",
                "diag-1",
                1,
                EvidenceEvaluationSample.SourcePlatform.GUANCE,
                false,
                false,
                "openai",
                "fixed-model",
                "modelcfg-sha256:" + "b".repeat(64),
                "claim-token-0001",
                NOW,
                NOW.plusSeconds(1));
    }
}
