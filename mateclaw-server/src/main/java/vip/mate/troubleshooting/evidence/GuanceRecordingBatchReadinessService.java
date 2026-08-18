package vip.mate.troubleshooting.evidence;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import vip.mate.exception.MateClawException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Evaluates the first T7 recording batch once for the whole workspace. */
@Service
public class GuanceRecordingBatchReadinessService {

    public static final String CONTRACT_VERSION =
            "t7-guance-recording-batch-readiness.v2";

    private static final String SCENARIO_SELECTOR = ":scenario:";
    private static final List<String> REQUIRED_SIGNALS = List.of(
            "log_search", "log_trace_bundle", "contrast_sample");

    private final GuanceRecordingTargetCatalog catalog;
    private final GuanceEvidenceReadinessService readinessService;
    private final GuanceBindingFingerprintService fingerprintService;
    private final Clock clock;

    @Autowired
    public GuanceRecordingBatchReadinessService(
            GuanceRecordingTargetCatalog catalog,
            GuanceEvidenceReadinessService readinessService,
            GuanceBindingFingerprintService fingerprintService) {
        this(catalog, readinessService, fingerprintService, Clock.systemUTC());
    }

    GuanceRecordingBatchReadinessService(
            GuanceRecordingTargetCatalog catalog,
            GuanceEvidenceReadinessService readinessService,
            GuanceBindingFingerprintService fingerprintService,
            Clock clock) {
        if (catalog == null || readinessService == null || fingerprintService == null) {
            throw new IllegalArgumentException(
                    "catalog, readiness service and fingerprint service are required");
        }
        this.catalog = catalog;
        this.readinessService = readinessService;
        this.fingerprintService = fingerprintService;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public GuanceRecordingBatchReadiness inspect(long workspaceId) {
        if (workspaceId <= 0) {
            throw new MateClawException(
                    "err.troubleshooting.invalid_request",
                    400,
                    "workspaceId must be positive");
        }
        GuanceRecordingTargetCatalog.FrozenBatch batch = catalog.frozenBatch();
        Map<Scope, ScopeRuntime> runtimes = new LinkedHashMap<>();
        List<GuanceRecordingBatchReadiness.TargetReadiness> targets =
                batch.targets().stream()
                        .map(target -> targetReadiness(
                                workspaceId,
                                target,
                                runtimes.computeIfAbsent(
                                        new Scope(target.system(), target.service()),
                                        scope -> inspectScope(workspaceId, scope))))
                        .toList();

        int frozenCount = targets.size();
        int executableCount = (int) targets.stream()
                .filter(GuanceRecordingBatchReadiness.TargetReadiness::executable)
                .count();
        List<String> blockers = batchBlockers(frozenCount, executableCount);
        boolean ready = blockers.isEmpty();
        return new GuanceRecordingBatchReadiness(
                CONTRACT_VERSION,
                "t7-first-" + batch.catalogFingerprint().substring(0, 24),
                workspaceId,
                batch.contractVersion(),
                batch.catalogFingerprint(),
                frozenCount,
                executableCount,
                ready,
                targets,
                clock.instant().getEpochSecond(),
                blockers);
    }

    private ScopeRuntime inspectScope(long workspaceId, Scope scope) {
        GuanceEvidenceReadiness readiness = readinessService.inspect(
                workspaceId, scope.system(), scope.service());
        Optional<GuanceBindingFingerprintService.Snapshot> fingerprint =
                fingerprintService.current(
                        workspaceId, scope.system(), scope.service());
        return new ScopeRuntime(readiness, activeBindings(readiness), fingerprint);
    }

    private GuanceRecordingBatchReadiness.TargetReadiness targetReadiness(
            long workspaceId,
            GuanceRecordingTargetCatalog.Target target,
            ScopeRuntime runtime) {
        List<String> blockers = new ArrayList<>();
        GuanceEvidenceReadiness readiness = runtime.readiness();
        boolean exactScope = normalize(target.system()).equals(normalize(readiness.system()))
                && normalize(target.service()).equals(normalize(readiness.service()));
        if (!exactScope) {
            blockers.add("running readiness resolved a different system/service scope");
        }
        if (!runtimeReady(readiness)) {
            blockers.add("target data source is not ready for a read-only query");
        }
        if (!target.bindingRefs().equals(runtime.activeBindings())) {
            blockers.add("frozen target bindings do not match the running bindings");
        }
        if (runtime.fingerprint().isEmpty()) {
            blockers.add("exact target binding cannot be uniquely fingerprinted");
        }

        String scenarioKey = scenarioKey(target.selectorKey());
        /*
         * GuanceBindingFingerprintService currently fingerprints only
         * workspace/system/service. Treating that legacy digest as a scenario
         * authorization would let one scenario inherit another scenario's
         * reviewed contracts. D20 requires an explicit scenario-scoped asset
         * binding, so scenario targets remain frozen but non-executable until
         * that migration exists.
         */
        if (scenarioKey != null) {
            blockers.add(
                    "scenario-scoped target binding is not explicitly configured");
        }
        String bindingFingerprint = runtime.fingerprint()
                .map(GuanceBindingFingerprintService.Snapshot::bindingFingerprint)
                .orElse(null);
        String targetBindingFingerprint = bindingFingerprint == null
                || scenarioKey != null
                ? null
                : targetBindingFingerprint(
                        workspaceId, target, scenarioKey, bindingFingerprint);
        return new GuanceRecordingBatchReadiness.TargetReadiness(
                target.targetId(),
                target.system(),
                target.service(),
                scenarioKey,
                target.selectorKey(),
                bindingFingerprint,
                targetBindingFingerprint,
                blockers.isEmpty(),
                blockers);
    }

    private Map<String, String> activeBindings(GuanceEvidenceReadiness readiness) {
        Map<String, String> bindings = new LinkedHashMap<>();
        for (GuanceEvidenceReadiness.SignalReadiness signal : readiness.signals()) {
            if (REQUIRED_SIGNALS.contains(signal.signalKind())
                    && signal.routedToGuance()
                    && readyOrObserved(signal.status())) {
                bindings.put(signal.signalKind(), signal.bindingRef());
            }
        }
        return Map.copyOf(bindings);
    }

    private boolean runtimeReady(GuanceEvidenceReadiness readiness) {
        return readiness.adapterEnabled()
                && readiness.endpointConfigured()
                && readiness.uniqueAssetAuthorized()
                && readiness.credentialState()
                        == GuanceEvidenceReadiness.CredentialState.CONFIGURED
                && (readiness.status()
                        == GuanceEvidenceReadiness.Status.READY_FOR_VALIDATION
                        || readiness.status()
                        == GuanceEvidenceReadiness.Status.CANONICAL_SIGNALS_OBSERVED);
    }

    private boolean readyOrObserved(GuanceEvidenceReadiness.SignalStatus status) {
        return status == GuanceEvidenceReadiness.SignalStatus.READY_FOR_VALIDATION
                || status
                        == GuanceEvidenceReadiness.SignalStatus.CANONICAL_RESULT_OBSERVED;
    }

    private List<String> batchBlockers(int frozenCount, int executableCount) {
        List<String> blockers = new ArrayList<>();
        if (frozenCount < GuanceRecordingTargetCatalog.MIN_WINDOW_TARGETS) {
            blockers.add("only " + frozenCount
                    + " workspace recording targets are frozen; "
                    + GuanceRecordingTargetCatalog.MIN_WINDOW_TARGETS + " required");
        }
        if (frozenCount > GuanceRecordingTargetCatalog.MAX_WINDOW_TARGETS) {
            blockers.add("workspace first recording batch contains " + frozenCount
                    + " targets; at most "
                    + GuanceRecordingTargetCatalog.MAX_WINDOW_TARGETS + " allowed");
        }
        if (executableCount < GuanceRecordingTargetCatalog.MIN_WINDOW_TARGETS) {
            blockers.add("only " + executableCount + " of " + frozenCount
                    + " workspace recording targets are executable; "
                    + GuanceRecordingTargetCatalog.MIN_WINDOW_TARGETS + " required");
        }
        return List.copyOf(blockers);
    }

    private String scenarioKey(String selectorKey) {
        String normalized = selectorKey == null ? "" : selectorKey.trim();
        int marker = normalized.toLowerCase(Locale.ROOT).indexOf(SCENARIO_SELECTOR);
        if (marker < 0) {
            return null;
        }
        String value = normalized.substring(marker + SCENARIO_SELECTOR.length()).trim();
        return value.isBlank() ? null : value;
    }

    private String targetBindingFingerprint(
            long workspaceId,
            GuanceRecordingTargetCatalog.Target target,
            String scenarioKey,
            String bindingFingerprint) {
        StringBuilder canonical = new StringBuilder()
                .append(workspaceId).append('\0')
                .append(normalize(target.system())).append('\0')
                .append(normalize(target.service())).append('\0')
                .append(scenarioKey == null ? "" : normalize(scenarioKey)).append('\0')
                .append(target.selectorKey()).append('\0')
                .append(bindingFingerprint);
        target.bindingRefs().entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .forEach(entry -> canonical
                        .append('\0').append(entry.getKey())
                        .append('\0').append(entry.getValue()));
        return sha256(canonical.toString());
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private record Scope(String system, String service) {
        private Scope {
            system = system == null ? "" : system.trim();
            service = service == null ? "" : service.trim();
        }
    }

    private record ScopeRuntime(
            GuanceEvidenceReadiness readiness,
            Map<String, String> activeBindings,
            Optional<GuanceBindingFingerprintService.Snapshot> fingerprint) {
    }
}
