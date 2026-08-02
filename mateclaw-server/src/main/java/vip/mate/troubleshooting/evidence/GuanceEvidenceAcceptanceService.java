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
import java.util.List;
import java.util.Optional;

/**
 * Persists an explicit owner decision only after re-running the live,
 * Guance-only canonical chain for the exact current binding configuration.
 */
@Service
public class GuanceEvidenceAcceptanceService {

    private final GuanceBindingFingerprintService fingerprintService;
    private final GuanceEvidenceReadinessService readinessService;
    private final GuanceEvidenceValidationService validationService;
    private final GuanceRecordingTargetCatalog recordingTargetCatalog;
    private final GuanceEvidenceAcceptanceStore store;
    private final Clock clock;

    @Autowired
    public GuanceEvidenceAcceptanceService(
            GuanceBindingFingerprintService fingerprintService,
            GuanceEvidenceReadinessService readinessService,
            GuanceEvidenceValidationService validationService,
            GuanceRecordingTargetCatalog recordingTargetCatalog,
            GuanceEvidenceAcceptanceStore store) {
        this(
                fingerprintService,
                readinessService,
                validationService,
                recordingTargetCatalog,
                store,
                Clock.systemUTC());
    }

    GuanceEvidenceAcceptanceService(
            GuanceBindingFingerprintService fingerprintService,
            GuanceEvidenceReadinessService readinessService,
            GuanceEvidenceValidationService validationService,
            GuanceRecordingTargetCatalog recordingTargetCatalog,
            GuanceEvidenceAcceptanceStore store,
            Clock clock) {
        this.fingerprintService = fingerprintService;
        this.readinessService = readinessService;
        this.validationService = validationService;
        this.recordingTargetCatalog = recordingTargetCatalog;
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
        GuanceRecordingTargetCatalog.View recordingTargets =
                recordingTargetCatalog.inspect(readinessService.inspect(
                        workspaceId, system, service));
        if (!recordingTargets.readyForOwnerAcceptance()) {
            throw conflict(
                    "at least " + GuanceRecordingTargetCatalog.MIN_WINDOW_TARGETS
                            + " server-frozen executable recording targets are required "
                            + "before T7 owner acceptance; current="
                            + recordingTargets.executableTargetCount());
        }
        GuanceBindingFingerprintService.Snapshot before =
                fingerprintService.current(workspaceId, system, service)
                        .orElseThrow(() -> conflict(
                                "current Guance binding cannot be uniquely fingerprinted"));

        GuanceEvidenceValidationReport report = validationService.validate(
                workspaceId,
                system,
                service,
                searchTerm,
                window,
                occurredAt);
        if (report.stage()
                != GuanceEvidenceValidationReport.Stage.CANONICAL_CHAIN_OBSERVED) {
            throw conflict(
                    "the live Guance canonical chain was not observed; "
                            + "T7 acceptance was not recorded");
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
                        validationFacts(report),
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
        GuanceEvidenceAcceptanceView view =
                inspect(workspaceId, system, service);
        if (!view.acceptedForCurrentBinding()) {
            throw conflict(
                    "T7 owner acceptance is required for the current Guance "
                            + "binding before collecting T8 samples");
        }
        return view.acceptance();
    }

    private GuanceEvidenceAcceptance.ValidationFacts validationFacts(
            GuanceEvidenceValidationReport report) {
        if (report.matchCount() == null
                || report.psId() == null
                || report.traceEntries() == null) {
            throw conflict(
                    "the Guance validation report lacks canonical chain facts");
        }
        long logSearchDuration = duration(report, "log_search");
        long logTraceDuration = duration(report, "log_trace_bundle");
        return new GuanceEvidenceAcceptance.ValidationFacts(
                report.matchCount(),
                report.traceEntries(),
                sha256(report.psId()),
                logSearchDuration,
                logTraceDuration,
                report.totalDurationMs(),
                report.completedAt());
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
}
