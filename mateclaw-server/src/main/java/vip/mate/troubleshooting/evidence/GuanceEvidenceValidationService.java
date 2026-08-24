package vip.mate.troubleshooting.evidence;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.TroubleshootingSecretRedactor;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs the smallest real-source validation chain used during T7.
 *
 * <p>This service never persists evidence, never falls back to a fixture source,
 * and returns only structural counts and identifiers. A successful run proves
 * transport plus canonical normalization for one sample; it does not satisfy
 * T7 field acceptance or the 20-30 sample T8 historical baseline.</p>
 */
@Service
public class GuanceEvidenceValidationService {

    private static final Set<String> GUANCE_ONLY = Set.of("guance");
    private static final Pattern SAFE_VALUE =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}");
    private static final Pattern WINDOW = Pattern.compile("-?([1-9][0-9]*)([smhd])");
    private static final String SEARCH_REQUEST = "T7-GUANCE-LOG-SEARCH";
    private static final String TRACE_REQUEST = "T7-GUANCE-TRACE-BUNDLE";
    private static final String SUCCESS_WARNING =
            "本次结果只证明一次 Guance 读链与规范化合同可用；仍需 owner 完成 T7 字段验收，"
                    + "再建立 T8 的 20–30 条历史样本；不会自动关闭 fixtureMode。";
    private static final String BLOCKED_WARNING =
            "本次未形成可验收的同 PS ID Guance 读链；T7/T8 状态不变，不会自动关闭 fixtureMode。";

    private final EvidenceSourceRouter router;
    private final GuanceEvidenceReadinessService readinessService;
    private final Clock clock;
    private final LongSupplier ticker;

    @Autowired
    public GuanceEvidenceValidationService(
            EvidenceSourceRouter router,
            GuanceEvidenceReadinessService readinessService) {
        this(router, readinessService, Clock.systemUTC(), System::nanoTime);
    }

    GuanceEvidenceValidationService(
            EvidenceSourceRouter router,
            GuanceEvidenceReadinessService readinessService,
            Clock clock) {
        this(router, readinessService, clock, System::nanoTime);
    }

    GuanceEvidenceValidationService(
            EvidenceSourceRouter router,
            GuanceEvidenceReadinessService readinessService,
            Clock clock,
            LongSupplier ticker) {
        this.router = router;
        this.readinessService = readinessService;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.ticker = ticker == null ? System::nanoTime : ticker;
    }

    public GuanceEvidenceValidationReport validate(
            long workspaceId,
            String system,
            String service,
            String searchTerm,
            String window,
            Instant occurredAt) {
        long validationStarted = ticker.getAsLong();
        String safeSearchTerm = safeValue(searchTerm, "searchTerm");
        String safeWindow = safeWindow(window);
        Instant observationEnd = safeOccurredAt(occurredAt);
        GuanceEvidenceReadiness readiness =
                readinessService.inspect(workspaceId, system, service);
        if (!canValidate(readiness.status())) {
            return blockedBeforeQuery(readiness, validationStarted);
        }

        IncidentContext incident = incident(readiness, observationEnd);
        List<GuanceEvidenceValidationReport.Step> steps = new ArrayList<>();
        EvidenceRequest searchRequest = new EvidenceRequest(
                SEARCH_REQUEST,
                "log_search",
                "Validate Guance canonical log search",
                Map.of("search_term", safeSearchTerm),
                safeWindow,
                true);
        TimedEvidenceResult timedSearch = collectTimed(searchRequest, workspaceId, incident);
        EvidenceResult search = timedSearch.result();
        SearchObservation searchObservation = searchObservation(searchRequest, search);
        if (searchObservation == null) {
            steps.add(blockedStep("log_search", SEARCH_REQUEST,
                    "Guance did not return valid canonical search evidence",
                    timedSearch.durationMs()));
            steps.add(notRunStep("log_trace_bundle",
                    "search evidence did not establish a PS ID"));
            return report(
                    GuanceEvidenceValidationReport.Stage.BLOCKED,
                    readinessService.inspect(workspaceId, system, service),
                    null,
                    null,
                    null,
                    steps,
                    validationStarted);
        }
        steps.add(observedStep(
                "log_search",
                SEARCH_REQUEST,
                "canonical match count and PS ID observed",
                timedSearch.durationMs(),
                search.collectedAt()));

        EvidenceRequest traceRequest = new EvidenceRequest(
                TRACE_REQUEST,
                "log_trace_bundle",
                "Validate Guance canonical log trace bundle",
                Map.of("ps_id", searchObservation.psId()),
                safeWindow,
                true);
        TimedEvidenceResult timedTrace = collectTimed(traceRequest, workspaceId, incident);
        EvidenceResult trace = timedTrace.result();
        Integer entryCount = traceEntryCount(traceRequest, trace, searchObservation.psId());
        if (entryCount == null) {
            steps.add(blockedStep(
                    "log_trace_bundle",
                    TRACE_REQUEST,
                    "Guance did not return the same PS ID as canonical trace evidence",
                    timedTrace.durationMs()));
            return report(
                    GuanceEvidenceValidationReport.Stage.BLOCKED,
                    readinessService.inspect(workspaceId, system, service),
                    searchObservation.matchCount(),
                    searchObservation.psId(),
                    null,
                    steps,
                    validationStarted);
        }
        steps.add(observedStep(
                "log_trace_bundle",
                TRACE_REQUEST,
                "canonical entries for the same PS ID observed",
                timedTrace.durationMs(),
                trace.collectedAt()));

        return report(
                GuanceEvidenceValidationReport.Stage.CANONICAL_CHAIN_OBSERVED,
                readinessService.inspect(workspaceId, system, service),
                searchObservation.matchCount(),
                searchObservation.psId(),
                entryCount,
                steps,
                validationStarted);
    }

    /**
     * Executes each requested semantic capability against Guance and returns
     * only the capabilities that produced a valid canonical result. A binding
     * declaration or field map alone never becomes live execution authority.
     */
    public Map<String, Long> validateCapabilities(
            long workspaceId,
            String system,
            String service,
            Set<String> signalKinds,
            String window,
            Instant occurredAt) {
        if (workspaceId <= 0) {
            throw invalid("workspaceId must be positive");
        }
        String safeSystem = safeValue(system, "system");
        String safeService = safeValue(service, "service");
        String safeWindow = safeWindow(window);
        Instant observationEnd = safeOccurredAt(occurredAt);
        IncidentContext incident = new IncidentContext(
                "guance-capability-validation-" + observationEnd.toEpochMilli(),
                safeSystem,
                safeService,
                null,
                "Guance generic read-only capability validation",
                "P2",
                "validation only",
                null,
                observationEnd,
                null,
                "guance_capability_validation",
                IncidentCompleteness.LOG,
                null);
        Map<String, Long> observed = new LinkedHashMap<>();
        for (String signalKind : signalKinds == null
                ? List.<String>of()
                : signalKinds.stream().sorted().toList()) {
            String safeSignal = safeValue(signalKind, "signalKind")
                    .toLowerCase(java.util.Locale.ROOT);
            if (!CanonicalEvidenceSchema.isExternallyRoutable(safeSignal)) {
                continue;
            }
            EvidenceRequest request = new EvidenceRequest(
                    "T7-GUANCE-CAPABILITY-" + safeSignal.toUpperCase(
                            java.util.Locale.ROOT).replace('_', '-'),
                    safeSignal,
                    "Validate one Guance generic read-only capability",
                    Map.of(),
                    safeWindow,
                    true);
            TimedEvidenceResult timed = collectTimed(request, workspaceId, incident);
            EvidenceResult result = timed.result();
            if (usable(request, result, "guance:" + safeSignal)
                    && CanonicalEvidenceSchema.isValid(
                            safeSignal, result.observed())) {
                observed.put(safeSignal, timed.durationMs());
            }
        }
        return Map.copyOf(observed);
    }

    private EvidenceResult collect(
            EvidenceRequest request,
            long workspaceId,
            IncidentContext incident) {
        return router.collect(workspaceId, request, incident, GUANCE_ONLY);
    }

    private TimedEvidenceResult collectTimed(
            EvidenceRequest request,
            long workspaceId,
            IncidentContext incident) {
        long started = ticker.getAsLong();
        EvidenceResult result = collect(request, workspaceId, incident);
        return new TimedEvidenceResult(result, elapsedMillis(started));
    }

    private SearchObservation searchObservation(
            EvidenceRequest request,
            EvidenceResult result) {
        if (!usable(request, result, "guance:log_search")) {
            return null;
        }
        Long matchCount = positiveLong(result.observed().get("match_count"));
        String psId = safeObservedIdentifier(result.observed().get("ps_id"));
        if (matchCount == null || psId == null) {
            return null;
        }
        return new SearchObservation(matchCount, psId);
    }

    private Integer traceEntryCount(
            EvidenceRequest request,
            EvidenceResult result,
            String expectedPsId) {
        if (!usable(request, result, "guance:log_trace_bundle")) {
            return null;
        }
        String observedPsId = safeObservedIdentifier(result.observed().get("ps_id"));
        Object entries = result.observed().get("entries");
        if (!expectedPsId.equals(observedPsId)
                || !(entries instanceof List<?> entryList)
                || entryList.isEmpty()) {
            return null;
        }
        return entryList.size();
    }

    private boolean usable(
            EvidenceRequest request,
            EvidenceResult result,
            String expectedSource) {
        return result != null
                && request.requestId().equals(result.queryId())
                && result.status() != EvidenceStatus.MISSING
                && expectedSource.equals(result.source());
    }

    private Long positiveLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            BigDecimal number = new BigDecimal(String.valueOf(value));
            long exact = number.longValueExact();
            return exact > 0 ? exact : null;
        } catch (RuntimeException invalidNumber) {
            return null;
        }
    }

    private String safeObservedIdentifier(Object value) {
        if (value == null) {
            return null;
        }
        String identifier = String.valueOf(value).trim();
        return SAFE_VALUE.matcher(identifier).matches()
                && TroubleshootingSecretRedactor.redact(identifier).equals(identifier)
                ? identifier
                : null;
    }

    private IncidentContext incident(
            GuanceEvidenceReadiness readiness,
            Instant occurredAt) {
        return new IncidentContext(
                "guance-validation-" + occurredAt.toEpochMilli(),
                readiness.system(),
                readiness.service(),
                null,
                "Guance read-only validation",
                "P2",
                "validation only",
                null,
                occurredAt,
                null,
                "guance_validation",
                IncidentCompleteness.LOG,
                null);
    }

    private GuanceEvidenceValidationReport blockedBeforeQuery(
            GuanceEvidenceReadiness readiness,
            long validationStarted) {
        return report(
                GuanceEvidenceValidationReport.Stage.BLOCKED,
                readiness,
                null,
                null,
                null,
                List.of(
                        notRunStep("log_search", "Guance readiness gate is not open"),
                        notRunStep("log_trace_bundle", "Guance readiness gate is not open")),
                validationStarted);
    }

    private GuanceEvidenceValidationReport report(
            GuanceEvidenceValidationReport.Stage stage,
            GuanceEvidenceReadiness readiness,
            Long matchCount,
            String psId,
            Integer traceEntries,
            List<GuanceEvidenceValidationReport.Step> steps,
            long validationStarted) {
        return new GuanceEvidenceValidationReport(
                stage,
                readiness,
                matchCount,
                psId,
                traceEntries,
                elapsedMillis(validationStarted),
                steps,
                Instant.now(clock),
                List.of(stage == GuanceEvidenceValidationReport.Stage.CANONICAL_CHAIN_OBSERVED
                        ? SUCCESS_WARNING
                        : BLOCKED_WARNING));
    }

    private GuanceEvidenceValidationReport.Step notRunStep(
            String signalKind,
            String detail) {
        return new GuanceEvidenceValidationReport.Step(
                signalKind,
                GuanceEvidenceValidationReport.StepStatus.NOT_RUN,
                "",
                detail,
                null,
                null);
    }

    private GuanceEvidenceValidationReport.Step blockedStep(
            String signalKind,
            String evidenceRef,
            String detail,
            long durationMs) {
        return new GuanceEvidenceValidationReport.Step(
                signalKind,
                GuanceEvidenceValidationReport.StepStatus.BLOCKED,
                evidenceRef,
                detail,
                durationMs,
                null);
    }

    private GuanceEvidenceValidationReport.Step observedStep(
            String signalKind,
            String evidenceRef,
            String detail,
            long durationMs,
            Instant collectedAt) {
        return new GuanceEvidenceValidationReport.Step(
                signalKind,
                GuanceEvidenceValidationReport.StepStatus.CANONICAL_RESULT_OBSERVED,
                evidenceRef,
                detail,
                durationMs,
                collectedAt);
    }

    private long elapsedMillis(long startedNanos) {
        long elapsedNanos = ticker.getAsLong() - startedNanos;
        return elapsedNanos <= 0L ? 0L : Duration.ofNanos(elapsedNanos).toMillis();
    }

    private boolean canValidate(GuanceEvidenceReadiness.Status status) {
        return status == GuanceEvidenceReadiness.Status.READY_FOR_VALIDATION
                || status == GuanceEvidenceReadiness.Status.CANONICAL_SIGNALS_OBSERVED;
    }

    private String safeValue(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (!SAFE_VALUE.matcher(normalized).matches()
                || !TroubleshootingSecretRedactor.redact(normalized).equals(normalized)) {
            throw invalid(field + " must be a safe source identifier");
        }
        return normalized;
    }

    private String safeWindow(String value) {
        String normalized = value == null || value.isBlank() ? "-15m" : value.trim();
        Matcher matcher = WINDOW.matcher(normalized);
        if (!matcher.matches()) {
            throw invalid("window must use a positive s, m, h, or d duration");
        }
        long amount;
        Duration duration;
        try {
            amount = Long.parseLong(matcher.group(1));
            duration = switch (matcher.group(2)) {
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                case "d" -> Duration.ofDays(amount);
                default -> throw invalid("unsupported window unit");
            };
        } catch (NumberFormatException | ArithmeticException overflow) {
            throw invalid("window duration is too large");
        }
        if (duration.compareTo(Duration.ofHours(24)) > 0) {
            throw invalid("window must not exceed 24 hours");
        }
        return normalized.startsWith("-") ? normalized : "-" + normalized;
    }

    private Instant safeOccurredAt(Instant value) {
        Instant normalized = value == null ? Instant.now(clock) : value;
        try {
            normalized.toEpochMilli();
            return normalized;
        } catch (ArithmeticException outOfRange) {
            throw invalid("occurredAt is outside the supported epoch range");
        }
    }

    private MateClawException invalid(String message) {
        return new MateClawException(
                "err.troubleshooting.invalid_request", 400, message);
    }

    private record SearchObservation(long matchCount, String psId) {
    }

    private record TimedEvidenceResult(EvidenceResult result, long durationMs) {
    }
}
