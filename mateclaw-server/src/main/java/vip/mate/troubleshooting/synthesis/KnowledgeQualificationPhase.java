package vip.mate.troubleshooting.synthesis;

/**
 * Qualification tier used when evaluating one review source.
 *
 * <p>Evidence-derived knowledge defaults to {@link #CALIBRATION}. Moving a
 * workspace to {@link #RUNTIME} requires the data-backed threshold in v4
 * section 5.7 and is deliberately not inferred from a date or reviewer
 * action. Other origins do not use the two-tier evidence-derived gate.</p>
 */
public enum KnowledgeQualificationPhase {
    CALIBRATION,
    RUNTIME,
    NOT_APPLICABLE,
    UNKNOWN
}
