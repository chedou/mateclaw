package vip.mate.troubleshooting.evidence;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import vip.mate.troubleshooting.CanonicalNumberParser;
import vip.mate.troubleshooting.TroubleshootingSecretRedactor;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.synthesis.DeterministicLogTraceCompressor;
import vip.mate.troubleshooting.synthesis.LogTraceSkeleton;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * The single Evidence Spine implementation shared by online diagnosis and SOP learning.
 *
 * <p>It performs only read-only Router calls, validates canonical results, and compresses
 * the trace before any caller can expose it to a model. The success comparison is optional;
 * search and trace are mandatory.</p>
 */
@Component
public final class EvidenceSpineOrchestrator {

    private static final Pattern SAFE_PS_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}");

    private final EvidenceSourceRouter router;
    private final DeterministicLogTraceCompressor compressor;
    private final Clock clock;
    private final LongSupplier ticker;

    @Autowired
    public EvidenceSpineOrchestrator(
            EvidenceSourceRouter router,
            DeterministicLogTraceCompressor compressor) {
        this(router, compressor, Clock.systemUTC());
    }

    EvidenceSpineOrchestrator(
            EvidenceSourceRouter router,
            DeterministicLogTraceCompressor compressor,
            Clock clock) {
        this(router, compressor, clock, System::nanoTime);
    }

    EvidenceSpineOrchestrator(
            EvidenceSourceRouter router,
            DeterministicLogTraceCompressor compressor,
            Clock clock,
            LongSupplier ticker) {
        this.router = router;
        this.compressor = compressor;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.ticker = ticker == null ? System::nanoTime : ticker;
    }

    public EvidenceSpineResult collect(
            long workspaceId,
            IncidentContext incident,
            EvidenceSpinePlan plan,
            Set<String> permittedPlatforms) {
        return collect(
                workspaceId,
                incident,
                plan,
                permittedPlatforms,
                EvidenceSpineRunControl.unbounded());
    }

    public EvidenceSpineResult collect(
            long workspaceId,
            IncidentContext incident,
            EvidenceSpinePlan plan,
            Set<String> permittedPlatforms,
            EvidenceSpineRunControl runControl) {
        if (workspaceId <= 0 || incident == null || plan == null) {
            throw new IllegalArgumentException(
                    "workspace, incident and evidence spine plan are required");
        }

        EvidenceRequest searchRequest = new EvidenceRequest(
                plan.searchRequestId(),
                "log_search",
                "sample logs and extract a PS ID",
                Map.of("search_term", plan.searchTerm()),
                plan.window(),
                true);
        requireControl(runControl).beforeSourceRequest(searchRequest.requestId());
        long searchStarted = ticker.getAsLong();
        EvidenceResult search = collectCanonical(
                workspaceId, searchRequest, incident, permittedPlatforms);
        long searchDurationMs = elapsedMillis(searchStarted);
        EvidenceSpineTimings timings = new EvidenceSpineTimings(
                searchDurationMs, null, null, null);
        if (search.status() == EvidenceStatus.MISSING) {
            return failed(search, null, 1, timings, "log_search evidence is missing");
        }
        Long matchCount = CanonicalNumberParser.parseExactLong(
                search.observed().get("match_count"));
        String psId = safePsId(search.observed().get("ps_id"));
        if (matchCount == null || matchCount <= 0 || psId == null) {
            return failed(
                    invalid(plan.searchRequestId(), "log_search canonical values are unusable"),
                    null,
                    1,
                    timings,
                    "log_search canonical values are unusable");
        }

        EvidenceRequest traceRequest = new EvidenceRequest(
                plan.traceRequestId(),
                "log_trace_bundle",
                "collect the bounded cross-service trace before deterministic compression",
                Map.of("ps_id", psId),
                plan.window(),
                true);
        runControl.beforeSourceRequest(traceRequest.requestId());
        long traceStarted = ticker.getAsLong();
        EvidenceResult trace = collectCanonical(
                workspaceId, traceRequest, incident, permittedPlatforms);
        long traceDurationMs = elapsedMillis(traceStarted);
        timings = new EvidenceSpineTimings(
                searchDurationMs, traceDurationMs, null, null);
        if (trace.status() == EvidenceStatus.MISSING) {
            return failed(
                    search, trace, 2, timings, "log_trace_bundle evidence is missing");
        }

        LogTraceSkeleton skeleton;
        long compressionStarted = ticker.getAsLong();
        long compressionDurationMs;
        try {
            skeleton = compressor.compress(trace);
        } catch (IllegalArgumentException malformedTrace) {
            compressionDurationMs = elapsedMillis(compressionStarted);
            return failed(
                    search,
                    invalid(plan.traceRequestId(), "log_trace_bundle cannot be compressed safely"),
                    2,
                    new EvidenceSpineTimings(
                            searchDurationMs, traceDurationMs, null, compressionDurationMs),
                    "log_trace_bundle cannot be compressed safely");
        }
        compressionDurationMs = elapsedMillis(compressionStarted);
        timings = new EvidenceSpineTimings(
                searchDurationMs, traceDurationMs, null, compressionDurationMs);
        if (!psId.equals(skeleton.psId())) {
            return failed(
                    search,
                    invalid(plan.traceRequestId(), "log_search and log_trace_bundle PS IDs differ"),
                    2,
                    timings,
                    "log_search and log_trace_bundle PS IDs differ");
        }

        EvidenceRequest contrastRequest = new EvidenceRequest(
                plan.contrastRequestId(),
                "contrast_sample",
                "compare same-window successful requests with the failed scenario",
                Map.of(
                        "scenario_key", plan.searchTerm(),
                        "exclude_ps_id", psId),
                plan.window(),
                false);
        runControl.beforeSourceRequest(contrastRequest.requestId());
        long contrastStarted = ticker.getAsLong();
        EvidenceResult contrast = collectCanonical(
                workspaceId, contrastRequest, incident, permittedPlatforms);
        long contrastDurationMs = elapsedMillis(contrastStarted);
        if (contrast.status() != EvidenceStatus.MISSING) {
            long contrastCompressionStarted = ticker.getAsLong();
            try {
                skeleton = compressor.compress(trace, contrast);
            } catch (IllegalArgumentException malformedContrast) {
                contrast = invalid(
                        plan.contrastRequestId(),
                        "contrast_sample cannot be compressed safely");
            } finally {
                compressionDurationMs = Math.addExact(
                        compressionDurationMs,
                        elapsedMillis(contrastCompressionStarted));
            }
        }

        timings = new EvidenceSpineTimings(
                searchDurationMs,
                traceDurationMs,
                contrastDurationMs,
                compressionDurationMs);
        return new EvidenceSpineResult(
                search, trace, contrast, skeleton, 3, timings, null);
    }

    private EvidenceSpineRunControl requireControl(EvidenceSpineRunControl runControl) {
        if (runControl == null) {
            throw new IllegalArgumentException("evidence spine run control is required");
        }
        return runControl;
    }

    private EvidenceSpineResult failed(
            EvidenceResult search,
            EvidenceResult trace,
            int requestCount,
            EvidenceSpineTimings timings,
            String reason) {
        return new EvidenceSpineResult(
                search, trace, null, null, requestCount, timings, reason);
    }

    private EvidenceResult collectCanonical(
            long workspaceId,
            EvidenceRequest request,
            IncidentContext incident,
            Set<String> permittedPlatforms) {
        EvidenceResult raw;
        try {
            raw = router.collect(workspaceId, request, incident, permittedPlatforms);
        } catch (RuntimeException sourceFailure) {
            return missing(request.requestId(), "evidence source invocation failed");
        }
        return canonical(request, raw);
    }

    private EvidenceResult canonical(EvidenceRequest request, EvidenceResult raw) {
        if (raw == null || !request.requestId().equals(raw.queryId())) {
            return invalid(request.requestId(), "evidence source returned an invalid identity");
        }
        EvidenceResult sanitized = TroubleshootingSecretRedactor.redact(raw);
        if (sanitized.status() == EvidenceStatus.MISSING) {
            return sanitized;
        }
        if (!CanonicalEvidenceSchema.isValid(request.signalKind(), sanitized.observed())) {
            return invalid(request.requestId(), "evidence violates the canonical contract");
        }
        return sanitized;
    }

    private String safePsId(Object raw) {
        if (!(raw instanceof String value)
                || !SAFE_PS_ID.matcher(value.trim()).matches()) {
            return null;
        }
        return value.trim();
    }

    private EvidenceResult invalid(String requestId, String summary) {
        return missing(requestId, summary, "evidence-spine:invalid");
    }

    private EvidenceResult missing(String requestId, String summary) {
        return missing(requestId, summary, "evidence-spine:unavailable");
    }

    private EvidenceResult missing(String requestId, String summary, String source) {
        return new EvidenceResult(
                requestId,
                "UNKNOWN",
                "",
                EvidenceStatus.MISSING,
                summary,
                Map.of(),
                source,
                clock.instant());
    }

    private long elapsedMillis(long startedNanos) {
        long elapsedNanos = ticker.getAsLong() - startedNanos;
        return elapsedNanos <= 0L ? 0L : Duration.ofNanos(elapsedNanos).toMillis();
    }
}
