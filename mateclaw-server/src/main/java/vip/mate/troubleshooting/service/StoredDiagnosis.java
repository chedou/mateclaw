package vip.mate.troubleshooting.service;

import vip.mate.troubleshooting.model.Diagnosis;

/** Persisted aggregate plus optimistic-lock and immutable pilot-enrollment metadata. */
public record StoredDiagnosis(
        Diagnosis diagnosis,
        int version,
        boolean created,
        Integer pilotPlanVersion) {

    /** Compatibility constructor for callers that do not read pilot enrollment. */
    public StoredDiagnosis(Diagnosis diagnosis, int version, boolean created) {
        this(diagnosis, version, created, null);
    }

    public StoredDiagnosis {
        if (diagnosis == null) {
            throw new IllegalArgumentException("diagnosis must not be null");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        // Legacy rows and unstubbed persistence shells can expose 0 for the
        // formerly absent enrollment column.  Absence must stay absence; it
        // must never be promoted to a formal pilot identity.
        if (pilotPlanVersion != null && pilotPlanVersion == 0) {
            pilotPlanVersion = null;
        }
        if (pilotPlanVersion != null && pilotPlanVersion < 0) {
            throw new IllegalArgumentException("pilotPlanVersion must be positive");
        }
    }
}
