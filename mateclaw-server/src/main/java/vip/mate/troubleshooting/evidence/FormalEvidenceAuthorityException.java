package vip.mate.troubleshooting.evidence;

/**
 * Fail-closed authority error for a formally admitted evidence invocation.
 *
 * <p>This is deliberately distinct from source unavailability. A missing or
 * failed verifier, an invalid frozen expectation, or configuration drift means
 * the server can no longer prove that it is invoking the source configuration
 * admitted for this run. Treating that state as ordinary missing evidence would
 * allow the run to persist an apparently valid "evidence insufficient" result.
 */
public final class FormalEvidenceAuthorityException extends IllegalStateException {

    public enum Reason {
        INVALID_EXPECTATION,
        VERIFIER_UNAVAILABLE,
        VERIFIER_FAILURE,
        CONFIGURATION_DRIFT,
        POLICY_BLOCKED
    }

    private final Reason reason;

    private FormalEvidenceAuthorityException(
            Reason reason,
            String message,
            Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public static FormalEvidenceAuthorityException invalidExpectation(String message) {
        return new FormalEvidenceAuthorityException(
                Reason.INVALID_EXPECTATION, message, null);
    }

    public static FormalEvidenceAuthorityException verifierUnavailable(String message) {
        return new FormalEvidenceAuthorityException(
                Reason.VERIFIER_UNAVAILABLE, message, null);
    }

    public static FormalEvidenceAuthorityException verifierFailure(
            String message,
            Throwable cause) {
        return new FormalEvidenceAuthorityException(
                Reason.VERIFIER_FAILURE, message, cause);
    }

    public static FormalEvidenceAuthorityException configurationDrift(String message) {
        return new FormalEvidenceAuthorityException(
                Reason.CONFIGURATION_DRIFT, message, null);
    }

    public static FormalEvidenceAuthorityException policyBlocked(String message) {
        return new FormalEvidenceAuthorityException(
                Reason.POLICY_BLOCKED, message, null);
    }
}
