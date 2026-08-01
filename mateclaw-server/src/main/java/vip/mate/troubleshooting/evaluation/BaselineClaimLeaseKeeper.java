package vip.mate.troubleshooting.evaluation;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Callable;

/** Keeps an atomic baseline claim alive while external evidence/model work is in flight. */
public interface BaselineClaimLeaseKeeper {

    Duration leaseDuration();

    LeaseHandle keepAlive(
            long workspaceId,
            BaselineEvaluationRunStore.RunClaim claim,
            BaselineEvaluationRunStore store,
            Clock clock);

    interface LeaseHandle extends AutoCloseable {
        boolean owned();

        default void requireOwnership() {
            if (!owned()) {
                throw new LeaseOwnershipLostException();
            }
        }

        /** Runs one bounded source/model boundary under the current claim. */
        default <T> T executeExternal(Callable<T> externalCall) {
            if (externalCall == null) {
                throw new IllegalArgumentException("external baseline call is required");
            }
            requireOwnership();
            try {
                T result = externalCall.call();
                requireOwnership();
                return result;
            } catch (LeaseOwnershipLostException lost) {
                throw lost;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                if (!owned()) {
                    throw new LeaseOwnershipLostException(interrupted);
                }
                throw new IllegalStateException("baseline external call was interrupted", interrupted);
            } catch (RuntimeException runtime) {
                throw runtime;
            } catch (Exception checked) {
                throw new IllegalStateException("baseline external call failed", checked);
            }
        }

        @Override
        void close();
    }

    final class LeaseOwnershipLostException extends RuntimeException {
        public LeaseOwnershipLostException() {
            super("baseline run claim ownership was lost");
        }

        public LeaseOwnershipLostException(Throwable cause) {
            super("baseline run claim ownership was lost", cause);
        }
    }

    static BaselineClaimLeaseKeeper noOp(Duration lease) {
        if (lease == null || lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("a positive baseline lease is required");
        }
        return new BaselineClaimLeaseKeeper() {
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
                return new LeaseHandle() {
                    @Override
                    public boolean owned() {
                        return true;
                    }

                    @Override
                    public void close() {
                    }
                };
            }
        };
    }
}
