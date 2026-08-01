package vip.mate.troubleshooting.evidence;

import vip.mate.troubleshooting.synthesis.LogTraceSkeleton;

/**
 * Server-only result that keeps the bounded model input beside the public preview.
 *
 * <p>The skeleton is never returned by the Guance acceptance controller or persisted
 * in the T8 sample ledger. Callers may derive a SHA-256 fingerprint or invoke the
 * one-shot baseline while the value remains in memory.</p>
 */
public record GuanceEvidenceSpineObservation(
        GuanceEvidenceSpinePreview preview,
        LogTraceSkeleton skeleton) {

    public GuanceEvidenceSpineObservation {
        if (preview == null) {
            throw new IllegalArgumentException("preview is required");
        }
        boolean blocked = preview.stage() == GuanceEvidenceSpinePreview.Stage.BLOCKED;
        if (blocked != (skeleton == null)) {
            throw new IllegalArgumentException(
                    "blocked observations cannot carry a skeleton and observed ones require it");
        }
    }
}
