package vip.mate.troubleshooting.agent;

import org.springframework.stereotype.Component;
import vip.mate.troubleshooting.TroubleshootingSecretRedactor;
import vip.mate.troubleshooting.evidence.CanonicalEvidenceSchema;
import vip.mate.troubleshooting.evidence.EvidenceSpineStage;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.synthesis.DeterministicLogTraceCompressor;
import vip.mate.troubleshooting.synthesis.LogTraceSkeleton;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The single model-facing projection for canonical troubleshooting evidence.
 *
 * <p>Stored evidence remains canonical and reviewable. Models receive neither source
 * queries nor raw trace entries: only bounded scalar facts and a deterministic trace
 * skeleton whose timeline deliberately omits log message bodies.</p>
 */
@Component
public final class TroubleshootingEvidenceModelProjector {

    private static final int MAX_SOURCE_CHARS = 256;
    private static final int MAX_FACT_STRING_CHARS = 256;
    private static final String TRUNCATION_MARKER = "...[TRUNCATED]";
    private static final Map<String, Set<String>> MODEL_FACT_FIELDS = Map.ofEntries(
            Map.entry("log_count", Set.of("count", "trace_id")),
            Map.entry("metric", Set.of(
                    "reachable", "connections_current", "connections_available",
                    "slow_query_count", "baseline_slow")),
            Map.entry("trace", Set.of("failed_hop", "status", "duration_ms")),
            Map.entry("log_search", Set.of("match_count", "ps_id")),
            Map.entry("error_log_scan", Set.of(
                    "error_count", "affected_trace_count", "latest_trace_id")),
            Map.entry("external_api_http_failure", Set.of(
                    "failure_count", "affected_trace_count", "http_status", "operation")),
            Map.entry("incident_reported_external_http_failure", Set.of(
                    "failure_count", "http_status", "operation", "evidence_grade")),
            Map.entry("incident_reported_business_policy_rejection", Set.of(
                    "failure_count", "operation", "policy_code", "client_surface",
                    "change_order_linked", "recommended_channel", "required_information",
                    "required_information_missing", "recommended_action", "evidence_grade")),
            Map.entry("monitor_event_scan", Set.of(
                    "event_count", "latest_status", "latest_checker")),
            Map.entry("k8s_workload_health", Set.of(
                    "pod_count", "container_count", "running_container_count",
                    "unhealthy_container_count", "max_cpu_percent", "max_memory_percent")),
            Map.entry("contrast_sample", Set.of(
                    "discriminating_feature", "failure_sample_count",
                    "failure_match_count", "success_sample_count", "success_match_count")),
            Map.entry("incident_impact", Set.of(
                    "function_scope", "affected_customers", "affected_users",
                    "blast_radius", "observed_at")));

    private final DeterministicLogTraceCompressor traceCompressor;

    public TroubleshootingEvidenceModelProjector(
            DeterministicLogTraceCompressor traceCompressor) {
        this.traceCompressor = traceCompressor;
    }

    public ModelEvidenceBundle project(List<EvidenceResult> evidence) {
        List<EvidenceResult> safeEvidence = evidence == null
                ? List.of()
                : evidence.stream()
                        .filter(item -> item != null)
                        .map(TroubleshootingSecretRedactor::redact)
                        .toList();
        List<EvidenceDescriptor> descriptors = safeEvidence.stream()
                .map(this::descriptor)
                .toList();
        List<String> warnings = new ArrayList<>();

        EvidenceResult trace = firstCanonical(safeEvidence, "log_trace_bundle");
        EvidenceResult contrast = firstCanonical(safeEvidence, "contrast_sample");
        List<ModelTraceSkeleton> skeletons = List.of();
        if (trace != null) {
            try {
                LogTraceSkeleton skeleton = contrast == null
                        ? traceCompressor.compress(trace)
                        : traceCompressor.compress(trace, contrast);
                skeletons = List.of(modelTraceSkeleton(skeleton));
                if (contrast == null) {
                    warnings.add(
                            "success comparison is unavailable; no normal baseline was inferred");
                }
            } catch (IllegalArgumentException unsafe) {
                warnings.add("trace evidence could not be projected safely and was withheld");
            }
        } else if (safeEvidence.stream().anyMatch(this::looksLikeTrace)) {
            warnings.add("malformed trace evidence was withheld from the model");
        }
        return new ModelEvidenceBundle(descriptors, skeletons, List.copyOf(warnings));
    }

    public EvidenceDescriptor descriptor(EvidenceResult evidence) {
        EvidenceResult safe = TroubleshootingSecretRedactor.redact(evidence);
        String signalKind = CanonicalEvidenceSchema.detectSignalKind(safe.observed());
        if (signalKind == null) {
            signalKind = EvidenceSpineStage.fromRequestId(safe.queryId())
                    .map(EvidenceSpineStage::signalKind)
                    .orElse(null);
        }
        return new EvidenceDescriptor(
                safe.queryId(),
                signalKind == null ? "unknown" : signalKind,
                safe.status(),
                modelSummary(signalKind, safe.status()),
                scalarFacts(signalKind, safe),
                safeSource(safe.source()),
                safe.collectedAt());
    }

    public ModelTraceSkeleton modelTraceSkeleton(LogTraceSkeleton skeleton) {
        if (skeleton == null) {
            return null;
        }
        return new ModelTraceSkeleton(
                skeleton.psId(),
                skeleton.startedAtEpochMs(),
                skeleton.endedAtEpochMs(),
                skeleton.elapsedMs(),
                skeleton.serviceSequence(),
                skeleton.timeline().stream()
                        .map(event -> new ModelTimelineEvent(
                                event.sequenceIndex(), event.offsetMs(), event.service(),
                                event.level(), event.durationMs(), event.anomalous()))
                        .toList(),
                skeleton.anomalySequenceIndexes(),
                skeleton.durationByService(),
                skeleton.sourceEntryCount(),
                skeleton.omittedEntryCount(),
                skeleton.contrast());
    }

    private EvidenceResult firstCanonical(
            List<EvidenceResult> evidence,
            String signalKind) {
        return evidence.stream()
                .filter(item -> item.status() != EvidenceStatus.MISSING)
                .filter(item -> CanonicalEvidenceSchema.isValid(signalKind, item.observed()))
                .findFirst()
                .orElse(null);
    }

    private boolean looksLikeTrace(EvidenceResult evidence) {
        return evidence.status() != EvidenceStatus.MISSING
                && (evidence.observed().containsKey("entries")
                || EvidenceSpineStage.TRACE.matchesRequestId(evidence.queryId()));
    }

    private Map<String, Object> scalarFacts(
            String signalKind,
            EvidenceResult evidence) {
        if (signalKind == null
                || evidence.status() == EvidenceStatus.MISSING
                || !CanonicalEvidenceSchema.isValid(signalKind, evidence.observed())) {
            return Map.of();
        }
        Set<String> permitted = MODEL_FACT_FIELDS.get(signalKind);
        if (permitted == null || permitted.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        evidence.observed().entrySet().stream()
                .filter(entry -> permitted.contains(entry.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> facts.put(
                        entry.getKey(), safeScalar(entry.getValue())));
        return Collections.unmodifiableMap(new LinkedHashMap<>(facts));
    }

    private Object safeScalar(Object value) {
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof String text) {
            return bounded(text, MAX_FACT_STRING_CHARS);
        }
        throw new IllegalArgumentException("canonical scalar projection received a nested value");
    }

    private String bounded(String value, int maxChars) {
        String safe = TroubleshootingSecretRedactor.redact(value == null ? "" : value.trim());
        if (safe.length() <= maxChars) {
            return safe;
        }
        int prefixLength = maxChars - TRUNCATION_MARKER.length();
        if (prefixLength > 0 && Character.isHighSurrogate(safe.charAt(prefixLength - 1))) {
            prefixLength--;
        }
        return safe.substring(0, Math.max(0, prefixLength)) + TRUNCATION_MARKER;
    }

    private String modelSummary(String signalKind, EvidenceStatus status) {
        String kind = signalKind == null ? "unknown" : signalKind;
        return kind + " evidence status=" + status.name();
    }

    private String safeSource(String value) {
        String safe = bounded(value, MAX_SOURCE_CHARS).toLowerCase(java.util.Locale.ROOT);
        if (safe.startsWith("recorded-replay")) {
            return "recorded-replay";
        }
        if (safe.startsWith("guance")) {
            return "guance";
        }
        if (safe.startsWith("router:")) {
            return "router";
        }
        if (safe.startsWith("evidence-spine:")) {
            return "evidence-spine";
        }
        if (safe.equals("supplied")) {
            return "supplied";
        }
        return "untrusted-source";
    }

    public record ModelEvidenceBundle(
            List<EvidenceDescriptor> evidence,
            List<ModelTraceSkeleton> traceSkeletons,
            List<String> warnings) {

        public ModelEvidenceBundle {
            evidence = List.copyOf(evidence == null ? List.of() : evidence);
            traceSkeletons = List.copyOf(traceSkeletons == null ? List.of() : traceSkeletons);
            warnings = List.copyOf(warnings == null ? List.of() : warnings);
        }
    }

    public record EvidenceDescriptor(
            String queryId,
            String signalKind,
            EvidenceStatus status,
            String summary,
            Map<String, Object> facts,
            String source,
            Instant collectedAt) {
    }

    public record ModelTraceSkeleton(
            String psId,
            long startedAtEpochMs,
            long endedAtEpochMs,
            long elapsedMs,
            List<String> serviceSequence,
            List<ModelTimelineEvent> timeline,
            List<Integer> anomalySequenceIndexes,
            Map<String, LogTraceSkeleton.DurationSummary> durationByService,
            int sourceEntryCount,
            int omittedEntryCount,
            LogTraceSkeleton.ContrastSummary contrast) {
    }

    public record ModelTimelineEvent(
            int sequenceIndex,
            long offsetMs,
            String service,
            String level,
            Double durationMs,
            boolean anomalous) {
    }
}
