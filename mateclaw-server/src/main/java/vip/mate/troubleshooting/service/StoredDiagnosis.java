package vip.mate.troubleshooting.service;

import vip.mate.troubleshooting.model.Diagnosis;

/** Persisted aggregate plus optimistic-lock metadata. */
public record StoredDiagnosis(Diagnosis diagnosis, int version, boolean created) {
    public StoredDiagnosis {
        if (diagnosis == null) {
            throw new IllegalArgumentException("diagnosis must not be null");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
    }
}
