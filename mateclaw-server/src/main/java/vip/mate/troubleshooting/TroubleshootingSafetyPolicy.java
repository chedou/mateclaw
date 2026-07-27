package vip.mate.troubleshooting;

/**
 * Cross-path safety invariants for the troubleshooting domain.
 *
 * <p>Keep these values code-owned until the corresponding production-data
 * graduation criteria are implemented and verified. They must not be exposed
 * as an operator toggle that can silently overstate evidence trust.</p>
 */
public final class TroubleshootingSafetyPolicy {

    /** Real evidence bindings have not completed T2/T3 verification yet. */
    public static final boolean EVIDENCE_IS_FIXTURE = true;

    private TroubleshootingSafetyPolicy() {
    }
}
