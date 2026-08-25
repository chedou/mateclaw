package vip.mate.troubleshooting.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vip.mate.troubleshooting.TroubleshootingEvidenceSanitizer;
import vip.mate.troubleshooting.TroubleshootingSecretRedactor;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.IncidentContext;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read-only Guance DQL adapter using the official query-data API shape. */
public final class GuanceEvidenceAdapter implements EvidenceSourceAdapter {

    private static final Logger log = LoggerFactory.getLogger(GuanceEvidenceAdapter.class);
    private static final String PLATFORM = "guance";
    private static final Pattern SAFE_VALUE =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}");
    private static final Pattern WINDOW = Pattern.compile("-?([1-9][0-9]*)([smhd])");
    private static final Pattern SAFE_CURL_EXIT =
            Pattern.compile("native curl transport failed with exit code ([0-9]{1,3})");
    private static final int MAX_BOUND_ROWS = 500;
    private static final int MAX_COMPONENT_QUERIES = 4;
    private static final int MAX_POINT_COUNT = 10_000;
    private static final int MAX_INTERVAL_SECONDS = 86_400;

    private final EvidenceProperties.Guance config;
    private final ObjectMapper objectMapper;
    private final EvidenceHttpTransport transport;
    private final WorkspaceObservabilityAssets workspaceAssets;
    private final WorkspaceEvidenceContracts workspaceContracts;
    /**
     * Per-workspace overrides for enablement, endpoint and credential.
     *
     * <p>Nullable, and null in every unit test that predates the settings
     * table. When absent the adapter behaves exactly as it did when these
     * three values could only come from application.yml.
     */
    private final WorkspaceEvidenceSettingsService workspaceSettings;
    /** Rechecks formal binding authority immediately before transport I/O. */
    private final Supplier<GuanceBindingFingerprintService> bindingFingerprintSource;
    private final Clock clock;
    private final ConcurrentMap<ObservationKey, Instant> observations =
            new ConcurrentHashMap<>();
    private final ThreadLocal<InspectionWarmCache> inspectionWarm = new ThreadLocal<>();

    private static final class InspectionWarmCache {
        private final long workspaceId;
        private EffectiveEvidenceSettings settings;
        private Map<String, EvidenceProperties.Binding> bindings;
        private Map<String, Set<String>> workspaceBindingIndex;

        private InspectionWarmCache(long workspaceId) {
            this.workspaceId = workspaceId;
        }
    }

    /**
     * Prefetch settings/bindings/workspace binding index for one catalog-style pass.
     * Must be paired with {@link #endInspectionWarm()} on the same thread.
     */
    void beginInspectionWarm(long workspaceId) {
        if (workspaceId <= 0) {
            return;
        }
        InspectionWarmCache cache = new InspectionWarmCache(workspaceId);
        cache.settings = loadSettings(workspaceId);
        cache.bindings = loadResolvedBindings(workspaceId);
        try {
            cache.workspaceBindingIndex = workspaceAssets.activeBindingReferencesBySignal();
        } catch (RuntimeException unavailableRegistry) {
            cache.workspaceBindingIndex = Map.of();
        }
        inspectionWarm.set(cache);
    }

    void endInspectionWarm() {
        inspectionWarm.remove();
    }

    GuanceEvidenceAdapter(
            EvidenceProperties.Guance config,
            ObjectMapper objectMapper,
            EvidenceHttpTransport transport,
            Clock clock) {
        this(config, objectMapper, transport, WorkspaceObservabilityAssets.NONE,
                WorkspaceEvidenceContracts.NONE, clock);
    }

    GuanceEvidenceAdapter(
            EvidenceProperties.Guance config,
            ObjectMapper objectMapper,
            EvidenceHttpTransport transport,
            WorkspaceObservabilityAssets workspaceAssets,
            Clock clock) {
        this(config, objectMapper, transport, workspaceAssets,
                WorkspaceEvidenceContracts.NONE, clock);
    }

    GuanceEvidenceAdapter(
            EvidenceProperties.Guance config,
            ObjectMapper objectMapper,
            EvidenceHttpTransport transport,
            WorkspaceObservabilityAssets workspaceAssets,
            WorkspaceEvidenceContracts workspaceContracts,
            Clock clock) {
        this(config, objectMapper, transport, workspaceAssets, workspaceContracts, null, clock);
    }

    GuanceEvidenceAdapter(
            EvidenceProperties.Guance config,
            ObjectMapper objectMapper,
            EvidenceHttpTransport transport,
            WorkspaceObservabilityAssets workspaceAssets,
            WorkspaceEvidenceContracts workspaceContracts,
            WorkspaceEvidenceSettingsService workspaceSettings,
            Clock clock) {
        this(
                config,
                objectMapper,
                transport,
                workspaceAssets,
                workspaceContracts,
                workspaceSettings,
                (Supplier<GuanceBindingFingerprintService>) null,
                clock);
    }

    GuanceEvidenceAdapter(
            EvidenceProperties.Guance config,
            ObjectMapper objectMapper,
            EvidenceHttpTransport transport,
            WorkspaceObservabilityAssets workspaceAssets,
            WorkspaceEvidenceContracts workspaceContracts,
            WorkspaceEvidenceSettingsService workspaceSettings,
            GuanceBindingFingerprintService bindingFingerprints,
            Clock clock) {
        this(
                config,
                objectMapper,
                transport,
                workspaceAssets,
                workspaceContracts,
                workspaceSettings,
                bindingFingerprints == null ? null : () -> bindingFingerprints,
                clock);
    }

    GuanceEvidenceAdapter(
            EvidenceProperties.Guance config,
            ObjectMapper objectMapper,
            EvidenceHttpTransport transport,
            WorkspaceObservabilityAssets workspaceAssets,
            WorkspaceEvidenceContracts workspaceContracts,
            WorkspaceEvidenceSettingsService workspaceSettings,
            Supplier<GuanceBindingFingerprintService> bindingFingerprintSource,
            Clock clock) {
        this.config = config == null ? new EvidenceProperties.Guance() : config;
        this.objectMapper = objectMapper;
        this.transport = transport;
        this.workspaceAssets = workspaceAssets == null
                ? WorkspaceObservabilityAssets.NONE : workspaceAssets;
        this.workspaceContracts = workspaceContracts == null
                ? WorkspaceEvidenceContracts.NONE : workspaceContracts;
        this.workspaceSettings = workspaceSettings;
        this.bindingFingerprintSource = bindingFingerprintSource == null
                ? () -> null : bindingFingerprintSource;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * The enablement, endpoint and credential in force for one workspace.
     *
     * <p>Resolved on every call unless an {@link #beginInspectionWarm(long)} cache is
     * active. Caching across requests would put back the restart that moving these
     * values into the database was meant to remove.
     */
    EffectiveEvidenceSettings settingsFor(long workspaceId) {
        InspectionWarmCache warm = inspectionWarm.get();
        if (warm != null && warm.workspaceId == workspaceId && warm.settings != null) {
            return warm.settings;
        }
        return loadSettings(workspaceId);
    }

    private EffectiveEvidenceSettings loadSettings(long workspaceId) {
        if (workspaceSettings == null || workspaceId <= 0) {
            return deploymentSettings();
        }
        try {
            return workspaceSettings.effective(workspaceId);
        } catch (RuntimeException lookupFailure) {
            // Evidence is fail-closed: if the override cannot be read we fall
            // back to the deployment values rather than inventing an
            // enablement nobody configured.
            log.warn("Workspace {} evidence settings lookup failed ({}); using deployment defaults",
                    workspaceId, lookupFailure.getClass().getSimpleName());
            return deploymentSettings();
        }
    }

    private EffectiveEvidenceSettings deploymentSettings() {
        // config::getApiKey rather than config.getApiKey(): an enablement or
        // endpoint question must not read the credential.
        return new EffectiveEvidenceSettings(
                config.isEnabled(),
                config.getBaseUrl(),
                config::getApiKey,
                config.isAllowInsecureHttp(),
                false,
                false,
                EffectiveEvidenceSettings.Origin.DEPLOYMENT);
    }

    @Override
    public String platform() {
        return PLATFORM;
    }

    @Override
    public boolean supports(String signalKind) {
        return hasAuthorizedBinding(signalKind);
    }

    @Override
    public EvidenceResult collect(
            long workspaceId,
            EvidenceRequest request,
            IncidentContext incident) {
        return collect(workspaceId, request, incident, timeout());
    }

    @Override
    public EvidenceResult collect(
            long workspaceId,
            EvidenceRequest request,
            IncidentContext incident,
            Duration callerTimeout) {
        return collect(workspaceId, request, incident, callerTimeout, null);
    }

    /**
     * Formal collection guarded by the exact binding fingerprint accepted at
     * admission. The binding, endpoint, credential and timeout used below are
     * copied before the final fingerprint check and never resolved again.
     */
    public EvidenceResult collect(
            long workspaceId,
            EvidenceRequest request,
            IncidentContext incident,
            Duration callerTimeout,
            String expectedBindingFingerprint) {
        if (workspaceId <= 0) {
            throw new IllegalArgumentException("workspaceId must be positive");
        }
        if (request == null || incident == null) {
            throw new IllegalArgumentException("request and incident are required");
        }
        if (callerTimeout == null || callerTimeout.isZero() || callerTimeout.isNegative()) {
            return missing(request, "caller evidence deadline is exhausted");
        }
        VerifiedAuthority admittedAuthority = assertAcceptedAuthorityStillCurrent(
                workspaceId,
                incident,
                request.signalKind(),
                expectedBindingFingerprint);
        AuthorizedBinding resolved;
        try {
            resolved = authorizedBinding(
                    workspaceId, incident.system(), incident.service(), request.signalKind());
        } catch (RuntimeException resolutionFailure) {
            if (formalExpectation(expectedBindingFingerprint)) {
                throw FormalEvidenceAuthorityException.configurationDrift(
                        "formal Guance binding could not be resolved after admission");
            }
            log.warn("Guance workspace asset resolution failed ({})",
                    resolutionFailure.getClass().getSimpleName());
            return missing(request, "workspace asset resolution failed");
        }
        if (resolved == null) {
            if (formalExpectation(expectedBindingFingerprint)) {
                throw FormalEvidenceAuthorityException.configurationDrift(
                        "formal Guance binding is no longer authorized");
            }
            return missing(request, "workspace asset or signal binding is not authorized");
        }
        AuthorizedBinding authorized = new AuthorizedBinding(
                copyBinding(resolved.binding()),
                Map.copyOf(resolved.parameters()));
        EffectiveEvidenceSettings settings;
        try {
            settings = freezeSettings(admittedAuthority == null
                    ? settingsFor(workspaceId)
                    : settingsForFormalAuthority(workspaceId));
            if (admittedAuthority != null) {
                String frozenSettingsFingerprint =
                        admittedAuthority.verifier().settingsFingerprint(
                                workspaceId, settings);
                if (!frozenSettingsFingerprint.equals(
                        admittedAuthority.snapshot().settingsFingerprint())) {
                    throw FormalEvidenceAuthorityException.configurationDrift(
                            "formal Guance settings changed after admission");
                }
            }
        } catch (FormalEvidenceAuthorityException authorityFailure) {
            throw authorityFailure;
        } catch (RuntimeException settingsFailure) {
            if (formalExpectation(expectedBindingFingerprint)) {
                throw FormalEvidenceAuthorityException.verifierFailure(
                        "formal Guance settings could not be frozen",
                        settingsFailure);
            }
            throw settingsFailure;
        }
        if (!settings.guanceCallable()) {
            if (formalExpectation(expectedBindingFingerprint)) {
                throw FormalEvidenceAuthorityException.configurationDrift(
                        "formal Guance source configuration is no longer callable");
            }
            return missing(request, "adapter disabled or base configuration missing");
        }
        boolean formalAuthority = formalExpectation(expectedBindingFingerprint);
        if (formalAuthority && workspaceSettings == null) {
            throw FormalEvidenceAuthorityException.verifierUnavailable(
                    "formal Guance endpoint policy verifier is unavailable");
        }
        if (workspaceSettings != null
                && (formalAuthority
                        || settings.origin() == EffectiveEvidenceSettings.Origin.WORKSPACE)) {
            // Formal calls always need a fresh, strict proof. Rehearsal keeps
            // the historical guard only for workspace-editable endpoints.
            try {
                if (formalAuthority) {
                    workspaceSettings.assertReachableEndpointStrict(
                            settings.guanceBaseUrl());
                } else {
                    workspaceSettings.assertReachableEndpoint(
                            settings.guanceBaseUrl());
                }
            } catch (SecurityException blocked) {
                log.warn("Workspace {} Guance endpoint rejected by the outbound guard", workspaceId);
                if (formalAuthority) {
                    throw FormalEvidenceAuthorityException.policyBlocked(
                            "formal Guance endpoint is blocked by outbound policy");
                }
                return missing(request, "Guance endpoint is not permitted by the outbound guard");
            }
        }

        try {
            EvidenceProperties.Binding binding = authorized.binding();
            URI endpoint = queryUri(settings);
            Duration requestTimeout = boundedTimeout(callerTimeout);
            assertAcceptedAuthorityStillCurrent(
                    workspaceId,
                    incident,
                    request.signalKind(),
                    expectedBindingFingerprint);
            WindowRange window = window(request.window(), incident.occurredAt());
            List<String> queries = configuredQueryTemplates(binding).stream()
                    .map(template -> render(
                            template, request, incident, window, authorized.parameters()))
                    .toList();
            String body = requestBody(queries, window, binding, request.signalKind());
            log.debug("Dispatching Guance evidence signal {} via {}",
                    normalizeKey(request.signalKind()),
                    transport.getClass().getSimpleName());
            EvidenceHttpTransport.Response response = transport.postJson(
                    endpoint,
                    Map.of(
                            "Content-Type", "application/json",
                            "DF-API-KEY", settings.guanceApiKey()),
                    body,
                    requestTimeout);
            log.debug("Guance evidence signal {} returned HTTP {}",
                    normalizeKey(request.signalKind()), response.statusCode());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return missing(request, "Guance returned HTTP " + response.statusCode());
            }

            Map<String, Object> observed = normalize(
                    response.body(), binding, request, incident.service());
            if (observed.isEmpty()) {
                return missingCanonical(request);
            }
            Instant collectedAt = Instant.now(clock);
            observations.put(
                    new ObservationKey(
                            workspaceId,
                            normalizeKey(incident.system()),
                            normalizeKey(incident.service()),
                            normalizeKey(request.signalKind())),
                    collectedAt);
            return new EvidenceResult(
                    request.requestId(), namespace(binding), "", EvidenceStatus.NORMAL,
                    summary(binding, request), observed,
                    "guance:" + normalizeKey(request.signalKind()), collectedAt);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return missing(request, "Guance collection interrupted");
        } catch (FormalEvidenceAuthorityException authorityFailure) {
            throw authorityFailure;
        } catch (Exception failure) {
            String safeFailure = safeFailureKind(failure);
            log.warn("Guance evidence collection failed for request {} ({})",
                    request.requestId(), safeFailure);
            return missing(request, "Guance collection failed: " + safeFailure);
        }
    }

    private String safeFailureKind(Exception failure) {
        String kind = failure.getClass().getSimpleName();
        String message = failure.getMessage();
        Matcher curlExit = message == null ? null : SAFE_CURL_EXIT.matcher(message);
        return curlExit != null && curlExit.matches()
                ? kind + ":exit-" + curlExit.group(1)
                : kind;
    }

    private VerifiedAuthority assertAcceptedAuthorityStillCurrent(
            long workspaceId,
            IncidentContext incident,
            String signalKind,
            String expectedBindingFingerprint) {
        if (!formalExpectation(expectedBindingFingerprint)) {
            return null;
        }
        String expected = expectedBindingFingerprint.trim().toLowerCase(Locale.ROOT);
        if (!expected.matches("[a-f0-9]{64}")) {
            throw FormalEvidenceAuthorityException.invalidExpectation(
                    "formal Guance binding fingerprint must be SHA-256 hex");
        }
        GuanceBindingFingerprintService bindingFingerprints;
        try {
            bindingFingerprints = bindingFingerprintSource.get();
        } catch (RuntimeException unavailableAuthority) {
            throw FormalEvidenceAuthorityException.verifierFailure(
                    "formal Guance binding verifier failed", unavailableAuthority);
        }
        if (bindingFingerprints == null) {
            throw FormalEvidenceAuthorityException.verifierUnavailable(
                    "formal Guance binding verifier is unavailable");
        }
        GuanceBindingFingerprintService.Snapshot current;
        try {
            current = bindingFingerprints.currentForFormalAuthority(
                            workspaceId, incident.system(), incident.service())
                    .orElseThrow(() -> FormalEvidenceAuthorityException.configurationDrift(
                            "formal Guance binding authority is no longer present"));
        } catch (FormalEvidenceAuthorityException authorityFailure) {
            throw authorityFailure;
        } catch (RuntimeException unavailableAuthority) {
            throw FormalEvidenceAuthorityException.verifierFailure(
                    "formal Guance binding verifier failed", unavailableAuthority);
        }
        if (!expected.equals(current.bindingFingerprint())
                || !current.readOnlySignalKinds().contains(normalizeKey(signalKind))) {
            throw FormalEvidenceAuthorityException.configurationDrift(
                    "formal Guance binding changed after admission");
        }
        return new VerifiedAuthority(bindingFingerprints, current);
    }

    private boolean formalExpectation(String expectedBindingFingerprint) {
        return expectedBindingFingerprint != null
                && !expectedBindingFingerprint.isBlank();
    }

    private EffectiveEvidenceSettings settingsForFormalAuthority(long workspaceId) {
        if (workspaceSettings == null || workspaceId <= 0) {
            return deploymentSettings();
        }
        try {
            return workspaceSettings.effective(workspaceId);
        } catch (RuntimeException lookupFailure) {
            throw FormalEvidenceAuthorityException.verifierFailure(
                    "formal Guance workspace settings lookup failed",
                    lookupFailure);
        }
    }

    private EffectiveEvidenceSettings freezeSettings(
            EffectiveEvidenceSettings settings) {
        if (settings == null) {
            return EffectiveEvidenceSettings.resolved(
                    false,
                    "",
                    null,
                    false,
                    false,
                    false,
                    EffectiveEvidenceSettings.Origin.DEPLOYMENT);
        }
        return EffectiveEvidenceSettings.resolved(
                settings.guanceEnabled(),
                settings.guanceBaseUrl(),
                settings.guanceApiKey(),
                settings.guanceAllowInsecureHttp(),
                settings.replayEnabled(),
                settings.agentEnabled(),
                settings.origin());
    }

    private EvidenceProperties.Binding copyBinding(
            EvidenceProperties.Binding source) {
        EvidenceProperties.Binding copy = new EvidenceProperties.Binding();
        copy.setSignalKind(source.getSignalKind());
        copy.setNamespace(source.getNamespace());
        copy.setSummary(source.getSummary());
        copy.setScenario(source.getScenario());
        copy.setQuestion(source.getQuestion());
        copy.setFixedConditions(List.copyOf(
                source.getFixedConditions() == null
                        ? List.of() : source.getFixedConditions()));
        copy.setQueryTemplate(source.getQueryTemplate());
        copy.setQueryTemplates(List.copyOf(
                source.getQueryTemplates() == null
                        ? List.of() : source.getQueryTemplates()));
        copy.setQueryOptions(copyQueryOptions(source.getQueryOptions()));
        copy.setMaxRows(source.getMaxRows());
        copy.setFieldAliases(Map.copyOf(
                source.getFieldAliases() == null
                        ? Map.of() : source.getFieldAliases()));
        copy.setConstantFields(Map.copyOf(
                source.getConstantFields() == null
                        ? Map.of() : source.getConstantFields()));
        copy.setAssetParameters(List.copyOf(
                source.getAssetParameters() == null
                        ? List.of() : source.getAssetParameters()));
        return copy;
    }

    private EvidenceProperties.QueryOptions copyQueryOptions(
            EvidenceProperties.QueryOptions source) {
        if (source == null) {
            return null;
        }
        EvidenceProperties.QueryOptions copy = new EvidenceProperties.QueryOptions();
        copy.setMaxPointCount(source.getMaxPointCount());
        copy.setInterval(source.getInterval());
        copy.setAlignTime(source.isAlignTime());
        copy.setSeriesLimit(source.getSeriesLimit());
        copy.setDisableSampling(source.isDisableSampling());
        copy.setTimeZone(source.getTimeZone());
        return copy;
    }

    @Override
    public EvidenceSourceHealth health() {
        if (!config.isEnabled()) {
            return new EvidenceSourceHealth(
                    PLATFORM, EvidenceSourceHealth.Status.DISABLED, false, "adapter disabled");
        }
        if (!hasAnyAuthorizedBinding()) {
            return new EvidenceSourceHealth(
                    PLATFORM, EvidenceSourceHealth.Status.DEGRADED, false,
                    "explicit workspace asset authorization missing or invalid");
        }
        if (!baseConfigured(deploymentSettings())) {
            return new EvidenceSourceHealth(
                    PLATFORM, EvidenceSourceHealth.Status.DEGRADED, false,
                    "base URL, API key, or bindings missing");
        }
        if (!observations.isEmpty()) {
            return new EvidenceSourceHealth(
                    PLATFORM, EvidenceSourceHealth.Status.READY, false,
                    "canonical evidence observed for at least one scoped signal; "
                            + "T7 sample acceptance remains unverified");
        }
        return new EvidenceSourceHealth(
                PLATFORM, EvidenceSourceHealth.Status.DEGRADED, false,
                "authorized but not live-verified");
    }

    private boolean baseConfigured(EffectiveEvidenceSettings settings) {
        return settings.guanceCallable()
                && ((config.getBindings() != null && !config.getBindings().isEmpty())
                    || workspaceContracts != WorkspaceEvidenceContracts.NONE);
    }

    /**
     * Deployment-level enablement, for the source health probe.
     *
     * <p>{@code health()} describes the process, not a tenant, so it keeps
     * reading the deployment values. Anything that knows which workspace it is
     * acting for should call {@link #enabled(long)} instead.
     */
    boolean enabled() {
        return config.isEnabled();
    }

    boolean enabled(long workspaceId) {
        return settingsFor(workspaceId).guanceEnabled();
    }

    /** Checks the endpoint shape without opening a connection or reading credentials. */
    boolean endpointConfigured() {
        return endpointConfigured(deploymentSettings());
    }

    boolean endpointConfigured(long workspaceId) {
        return endpointConfigured(settingsFor(workspaceId));
    }

    private boolean endpointConfigured(EffectiveEvidenceSettings settings) {
        if (!settings.guanceEnabled() || !present(settings.guanceBaseUrl())) {
            return false;
        }
        try {
            queryUri(settings);
            return true;
        } catch (RuntimeException invalidEndpoint) {
            return false;
        }
    }

    /** Must be called only after an exact workspace asset authorization was established. */
    GuanceEvidenceReadiness.CredentialState credentialState() {
        return credentialState(deploymentSettings());
    }

    GuanceEvidenceReadiness.CredentialState credentialState(long workspaceId) {
        return credentialState(settingsFor(workspaceId));
    }

    private GuanceEvidenceReadiness.CredentialState credentialState(
            EffectiveEvidenceSettings settings) {
        return present(settings.guanceApiKey())
                ? GuanceEvidenceReadiness.CredentialState.CONFIGURED
                : GuanceEvidenceReadiness.CredentialState.MISSING;
    }

    boolean hasUniqueAssetScope(long workspaceId, String system, String service) {
        return exactAssetScopes(workspaceId, system, service).size() == 1;
    }

    SignalInspection inspectSignal(
            long workspaceId,
            String system,
            String service,
            String signalKind) {
        String normalizedSignal = normalizeKey(signalKind);
        List<AssetScope> matches;
        try {
            matches = effectiveAssetScopes(workspaceId, system, service, signalKind);
        } catch (RuntimeException resolutionFailure) {
            return new SignalInspection(
                    GuanceEvidenceReadiness.SignalStatus.INVALID_BINDING,
                    "", null, "workspace asset resolution failed");
        }
        if (matches.isEmpty()) {
            return new SignalInspection(
                    GuanceEvidenceReadiness.SignalStatus.UNAUTHORIZED,
                    "", null, "workspace asset is not authorized");
        }
        if (matches.size() != 1) {
            return new SignalInspection(
                    GuanceEvidenceReadiness.SignalStatus.INVALID_BINDING,
                    "", null, "workspace asset authorization is ambiguous");
        }

        AssetScope asset = matches.getFirst();
        if (!asset.enabled() || !PLATFORM.equals(normalizeKey(asset.platform()))) {
            return new SignalInspection(
                    GuanceEvidenceReadiness.SignalStatus.UNAUTHORIZED,
                    "", null, "workspace asset is disabled or belongs to another platform");
        }
        if (asset.signalBindings() == null) {
            return new SignalInspection(
                    GuanceEvidenceReadiness.SignalStatus.UNAUTHORIZED,
                    "", null, "signal binding is not authorized");
        }
        List<Map.Entry<String, String>> signalEntries = asset.signalBindings().entrySet().stream()
                .filter(entry -> normalizeKey(entry.getKey()).equals(normalizedSignal))
                .toList();
        if (signalEntries.isEmpty()) {
            return new SignalInspection(
                    GuanceEvidenceReadiness.SignalStatus.UNAUTHORIZED,
                    "", null, "signal binding is not authorized");
        }
        if (signalEntries.size() != 1) {
            return new SignalInspection(
                    GuanceEvidenceReadiness.SignalStatus.INVALID_BINDING,
                    "", null, "signal binding is ambiguous");
        }

        String rawBindingRef = signalEntries.getFirst().getValue();
        String bindingRef = rawBindingRef == null ? "" : rawBindingRef.trim();
        if (!safeReference(bindingRef)) {
            return new SignalInspection(
                    GuanceEvidenceReadiness.SignalStatus.INVALID_BINDING,
                    "", null, "signal binding reference is invalid");
        }
        String normalizedBindingRef = normalizeKey(bindingRef);
        List<Map.Entry<String, EvidenceProperties.Binding>> bindingEntries =
                resolvedBindings(asset.workspaceId()).entrySet().stream()
                        .filter(entry -> safeReference(entry.getKey())
                                && normalizeKey(entry.getKey()).equals(normalizedBindingRef))
                        .toList();
        if (bindingEntries.size() != 1
                || !validBinding(signalKind, bindingEntries.getFirst().getValue())) {
            return new SignalInspection(
                    GuanceEvidenceReadiness.SignalStatus.INVALID_BINDING,
                    bindingRef, null, "canonical binding is missing or invalid");
        }
        EvidenceProperties.Binding binding = bindingEntries.getFirst().getValue();
        if (SystemObservabilityScopePolicy.isSystemService(asset.service())
                && (!SystemObservabilityScopePolicy.allowsSignal(signalKind)
                        || !SystemObservabilityScopePolicy
                                .safelyFiltersRuntimeService(binding))) {
            return new SignalInspection(
                    GuanceEvidenceReadiness.SignalStatus.INVALID_BINDING,
                    bindingRef, null,
                    "system-wide binding does not safely filter the runtime service");
        }
        if (asset.workspaceOwned()
                && !asset.parameters().keySet().containsAll(assetParameterNames(binding))) {
            return new SignalInspection(
                    GuanceEvidenceReadiness.SignalStatus.INVALID_BINDING,
                    bindingRef, null, "required workspace asset parameters are missing");
        }

        Instant observedAt = observations.get(new ObservationKey(
                workspaceId,
                normalizeKey(system),
                normalizeKey(service),
                normalizedSignal));
        return new SignalInspection(
                observedAt == null
                        ? GuanceEvidenceReadiness.SignalStatus.READY_FOR_VALIDATION
                        : GuanceEvidenceReadiness.SignalStatus.CANONICAL_RESULT_OBSERVED,
                bindingRef,
                observedAt,
                observedAt == null
                        ? "authorized canonical binding is ready for validation"
                        : "canonical result observed in this process");
    }

    private boolean hasAnyAuthorizedBinding() {
        boolean deployed = assetBindings().stream()
                .filter(this::hasUniqueAssetScope)
                .filter(this::deploymentScopeIsEffective)
                .anyMatch(asset -> asset.getSignalBindings() != null
                        && asset.getSignalBindings().keySet().stream()
                                .anyMatch(signal -> bindingFor(asset, signal) != null));
        if (deployed) {
            return true;
        }
        Map<String, Set<String>> index = workspaceBindingIndex();
        return CanonicalEvidenceSchema.externallyRoutableSignalKinds().stream()
                .anyMatch(signal -> index.getOrDefault(normalizeKey(signal), Set.of()).stream()
                        .anyMatch(reference -> bindingFor(reference, signal) != null));
    }

    private boolean hasAuthorizedBinding(String signalKind) {
        if (!present(signalKind)) {
            return false;
        }
        return assetBindings().stream()
                .filter(this::hasUniqueAssetScope)
                .filter(this::deploymentScopeIsEffective)
                .anyMatch(asset -> bindingFor(asset, signalKind) != null)
                || hasWorkspaceBinding(signalKind);
    }

    private boolean deploymentScopeIsEffective(EvidenceProperties.AssetBinding asset) {
        try {
            return workspaceAssets.find(
                    asset.getWorkspaceId(), asset.getSystem(), asset.getService()).isEmpty();
        } catch (RuntimeException unavailableRegistry) {
            // A registry outage must remove capability, never restore a broader fallback.
            return false;
        }
    }

    private boolean hasWorkspaceBinding(String signalKind) {
        try {
            return workspaceBindingIndex()
                    .getOrDefault(normalizeKey(signalKind), Set.of())
                    .stream()
                    .anyMatch(reference -> bindingFor(reference, signalKind) != null);
        } catch (RuntimeException unavailableRegistry) {
            return false;
        }
    }

    private Map<String, Set<String>> workspaceBindingIndex() {
        InspectionWarmCache warm = inspectionWarm.get();
        if (warm != null && warm.workspaceBindingIndex != null) {
            return warm.workspaceBindingIndex;
        }
        return workspaceAssets.activeBindingReferencesBySignal();
    }

    private AuthorizedBinding authorizedBinding(
            long workspaceId,
            String system,
            String service,
            String signalKind) {
        List<AssetScope> matches =
                effectiveAssetScopes(workspaceId, system, service, signalKind);
        if (matches.size() != 1) {
            return null;
        }
        AssetScope asset = matches.getFirst();
        if (!asset.enabled() || !PLATFORM.equals(normalizeKey(asset.platform()))) {
            return null;
        }
        EvidenceProperties.Binding binding = bindingFor(asset, signalKind);
        if (binding == null) {
            return null;
        }
        if (SystemObservabilityScopePolicy.isSystemService(asset.service())
                && (!SystemObservabilityScopePolicy.allowsSignal(signalKind)
                        || !SystemObservabilityScopePolicy
                                .safelyFiltersRuntimeService(binding))) {
            return null;
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        if (asset.workspaceOwned()) {
            for (String name : assetParameterNames(binding)) {
                String value = asset.parameters().get(name);
                // Admin-declared and never request-supplied, so this may name an
                // observed object in any script; render() applies the same rule.
                if (!EvidenceParameterValuePolicy.safeLabel(value)) {
                    return null;
                }
                parameters.put(name, value.trim());
            }
        }
        return new AuthorizedBinding(binding, Map.copyOf(parameters));
    }

    private EvidenceProperties.Binding bindingFor(
            EvidenceProperties.AssetBinding asset,
            String signalKind) {
        if (asset == null) {
            return null;
        }
        return bindingFor(new AssetScope(
                asset.getWorkspaceId(), asset.getSystem(), asset.getService(), PLATFORM,
                true, asset.getSignalBindings(), Map.of(), false), signalKind);
    }

    private EvidenceProperties.Binding bindingFor(
            AssetScope asset,
            String signalKind) {
        if (asset == null || !present(signalKind) || asset.signalBindings() == null) {
            return null;
        }
        String wantedSignal = normalizeKey(signalKind);
        List<Map.Entry<String, String>> signalEntries = asset.signalBindings().entrySet().stream()
                .filter(entry -> normalizeKey(entry.getKey()).equals(wantedSignal))
                .toList();
        if (signalEntries.size() != 1
                || !safeReference(signalEntries.getFirst().getValue())) {
            return null;
        }
        return bindingFor(
                asset.workspaceId(), signalEntries.getFirst().getValue(), signalKind);
    }

    private EvidenceProperties.Binding bindingFor(
            String bindingReference,
            String signalKind) {
        return bindingFor(0L, bindingReference, signalKind);
    }

    private EvidenceProperties.Binding bindingFor(
            long workspaceId,
            String bindingReference,
            String signalKind) {
        if (!safeReference(bindingReference)) {
            return null;
        }
        Map<String, EvidenceProperties.Binding> resolved = resolvedBindings(workspaceId);
        if (resolved.isEmpty()) {
            return null;
        }
        String wantedBinding = normalizeKey(bindingReference);
        List<Map.Entry<String, EvidenceProperties.Binding>> bindingEntries =
                resolved.entrySet().stream()
                .filter(entry -> safeReference(entry.getKey())
                        && normalizeKey(entry.getKey()).equals(wantedBinding))
                .toList();
        if (bindingEntries.size() != 1) {
            return null;
        }
        EvidenceProperties.Binding binding = bindingEntries.getFirst().getValue();
        if (present(binding.getSignalKind())
                && !normalizeKey(binding.getSignalKind()).equals(normalizeKey(signalKind))) {
            return null;
        }
        return validBinding(signalKind, binding) ? binding : null;
    }

    private Map<String, EvidenceProperties.Binding> resolvedBindings(long workspaceId) {
        InspectionWarmCache warm = inspectionWarm.get();
        if (warm != null && warm.workspaceId == workspaceId && warm.bindings != null) {
            return warm.bindings;
        }
        return loadResolvedBindings(workspaceId);
    }

    private Map<String, EvidenceProperties.Binding> loadResolvedBindings(long workspaceId) {
        Map<String, EvidenceProperties.Binding> merged = new LinkedHashMap<>();
        Set<String> ambiguous = new LinkedHashSet<>();
        if (config.getBindings() != null) {
            Set<String> seen = new LinkedHashSet<>();
            config.getBindings().forEach((key, value) -> {
                if (!safeReference(key) || value == null) {
                    return;
                }
                String normalized = normalizeKey(key);
                if (!seen.add(normalized)) {
                    ambiguous.add(normalized);
                }
                merged.put(normalized, value);
            });
        }
        if (workspaceId > 0) {
            Set<String> seen = new LinkedHashSet<>();
            workspaceContracts.bindings(workspaceId).forEach((key, value) -> {
                if (!safeReference(key) || value == null) {
                    return;
                }
                String normalized = normalizeKey(key);
                if (!seen.add(normalized)) {
                    ambiguous.add(normalized);
                }
                merged.put(normalized, value);
            });
        }
        ambiguous.forEach(merged::remove);
        return merged;
    }

    private boolean hasUniqueAssetScope(EvidenceProperties.AssetBinding candidate) {
        if (candidate == null
                || candidate.getWorkspaceId() <= 0
                || !present(candidate.getSystem())
                || !present(candidate.getService())) {
            return false;
        }
        long matches = assetBindings().stream()
                .filter(asset -> asset != null
                        && asset.getWorkspaceId() == candidate.getWorkspaceId()
                        && normalizeKey(asset.getSystem()).equals(normalizeKey(candidate.getSystem()))
                        && normalizeKey(asset.getService()).equals(normalizeKey(candidate.getService())))
                .count();
        return matches == 1;
    }

    private List<EvidenceProperties.AssetBinding> assetBindings() {
        List<EvidenceProperties.AssetBinding> configured = config.getAssetBindings();
        return configured == null ? List.of() : configured;
    }

    private List<AssetScope> exactAssetScopes(
            long workspaceId,
            String system,
            String service) {
        Optional<WorkspaceObservabilityAsset> declared =
                workspaceAssets.find(workspaceId, system, service);
        if (declared.isPresent()) {
            WorkspaceObservabilityAsset asset = declared.get();
            return List.of(new AssetScope(
                    asset.workspaceId(), asset.system(), asset.service(), asset.platform(),
                    asset.enabled(), asset.signalBindings(), asset.parameters(), true));
        }
        return assetBindings().stream()
                .filter(asset -> asset != null
                        && asset.getWorkspaceId() == workspaceId
                        && normalizeKey(asset.getSystem()).equals(normalizeKey(system))
                        && normalizeKey(asset.getService()).equals(normalizeKey(service)))
                .map(asset -> new AssetScope(
                        asset.getWorkspaceId(), asset.getSystem(), asset.getService(), PLATFORM,
                        true, asset.getSignalBindings(), Map.of(), false))
                .toList();
    }

    /**
     * A module asset is an optional override. A disabled exact asset is an
     * explicit stop; an enabled exact binding wins for that signal; otherwise
     * the system-wide generic binding may serve the runtime service.
     */
    private List<AssetScope> effectiveAssetScopes(
            long workspaceId,
            String system,
            String service,
            String signalKind) {
        Optional<WorkspaceObservabilityAsset> exact =
                workspaceAssets.find(workspaceId, system, service);
        if (exact.isPresent()) {
            WorkspaceObservabilityAsset asset = exact.orElseThrow();
            if (!asset.enabled()
                    || SystemObservabilityScopePolicy.hasSignal(asset, signalKind)) {
                return List.of(workspaceScope(asset));
            }
        }
        if (SystemObservabilityScopePolicy.allowsSignal(signalKind)) {
            Optional<WorkspaceObservabilityAsset> systemAsset =
                    workspaceAssets.findSystem(workspaceId, system);
            if (systemAsset.isPresent()) {
                WorkspaceObservabilityAsset asset = systemAsset.orElseThrow();
                return List.of(workspaceScope(asset));
            }
        }
        if (exact.isPresent()) {
            return List.of(workspaceScope(exact.orElseThrow()));
        }
        return exactAssetScopes(workspaceId, system, service);
    }

    private AssetScope workspaceScope(WorkspaceObservabilityAsset asset) {
        return new AssetScope(
                asset.workspaceId(), asset.system(), asset.service(), asset.platform(),
                asset.enabled(), asset.signalBindings(), asset.parameters(), true);
    }

    private boolean validBinding(String signalKind, EvidenceProperties.Binding binding) {
        List<String> queryTemplates = configuredQueryTemplates(binding);
        if (!CanonicalEvidenceSchema.isExternallyRoutable(signalKind)
                || binding == null
                || queryTemplates.isEmpty()
                || queryTemplates.size() > MAX_COMPONENT_QUERIES
                || (CanonicalEvidenceSchema.isRowSet(signalKind)
                        && queryTemplates.size() != 1)
                || binding.getMaxRows() < 1
                || binding.getMaxRows() > MAX_BOUND_ROWS
                || !validAssetParameters(binding, queryTemplates)
                || !validQueryOptions(binding.getQueryOptions())) {
            return false;
        }
        Set<String> canonicalFields = CanonicalEvidenceSchema.fields(signalKind);
        Map<String, String> aliases = binding.getFieldAliases();
        Map<String, String> constants = binding.getConstantFields();
        boolean validAliases = aliases == null || aliases.entrySet().stream().allMatch(entry ->
                present(entry.getKey())
                        && present(entry.getValue())
                        && canonicalFields.contains(entry.getValue()));
        boolean validConstants = constants == null || constants.entrySet().stream().allMatch(entry ->
                canonicalFields.contains(entry.getKey())
                        && safeReference(entry.getValue()));
        return validAliases && validConstants;
    }

    private boolean validAssetParameters(
            EvidenceProperties.Binding binding,
            List<String> queryTemplates) {
        List<String> rawNames = binding.getAssetParameters() == null
                ? List.of() : binding.getAssetParameters();
        List<String> names = rawNames.stream()
                .filter(this::present)
                .map(this::normalizeKey)
                .toList();
        if (names.size() != rawNames.size() || Set.copyOf(names).size() != names.size()) {
            return false;
        }
        if (!EvidenceTemplateParameterPolicy.usesCanonicalLowercaseNames(queryTemplates)) {
            return false;
        }
        Set<String> placeholders = EvidenceTemplateParameterPolicy.placeholders(queryTemplates);
        return names.stream().allMatch(name -> safeReference(name)
                && placeholders.contains(name)
                && !EvidenceTemplateParameterPolicy.runtimeOwned(name));
    }

    private Set<String> assetParameterNames(EvidenceProperties.Binding binding) {
        if (binding == null || binding.getAssetParameters() == null) {
            return Set.of();
        }
        return binding.getAssetParameters().stream()
                .filter(this::present)
                .map(this::normalizeKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private String requestBody(
            List<String> queries,
            WindowRange window,
            EvidenceProperties.Binding binding,
            String signalKind) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        var queryItems = root.putArray("queries");
        for (String query : queries) {
            ObjectNode querySpec = objectMapper.createObjectNode();
            querySpec.put("q", query);
            addQueryOptions(querySpec, binding.getQueryOptions(), window, signalKind);
            querySpec.putArray("timeRange")
                    .add(window.start().toEpochMilli())
                    .add(window.end().toEpochMilli());
            querySpec.put("limit", binding.getMaxRows() + 1);
            ObjectNode item = objectMapper.createObjectNode();
            item.put("qtype", "dql");
            item.set("query", querySpec);
            queryItems.add(item);
        }
        return objectMapper.writeValueAsString(root);
    }

    private List<String> configuredQueryTemplates(EvidenceProperties.Binding binding) {
        if (binding == null) {
            return List.of();
        }
        boolean hasSingle = present(binding.getQueryTemplate());
        List<String> compound = binding.getQueryTemplates() == null
                ? List.of()
                : binding.getQueryTemplates().stream()
                        .filter(this::present)
                        .map(String::trim)
                        .toList();
        // A binding has exactly one query source; mixed modes are ambiguous and fail closed.
        if (hasSingle == !compound.isEmpty()) {
            return List.of();
        }
        return hasSingle ? List.of(binding.getQueryTemplate().trim()) : compound;
    }

    private void addQueryOptions(
            ObjectNode querySpec,
            EvidenceProperties.QueryOptions options,
            WindowRange window,
            String signalKind) {
        if (options == null) {
            return;
        }
        querySpec.putArray("_funcList");
        querySpec.putArray("funcList");
        querySpec.put("maxPointCount", options.getMaxPointCount());
        querySpec.put("interval", scalarInterval(options, window, signalKind));
        querySpec.put("align_time", options.isAlignTime());
        querySpec.putArray("sorder_by");
        querySpec.put("slimit", options.getSeriesLimit());
        querySpec.put("disable_sampling", options.isDisableSampling());
        querySpec.put("tz", options.getTimeZone().trim());
    }

    /**
     * Aggregation interval for one query, in seconds.
     *
     * <p>Guance's {@code limit} bounds how many aggregation buckets the query
     * scans, not how many rows it returns. A scalar contract sends
     * {@code limit = maxRows + 1} to detect an over-budget result, so a fixed
     * short interval silently narrows the query to the last few buckets: at
     * {@code interval=10} with {@code limit=2} the request only ever looks at
     * the last 20 seconds. Any probe reporting less often than that reads empty
     * most of the time — measured against the live deployment, a 30s dial task
     * returned a row in 1 of 6 attempts, and the contract then withheld the
     * evidence as incomplete rather than reporting a source problem.
     *
     * <p>A scalar contract asks for the latest value <em>in the window</em>, so
     * the window is the bucket. Row-set signals keep the configured interval:
     * they need the individual points, not one aggregate.
     */
    private long scalarInterval(
            EvidenceProperties.QueryOptions options,
            WindowRange window,
            String signalKind) {
        if (CanonicalEvidenceSchema.isRowSet(signalKind)) {
            return options.getInterval();
        }
        long span = Duration.between(window.start(), window.end()).toSeconds();
        return Math.max(1L, span);
    }

    private boolean validQueryOptions(EvidenceProperties.QueryOptions options) {
        if (options == null) {
            return true;
        }
        if (options.getMaxPointCount() < 1
                || options.getMaxPointCount() > MAX_POINT_COUNT
                || options.getInterval() < 1
                || options.getInterval() > MAX_INTERVAL_SECONDS
                || options.getSeriesLimit() < 1
                || options.getSeriesLimit() > MAX_BOUND_ROWS
                || !present(options.getTimeZone())) {
            return false;
        }
        try {
            ZoneId.of(options.getTimeZone().trim());
            return true;
        } catch (RuntimeException invalidTimeZone) {
            return false;
        }
    }

    private String render(
            String template,
            EvidenceRequest request,
            IncidentContext incident,
            WindowRange window,
            Map<String, String> assetParameters) {
        Map<String, Object> values = new LinkedHashMap<>(request.target());
        for (Map.Entry<String, String> entry : assetParameters.entrySet()) {
            Object requested = values.get(entry.getKey());
            if (requested != null
                    && !entry.getValue().equals(String.valueOf(requested).trim())) {
                throw new IllegalArgumentException(
                        "request cannot override workspace asset parameter: " + entry.getKey());
            }
            values.put(entry.getKey(), entry.getValue());
        }
        values.put("incident_id", incident.incidentId());
        values.put("system", incident.system());
        values.put("service", incident.service());
        values.put("error_code", incident.errorCode());
        values.put("trace_id", incident.traceId());
        values.put("window", window.expression());
        values.put("window_span", window.span());

        // Omit {{?name}}...{{/name}} blocks when the value is absent/blank so
        // reviewed contracts can still query with only runtime/asset context.
        String expanded = EvidenceTemplateParameterPolicy.applyOptionalSections(
                template,
                key -> {
                    Object raw = values.get(key);
                    return raw != null && !String.valueOf(raw).trim().isEmpty();
                });

        Matcher matcher = EvidenceTemplateParameterPolicy.matcher(expanded);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String key = normalizeKey(matcher.group(1));
            Object raw = values.get(key);
            if (raw == null || String.valueOf(raw).trim().isEmpty()) {
                throw new IllegalArgumentException("missing query template value: " + key);
            }
            String value = String.valueOf(raw).trim();
            if ("window".equals(key)) {
                if (!WINDOW.matcher(value).matches()) {
                    throw new IllegalArgumentException("unsafe window value");
                }
            } else if ("window_span".equals(key)) {
                if (!WINDOW.matcher(value).matches() || value.startsWith("-")) {
                    throw new IllegalArgumentException("unsafe window span value");
                }
            } else if (assetParameters.containsKey(key)) {
                // Asset-owned values are declared by a workspace admin, never by
                // the browser or the model, so they may carry a human-authored
                // name such as a Chinese CloudDial task.
                if (!EvidenceParameterValuePolicy.safeLabel(value)) {
                    throw new IllegalArgumentException("unsafe query template value: " + key);
                }
            } else if (!SAFE_VALUE.matcher(value).matches()) {
                throw new IllegalArgumentException("unsafe query template value: " + key);
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(rendered);
        if (EvidenceTemplateParameterPolicy.matcher(rendered.toString()).find()) {
            throw new IllegalArgumentException("unresolved query template placeholder");
        }
        return rendered.toString();
    }

    private WindowRange window(String expression, Instant occurredAt) {
        String normalized = expression == null || expression.isBlank() ? "-15m" : expression.trim();
        Matcher matcher = WINDOW.matcher(normalized);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("unsupported evidence window: " + normalized);
        }
        long amount = Long.parseLong(matcher.group(1));
        Duration duration = switch (matcher.group(2)) {
            case "s" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            case "d" -> Duration.ofDays(amount);
            default -> throw new IllegalArgumentException("unsupported evidence window unit");
        };
        Instant end = occurredAt == null ? Instant.now(clock) : occurredAt;
        String directed = normalized.startsWith("-") ? normalized : "-" + normalized;
        return new WindowRange(directed, directed.substring(1), end.minus(duration), end);
    }

    private Map<String, Object> normalize(
            String responseBody,
            EvidenceProperties.Binding binding,
            EvidenceRequest request,
            String serviceFallback) throws Exception {
        String signalKind = request.signalKind();
        JsonNode root = objectMapper.readTree(responseBody);
        if (root.path("code").asInt(-1) != 200 || !root.path("success").asBoolean(false)) {
            throw new GuanceResponseException("business contract rejected");
        }

        List<List<JsonNode>> populatedDatasets = new ArrayList<>();
        JsonNode data = root.path("content").path("data");
        if (!data.isArray()) {
            throw new GuanceResponseException("data array missing");
        }
        if ("cti_failure_pattern_scan".equals(normalizeKey(signalKind))) {
            return normalizeCtiFailurePatternSets(data, binding, signalKind);
        }
        for (JsonNode dataset : data) {
            List<JsonNode> populatedSeries = new ArrayList<>();
            JsonNode series = dataset.path("series");
            if (series.isArray()) {
                for (JsonNode item : series) {
                    if (hasRows(item)) {
                        populatedSeries.add(item);
                    }
                }
            }
            populatedDatasets.add(List.copyOf(populatedSeries));
        }
        int expectedDatasets = configuredQueryTemplates(binding).size();
        if (populatedDatasets.size() != expectedDatasets
                || populatedDatasets.stream().anyMatch(List::isEmpty)) {
            return withhold(signalKind, "query_dataset_contract_incomplete");
        }
        if (CanonicalEvidenceSchema.isRowSet(signalKind)) {
            List<JsonNode> populatedSeries = populatedDatasets.getFirst();
            if (populatedSeries.size() != 1) {
                return withhold(signalKind, "trace_requires_single_record_series");
            }
            return normalizeRowSet(
                    populatedSeries.getFirst(), binding, signalKind,
                    targetValue(request, "ps_id"), serviceFallback);
        }
        if (populatedDatasets.size() == 1) {
            return normalizeScalar(populatedDatasets.getFirst(), binding, signalKind);
        }
        return normalizeCompoundScalar(populatedDatasets, binding, signalKind);
    }

    /**
     * Intersects bounded correlation-id sets in memory and returns counts only.
     * The identifiers never leave this method, enter an EvidenceResult, or reach
     * logs/audit/UI. This is deliberately CTI-specific until another scenario
     * proves a reusable pattern vocabulary.
     */
    private Map<String, Object> normalizeCtiFailurePatternSets(
            JsonNode data,
            EvidenceProperties.Binding binding,
            String signalKind) {
        if (data.size() != 3) {
            return withhold(signalKind, "cti_pattern_dataset_count_invalid");
        }
        List<Set<String>> correlationSets = new ArrayList<>(3);
        for (JsonNode dataset : data) {
            Set<String> ids = boundedCorrelationIds(dataset, binding.getMaxRows());
            if (ids == null) {
                return withhold(signalKind, "cti_pattern_correlation_set_invalid");
            }
            correlationSets.add(ids);
        }

        Set<String> failed = correlationSets.get(0);
        Set<String> missingRequiredCode = intersection(failed, correlationSets.get(1));
        Set<String> downstreamRecordNotFound = intersection(failed, correlationSets.get(2));
        Set<String> classified = new LinkedHashSet<>(missingRequiredCode);
        classified.addAll(downstreamRecordNotFound);

        Map<String, Object> observed = Map.of(
                "failure_request_count", failed.size(),
                "classified_failure_request_count", classified.size(),
                "missing_required_code_request_count", missingRequiredCode.size(),
                "downstream_record_not_found_request_count",
                downstreamRecordNotFound.size());
        return CanonicalEvidenceSchema.isValid(signalKind, observed)
                ? observed
                : withhold(signalKind, "cti_pattern_count_contract_invalid");
    }

    private Set<String> boundedCorrelationIds(JsonNode dataset, int maxRows) {
        JsonNode series = dataset.path("series");
        if (!series.isArray()) {
            return null;
        }
        Set<String> ids = new LinkedHashSet<>();
        int rowCount = 0;
        for (JsonNode item : series) {
            JsonNode columns = item.path("columns");
            JsonNode values = item.path("values");
            if (!columns.isArray() || !values.isArray()) {
                return null;
            }
            int traceIndex = correlationIdColumn(columns);
            if (traceIndex < 0) {
                return null;
            }
            for (JsonNode row : values) {
                rowCount++;
                if (rowCount > maxRows || !row.isArray() || row.size() <= traceIndex) {
                    return null;
                }
                JsonNode value = row.get(traceIndex);
                String id = value == null || value.isNull() ? "" : value.asText("").trim();
                if (!TroubleshootingEvidenceSanitizer.isSafeEvidenceId(id)) {
                    return null;
                }
                ids.add(id);
            }
        }
        return Set.copyOf(ids);
    }

    private int correlationIdColumn(JsonNode columns) {
        int index = -1;
        for (int candidate = 0; candidate < columns.size(); candidate++) {
            String normalized = columns.get(candidate).asText("")
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9]", "");
            if (!normalized.endsWith("traceid")) {
                continue;
            }
            if (index >= 0) {
                return -1;
            }
            index = candidate;
        }
        return index;
    }

    private Set<String> intersection(Set<String> left, Set<String> right) {
        Set<String> result = new LinkedHashSet<>(left);
        result.retainAll(right);
        return Set.copyOf(result);
    }

    private Map<String, Object> normalizeScalar(
            List<JsonNode> populatedSeries,
            EvidenceProperties.Binding binding,
            String signalKind) {
        Map<String, Object> observed = normalizeScalarFragment(
                populatedSeries, binding, signalKind);
        observed = withConstantFields(observed, binding, signalKind);
        if (CanonicalEvidenceSchema.isValid(signalKind, observed)) {
            return observed;
        }
        log.debug("Guance scalar shape rejected for signal {}: sourceColumns={}, canonicalTypes={}",
                normalizeKey(signalKind),
                populatedSeries.stream()
                        .map(series -> {
                            JsonNode columns = series.path("columns");
                            if (!columns.isArray()) {
                                return List.of();
                            }
                            List<String> names = new ArrayList<>();
                            columns.forEach(column -> names.add(column.asText()));
                            return List.copyOf(names);
                        })
                        .toList(),
                observed.entrySet().stream()
                        .map(entry -> entry.getKey() + "="
                                + entry.getValue().getClass().getSimpleName())
                        .sorted()
                        .toList());
        return withhold(signalKind, "scalar_contract_invalid");
    }

    private Map<String, Object> normalizeCompoundScalar(
            List<List<JsonNode>> populatedDatasets,
            EvidenceProperties.Binding binding,
            String signalKind) {
        Map<String, Object> merged = new LinkedHashMap<>();
        for (List<JsonNode> dataset : populatedDatasets) {
            Map<String, Object> fragment = normalizeScalarFragment(
                    dataset, binding, signalKind);
            if (fragment.isEmpty() || !mergeDistinct(merged, fragment)) {
                return withhold(signalKind, "compound_scalar_fragment_invalid");
            }
        }
        Map<String, Object> observed = withConstantFields(merged, binding, signalKind);
        return CanonicalEvidenceSchema.isValid(signalKind, observed)
                ? observed
                : withhold(signalKind, "compound_scalar_contract_invalid");
    }

    private Map<String, Object> normalizeScalarFragment(
            List<JsonNode> populatedSeries,
            EvidenceProperties.Binding binding,
            String signalKind) {
        if (populatedSeries.size() == 1) {
            JsonNode series = populatedSeries.getFirst();
            if (series.path("values").size() > binding.getMaxRows()) {
                return withhold(signalKind, "scalar_row_budget_exceeded");
            }
            return latestCanonicalRow(series, binding, signalKind);
        }

        BigDecimal observationTime = null;
        Map<String, Object> merged = new LinkedHashMap<>();
        for (JsonNode series : populatedSeries) {
            JsonNode columns = series.path("columns");
            JsonNode values = series.path("values");
            if (!columns.isArray()
                    || !values.isArray()
                    || values.isEmpty()
                    || values.size() > binding.getMaxRows()) {
                return withhold(signalKind, "scalar_series_shape_invalid");
            }
            JsonNode row = latestRow(columns, values);
            BigDecimal timestamp = timestamp(columns, row);
            if (timestamp == null) {
                return withhold(signalKind, "scalar_timestamp_missing");
            }
            if (observationTime != null
                    && observationTime.compareTo(timestamp) != 0) {
                return withhold(signalKind, "scalar_timestamps_misaligned");
            }
            observationTime = timestamp;

            Map<String, Object> fragment = canonicalRow(
                    columns, row, binding, signalKind);
            if (fragment.isEmpty()) {
                return withhold(signalKind, "scalar_canonical_fragment_empty");
            }
            if (!mergeDistinct(merged, fragment)) {
                return withhold(signalKind, "scalar_canonical_field_duplicate");
            }
        }
        return Map.copyOf(merged);
    }

    private boolean hasRows(JsonNode series) {
        JsonNode values = series.path("values");
        return values.isArray() && !values.isEmpty();
    }

    private Map<String, Object> withhold(String signalKind, String reason) {
        log.debug("Guance canonical response withheld for signal {} ({})",
                normalizeKey(signalKind), reason);
        return Map.of();
    }

    private Map<String, Object> latestCanonicalRow(
            JsonNode series,
            EvidenceProperties.Binding binding,
            String signalKind) {
        JsonNode columns = series.path("columns");
        JsonNode values = series.path("values");
        if (!columns.isArray() || !values.isArray() || values.isEmpty()) {
            return Map.of();
        }
        JsonNode row = latestRow(columns, values);
        if (row == null || !row.isArray()) {
            return Map.of();
        }

        return canonicalRow(columns, row, binding, signalKind);
    }

    private Map<String, Object> normalizeRowSet(
            JsonNode series,
            EvidenceProperties.Binding binding,
            String signalKind,
            String expectedPsId,
            String serviceFallback) {
        JsonNode columns = series.path("columns");
        JsonNode values = series.path("values");
        if (!columns.isArray()
                || !values.isArray()
                || values.isEmpty()
                || values.size() > binding.getMaxRows()) {
            return Map.of();
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode row : values) {
            if (!row.isArray()) {
                return Map.of();
            }
            Map<String, Object> canonical = canonicalRow(columns, row, binding, signalKind);
            rows.add(canonical);
        }
        return normalizeCanonicalRows(rows, binding, signalKind, expectedPsId, serviceFallback);
    }

    private Map<String, Object> normalizeCanonicalRows(
            List<Map<String, Object>> rows,
            EvidenceProperties.Binding binding,
            String signalKind,
            String expectedPsId,
            String serviceFallback) {
        String psId = null;
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Map<String, Object> sourceRow : rows) {
            Map<String, Object> canonical = withConstantFields(
                    sourceRow, binding, signalKind);
            if (present(serviceFallback)
                    && CanonicalEvidenceSchema.fields(signalKind).contains("service")) {
                Map<String, Object> withService = new LinkedHashMap<>(canonical);
                withService.putIfAbsent("service", serviceFallback.trim());
                canonical = Map.copyOf(withService);
            }
            if (!CanonicalEvidenceSchema.isValidRow(signalKind, canonical)) {
                log.debug("Guance canonical trace row shape rejected: {}",
                        canonical.entrySet().stream()
                                .map(entry -> entry.getKey() + "="
                                        + entry.getValue().getClass().getSimpleName())
                                .sorted()
                                .toList());
                return withhold(signalKind, "trace_canonical_row_invalid");
            }
            String rowPsId = String.valueOf(canonical.get("ps_id"));
            if (psId != null && !psId.equals(rowPsId)) {
                return withhold(signalKind, "trace_rows_contain_different_ps_ids");
            }
            psId = rowPsId;
            Map<String, Object> entry = new LinkedHashMap<>(canonical);
            entry.remove("ps_id");
            entries.add(entry);
        }
        if (!present(expectedPsId)) {
            return withhold(signalKind, "trace_expected_ps_id_missing");
        }
        if (!expectedPsId.equals(psId)) {
            return withhold(signalKind, "trace_ps_id_differs_from_search");
        }
        entries.sort(Comparator
                .comparing((Map<String, Object> entry) -> number(entry.get("timestamp")))
                .thenComparing(entry -> String.valueOf(entry.get("service")))
                .thenComparing(entry -> String.valueOf(entry.get("message"))));

        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("ps_id", psId);
        observed.put("entries", List.copyOf(entries));
        return CanonicalEvidenceSchema.isValid(signalKind, observed)
                ? observed
                : withhold(signalKind, "trace_bundle_invalid");
    }

    private boolean mergeDistinct(
            Map<String, Object> target,
            Map<String, Object> fragment) {
        for (Map.Entry<String, Object> entry : fragment.entrySet()) {
            if (target.putIfAbsent(entry.getKey(), entry.getValue()) != null) {
                return false;
            }
        }
        return true;
    }

    private Map<String, Object> withConstantFields(
            Map<String, Object> source,
            EvidenceProperties.Binding binding,
            String signalKind) {
        Map<String, Object> merged = new LinkedHashMap<>(source);
        Map<String, String> constants = binding.getConstantFields() == null
                ? Map.of()
                : binding.getConstantFields();
        for (Map.Entry<String, String> entry : constants.entrySet()) {
            if (!CanonicalEvidenceSchema.fields(signalKind).contains(entry.getKey())
                    || !safeReference(entry.getValue())
                    || merged.putIfAbsent(entry.getKey(), entry.getValue().trim()) != null) {
                return Map.of();
            }
        }
        return Map.copyOf(merged);
    }

    private BigDecimal timestamp(JsonNode columns, JsonNode row) {
        if (!columns.isArray() || row == null || !row.isArray()) {
            return null;
        }
        for (int index = 0; index < columns.size(); index++) {
            if (!"time".equalsIgnoreCase(columns.get(index).asText())
                    || row.size() <= index
                    || !row.get(index).isNumber()) {
                continue;
            }
            return row.get(index).decimalValue().stripTrailingZeros();
        }
        return null;
    }

    private Map<String, Object> canonicalRow(
            JsonNode columns,
            JsonNode row,
            EvidenceProperties.Binding binding,
            String signalKind) {

        Map<String, Object> observed = new LinkedHashMap<>();
        Map<String, String> aliases = binding.getFieldAliases() == null
                ? Map.of()
                : binding.getFieldAliases();
        Set<String> canonicalFields = CanonicalEvidenceSchema.fields(signalKind);
        for (int index = 0; index < columns.size() && index < row.size(); index++) {
            String sourceField = columns.get(index).asText();
            JsonNode rawValue = row.get(index);
            if (rawValue.isNull()) {
                continue;
            }
            List<Map.Entry<String, String>> nestedAliases = aliases.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(sourceField + "@"))
                    .toList();
            String canonicalField = aliases.get(sourceField);
            if (present(canonicalField)) {
                if (!putCanonical(observed, canonicalFields, canonicalField, rawValue)) {
                    return Map.of();
                }
            } else if (nestedAliases.isEmpty() && canonicalFields.contains(sourceField)) {
                if (!putCanonical(observed, canonicalFields, sourceField, rawValue)) {
                    return Map.of();
                }
            }
            if (!nestedAliases.isEmpty()
                    && !putJsonCanonicalFields(
                            observed, canonicalFields, sourceField, rawValue, nestedAliases)) {
                return Map.of();
            }
        }
        return observed;
    }

    private boolean putCanonical(
            Map<String, Object> target,
            Set<String> canonicalFields,
            String canonicalField,
            JsonNode rawValue) {
        if (!canonicalFields.contains(canonicalField)) {
            return false;
        }
        Object value = objectMapper.convertValue(rawValue, Object.class);
        return target.putIfAbsent(canonicalField, value) == null;
    }

    private boolean putJsonCanonicalFields(
            Map<String, Object> target,
            Set<String> canonicalFields,
            String sourceField,
            JsonNode rawValue,
            List<Map.Entry<String, String>> nestedAliases) {
        if (!rawValue.isTextual()) {
            return false;
        }
        JsonNode document;
        try {
            document = objectMapper.readTree(rawValue.textValue());
        } catch (Exception malformedDocument) {
            return false;
        }
        if (!document.isObject()) {
            return false;
        }
        String prefix = sourceField + "@";
        for (Map.Entry<String, String> alias : nestedAliases) {
            String jsonField = alias.getKey().substring(prefix.length());
            JsonNode value = document.get(jsonField);
            if (!present(jsonField)
                    || jsonField.indexOf('.') >= 0
                    || value == null
                    || value.isNull()
                    || !putCanonical(target, canonicalFields, alias.getValue(), value)) {
                return false;
            }
        }
        return true;
    }

    private BigDecimal number(Object value) {
        return new BigDecimal(String.valueOf(value));
    }

    private String targetValue(EvidenceRequest request, String key) {
        Object value = request.target().get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    private JsonNode latestRow(JsonNode columns, JsonNode values) {
        if (values.size() == 1) {
            return values.get(0);
        }
        int timeIndex = -1;
        for (int index = 0; index < columns.size(); index++) {
            if ("time".equalsIgnoreCase(columns.get(index).asText())) {
                timeIndex = index;
                break;
            }
        }
        if (timeIndex < 0) {
            return null;
        }

        JsonNode latest = null;
        BigDecimal latestTimestamp = null;
        int latestCount = 0;
        for (JsonNode candidate : values) {
            if (!candidate.isArray() || candidate.size() <= timeIndex) {
                return null;
            }
            JsonNode rawTimestamp = candidate.get(timeIndex);
            if (rawTimestamp == null || !rawTimestamp.isNumber()) {
                return null;
            }
            BigDecimal timestamp = rawTimestamp.decimalValue();
            if (latestTimestamp == null || timestamp.compareTo(latestTimestamp) > 0) {
                latestTimestamp = timestamp;
                latest = candidate;
                latestCount = 1;
            } else if (timestamp.compareTo(latestTimestamp) == 0) {
                latestCount++;
            }
        }
        return latestCount == 1 ? latest : null;
    }

    /**
     * Build the query endpoint.
     *
     * <p>The host half comes from {@code settings} because a workspace may own
     * it; the path stays deployment-owned, since it names the Guance API
     * contract this adapter was written against rather than a tenant choice.
     */
    private URI queryUri(EffectiveEvidenceSettings settings) {
        String base = settings.guanceBaseUrl().trim().replaceAll("/+$", "");
        String path = present(config.getQueryPath())
                ? config.getQueryPath().trim()
                : "/api/v1/df/query_data_v1";
        URI uri = URI.create(base + (path.startsWith("/") ? path : "/" + path));
        if (!"https".equalsIgnoreCase(uri.getScheme())
                && !(settings.guanceAllowInsecureHttp() && "http".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException(
                    "Guance base URL must use HTTPS unless insecure HTTP is explicitly allowed");
        }
        return uri;
    }

    private Duration timeout() {
        Duration configured = config.getTimeout();
        return configured == null || configured.isZero() || configured.isNegative()
                ? Duration.ofSeconds(5)
                : configured;
    }

    private Duration boundedTimeout(Duration callerTimeout) {
        Duration configured = timeout();
        return callerTimeout.compareTo(configured) < 0 ? callerTimeout : configured;
    }

    private EvidenceResult missing(EvidenceRequest request, String summary) {
        return new EvidenceResult(
                request.requestId(), "UNKNOWN", "", EvidenceStatus.MISSING, summary,
                Map.of(), "guance:unavailable", Instant.now(clock));
    }

    private EvidenceResult missingCanonical(EvidenceRequest request) {
        return new EvidenceResult(
                request.requestId(), "UNKNOWN", "", EvidenceStatus.MISSING,
                "Guance returned no canonical evidence rows",
                Map.of(), "guance:no_canonical_evidence", Instant.now(clock));
    }

    private String namespace(EvidenceProperties.Binding binding) {
        return present(binding.getNamespace()) ? binding.getNamespace().trim() : "UNKNOWN";
    }

    private String summary(EvidenceProperties.Binding binding, EvidenceRequest request) {
        return present(binding.getSummary()) ? binding.getSummary().trim() : request.purpose();
    }

    private boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static final class GuanceResponseException extends RuntimeException {
        private GuanceResponseException(String message) {
            super(message);
        }
    }

    private boolean safeReference(String value) {
        if (!present(value)) {
            return false;
        }
        String normalized = value.trim();
        return SAFE_VALUE.matcher(normalized).matches()
                && TroubleshootingSecretRedactor.redact(normalized).equals(normalized);
    }

    private String normalizeKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private record WindowRange(String expression, String span, Instant start, Instant end) {
    }

    private record AuthorizedBinding(
            EvidenceProperties.Binding binding,
            Map<String, String> parameters) {
    }

    private record VerifiedAuthority(
            GuanceBindingFingerprintService verifier,
            GuanceBindingFingerprintService.Snapshot snapshot) {
    }

    private record AssetScope(
            long workspaceId,
            String system,
            String service,
            String platform,
            boolean enabled,
            Map<String, String> signalBindings,
            Map<String, String> parameters,
            boolean workspaceOwned) {

        private AssetScope {
            signalBindings = Map.copyOf(
                    signalBindings == null ? Map.of() : signalBindings);
            parameters = Map.copyOf(parameters == null ? Map.of() : parameters);
        }
    }

    record SignalInspection(
            GuanceEvidenceReadiness.SignalStatus status,
            String bindingRef,
            Instant lastObservedAt,
            String detail) {
    }

    private record ObservationKey(
            long workspaceId,
            String system,
            String service,
            String signalKind) {
    }
}
