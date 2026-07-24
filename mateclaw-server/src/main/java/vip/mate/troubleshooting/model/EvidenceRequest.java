package vip.mate.troubleshooting.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Platform-neutral evidence intent stored on a SOP. */
public record EvidenceRequest(
        String requestId,
        String signalKind,
        String purpose,
        Map<String, Object> target,
        String window,
        boolean required) {

    public EvidenceRequest {
        requestId = required(requestId, "requestId");
        signalKind = required(signalKind, "signalKind");
        purpose = purpose == null ? "" : purpose;
        target = Collections.unmodifiableMap(
                new LinkedHashMap<>(target == null ? Map.of() : target));
        window = window == null || window.isBlank() ? null : window.trim();
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
