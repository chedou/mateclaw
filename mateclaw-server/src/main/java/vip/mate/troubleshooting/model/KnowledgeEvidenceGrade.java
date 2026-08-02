package vip.mate.troubleshooting.model;

/**
 * How the criteria inside a Playbook earned their factual authority.
 *
 * <p>This is deliberately orthogonal to Diagnosis {@code fixtureMode}: the
 * latter says whether one evidence run was replayed, while this enum says
 * whether the knowledge's thresholds came from recorded aggregate facts or
 * were authored as a test fixture.</p>
 */
public enum KnowledgeEvidenceGrade {
    RECORDED_AGGREGATE,
    AUTHORED_FIXTURE,
    UNVERIFIED;

    public static KnowledgeEvidenceGrade fromStored(String value) {
        if (value == null || value.isBlank()) {
            return UNVERIFIED;
        }
        try {
            return valueOf(value.trim());
        } catch (IllegalArgumentException ignored) {
            // Unknown historical or externally repaired values must never
            // upgrade authority or make the entire registry unavailable.
            return UNVERIFIED;
        }
    }
}
