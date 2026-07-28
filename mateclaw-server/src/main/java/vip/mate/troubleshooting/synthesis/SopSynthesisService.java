package vip.mate.troubleshooting.synthesis;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.TroubleshootingSecretRedactor;
import vip.mate.troubleshooting.TroubleshootingSafetyPolicy;
import vip.mate.troubleshooting.evidence.EvidenceProperties;
import vip.mate.troubleshooting.evidence.EvidenceSourceRouter;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * First half of the SOP learning loop: sample logs, follow the returned PS ID,
 * and deterministically compress the bounded trace bundle.
 *
 * <p>This preview deliberately stops before model induction and persistence.
 * It cannot create or promote a SOP candidate. Until a workspace-to-observability
 * asset mapping exists, it is also confined to explicitly registered fixture
 * scopes and the recorded replay adapter.</p>
 */
@Service
public final class SopSynthesisService {

    private static final String SEARCH_REQUEST_ID = "SYNTH-LOG-SEARCH";
    private static final String TRACE_REQUEST_ID = "SYNTH-TRACE-BUNDLE";
    private static final Pattern SAFE_TARGET =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}");
    private static final Pattern SAFE_WINDOW = Pattern.compile("-?([1-9][0-9]*)([smhd])");
    private static final long MAX_LOOKBACK_SECONDS = Duration.ofHours(24).toSeconds();
    private static final int MAX_WINDOW_CHARS = 12;
    private static final int MAX_REFERENCE_SOURCE_CHARS = 256;
    private static final Set<String> FIXTURE_ONLY_SOURCES = Set.of("recorded-replay");

    private final EvidenceSourceRouter evidenceRouter;
    private final DeterministicLogTraceCompressor compressor;
    private final EvidenceProperties.SynthesisPreview previewPolicy;
    private final Clock clock;

    @Autowired
    public SopSynthesisService(
            EvidenceSourceRouter evidenceRouter,
            DeterministicLogTraceCompressor compressor,
            EvidenceProperties properties) {
        this(evidenceRouter, compressor, previewPolicy(properties), Clock.systemUTC());
    }

    public SopSynthesisService(
            EvidenceSourceRouter evidenceRouter,
            DeterministicLogTraceCompressor compressor) {
        this(evidenceRouter, compressor, previewPolicy(null), Clock.systemUTC());
    }

    SopSynthesisService(
            EvidenceSourceRouter evidenceRouter,
            DeterministicLogTraceCompressor compressor,
            Clock clock) {
        this(evidenceRouter, compressor, previewPolicy(null), clock);
    }

    private SopSynthesisService(
            EvidenceSourceRouter evidenceRouter,
            DeterministicLogTraceCompressor compressor,
            EvidenceProperties.SynthesisPreview previewPolicy,
            Clock clock) {
        this.evidenceRouter = evidenceRouter;
        this.compressor = compressor;
        this.previewPolicy = previewPolicy;
        this.clock = clock;
    }

    public SopSynthesisPreview preview(long workspaceId, SopSynthesisRequest request) {
        if (workspaceId <= 0 || request == null) {
            throw invalid("workspaceId and synthesis request are required");
        }
        if (!safeIdentifier(request.system()) || !safeIdentifier(request.service())) {
            throw invalid("system and service must be mapped safe identifiers");
        }
        if (!safeIdentifier(request.searchTerm())) {
            throw invalid("searchTerm must be a mapped safe error code or scenario keyword");
        }
        ValidatedWindow window = validatedWindow(request.window());
        Instant occurredAt = validatedOccurredAt(request.occurredAt(), window.duration());
        requireFixtureScope(workspaceId, request);

        IncidentContext incident = incident(request, occurredAt);
        EvidenceResult search = collect(
                new EvidenceRequest(
                        SEARCH_REQUEST_ID,
                        "log_search",
                        "sample logs and extract PS ID for SOP synthesis",
                        Map.of("search_term", request.searchTerm()),
                        window.expression(),
                        true),
                incident,
                "log_search");
        long matchCount = positiveLong(search.observed().get("match_count"), "match_count");
        String psId = safePsId(search.observed().get("ps_id"));

        EvidenceResult trace = collect(
                new EvidenceRequest(
                        TRACE_REQUEST_ID,
                        "log_trace_bundle",
                        "collect the bounded cross-service trace before deterministic compression",
                        Map.of("ps_id", psId),
                        window.expression(),
                        true),
                incident,
                "log_trace_bundle");

        LogTraceSkeleton skeleton;
        try {
            skeleton = compressor.compress(trace);
        } catch (IllegalArgumentException malformed) {
            throw unavailable("log_trace_bundle cannot be compressed safely");
        }
        if (!psId.equals(skeleton.psId())) {
            throw unavailable("log_search and log_trace_bundle returned different PS IDs");
        }

        return new SopSynthesisPreview(
                SopSynthesisPreview.Stage.READY_FOR_MODEL,
                request.system(),
                request.service(),
                request.searchTerm(),
                matchCount,
                psId,
                evidenceReference(search),
                evidenceReference(trace),
                skeleton,
                TroubleshootingSafetyPolicy.EVIDENCE_IS_FIXTURE,
                List.of(
                        "当前仅完成日志取样、全链路拉取和确定性压缩；尚未调用模型或创建 SOP candidate。",
                        "当前预览只允许登记过的 fixture workspace/service，并仅调用 recorded-replay；"
                                + "真实 PS ID 贯通与 DQL 字段待 T2 内网验证。"));
    }

    private IncidentContext incident(SopSynthesisRequest request, Instant occurredAt) {
        return new IncidentContext(
                "synthesis-preview-" + occurredAt.toEpochMilli(),
                request.system(),
                request.service(),
                null,
                "SOP synthesis preview",
                "P2",
                "preview only",
                null,
                occurredAt,
                null,
                "synthesis_preview",
                IncidentCompleteness.LOG,
                null);
    }

    private EvidenceResult collect(
            EvidenceRequest request,
            IncidentContext incident,
            String stage) {
        EvidenceResult raw = evidenceRouter.collect(
                request, incident, FIXTURE_ONLY_SOURCES);
        if (raw == null) {
            throw unavailable(stage + " returned no evidence");
        }
        if (!request.requestId().equals(raw.queryId())) {
            throw unavailable(stage + " returned an unexpected evidence id");
        }
        if (raw.status() == EvidenceStatus.MISSING) {
            throw unavailable(stage + " evidence is missing");
        }
        return raw;
    }

    private void requireFixtureScope(long workspaceId, SopSynthesisRequest request) {
        if (previewPolicy == null || workspaceId != previewPolicy.getFixtureWorkspaceId()) {
            throw forbidden("synthesis preview is not registered for this workspace");
        }
        Map<String, List<String>> scopes = previewPolicy.getFixtureServices();
        boolean permitted = scopes != null && scopes.entrySet().stream()
                .filter(entry -> entry.getKey() != null
                        && entry.getKey().equalsIgnoreCase(request.system()))
                .map(Map.Entry::getValue)
                .filter(services -> services != null)
                .flatMap(List::stream)
                .anyMatch(service -> service != null
                        && service.equalsIgnoreCase(request.service()));
        if (!permitted) {
            throw forbidden("synthesis preview is not registered for this system/service");
        }
    }

    private boolean safeIdentifier(String value) {
        return SAFE_TARGET.matcher(value).matches()
                && value.equals(TroubleshootingSecretRedactor.redact(value));
    }

    private ValidatedWindow validatedWindow(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() > MAX_WINDOW_CHARS) {
            throw invalid("synthesis window must be between 1 second and 24 hours");
        }
        Matcher matcher = SAFE_WINDOW.matcher(value);
        if (!matcher.matches()) {
            throw invalid("unsupported synthesis window");
        }
        try {
            long amount = Long.parseLong(matcher.group(1));
            long multiplier = switch (matcher.group(2)) {
                case "s" -> 1L;
                case "m" -> 60L;
                case "h" -> 3_600L;
                case "d" -> 86_400L;
                default -> throw invalid("unsupported synthesis window unit");
            };
            if (amount > MAX_LOOKBACK_SECONDS / multiplier) {
                throw invalid("synthesis window must be between 1 second and 24 hours");
            }
            Duration duration = Duration.ofSeconds(amount * multiplier);
            return new ValidatedWindow(
                    "-" + amount + matcher.group(2), duration);
        } catch (NumberFormatException overflow) {
            throw invalid("unsupported synthesis window");
        }
    }

    private Instant validatedOccurredAt(Instant requested, Duration lookback) {
        Instant occurredAt = requested == null ? Instant.now(clock) : requested;
        try {
            occurredAt.toEpochMilli();
            occurredAt.minus(lookback).toEpochMilli();
            return occurredAt;
        } catch (ArithmeticException | DateTimeException invalidTime) {
            throw invalid("occurredAt is outside the supported epoch range");
        }
    }

    private SopSynthesisPreview.EvidenceReference evidenceReference(
            EvidenceResult evidence) {
        String source = evidence.source();
        if (source.length() > MAX_REFERENCE_SOURCE_CHARS) {
            throw unavailable("evidence source exceeds the safe character bound");
        }
        try {
            evidence.collectedAt().toEpochMilli();
        } catch (ArithmeticException invalidTime) {
            throw unavailable("evidence collection time is outside the supported epoch range");
        }
        return new SopSynthesisPreview.EvidenceReference(
                evidence.queryId(),
                evidence.status(),
                TroubleshootingSecretRedactor.redact(source),
                evidence.collectedAt());
    }

    private long positiveLong(Object raw, String field) {
        if (!(raw instanceof Number number)) {
            throw unavailable(field + " is missing or malformed");
        }
        try {
            long value = new BigDecimal(String.valueOf(number)).longValueExact();
            if (value <= 0) {
                throw unavailable(field + " must be positive");
            }
            return value;
        } catch (ArithmeticException invalid) {
            throw unavailable(field + " must be an integer");
        }
    }

    private String safePsId(Object raw) {
        if (!(raw instanceof String value)
                || !SAFE_TARGET.matcher(value.trim()).matches()) {
            throw unavailable("log_search returned no safe PS ID");
        }
        return value.trim();
    }

    private MateClawException invalid(String message) {
        return new MateClawException(
                "err.troubleshooting.invalid_synthesis_request", 400, message);
    }

    private MateClawException unavailable(String message) {
        return new MateClawException(
                "err.troubleshooting.synthesis_evidence_missing", 409, message);
    }

    private MateClawException forbidden(String message) {
        return new MateClawException(
                "err.troubleshooting.synthesis_scope_forbidden", 403, message);
    }

    private static EvidenceProperties.SynthesisPreview previewPolicy(
            EvidenceProperties properties) {
        EvidenceProperties source = properties == null ? new EvidenceProperties() : properties;
        return source.getSynthesisPreview();
    }

    private record ValidatedWindow(String expression, Duration duration) {
    }
}
