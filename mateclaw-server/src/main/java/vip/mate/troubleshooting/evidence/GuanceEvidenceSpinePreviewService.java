package vip.mate.troubleshooting.evidence;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.CanonicalNumberParser;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.synthesis.LogTraceSkeleton;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Runs the shared three-stage Evidence Spine against Guance only.
 *
 * <p>This is an acceptance preview, not an online routing switch. It never falls
 * back to Recorded Replay, never persists evidence or candidates, and cannot
 * close the T7/T8 gates by itself.</p>
 */
@Service
public class GuanceEvidenceSpinePreviewService {

    private static final Set<String> GUANCE_ONLY = Set.of("guance");
    private static final String SEARCH_REQUEST =
            GuanceEvidenceSpinePreview.SEARCH_EVIDENCE_REF;
    private static final String TRACE_REQUEST =
            GuanceEvidenceSpinePreview.TRACE_EVIDENCE_REF;
    private static final String CONTRAST_REQUEST =
            GuanceEvidenceSpinePreview.CONTRAST_EVIDENCE_REF;
    private static final String FULL_WARNING =
            "本次只证明一条真实 Guance Evidence Spine 可被确定性压缩；仍需 owner 完成 T7 字段验收，"
                    + "并积累 20–30 条 T8 历史样本；不会自动关闭 fixtureMode。";
    private static final String CORE_WARNING =
            "核心同 PS ID 链路已观测，但成功样本对照不可用；结果继续处于校准期，"
                    + "不得据此关闭 fixtureMode 或提升知识晋升权限。";
    private static final String BLOCKED_WARNING =
            "本次未形成可压缩的 Guance 核心链路；T7/T8 状态不变，且未回退 Recorded Replay。";

    private final EvidenceSpineOrchestrator orchestrator;
    private final GuanceEvidenceReadinessService readinessService;
    private final Clock clock;
    private final LongSupplier ticker;

    @Autowired
    public GuanceEvidenceSpinePreviewService(
            EvidenceSpineOrchestrator orchestrator,
            GuanceEvidenceReadinessService readinessService) {
        this(orchestrator, readinessService, Clock.systemUTC(), System::nanoTime);
    }

    GuanceEvidenceSpinePreviewService(
            EvidenceSpineOrchestrator orchestrator,
            GuanceEvidenceReadinessService readinessService,
            Clock clock,
            LongSupplier ticker) {
        this.orchestrator = orchestrator;
        this.readinessService = readinessService;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.ticker = ticker == null ? System::nanoTime : ticker;
    }

    public GuanceEvidenceSpinePreview preview(
            long workspaceId,
            String system,
            String service,
            String searchTerm,
            String window,
            Instant occurredAt) {
        long started = ticker.getAsLong();
        GuanceEvidenceReadiness readiness =
                readinessService.inspect(workspaceId, system, service);
        if (!canCollect(readiness.status())) {
            return blocked(readiness, elapsedMillis(started));
        }

        Instant observationEnd = safeOccurredAt(occurredAt);
        EvidenceSpinePlan plan = safePlan(searchTerm, window);
        IncidentContext incident = incident(readiness, observationEnd);
        EvidenceSpineResult spine = orchestrator.collect(
                workspaceId, incident, plan, GUANCE_ONLY);
        GuanceEvidenceReadiness updatedReadiness =
                readinessService.inspect(workspaceId, system, service);
        return project(spine, updatedReadiness, elapsedMillis(started));
    }

    private GuanceEvidenceSpinePreview project(
            EvidenceSpineResult spine,
            GuanceEvidenceReadiness readiness,
            long totalDurationMs) {
        LogTraceSkeleton skeleton = spine.skeleton();
        GuanceEvidenceSpinePreview.Stage stage;
        List<String> warnings;
        if (!spine.coreComplete()) {
            stage = GuanceEvidenceSpinePreview.Stage.BLOCKED;
            warnings = List.of(BLOCKED_WARNING);
        } else if (spine.contrastAvailable()) {
            stage = GuanceEvidenceSpinePreview.Stage.FULL_SPINE_OBSERVED;
            warnings = List.of(FULL_WARNING);
        } else {
            stage = GuanceEvidenceSpinePreview.Stage.CORE_CHAIN_OBSERVED;
            warnings = List.of(CORE_WARNING, FULL_WARNING);
        }

        return new GuanceEvidenceSpinePreview(
                stage,
                readiness,
                matchCount(spine.searchEvidence()),
                skeleton == null ? null : skeleton.psId(),
                traceEntries(spine.traceEvidence()),
                skeleton == null ? List.of() : skeleton.serviceSequence(),
                skeleton == null ? 0 : skeleton.anomalySequenceIndexes().size(),
                skeleton == null ? null : skeleton.elapsedMs(),
                contrast(skeleton),
                spine.sourceRequestCount(),
                totalDurationMs,
                steps(spine),
                Instant.now(clock),
                warnings);
    }

    private GuanceEvidenceSpinePreview blocked(
            GuanceEvidenceReadiness readiness,
            long totalDurationMs) {
        return new GuanceEvidenceSpinePreview(
                GuanceEvidenceSpinePreview.Stage.BLOCKED,
                readiness,
                null,
                null,
                null,
                List.of(),
                0,
                null,
                GuanceEvidenceSpinePreview.Contrast.unavailable(),
                0,
                totalDurationMs,
                List.of(
                        notRun("log_search", SEARCH_REQUEST),
                        notRun("log_trace_bundle", TRACE_REQUEST),
                        notRun("contrast_sample", CONTRAST_REQUEST)),
                Instant.now(clock),
                List.of(BLOCKED_WARNING));
    }

    private List<GuanceEvidenceSpinePreview.Step> steps(EvidenceSpineResult spine) {
        List<GuanceEvidenceSpinePreview.Step> steps = new ArrayList<>(3);
        steps.add(step("log_search", SEARCH_REQUEST, spine.searchEvidence()));
        steps.add(step("log_trace_bundle", TRACE_REQUEST, spine.traceEvidence()));
        steps.add(step("contrast_sample", CONTRAST_REQUEST, spine.contrastEvidence()));
        return List.copyOf(steps);
    }

    private GuanceEvidenceSpinePreview.Step step(
            String signalKind,
            String evidenceRef,
            EvidenceResult evidence) {
        if (evidence == null) {
            return notRun(signalKind, evidenceRef);
        }
        return new GuanceEvidenceSpinePreview.Step(
                signalKind,
                evidence.status() == EvidenceStatus.MISSING
                        ? GuanceEvidenceSpinePreview.StepStatus.MISSING
                        : GuanceEvidenceSpinePreview.StepStatus.CANONICAL_RESULT_OBSERVED,
                evidenceRef,
                evidence.collectedAt());
    }

    private GuanceEvidenceSpinePreview.Step notRun(
            String signalKind,
            String evidenceRef) {
        return new GuanceEvidenceSpinePreview.Step(
                signalKind,
                GuanceEvidenceSpinePreview.StepStatus.NOT_RUN,
                evidenceRef,
                null);
    }

    private Long matchCount(EvidenceResult search) {
        if (search == null || search.status() == EvidenceStatus.MISSING) {
            return null;
        }
        Long value = CanonicalNumberParser.parseExactLong(
                search.observed().get("match_count"));
        return value != null && value > 0L ? value : null;
    }

    private Integer traceEntries(EvidenceResult trace) {
        if (trace == null || trace.status() == EvidenceStatus.MISSING) {
            return null;
        }
        Object entries = trace.observed().get("entries");
        return entries instanceof List<?> list ? list.size() : null;
    }

    private GuanceEvidenceSpinePreview.Contrast contrast(LogTraceSkeleton skeleton) {
        if (skeleton == null || !skeleton.contrast().available()) {
            return GuanceEvidenceSpinePreview.Contrast.unavailable();
        }
        LogTraceSkeleton.ContrastSummary value = skeleton.contrast();
        return new GuanceEvidenceSpinePreview.Contrast(
                true,
                value.failureSampleCount(),
                value.failureMatchCount(),
                value.successSampleCount(),
                value.successMatchCount(),
                value.failureRate(),
                value.successRate(),
                value.rateDelta());
    }

    private IncidentContext incident(
            GuanceEvidenceReadiness readiness,
            Instant occurredAt) {
        return new IncidentContext(
                "guance-spine-preview-" + occurredAt.toEpochMilli(),
                readiness.system(),
                readiness.service(),
                null,
                "Guance Evidence Spine preview",
                "P2",
                "validation only",
                null,
                occurredAt,
                null,
                "guance_spine_preview",
                IncidentCompleteness.LOG,
                null);
    }

    private EvidenceSpinePlan safePlan(String searchTerm, String window) {
        try {
            return new EvidenceSpinePlan(
                    SEARCH_REQUEST,
                    TRACE_REQUEST,
                    CONTRAST_REQUEST,
                    searchTerm,
                    window);
        } catch (IllegalArgumentException invalidPlan) {
            throw invalid(invalidPlan.getMessage());
        }
    }

    private Instant safeOccurredAt(Instant occurredAt) {
        Instant normalized = occurredAt == null ? Instant.now(clock) : occurredAt;
        try {
            normalized.toEpochMilli();
            return normalized;
        } catch (ArithmeticException outOfRange) {
            throw invalid("occurredAt is outside the supported epoch range");
        }
    }

    private long elapsedMillis(long startedNanos) {
        long elapsedNanos = ticker.getAsLong() - startedNanos;
        return elapsedNanos <= 0L ? 0L : Duration.ofNanos(elapsedNanos).toMillis();
    }

    private boolean canCollect(GuanceEvidenceReadiness.Status status) {
        return status == GuanceEvidenceReadiness.Status.READY_FOR_VALIDATION
                || status == GuanceEvidenceReadiness.Status.CANONICAL_SIGNALS_OBSERVED;
    }

    private MateClawException invalid(String message) {
        return new MateClawException(
                "err.troubleshooting.invalid_request", 400, message);
    }
}
