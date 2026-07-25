package vip.mate.troubleshooting.model;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Canonical, source-independent evidence result consumed by the rule engine. */
public record EvidenceResult(
        String queryId,
        String namespace,
        String query,
        EvidenceStatus status,
        String summary,
        Map<String, Object> observed,
        String source,
        Instant collectedAt) {

    public EvidenceResult {
        queryId = required(queryId, "queryId");
        namespace = required(namespace, "namespace");
        query = query == null ? "" : query;
        status = status == null ? EvidenceStatus.MISSING : status;
        summary = summary == null ? "" : summary;
        observed = Collections.unmodifiableMap(
                new LinkedHashMap<>(observed == null ? Map.of() : observed));
        source = required(source, "source");
        collectedAt = collectedAt == null ? Instant.EPOCH : collectedAt;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
