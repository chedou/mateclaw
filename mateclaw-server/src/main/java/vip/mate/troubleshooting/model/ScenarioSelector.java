package vip.mate.troubleshooting.model;

import java.util.Locale;

/** Canonical identity used to route an explicitly selected troubleshooting scenario. */
public record ScenarioSelector(String system, String scenarioKey) {

    public ScenarioSelector {
        system = required(system, "system");
        scenarioKey = required(scenarioKey, "scenarioKey");
    }

    public String routingKey() {
        return system.toLowerCase(Locale.ROOT) + ":scenario:" + scenarioKey;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
