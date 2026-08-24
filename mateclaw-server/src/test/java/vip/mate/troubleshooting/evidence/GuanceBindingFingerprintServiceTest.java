package vip.mate.troubleshooting.evidence;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GuanceBindingFingerprintServiceTest {

    @Test
    void fingerprintsTheExactRoutesQueriesFieldsAndCredentialIdentityWithoutLeakingSecrets() {
        EvidenceProperties properties = properties();
        GuanceBindingFingerprintService service =
                new GuanceBindingFingerprintService(properties);

        GuanceBindingFingerprintService.Snapshot first =
                service.current(7L, "CSDP", "session-svc").orElseThrow();
        properties.getGuance().setApiKey("rotated-runtime-secret");
        GuanceBindingFingerprintService.Snapshot afterCredentialRotation =
                service.current(7L, "csdp", "SESSION-SVC").orElseThrow();
        properties.getGuance().getBindings().get("search-binding")
                .getQueryOptions().setInterval(11);
        GuanceBindingFingerprintService.Snapshot afterQueryOptionsChange =
                service.current(7L, "CSDP", "session-svc").orElseThrow();
        properties.getGuance().getBindings().get("search-binding")
                .setQueryTemplate("L::other_measurement:(message=@search)");
        GuanceBindingFingerprintService.Snapshot afterQueryChange =
                service.current(7L, "CSDP", "session-svc").orElseThrow();
        properties.getGuance().getBindings().get("contrast-binding")
                .setConstantFields(Map.of(
                        "discriminating_feature", "message_length_eq_3000"));
        GuanceBindingFingerprintService.Snapshot afterConstantChange =
                service.current(7L, "CSDP", "session-svc").orElseThrow();

        assertThat(first.scopeKey()).matches("[a-f0-9]{64}");
        assertThat(first.bindingFingerprint()).matches("[a-f0-9]{64}");
        assertThat(afterCredentialRotation.scopeKey()).isEqualTo(first.scopeKey());
        assertThat(afterCredentialRotation.bindingFingerprint())
                .isNotEqualTo(first.bindingFingerprint());
        assertThat(afterQueryOptionsChange.bindingFingerprint())
                .isNotEqualTo(first.bindingFingerprint());
        assertThat(afterQueryChange.bindingFingerprint())
                .isNotEqualTo(afterQueryOptionsChange.bindingFingerprint());
        assertThat(afterConstantChange.bindingFingerprint())
                .isNotEqualTo(afterQueryChange.bindingFingerprint());
        assertThat(first.toString())
                .doesNotContain("runtime-secret", "L::logs", "message=@search");
    }

    @Test
    void settingsOriginChangesAuthorityEvenWhenEndpointAndCredentialAreIdentical() {
        EvidenceProperties properties = properties();
        EffectiveEvidenceSettings workspace = EffectiveEvidenceSettings.resolved(
                true,
                properties.getGuance().getBaseUrl(),
                properties.getGuance().getApiKey(),
                properties.getGuance().isAllowInsecureHttp(),
                false,
                false,
                EffectiveEvidenceSettings.Origin.WORKSPACE);
        WorkspaceEvidenceSettingsService settings =
                org.mockito.Mockito.mock(WorkspaceEvidenceSettingsService.class);
        org.mockito.Mockito.when(settings.effective(7L)).thenReturn(workspace);

        GuanceBindingFingerprintService.Snapshot deployment =
                new GuanceBindingFingerprintService(properties)
                        .current(7L, "CSDP", "session-svc").orElseThrow();
        GuanceBindingFingerprintService.Snapshot workspaceOwned =
                new GuanceBindingFingerprintService(
                        properties,
                        WorkspaceObservabilityAssets.NONE,
                        WorkspaceEvidenceContracts.NONE,
                        WorkspaceEvidenceRoutes.NONE,
                        settings)
                        .current(7L, "CSDP", "session-svc").orElseThrow();

        assertThat(workspaceOwned.bindingFingerprint())
                .isNotEqualTo(deployment.bindingFingerprint());
        assertThat(workspaceOwned.settingsFingerprint())
                .isNotEqualTo(deployment.settingsFingerprint());
        assertThat(workspaceOwned.toString())
                .doesNotContain(properties.getGuance().getApiKey());
    }

    @Test
    void formalFingerprintLookupNeverFallsBackWhenWorkspaceSettingsLookupFails() {
        EvidenceProperties properties = properties();
        WorkspaceEvidenceSettingsService settings =
                org.mockito.Mockito.mock(WorkspaceEvidenceSettingsService.class);
        org.mockito.Mockito.when(settings.effective(7L))
                .thenThrow(new IllegalStateException("settings store unavailable"));
        GuanceBindingFingerprintService service =
                new GuanceBindingFingerprintService(
                        properties,
                        WorkspaceObservabilityAssets.NONE,
                        WorkspaceEvidenceContracts.NONE,
                        WorkspaceEvidenceRoutes.NONE,
                        settings);

        assertThat(service.current(7L, "CSDP", "session-svc"))
                .as("non-formal inspection keeps the deployment fallback")
                .isPresent();
        assertThatThrownBy(() -> service.currentForFormalAuthority(
                        7L, "CSDP", "session-svc"))
                .isInstanceOf(FormalEvidenceAuthorityException.class)
                .extracting(failure ->
                        ((FormalEvidenceAuthorityException) failure).reason())
                .isEqualTo(FormalEvidenceAuthorityException.Reason.VERIFIER_FAILURE);
    }

    @Test
    void rejectsAmbiguousNormalizedAssetsButKeepsRemainingSafeRoutes() {
        EvidenceProperties properties = properties();
        EvidenceProperties.AssetBinding duplicate = asset("csdp", "SESSION-SVC");
        properties.getGuance().setAssetBindings(List.of(
                asset("CSDP", "session-svc"),
                duplicate));

        GuanceBindingFingerprintService service =
                new GuanceBindingFingerprintService(properties);

        assertThat(service.current(7L, "CSDP", "session-svc")).isEmpty();

        properties.getGuance().setAssetBindings(
                List.of(asset("CSDP", "session-svc")));
        properties.getRoutes().get("CSDP").remove("log_trace_bundle");

        GuanceBindingFingerprintService.Snapshot remaining =
                service.current(7L, "CSDP", "session-svc").orElseThrow();
        assertThat(remaining.readOnlySignalKinds())
                .contains("log_search")
                .doesNotContain("log_trace_bundle");
    }

    @Test
    void workspaceAssetRevisionAndParametersInvalidateTheAcceptedFingerprint() {
        EvidenceProperties properties = properties();
        AtomicReference<WorkspaceObservabilityAsset> current = new AtomicReference<>(
                workspaceAsset(1, true, Map.of("namespace", "csdp-prod")));
        WorkspaceObservabilityAssets assets = new WorkspaceObservabilityAssets() {
            @Override
            public Optional<WorkspaceObservabilityAsset> find(
                    long workspaceId, String system, String service) {
                return workspaceId == 7L
                                && "csdp".equalsIgnoreCase(system)
                                && "session-svc".equalsIgnoreCase(service)
                        ? Optional.of(current.get()) : Optional.empty();
            }

            @Override
            public Set<String> activeBindingReferences(String signalKind) {
                return Set.copyOf(current.get().signalBindings().values());
            }
        };
        GuanceBindingFingerprintService service =
                new GuanceBindingFingerprintService(properties, assets);

        String first = service.current(7L, "CSDP", "session-svc")
                .orElseThrow().bindingFingerprint();
        current.set(workspaceAsset(2, true, Map.of("namespace", "csdp-prod")));
        String afterRevision = service.current(7L, "CSDP", "session-svc")
                .orElseThrow().bindingFingerprint();
        current.set(workspaceAsset(3, true, Map.of("namespace", "csdp-next")));
        String afterParameter = service.current(7L, "CSDP", "session-svc")
                .orElseThrow().bindingFingerprint();
        current.set(workspaceAsset(4, false, Map.of("namespace", "csdp-next")));

        assertThat(afterRevision).isNotEqualTo(first);
        assertThat(afterParameter).isNotEqualTo(afterRevision);
        assertThat(service.current(7L, "CSDP", "session-svc")).isEmpty();
    }

    @Test
    void bindingSignalAndAssetOwnershipPolicyInvalidateTheFingerprint() {
        EvidenceProperties properties = properties();
        EvidenceProperties.Binding search = properties.getGuance()
                .getBindings().get("search-binding");
        search.setSignalKind("log_search");
        search.setQueryTemplate(
                "L::logs:(message=@search) {deployment='{{deployment}}',"
                        + "namespace='{{namespace}}'}");
        search.setAssetParameters(List.of("deployment", "namespace"));
        GuanceBindingFingerprintService service =
                new GuanceBindingFingerprintService(properties);

        String first = service.current(7L, "CSDP", "session-svc")
                .orElseThrow().bindingFingerprint();
        search.setAssetParameters(List.of("deployment"));
        String afterOwnershipChange = service.current(7L, "CSDP", "session-svc")
                .orElseThrow().bindingFingerprint();
        search.setSignalKind("error_log_scan");
        String afterSignalChange = service.current(7L, "CSDP", "session-svc")
                .orElseThrow().bindingFingerprint();

        assertThat(afterOwnershipChange).isNotEqualTo(first);
        assertThat(afterSignalChange).isNotEqualTo(afterOwnershipChange);
    }

    @Test
    void exposesOnlyExactGuanceCapabilitiesCoveredByTheFingerprint() {
        EvidenceProperties properties = properties();
        properties.getRoutes().get("CSDP").put(
                "error_log_scan", List.of("guance"));
        properties.getRoutes().get("CSDP").put(
                "k8s_workload_health", List.of("guance"));
        EvidenceProperties.Binding errors = binding("error_log_scan");
        EvidenceProperties.Binding workload = binding("k8s_workload_health");
        workload.setAssetParameters(List.of("deployment", "namespace"));
        properties.getGuance().getBindings().put("error-binding", errors);
        properties.getGuance().getBindings().put("workload-binding", workload);

        AtomicReference<WorkspaceObservabilityAsset> current = new AtomicReference<>(
                exactAsset(Map.of("deployment", "session-svc")));
        WorkspaceObservabilityAssets assets = new WorkspaceObservabilityAssets() {
            @Override
            public Optional<WorkspaceObservabilityAsset> find(
                    long workspaceId, String system, String service) {
                return Optional.of(current.get());
            }

            @Override
            public Set<String> activeBindingReferences(String signalKind) {
                return Set.copyOf(current.get().signalBindings().values());
            }
        };
        GuanceBindingFingerprintService service =
                new GuanceBindingFingerprintService(properties, assets);

        GuanceBindingFingerprintService.Snapshot missingNamespace =
                service.current(7L, "CSDP", "session-svc").orElseThrow();
        current.set(exactAsset(Map.of(
                "deployment", "session-svc",
                "namespace", "csdp-prod")));
        GuanceBindingFingerprintService.Snapshot complete =
                service.current(7L, "CSDP", "session-svc").orElseThrow();

        assertThat(missingNamespace.readOnlySignalKinds())
                .contains("error_log_scan")
                .doesNotContain("k8s_workload_health");
        assertThat(complete.readOnlySignalKinds())
                .contains("error_log_scan", "k8s_workload_health");
        assertThat(complete.bindingFingerprint())
                .isNotEqualTo(missingNamespace.bindingFingerprint());
    }

    @Test
    void fingerprintsTheEffectiveWorkspaceContractAndRouteUsedAtRuntime() {
        EvidenceProperties properties = properties();
        AtomicReference<EvidenceProperties.Binding> workspaceSearch =
                new AtomicReference<>(binding("log_search"));
        WorkspaceEvidenceContracts contracts = new WorkspaceEvidenceContracts() {
            @Override
            public Map<String, EvidenceProperties.Binding> bindings(long workspaceId) {
                return Map.of("search-binding", workspaceSearch.get());
            }

            @Override
            public Optional<EvidenceProperties.Binding> find(
                    long workspaceId, String contractRef) {
                return "search-binding".equalsIgnoreCase(contractRef)
                        ? Optional.of(workspaceSearch.get()) : Optional.empty();
            }
        };
        AtomicReference<List<String>> workspaceSearchRoute =
                new AtomicReference<>(List.of("guance"));
        WorkspaceEvidenceRoutes routes = (workspaceId, system, signalKind) ->
                "log_search".equalsIgnoreCase(signalKind)
                        ? Optional.of(workspaceSearchRoute.get()) : Optional.empty();
        GuanceBindingFingerprintService service =
                new GuanceBindingFingerprintService(
                        properties,
                        WorkspaceObservabilityAssets.NONE,
                        contracts,
                        routes,
                        null);

        GuanceBindingFingerprintService.Snapshot first =
                service.current(7L, "CSDP", "session-svc").orElseThrow();
        workspaceSearch.get().setQueryTemplate(
                "L::workspace_logs:(count(*) as match_count)");
        GuanceBindingFingerprintService.Snapshot afterContractChange =
                service.current(7L, "CSDP", "session-svc").orElseThrow();
        workspaceSearchRoute.set(List.of());
        GuanceBindingFingerprintService.Snapshot afterRouteChange =
                service.current(7L, "CSDP", "session-svc").orElseThrow();

        assertThat(afterContractChange.bindingFingerprint())
                .isNotEqualTo(first.bindingFingerprint());
        assertThat(afterRouteChange.bindingFingerprint())
                .isNotEqualTo(afterContractChange.bindingFingerprint());
        assertThat(afterRouteChange.readOnlySignalKinds())
                .doesNotContain("log_search");
    }

    @Test
    void runtimeSignificantFieldAndConstantWhitespaceChangesTheFingerprint() {
        EvidenceProperties properties = properties();
        EvidenceProperties.Binding search = properties.getGuance()
                .getBindings().get("search-binding");
        search.setFieldAliases(new LinkedHashMap<>(Map.of(
                "content", "message")));
        EvidenceProperties.Binding contrast = properties.getGuance()
                .getBindings().get("contrast-binding");
        contrast.setConstantFields(new LinkedHashMap<>(Map.of(
                "discriminating_feature", "message_length_eq_2875")));
        GuanceBindingFingerprintService service =
                new GuanceBindingFingerprintService(properties);

        String canonical = service.current(7L, "CSDP", "session-svc")
                .orElseThrow().bindingFingerprint();
        search.setFieldAliases(new LinkedHashMap<>(Map.of(
                " content ", "message")));
        String sourceAliasWhitespace = service.current(7L, "CSDP", "session-svc")
                .orElseThrow().bindingFingerprint();
        search.setFieldAliases(new LinkedHashMap<>(Map.of(
                "content", " message ")));
        String canonicalAliasWhitespace = service.current(7L, "CSDP", "session-svc")
                .orElseThrow().bindingFingerprint();
        search.setFieldAliases(new LinkedHashMap<>(Map.of(
                "content", "message")));
        contrast.setConstantFields(new LinkedHashMap<>(Map.of(
                " discriminating_feature ", "message_length_eq_2875")));
        String constantFieldWhitespace = service.current(7L, "CSDP", "session-svc")
                .orElseThrow().bindingFingerprint();

        assertThat(sourceAliasWhitespace).isNotEqualTo(canonical);
        assertThat(canonicalAliasWhitespace).isNotEqualTo(canonical);
        assertThat(constantFieldWhitespace).isNotEqualTo(canonical);
    }

    @Test
    void runtimeSignificantWorkspaceParameterKeyWhitespaceChangesTheFingerprint() {
        EvidenceProperties properties = properties();
        EvidenceProperties.Binding search = properties.getGuance()
                .getBindings().get("search-binding");
        search.setQueryTemplate("L::logs:(message=@search) {namespace='{{namespace}}'}");
        search.setAssetParameters(List.of("namespace"));
        AtomicReference<WorkspaceObservabilityAsset> current = new AtomicReference<>(
                workspaceAsset(1, true, Map.of("namespace", "csdp-prod")));
        WorkspaceObservabilityAssets assets = new WorkspaceObservabilityAssets() {
            @Override
            public Optional<WorkspaceObservabilityAsset> find(
                    long workspaceId, String system, String service) {
                return Optional.of(current.get());
            }

            @Override
            public Set<String> activeBindingReferences(String signalKind) {
                return Set.copyOf(current.get().signalBindings().values());
            }
        };
        GuanceBindingFingerprintService service =
                new GuanceBindingFingerprintService(properties, assets);

        String canonical = service.current(7L, "CSDP", "session-svc")
                .orElseThrow().bindingFingerprint();
        current.set(workspaceAsset(1, true, Map.of(" namespace ", "csdp-prod")));
        String changed = service.current(7L, "CSDP", "session-svc")
                .orElseThrow().bindingFingerprint();

        assertThat(changed).isNotEqualTo(canonical);
    }

    @Test
    void fingerprintsAServiceWithOnlyOneSafeGenericSignal() {
        EvidenceProperties properties = properties();
        properties.setRoutes(Map.of(
                "CSDP", Map.of("error_log_scan", List.of("guance"))));
        properties.getGuance().setBindings(Map.of(
                "error-binding", binding("error_log_scan")));
        EvidenceProperties.AssetBinding asset = new EvidenceProperties.AssetBinding();
        asset.setWorkspaceId(7L);
        asset.setSystem("CSDP");
        asset.setService("generic-only-svc");
        asset.setSignalBindings(Map.of("error_log_scan", "error-binding"));
        properties.getGuance().setAssetBindings(List.of(asset));

        GuanceBindingFingerprintService.Snapshot snapshot =
                new GuanceBindingFingerprintService(properties)
                        .current(7L, "CSDP", "generic-only-svc")
                        .orElseThrow();

        assertThat(snapshot.readOnlySignalKinds())
                .containsExactly("error_log_scan");
    }

    private EvidenceProperties properties() {
        EvidenceProperties properties = new EvidenceProperties();
        properties.setRoutes(new LinkedHashMap<>(Map.of(
                "CSDP",
                new LinkedHashMap<>(Map.of(
                        "log_search", List.of("guance"),
                        "log_trace_bundle", List.of("guance"),
                        "contrast_sample", List.of("guance"))))));
        properties.getGuance().setEnabled(true);
        properties.getGuance().setBaseUrl("https://guance.example.test");
        properties.getGuance().setApiKey("runtime-secret");
        properties.getGuance().setQueryPath("/api/v1/query");
        properties.getGuance().setTimeout(Duration.ofSeconds(5));
        properties.getGuance().setAssetBindings(
                List.of(asset("CSDP", "session-svc")));

        EvidenceProperties.Binding search = new EvidenceProperties.Binding();
        search.setSignalKind("log_search");
        search.setNamespace("logs");
        search.setQueryTemplate("L::logs:(message=@search)");
        search.setMaxRows(200);
        search.setQueryOptions(new EvidenceProperties.QueryOptions());
        search.setFieldAliases(new LinkedHashMap<>(Map.of(
                "message", "message",
                "psid", "ps_id")));
        EvidenceProperties.Binding trace = new EvidenceProperties.Binding();
        trace.setSignalKind("log_trace_bundle");
        trace.setNamespace("logs");
        trace.setQueryTemplate("L::logs:(psid=@ps_id)");
        trace.setMaxRows(200);
        trace.setFieldAliases(new LinkedHashMap<>(Map.of(
                "service", "service",
                "status", "status")));
        EvidenceProperties.Binding contrast = new EvidenceProperties.Binding();
        contrast.setSignalKind("contrast_sample");
        contrast.setNamespace("logs");
        contrast.setQueryTemplates(List.of(
                "L::logs:(count(*) as failure_sample_count)",
                "L::logs:(count(*) as success_sample_count)"));
        contrast.setConstantFields(Map.of(
                "discriminating_feature", "message_length_eq_2875"));
        properties.getGuance().setBindings(new LinkedHashMap<>(Map.of(
                "search-binding", search,
                "trace-binding", trace,
                "contrast-binding", contrast)));
        return properties;
    }

    private EvidenceProperties.Binding binding(String signalKind) {
        EvidenceProperties.Binding binding = new EvidenceProperties.Binding();
        binding.setSignalKind(signalKind);
        binding.setNamespace("logs");
        binding.setQueryTemplate("L::logs:(count(*) as count)");
        binding.setMaxRows(1);
        return binding;
    }

    private WorkspaceObservabilityAsset exactAsset(Map<String, String> parameters) {
        return new WorkspaceObservabilityAsset(
                "asset-exact",
                7L,
                "csdp",
                "session-svc",
                "guance",
                true,
                Map.of(
                        "log_search", "search-binding",
                        "log_trace_bundle", "trace-binding",
                        "contrast_sample", "contrast-binding",
                        "error_log_scan", "error-binding",
                        "k8s_workload_health", "workload-binding"),
                parameters,
                1);
    }

    private EvidenceProperties.AssetBinding asset(String system, String service) {
        EvidenceProperties.AssetBinding asset =
                new EvidenceProperties.AssetBinding();
        asset.setWorkspaceId(7L);
        asset.setSystem(system);
        asset.setService(service);
        asset.setSignalBindings(new LinkedHashMap<>(Map.of(
                "log_search", "search-binding",
                "log_trace_bundle", "trace-binding",
                "contrast_sample", "contrast-binding")));
        return asset;
    }

    private WorkspaceObservabilityAsset workspaceAsset(
            int version, boolean enabled, Map<String, String> parameters) {
        return new WorkspaceObservabilityAsset(
                "asset-" + version,
                7L,
                "csdp",
                "session-svc",
                "guance",
                enabled,
                Map.of(
                        "log_search", "search-binding",
                        "log_trace_bundle", "trace-binding",
                        "contrast_sample", "contrast-binding"),
                parameters,
                version);
    }
}
