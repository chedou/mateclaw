package vip.mate.troubleshooting.model;

import vip.mate.troubleshooting.engine.Criterion;

/** Connects one evidence request to one named deterministic signal. */
public record AnomalyCriterion(
        String signal,
        String sourceRequestId,
        String description,
        Criterion rule) {

    public AnomalyCriterion {
        signal = required(signal, "signal");
        sourceRequestId = required(sourceRequestId, "sourceRequestId");
        description = description == null ? "" : description;
        if (rule == null) {
            throw new IllegalArgumentException("rule must not be null");
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
