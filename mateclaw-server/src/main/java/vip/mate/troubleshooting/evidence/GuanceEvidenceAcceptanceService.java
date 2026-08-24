package vip.mate.troubleshooting.evidence;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.TroubleshootingSecretRedactor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Persists an explicit owner decision only after re-running the live,
 * Guance-only canonical chain for the exact current binding configuration.
 */
@Service
public class GuanceEvidenceAcceptanceService {

    private static final Set<String> GENERIC_SAFE_SIGNALS = Set.of(
            "error_log_scan",
            "k8s_workload_health");

    private final GuanceBindingFingerprintService fingerprintService;
    private final GuanceEvidenceValidationService validationService;
    private final GuanceRecordingBatchReadinessService recordingBatchReadiness;
    private final GuanceEvidenceAcceptanceStore store;
    private final Clock clock;

    @Autowired
    public GuanceEvidenceAcceptanceService(
            GuanceBindingFingerprintService fingerprintService,
            GuanceEvidenceValidationService validationService,
            GuanceRecordingBatchReadinessService recordingBatchReadiness,
            GuanceEvidenceAcceptanceStore store) {
        this(
                fingerprintService,
                validationService,
                recordingBatchReadiness,
                store,
                Clock.systemUTC());
    }

    GuanceEvidenceAcceptanceService(
            GuanceBindingFingerprintService fingerprintService,
            GuanceEvidenceValidationService validationService,
            GuanceRecordingBatchReadinessService recordingBatchReadiness,
            GuanceEvidenceAcceptanceStore store,
            Clock clock) {
        this.fingerprintService = fingerprintService;
        this.validationService = validationService;
        this.recordingBatchReadiness = recordingBatchReadiness;
        this.store = store;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public GuanceEvidenceAcceptanceView inspect(
            long workspaceId,
            String system,
            String service) {
        String scopeKey = fingerprintService.scopeKey(
                workspaceId, system, service);
        Optional<GuanceBindingFingerprintService.Snapshot> snapshot =
                fingerprintService.current(workspaceId, system, service);
        Optional<GuanceEvidenceAcceptance> latest =
                store.findLatest(workspaceId, scopeKey);
        if (snapshot.isEmpty()) {
            return new GuanceEvidenceAcceptanceView(
                    GuanceEvidenceAcceptanceView.Status.BLOCKED,
                    normalize(system),
                    normalize(service),
                    null,
                    latest.orElse(null),
                    List.of(
                            "current Guance asset, core routes or binding configuration "
                                    + "cannot be uniquely fingerprinted"));
        }

        GuanceBindingFingerprintService.Snapshot current = snapshot.orElseThrow();
        Optional<GuanceEvidenceAcceptance> accepted = store.findByFingerprint(
                workspaceId,
                current.scopeKey(),
                current.bindingFingerprint());
        if (accepted.isPresent()) {
            return new GuanceEvidenceAcceptanceView(
                    GuanceEvidenceAcceptanceView.Status.ACCEPTED,
                    current.system(),
                    current.service(),
                    current.bindingFingerprint(),
                    accepted.orElseThrow(),
                    List.of());
        }
        if (latest.isPresent()) {
            return new GuanceEvidenceAcceptanceView(
                    GuanceEvidenceAcceptanceView.Status.STALE,
                    current.system(),
                    current.service(),
                    current.bindingFingerprint(),
                    latest.orElseThrow(),
                    List.of(
                            "the last owner acceptance belongs to an older "
                                    + "Guance binding fingerprint"));
        }
        return new GuanceEvidenceAcceptanceView(
                GuanceEvidenceAcceptanceView.Status.NOT_ACCEPTED,
                current.system(),
                current.service(),
                current.bindingFingerprint(),
                null,
                List.of(
                        "the current Guance binding has not been explicitly "
                                + "accepted by an owner"));
    }

    public GuanceEvidenceAcceptanceView accept(
            long workspaceId,
            String system,
            String service,
            String searchTerm,
            String window,
            Instant occurredAt,
            GuanceEvidenceAcceptance.Checklist checklist,
            String actor) {
        if (checklist == null || !checklist.complete()) {
            throw invalid("all T7 owner confirmations are required");
        }
        String safeActor = actor(actor);
        GuanceBindingFingerprintService.Snapshot before =
                fingerprintService.current(workspaceId, system, service)
                        .orElseThrow(() -> conflict(
                                "current Guance binding cannot be uniquely fingerprinted"));

        Set<String> genericCandidates = new LinkedHashSet<>(
                before.readOnlySignalKinds());
        genericCandidates.retainAll(GENERIC_SAFE_SIGNALS);
        Map<String, Long> liveCapabilities = genericCandidates.isEmpty()
                ? Map.of()
                : validationService.validateCapabilities(
                        workspaceId,
                        system,
                        service,
                        Set.copyOf(genericCandidates),
                        window,
                        occurredAt);

        GuanceEvidenceValidationReport report = null;
        if (before.readOnlySignalKinds().containsAll(
                Set.of("log_search", "log_trace_bundle"))) {
            report = validationService.validate(
                    workspaceId,
                    system,
                    service,
                    searchTerm,
                    window,
                    occurredAt);
        }
        boolean coreObserved = report != null
                && report.stage()
                        == GuanceEvidenceValidationReport.Stage.CANONICAL_CHAIN_OBSERVED;
        if (!coreObserved && liveCapabilities.isEmpty()) {
            throw conflict(
                    "no configured Guance capability produced a live canonical result; "
                            + "owner acceptance was not recorded");
        }

        GuanceBindingFingerprintService.Snapshot after =
                fingerprintService.current(workspaceId, system, service)
                        .orElseThrow(() -> conflict(
                                "Guance binding changed during owner acceptance"));
        if (!before.scopeKey().equals(after.scopeKey())
                || !before.bindingFingerprint().equals(
                        after.bindingFingerprint())) {
            throw conflict("Guance binding changed during owner acceptance");
        }

        GuanceEvidenceAcceptance acceptance =
                new GuanceEvidenceAcceptance(
                        acceptanceId(before),
                        before.system(),
                        before.service(),
                        before.bindingFingerprint(),
                        checklist,
                        validationFacts(coreObserved ? report : null, liveCapabilities),
                        safeActor,
                        Instant.now(clock));
        store.saveOrGet(workspaceId, before.scopeKey(), acceptance);
        return inspect(workspaceId, system, service);
    }

    /** Called before any T8 Guance source request. */
    public GuanceEvidenceAcceptance requireAccepted(
            long workspaceId,
            String system,
            String service) {
        // Acceptance is a binding-scoped historical decision. It never
        // supersedes the current workspace-wide T7 recording-batch gate.
        // Recheck the mutable batch before reading an existing acceptance so
        // a previously accepted 0/20 workspace cannot reach Guance source I/O.
        requireRecordingBatchReady(workspaceId);
        GuanceEvidenceAcceptance accepted =
                requireAcceptedBinding(workspaceId, system, service);
        if (!accepted.validation().coreChainObserved()) {
            throw conflict(
                    "该验收只覆盖通用调查能力；场景排障仍需完成"
                            + "日志搜索与关联链的真实验证");
        }
        return accepted;
    }

    /**
     * Exact service-level authority for generic bounded investigation.
     * Workspace recording-batch size is a scale-readiness metric, not proof
     * for (or against) this individual binding.
     */
    public GuanceEvidenceAcceptance requireAcceptedBinding(
            long workspaceId,
            String system,
            String service) {
        return requireAcceptedBindingAuthority(
                workspaceId, system, service).acceptance();
    }

    /**
     * Freezes the accepted fingerprint and the exact semantic read-only
     * capabilities it covers. Callers must pass this immutable set into their
     * planner instead of inferring tools from global registration.
     */
    public AcceptedBinding requireAcceptedBindingAuthority(
            long workspaceId,
            String system,
            String service) {
        GuanceBindingFingerprintService.Snapshot snapshot =
                fingerprintService.currentForFormalAuthority(
                                workspaceId, system, service)
                        .orElseThrow(() -> bindingNotAccepted());
        GuanceEvidenceAcceptance accepted = store.findByFingerprint(
                        workspaceId,
                        snapshot.scopeKey(),
                        snapshot.bindingFingerprint())
                .orElseThrow(this::bindingNotAccepted);
        if (!same(snapshot.system(), accepted.system())
                || !same(snapshot.service(), accepted.service())
                || !snapshot.bindingFingerprint().equals(
                        accepted.bindingFingerprint())) {
            throw bindingNotAccepted();
        }
        Set<String> liveAccepted = new LinkedHashSet<>(
                accepted.validation().liveAcceptedSignalKinds());
        liveAccepted.retainAll(snapshot.readOnlySignalKinds());
        liveAccepted.retainAll(GENERIC_SAFE_SIGNALS);
        return new AcceptedBinding(accepted, liveAccepted);
    }

    private GuanceRecordingBatchReadiness requireRecordingBatchReady(
            long workspaceId) {
        GuanceRecordingBatchReadiness recordingTargets =
                recordingBatchReadiness.inspect(workspaceId);
        if (!recordingTargets.readyForOwnerAcceptance()) {
            throw conflict(
                    "at least " + GuanceRecordingTargetCatalog.MIN_WINDOW_TARGETS
                            + " executable workspace recording targets are required "
                            + "before T7 owner acceptance or T8 collection; current="
                            + recordingTargets.executableTargetCount());
        }
        return recordingTargets;
    }

    private GuanceEvidenceAcceptance.ValidationFacts validationFacts(
            GuanceEvidenceValidationReport report,
            Map<String, Long> liveCapabilities) {
        if (report == null) {
            long genericDuration = liveCapabilities.values().stream()
                    .mapToLong(Long::longValue)
                    .sum();
            return new GuanceEvidenceAcceptance.ValidationFacts(
                    0,
                    0,
                    null,
                    0,
                    0,
                    genericDuration,
                    Instant.now(clock),
                    liveCapabilities);
        }
        if (report.matchCount() == null
                || report.psId() == null
                || report.traceEntries() == null) {
            throw conflict(
                    "the Guance validation report lacks canonical chain facts");
        }
        long logSearchDuration = duration(report, "log_search");
        long logTraceDuration = duration(report, "log_trace_bundle");
        long genericDuration = liveCapabilities.values().stream()
                .mapToLong(Long::longValue)
                .sum();
        return new GuanceEvidenceAcceptance.ValidationFacts(
                report.matchCount(),
                report.traceEntries(),
                sha256(report.psId()),
                logSearchDuration,
                logTraceDuration,
                report.totalDurationMs() + genericDuration,
                report.completedAt(),
                liveCapabilities);
    }

    private long duration(
            GuanceEvidenceValidationReport report,
            String signalKind) {
        return report.steps().stream()
                .filter(step -> signalKind.equals(step.signalKind()))
                .filter(step -> step.status()
                        == GuanceEvidenceValidationReport.StepStatus
                                .CANONICAL_RESULT_OBSERVED)
                .map(GuanceEvidenceValidationReport.Step::durationMs)
                .filter(value -> value != null)
                .findFirst()
                .orElseThrow(() -> conflict(
                        "the Guance validation report lacks measured "
                                + signalKind + " facts"));
    }

    private String acceptanceId(
            GuanceBindingFingerprintService.Snapshot snapshot) {
        return "t7-" + sha256(
                snapshot.scopeKey() + ":" + snapshot.bindingFingerprint())
                .substring(0, 24);
    }

    private String actor(String value) {
        String normalized = normalize(value);
        if (normalized.isBlank()
                || normalized.length() > 255
                || !TroubleshootingSecretRedactor.redact(normalized)
                        .equals(normalized)) {
            throw invalid("an authenticated, safe operator is required");
        }
        return normalized;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean same(String left, String right) {
        return normalize(left).toLowerCase(Locale.ROOT)
                .equals(normalize(right).toLowerCase(Locale.ROOT));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private MateClawException invalid(String message) {
        return new MateClawException(
                "err.troubleshooting.invalid_request", 400, message);
    }

    private MateClawException conflict(String message) {
        return new MateClawException(
                "err.troubleshooting.guance_acceptance_conflict", 409, message);
    }

    private MateClawException bindingNotAccepted() {
        return conflict(
                "当前系统/服务的观测云只读取证尚未验收；"
                        + "请管理员完成数据源接入、精确资产配置和连通验证");
    }

    public record AcceptedBinding(
            GuanceEvidenceAcceptance acceptance,
            Set<String> readOnlySignalKinds) {

        public AcceptedBinding {
            if (acceptance == null) {
                throw new IllegalArgumentException("acceptance is required");
            }
            readOnlySignalKinds = Set.copyOf(
                    readOnlySignalKinds == null ? Set.of() : readOnlySignalKinds);
        }
    }
}
