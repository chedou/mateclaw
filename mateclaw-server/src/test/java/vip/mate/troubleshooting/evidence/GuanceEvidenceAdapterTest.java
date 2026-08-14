package vip.mate.troubleshooting.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class GuanceEvidenceAdapterTest {

    private static final long WORKSPACE_ID = 1L;
    private static final Instant NOW = Instant.parse("2026-07-25T09:12:03Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void refusesGlobalCredentialsWithoutAnExplicitWorkspaceAssetBinding() {
        CapturingTransport transport = new CapturingTransport(200, "{}");
        EvidenceProperties.Guance config = guanceConfig();
        config.setAssetBindings(List.of());
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                config, objectMapper, transport, CLOCK);

        EvidenceResult result = adapter.collect(WORKSPACE_ID, request("-15m"), incident());

        assertThat(result.status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(result.observed()).isEmpty();
        assertThat(transport.calls.get()).isZero();
        assertThat(adapter.health().status()).isEqualTo(EvidenceSourceHealth.Status.DEGRADED);
        assertThat(adapter.health().detail()).contains("authorization");
    }

    @Test
    void doesNotReadTheCredentialBeforeExactAssetAuthorization() {
        EvidenceProperties.Guance template = guanceConfig();
        CredentialGuardedGuance config = new CredentialGuardedGuance();
        config.setEnabled(true);
        config.setBaseUrl("https://guance.example");
        config.setBindings(template.getBindings());
        config.setAssetBindings(List.of());
        CapturingTransport transport = new CapturingTransport(200, "{}");
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                config, objectMapper, transport, CLOCK);

        assertThat(adapter.supports("log_count")).isFalse();
        EvidenceResult result = adapter.collect(WORKSPACE_ID, request("-15m"), incident());

        assertThat(result.status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(transport.calls.get()).isZero();
        assertThat(adapter.health().status()).isEqualTo(EvidenceSourceHealth.Status.DEGRADED);
        assertThat(adapter.health().detail()).contains("authorization");
    }

    // ---- workspace-owned endpoint, credential and enablement (V203) ----
    //
    // These three used to be process-wide yml values. Now a workspace row can
    // override them at runtime, so the adapter has to read the row on the call
    // path rather than the config it was constructed with.

    @Test
    void aWorkspaceRowSuppliesTheEndpointAndCredentialInsteadOfTheDeploymentConfig() {
        CapturingTransport transport = new CapturingTransport(200, "{}");
        EvidenceProperties.Guance config = guanceConfig();
        config.setBaseUrl("https://deployment.example.invalid");
        config.setApiKey("deployment-key");
        config.setBindings(Map.of(
                "csdp-order-log-count", config.getBindings().get("log_count")));
        config.setAssetBindings(List.of(assetBinding(
                WORKSPACE_ID, "CSDP", "order-svc", Map.of("log_count", "csdp-order-log-count"))));
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                config, objectMapper, transport, WorkspaceObservabilityAssets.NONE,
                WorkspaceEvidenceContracts.NONE,
                settings(EffectiveEvidenceSettings.resolved(
                        true, "https://workspace.example.invalid", "workspace-key",
                        false, false, false,
                        EffectiveEvidenceSettings.Origin.WORKSPACE)),
                CLOCK);

        adapter.collect(WORKSPACE_ID, request("-15m"), incident());

        // What matters here is where the request went and what authenticated
        // it, not what came back — normalization is covered elsewhere.
        assertThat(transport.calls.get()).isEqualTo(1);
        assertThat(transport.uri.toString())
                .startsWith("https://workspace.example.invalid");
        assertThat(transport.headers).containsEntry("DF-API-KEY", "workspace-key");
        assertThat(transport.headers.values())
                .as("the deployment credential must not leak into a workspace-owned call")
                .doesNotContain("deployment-key");
    }

    @Test
    void aWorkspaceThatSwitchedGuanceOffIsNotCalledEvenThoughTheDeploymentEnabledIt() {
        CapturingTransport transport = new CapturingTransport(200, "{}");
        EvidenceProperties.Guance config = guanceConfig();
        config.setBindings(Map.of(
                "csdp-order-log-count", config.getBindings().get("log_count")));
        config.setAssetBindings(List.of(assetBinding(
                WORKSPACE_ID, "CSDP", "order-svc", Map.of("log_count", "csdp-order-log-count"))));
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                config, objectMapper, transport, WorkspaceObservabilityAssets.NONE,
                WorkspaceEvidenceContracts.NONE,
                settings(EffectiveEvidenceSettings.resolved(
                        false, "https://workspace.example.invalid", "workspace-key",
                        false, false, false,
                        EffectiveEvidenceSettings.Origin.WORKSPACE)),
                CLOCK);

        EvidenceResult result = adapter.collect(WORKSPACE_ID, request("-15m"), incident());

        assertThat(result.status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(transport.calls.get()).isZero();
    }

    @Test
    void aWorkspaceEndpointPointingAtLoopbackIsBlockedOnTheCallPathNotOnlyOnSave() {
        // Simulates a row written straight into the database, bypassing the
        // service that validates on save.
        CapturingTransport transport = new CapturingTransport(200, "{}");
        EvidenceProperties.Guance config = guanceConfig();
        config.setBindings(Map.of(
                "csdp-order-log-count", config.getBindings().get("log_count")));
        config.setAssetBindings(List.of(assetBinding(
                WORKSPACE_ID, "CSDP", "order-svc", Map.of("log_count", "csdp-order-log-count"))));
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                config, objectMapper, transport, WorkspaceObservabilityAssets.NONE,
                WorkspaceEvidenceContracts.NONE,
                settings(EffectiveEvidenceSettings.resolved(
                        true, "https://127.0.0.1:9529", "workspace-key",
                        false, false, false,
                        EffectiveEvidenceSettings.Origin.WORKSPACE)),
                CLOCK);

        EvidenceResult result = adapter.collect(WORKSPACE_ID, request("-15m"), incident());

        assertThat(result.status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(transport.calls.get())
                .as("the outbound guard must run before the request leaves")
                .isZero();
    }

    /** A settings service stubbed to one fixed answer, with the real SSRF guard. */
    private WorkspaceEvidenceSettingsService settings(EffectiveEvidenceSettings effective) {
        WorkspaceEvidenceSettingsService service =
                org.mockito.Mockito.mock(WorkspaceEvidenceSettingsService.class);
        org.mockito.Mockito.when(service.effective(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(effective);
        org.mockito.Mockito.doAnswer(invocation -> {
            vip.mate.tool.browser.UrlSafetyChecker.check(invocation.getArgument(0), List.of(), false);
            return null;
        }).when(service).assertReachableEndpoint(org.mockito.ArgumentMatchers.anyString());
        return service;
    }

    @Test
    void resolvesAConcreteBindingOnlyForTheExactWorkspaceSystemServiceAndSignal() {
        CapturingTransport transport = new CapturingTransport(200, """
                {
                  "code": 200,
                  "success": true,
                  "content": {"data": [{"series": [{
                    "columns": ["time", "total", "trace"],
                    "values": [[1753434723000, 148, "7f3a91c"]]
                  }]}]}
                }
                """);
        EvidenceProperties.Guance config = guanceConfig();
        config.setBindings(Map.of(
                "csdp-order-log-count", config.getBindings().get("log_count")));
        config.setAssetBindings(List.of(assetBinding(
                WORKSPACE_ID,
                "CSDP",
                "order-svc",
                Map.of("log_count", "csdp-order-log-count"))));
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                config, objectMapper, transport, CLOCK);

        EvidenceResult result = adapter.collect(WORKSPACE_ID, request("-15m"), incident());

        assertThat(result.status()).isEqualTo(EvidenceStatus.NORMAL);
        assertThat(result.observed()).containsEntry("count", 148);
        assertThat(transport.calls.get()).isEqualTo(1);
    }

    @Test
    void workspaceAssetSuppliesServerOwnedQueryDimensions() {
        CapturingTransport transport = new CapturingTransport(200, """
                {
                  "code": 200,
                  "success": true,
                  "content": {"data": [{"series": [{
                    "columns": ["time", "event_count", "latest_status", "latest_checker"],
                    "values": [[1753434723000, 2, "warning", "csdp-session-error-rate"]]
                  }]}]}
                }
                """);
        EvidenceProperties.Guance config = monitorConfig();
        config.setAssetBindings(List.of());
        WorkspaceObservabilityAssets assets = assets(new WorkspaceObservabilityAsset(
                "asset-1", WORKSPACE_ID, "csdp", "order-svc", "guance", true,
                Map.of("monitor_event_scan", "monitor-binding"),
                Map.of("monitor_checker", "csdp-session-error-rate"), 1));
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                config, objectMapper, transport, assets, CLOCK);
        EvidenceRequest request = new EvidenceRequest(
                "EV-ASSET-1", "monitor_event_scan", "check exact monitor",
                Map.of(), "-15m", true);

        EvidenceResult result = adapter.collect(WORKSPACE_ID, request, incident());

        assertThat(result.status()).isEqualTo(EvidenceStatus.NORMAL);
        assertThat(result.observed()).containsEntry("event_count", 2);
        assertThat(transport.body).contains("csdp-session-error-rate");
        assertThat(adapter.supports("monitor_event_scan")).isTrue();
    }

    @Test
    void disabledWorkspaceAssetShadowsDeploymentAuthorization() {
        CapturingTransport transport = new CapturingTransport(200, "{}");
        EvidenceProperties.Guance config = monitorConfig();
        config.setAssetBindings(List.of(assetBinding(
                WORKSPACE_ID, "CSDP", "order-svc",
                Map.of("monitor_event_scan", "monitor-binding"))));
        WorkspaceObservabilityAssets assets = assets(new WorkspaceObservabilityAsset(
                "asset-1", WORKSPACE_ID, "csdp", "order-svc", "guance", false,
                Map.of("monitor_event_scan", "monitor-binding"),
                Map.of("monitor_checker", "csdp-session-error-rate"), 2));
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                config, objectMapper, transport, assets, CLOCK);
        EvidenceRequest request = new EvidenceRequest(
                "EV-ASSET-2", "monitor_event_scan", "disabled",
                Map.of(), "-15m", true);

        EvidenceResult result = adapter.collect(WORKSPACE_ID, request, incident());

        assertThat(result.status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(transport.calls.get()).isZero();
        assertThat(adapter.supports("monitor_event_scan"))
                .as("a disabled workspace declaration must also shadow YAML capability")
                .isFalse();
    }

    @Test
    void playbookCannotOverrideAWorkspaceOwnedQueryDimension() {
        CapturingTransport transport = new CapturingTransport(200, "{}");
        EvidenceProperties.Guance config = monitorConfig();
        config.setAssetBindings(List.of());
        WorkspaceObservabilityAssets assets = assets(new WorkspaceObservabilityAsset(
                "asset-1", WORKSPACE_ID, "csdp", "order-svc", "guance", true,
                Map.of("monitor_event_scan", "monitor-binding"),
                Map.of("monitor_checker", "csdp-session-error-rate"), 1));
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                config, objectMapper, transport, assets, CLOCK);
        EvidenceRequest request = new EvidenceRequest(
                "EV-ASSET-3", "monitor_event_scan", "override",
                Map.of("monitor_checker", "another-system-checker"), "-15m", true);

        EvidenceResult result = adapter.collect(WORKSPACE_ID, request, incident());

        assertThat(result.status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(transport.calls.get()).isZero();
    }

    @Test
    void failsClosedBeforeTransportForEveryUnauthorizedAssetScope() {
        CapturingTransport transport = new CapturingTransport(200, "{}");
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                guanceConfig(), objectMapper, transport, CLOCK);

        List<EvidenceResult> results = List.of(
                adapter.collect(2L, request("-15m"), incident()),
                adapter.collect(WORKSPACE_ID, request("-15m"), incident("ERP", "order-svc")),
                adapter.collect(WORKSPACE_ID, request("-15m"), incident("CSDP", "other-svc")),
                adapter.collect(WORKSPACE_ID, new EvidenceRequest(
                                "EV-2", "metric", "confirm", Map.of(), "-15m", true),
                        incident()));

        assertThat(results).allMatch(result -> result.status() == EvidenceStatus.MISSING);
        assertThat(results).allMatch(result -> result.observed().isEmpty());
        assertThat(transport.calls.get()).isZero();
    }

    @Test
    void failsClosedWhenNormalizedAssetBindingsAreAmbiguous() {
        CapturingTransport transport = new CapturingTransport(200, "{}");
        EvidenceProperties.Guance config = guanceConfig();
        config.setAssetBindings(List.of(
                assetBinding(WORKSPACE_ID, "CSDP", "order-svc",
                        Map.of("log_count", "log_count")),
                assetBinding(WORKSPACE_ID, " csdp ", " ORDER-SVC ",
                        Map.of("LOG_COUNT", "log_count"))));
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                config, objectMapper, transport, CLOCK);

        EvidenceResult result = adapter.collect(WORKSPACE_ID, request("-15m"), incident());

        assertThat(result.status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(transport.calls.get()).isZero();
    }

    @Test
    void failsClosedWhenSignalOrConcreteBindingNamesAreAmbiguous() {
        CapturingTransport transport = new CapturingTransport(200, "{}");
        EvidenceProperties.Guance ambiguousSignal = guanceConfig();
        ambiguousSignal.setAssetBindings(List.of(assetBinding(
                WORKSPACE_ID,
                "CSDP",
                "order-svc",
                Map.of("log_count", "log_count", " LOG_COUNT ", "log_count"))));

        EvidenceProperties.Guance ambiguousBinding = guanceConfig();
        EvidenceProperties.Binding binding = ambiguousBinding.getBindings().get("log_count");
        ambiguousBinding.setBindings(Map.of(
                "log_count", binding,
                " LOG_COUNT ", binding));

        EvidenceResult signalResult = new GuanceEvidenceAdapter(
                ambiguousSignal, objectMapper, transport, CLOCK)
                .collect(WORKSPACE_ID, request("-15m"), incident());
        EvidenceResult bindingResult = new GuanceEvidenceAdapter(
                ambiguousBinding, objectMapper, transport, CLOCK)
                .collect(WORKSPACE_ID, request("-15m"), incident());

        assertThat(signalResult.status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(bindingResult.status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(transport.calls.get()).isZero();
    }

    @Test
    void postsOfficialDqlShapeAndNormalizesTheLatestSeriesRow() throws Exception {
        CapturingTransport transport = new CapturingTransport(200, """
                {
                  "code": 200,
                  "success": true,
                  "content": {
                    "data": [{
                      "series": [{
                        "columns": ["time", "total", "trace"],
                        "values": [[1753434723000, 148, "7f3a91c"], [1753433823000, 12, "old"]]
                      }]
                    }]
                  }
                }
                """);
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                guanceConfig(), objectMapper, transport, CLOCK);

        EvidenceResult result = adapter.collect(WORKSPACE_ID, request("-15m"), incident());

        assertThat(result.status()).isEqualTo(EvidenceStatus.NORMAL);
        assertThat(result.namespace()).isEqualTo("L");
        assertThat(result.source()).isEqualTo("guance:log_count");
        assertThat(result.observed())
                .containsEntry("count", 148)
                .containsEntry("trace_id", "7f3a91c")
                .doesNotContainKey("time");
        assertThat(transport.uri).isEqualTo(
                URI.create("https://guance.example/api/v1/df/query_data_v1"));
        assertThat(transport.headers)
                .containsEntry("Content-Type", "application/json")
                .containsEntry("DF-API-KEY", "secret-key");
        assertThat(transport.timeout).isEqualTo(Duration.ofSeconds(3));

        JsonNode body = objectMapper.readTree(transport.body);
        JsonNode query = body.path("queries").path(0);
        assertThat(query.path("qtype").asText()).isEqualTo("dql");
        assertThat(query.path("query").path("q").asText())
                .isEqualTo("L::order-svc:(count,trace) {error_code='903001'} [-15m]");
        assertThat(query.path("query").path("timeRange").path(0).asLong())
                .isEqualTo(NOW.minus(Duration.ofMinutes(15)).toEpochMilli());
        assertThat(query.path("query").path("timeRange").path(1).asLong())
                .isEqualTo(NOW.toEpochMilli());
        assertThat(query.path("query").has("maxPointCount")).isFalse();
    }

    @Test
    void keepsGuanceTimeRangeNumericWhenTheApplicationMapperSerializesLongsAsStrings()
            throws Exception {
        SimpleModule longAsString = new SimpleModule();
        longAsString.addSerializer(Long.class, ToStringSerializer.instance);
        longAsString.addSerializer(Long.TYPE, ToStringSerializer.instance);
        ObjectMapper applicationMapper = new ObjectMapper().registerModule(longAsString);
        CapturingTransport transport = new CapturingTransport(200, """
                {
                  "code": 200,
                  "success": true,
                  "content": {"data": [{"series": [{
                    "columns": ["time", "total", "trace"],
                    "values": [[1753434723000, 148, "7f3a91c"]]
                  }]}]}
                }
                """);
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                guanceConfig(), applicationMapper, transport, CLOCK);

        EvidenceResult result = adapter.collect(WORKSPACE_ID, request("-15m"), incident());

        assertThat(result.status()).isEqualTo(EvidenceStatus.NORMAL);
        JsonNode query = objectMapper.readTree(transport.body)
                .path("queries").path(0).path("query");
        assertThat(query.path("timeRange").path(0).isNumber()).isTrue();
        assertThat(query.path("timeRange").path(1).isNumber()).isTrue();
        assertThat(query.path("limit").isNumber()).isTrue();
    }

    @Test
    void postsTheCloudDialEnvelopeAndNormalizesTheCspProbe() throws Exception {
        CapturingTransport transport = new CapturingTransport(200, """
                {
                  "code": 200,
                  "success": true,
                  "content": {
                    "data": [{
                      "series": [{
                        "columns": ["time", "status_code", "url", "name"],
                        "values": [
                          [1753434723000, 200, "https://csdp-applet.sangfor.com", "客服数字化平台-首页-可用性监控"],
                          [1753434123000, 503, "https://csdp-applet.sangfor.com", "客服数字化平台-首页-可用性监控"]
                        ]
                      }]
                    }]
                  }
                }
                """);
        EvidenceProperties.Binding binding = binding(
                "D",
                "CSP PRM 小程序拨测状态",
                "D::http_dial_testing:(`status_code`, `url`, `name`) "
                        + "{ `name` = '客服数字化平台-首页-可用性监控' }",
                Map.of(
                        "url", "target_url",
                        "name", "probe_name"),
                20);
        binding.setQueryOptions(cloudDialQueryOptions());
        EvidenceProperties.Guance config = guanceConfig("synthetic_probe", binding);
        config.setAssetBindings(List.of(assetBinding(
                WORKSPACE_ID,
                "csp-deployment",
                "csp-prm-miniapp",
                Map.of("synthetic_probe", "synthetic_probe"))));
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                config, objectMapper, transport, CLOCK);
        EvidenceRequest request = new EvidenceRequest(
                "EV-CLOUD-DIAL-1", "synthetic_probe", "read the approved CloudDial task",
                Map.of(), "-5m", true);

        EvidenceResult result = adapter.collect(
                WORKSPACE_ID, request, incident("csp-deployment", "csp-prm-miniapp"));

        assertThat(result.status()).isEqualTo(EvidenceStatus.NORMAL);
        assertThat(result.namespace()).isEqualTo("D");
        assertThat(result.source()).isEqualTo("guance:synthetic_probe");
        assertThat(result.query()).isEmpty();
        assertThat(result.observed()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "status_code", 200,
                "target_url", "https://csdp-applet.sangfor.com",
                "probe_name", "客服数字化平台-首页-可用性监控"));

        JsonNode query = objectMapper.readTree(transport.body)
                .path("queries").path(0).path("query");
        assertThat(query.path("q").asText()).isEqualTo(
                "D::http_dial_testing:(`status_code`, `url`, `name`) "
                        + "{ `name` = '客服数字化平台-首页-可用性监控' }");
        assertThat(query.path("_funcList").isArray()).isTrue();
        assertThat(query.path("funcList").isArray()).isTrue();
        assertThat(query.path("maxPointCount").asInt()).isEqualTo(720);
        // Window-derived, not the configured 10s: `limit` bounds scanned buckets,
        // so a short interval silently truncates a scalar read to the last few
        // seconds. See aScalarProbeAggregatesTheWholeWindowInsteadOfTheLastFewSeconds.
        assertThat(query.path("interval").asLong())
                .isEqualTo(Duration.ofMinutes(5).toSeconds());
        assertThat(query.path("align_time").asBoolean()).isTrue();
        assertThat(query.path("sorder_by").isArray()).isTrue();
        assertThat(query.path("slimit").asInt()).isEqualTo(20);
        assertThat(query.path("disable_sampling").asBoolean()).isFalse();
        assertThat(query.path("tz").asText()).isEqualTo("Asia/Shanghai");
        assertThat(transport.body).doesNotContain("secret-key");
    }

    /**
     * Without this, every Chinese-named dial task needs its own reviewed contract
     * with the name hardcoded in DQL, so onboarding cost scales per probe.
     */
    @Test
    void oneGenericProbeContractServesAChineseDialTaskNamedByTheAsset() throws Exception {
        CapturingTransport transport = new CapturingTransport(200, """
                {
                  "code": 200,
                  "success": true,
                  "content": {"data": [{"series": [{
                    "columns": ["time", "status_code", "url", "name"],
                    "values": [[1753434723000, 502, "https://icarenew.sangfor.com/index.html",
                      "sf-icare-app-虚机-拨测检测异常"]]
                  }]}]}
                }
                """);
        EvidenceProperties.Binding binding = binding(
                "D",
                "HTTP 拨测最近状态（通用）",
                "D::http_dial_testing:(`status_code`, `url`, `name`) "
                        + "{ `name` = '{{probe_name}}' }",
                Map.of(
                        "url", "target_url",
                        "name", "probe_name"),
                1);
        binding.setAssetParameters(List.of("probe_name"));
        binding.setQueryOptions(cloudDialQueryOptions());
        EvidenceProperties.Guance config = guanceConfig("synthetic_probe", binding);
        config.setAssetBindings(List.of());
        WorkspaceObservabilityAssets assets = assets(new WorkspaceObservabilityAsset(
                "asset-icare", WORKSPACE_ID, "icare", "sf-icare-app", "guance", true,
                Map.of("synthetic_probe", "synthetic_probe"),
                Map.of("probe_name", "sf-icare-app-虚机-拨测检测异常"), 1));
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                config, objectMapper, transport, assets, CLOCK);
        EvidenceRequest request = new EvidenceRequest(
                "EV-CJK-PROBE-1", "synthetic_probe", "read the authorized dial task",
                Map.of(), "-5m", true);

        EvidenceResult result = adapter.collect(
                WORKSPACE_ID, request, incident("icare", "sf-icare-app"));

        JsonNode query = objectMapper.readTree(transport.body)
                .path("queries").path(0).path("query");
        assertThat(query.path("q").asText()).isEqualTo(
                "D::http_dial_testing:(`status_code`, `url`, `name`) "
                        + "{ `name` = 'sf-icare-app-虚机-拨测检测异常' }");
        assertThat(result.status())
                .as("reason: %s", result.summary())
                .isNotEqualTo(EvidenceStatus.MISSING);
        assertThat(result.observed()).containsEntry(
                "probe_name", "sf-icare-app-虚机-拨测检测异常");
    }

    @Test
    void aScalarProbeAggregatesTheWholeWindowInsteadOfTheLastFewSeconds()
            throws Exception {
        // Guance's `limit` bounds scanned aggregation buckets, not returned rows.
        // The contract sends limit = maxRows + 1 = 2, so the configured 10s
        // interval narrowed every scalar read to the last 20 seconds and a 30s
        // dial task read empty 5 times out of 6 against the live deployment.
        CapturingTransport transport = new CapturingTransport(200, """
                {
                  "code": 200,
                  "success": true,
                  "content": {"data": [{"series": [{
                    "columns": ["time", "status_code", "url", "name"],
                    "values": [[1753434723000, 200, "https://icarenew.sangfor.com/x",
                      "icare-app服务-虚机"]]
                  }]}]}
                }
                """);
        EvidenceProperties.Binding binding = binding(
                "D",
                "HTTP 拨测最近状态（通用）",
                "D::http_dial_testing:(`status_code`, `url`, `name`) "
                        + "{ `name` = '{{probe_name}}' }",
                Map.of("url", "target_url", "name", "probe_name"),
                1);
        binding.setAssetParameters(List.of("probe_name"));
        binding.setQueryOptions(cloudDialQueryOptions());
        EvidenceProperties.Guance config = guanceConfig("synthetic_probe", binding);
        config.setAssetBindings(List.of());
        WorkspaceObservabilityAssets assets = assets(new WorkspaceObservabilityAsset(
                "asset-icare", WORKSPACE_ID, "icare", "icare-app", "guance", true,
                Map.of("synthetic_probe", "synthetic_probe"),
                Map.of("probe_name", "icare-app服务-虚机"), 1));
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                config, objectMapper, transport, assets, CLOCK);

        adapter.collect(
                WORKSPACE_ID,
                new EvidenceRequest(
                        "EV-PROBE-WINDOW-1", "synthetic_probe",
                        "read the authorized dial task", Map.of(), "-30m", true),
                incident("icare", "icare-app"));

        JsonNode query = objectMapper.readTree(transport.body)
                .path("queries").path(0).path("query");
        assertThat(query.path("interval").asLong())
                .as("the whole window must collapse into one bucket so last() "
                        + "means last-in-window")
                .isEqualTo(Duration.ofMinutes(30).toSeconds());
        assertThat(query.path("limit").asInt()).isEqualTo(2);
    }

    @Test
    void aRowSetSignalKeepsItsConfiguredIntervalBecauseItNeedsThePoints()
            throws Exception {
        CapturingTransport transport = new CapturingTransport(200, "{}");
        EvidenceProperties.Binding binding = binding(
                "L",
                "链路还原",
                "L::`csdp`:(`time`, `service`, `level`, `message`, `duration_ms`, "
                        + "`ps_id`) { `ps_id` = '{{ps_id}}' }",
                Map.of(),
                20);
        binding.setQueryOptions(cloudDialQueryOptions());
        EvidenceProperties.Guance config = guanceConfig("log_trace_bundle", binding);
        config.setAssetBindings(List.of());
        WorkspaceObservabilityAssets assets = assets(new WorkspaceObservabilityAsset(
                "asset-csdp", WORKSPACE_ID, "csdp", "csdp-wechat", "guance", true,
                Map.of("log_trace_bundle", "log_trace_bundle"), Map.of(), 1));
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                config, objectMapper, transport, assets, CLOCK);

        adapter.collect(
                WORKSPACE_ID,
                new EvidenceRequest(
                        "EV-TRACE-WINDOW-1", "log_trace_bundle", "restore the trace",
                        Map.of("ps_id", "PS-1"), "-30m", true),
                incident("csdp", "csdp-wechat"));

        JsonNode query = objectMapper.readTree(transport.body)
                .path("queries").path(0).path("query");
        assertThat(query.path("interval").asLong())
                .as("a trace needs its individual points, not one aggregate")
                .isEqualTo(cloudDialQueryOptions().getInterval());
    }

    @Test
    void anAssetParameterStillCannotCarryDqlSyntaxIntoTheRenderedQuery() {
        CapturingTransport transport = new CapturingTransport(200, "{}");
        EvidenceProperties.Binding binding = binding(
                "D",
                "HTTP 拨测最近状态（通用）",
                "D::http_dial_testing:(last(`status_code`) as status_code) "
                        + "{ `name` = '{{probe_name}}' }",
                Map.of(),
                1);
        binding.setAssetParameters(List.of("probe_name"));
        binding.setQueryOptions(cloudDialQueryOptions());
        EvidenceProperties.Guance config = guanceConfig("synthetic_probe", binding);
        config.setAssetBindings(List.of());
        WorkspaceObservabilityAssets assets = assets(new WorkspaceObservabilityAsset(
                "asset-icare", WORKSPACE_ID, "icare", "sf-icare-app", "guance", true,
                Map.of("synthetic_probe", "synthetic_probe"),
                Map.of("probe_name", "任务' OR `service` = 'x"), 1));
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                config, objectMapper, transport, assets, CLOCK);
        EvidenceRequest request = new EvidenceRequest(
                "EV-CJK-PROBE-2", "synthetic_probe", "injection attempt",
                Map.of(), "-5m", true);

        EvidenceResult result = adapter.collect(
                WORKSPACE_ID, request, incident("icare", "sf-icare-app"));

        assertThat(result.status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(transport.calls.get()).isZero();
    }

    @Test
    void normalizesALogSearchSampleWithoutRequiringAnErrorCode() throws Exception {
        CapturingTransport transport = new CapturingTransport(200, """
                {
                  "code": 200,
                  "success": true,
                  "content": {
                    "data": [{
                      "series": [{
                        "columns": ["time", "total", "trace", "sample"],
                        "values": [[1753434723000, 4, "synthetic-ps-001", "message send failed"]]
                      }]
                    }]
                  }
                }
                """);
        EvidenceProperties.Binding binding = binding(
                "L",
                "场景关键词日志取样",
                "L::session-log:(count,ps_id,message) {service='{{service}}' AND "
                        + "(error_code='{{search_term}}' OR message=~'{{search_term}}')} [{{window}}]",
                Map.of(
                        "total", "match_count",
                        "trace", "ps_id",
                        "sample", "sample_message"),
                1);
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                guanceConfig("log_search", binding), objectMapper, transport, CLOCK);
        EvidenceRequest request = new EvidenceRequest(
                "EV-P6-1", "log_search", "sample logs",
                Map.of("search_term", "message_send_failed"), "-15m", true);

        EvidenceResult result = adapter.collect(WORKSPACE_ID, request, incidentWithoutErrorCode());

        assertThat(result.status()).isEqualTo(EvidenceStatus.NORMAL);
        assertThat(result.source()).isEqualTo("guance:log_search");
        assertThat(result.query()).isEmpty();
        assertThat(result.observed()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "match_count", 4,
                "ps_id", "synthetic-ps-001",
                "sample_message", "message send failed"));
        JsonNode query = objectMapper.readTree(transport.body)
                .path("queries").path(0).path("query");
        assertThat(query.path("limit").asInt()).isEqualTo(2);
        assertThat(query.path("q").asText())
                .contains("error_code='message_send_failed'")
                .contains("message=~'message_send_failed'");
    }

    @Test
    void mergesTheRealGuanceFieldPerSeriesScalarResponseDeterministically() {
        CapturingTransport transport = new CapturingTransport(200, """
                {
                  "code": 200,
                  "success": true,
                  "content": {
                    "data": [{
                      "series": [
                        {
                          "columns": ["time", "match_count"],
                          "values": [[1753434723000, 2]]
                        },
                        {
                          "columns": ["time", "ps_id"],
                          "values": [[1753434723000, "safe-ps-001"]]
                        },
                        {
                          "columns": ["time", "sample_message"],
                          "values": [[1753434723000, "sendmsg failed"]]
                        }
                      ]
                    }]
                  }
                }
                """);
        EvidenceProperties.Binding binding = binding(
                "L",
                "真实失败日志检索",
                "L::`csp-rpc-msg`:(count(*) as match_count,last(`@trace_id`) as ps_id,"
                        + "last(`@msg`) as sample_message) "
                        + "{ query_string(`message`, \"failed AND sendmsg\") }",
                Map.of(),
                1);
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                guanceConfig("log_search", binding), objectMapper, transport, CLOCK);
        EvidenceRequest request = new EvidenceRequest(
                "EV-T7-SEARCH", "log_search", "sample real failure logs",
                Map.of("search_term", "message_send_failed"), "-24h", true);

        EvidenceResult result = adapter.collect(
                WORKSPACE_ID, request, incidentWithoutErrorCode());

        assertThat(result.status()).isEqualTo(EvidenceStatus.NORMAL);
        assertThat(result.observed()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "match_count", 2,
                "ps_id", "safe-ps-001",
                "sample_message", "sendmsg failed"));
    }

    @Test
    void normalizesAnErrorLogSkillAsAggregateFactsWithoutReturningRawRows() {
        CapturingTransport transport = new CapturingTransport(200, """
                {
                  "code": 200,
                  "success": true,
                  "content": {"data": [{"series": [{
                    "columns": ["time", "error_count", "affected_trace_count", "latest_trace_id"],
                    "values": [[1753434723000, 12, 7, "trace-007"]]
                  }]}]}
                }
                """);
        EvidenceProperties.Binding binding = binding(
                "L",
                "应用 ERROR 聚合巡检",
                "L::`csp-rpc-msg`:(count(*) as error_count,"
                        + "count_distinct(`@trace_id`) as affected_trace_count,"
                        + "last(`@trace_id`) as latest_trace_id) "
                        + "{ query_string(`message`, \"level:ERROR\") } "
                        + "[{{window_span}}::{{window_span}}]",
                Map.of(),
                1);
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                guanceConfig("error_log_scan", binding), objectMapper, transport, CLOCK);
        EvidenceRequest request = new EvidenceRequest(
                "EV-ERROR-SCAN",
                "error_log_scan",
                "scan application errors",
                Map.of(),
                "-24h",
                true);

        EvidenceResult result = adapter.collect(
                WORKSPACE_ID, request, incidentWithoutErrorCode());

        assertThat(result.status()).isEqualTo(EvidenceStatus.NORMAL);
        assertThat(result.query()).isEmpty();
        assertThat(result.source()).isEqualTo("guance:error_log_scan");
        assertThat(result.observed()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "error_count", 12,
                "affected_trace_count", 7,
                "latest_trace_id", "trace-007"));
        assertThat(result.observed())
                .doesNotContainKeys("message", "content", "host");
    }

    @Test
    void preservesARealZeroMonitorEventAggregateWithoutInventingAlertDetails() {
        CapturingTransport transport = new CapturingTransport(200, """
                {
                  "code": 200,
                  "success": true,
                  "content": {"data": [{"series": [{
                    "columns": ["time", "event_count", "latest_status", "latest_checker"],
                    "values": [[1753434723000, 0, null, null]]
                  }]}]}
                }
                """);
        EvidenceProperties.Binding binding = binding(
                "E",
                "监控事件聚合巡检",
                "E::monitor:(count(*) as event_count,last(`df_status`) as latest_status,"
                        + "last(`df_monitor_checker_name`) as latest_checker) "
                        + "{ `df_status` IN ['critical', 'error', 'warning'] "
                        + "AND `df_monitor_checker_name` = '{{monitor_checker}}' } "
                        + "[{{window_span}}::{{window_span}}]",
                Map.of(),
                1);
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                guanceConfig("monitor_event_scan", binding), objectMapper, transport, CLOCK);
        EvidenceRequest request = new EvidenceRequest(
                "EV-MONITOR-SCAN",
                "monitor_event_scan",
                "scan monitor events",
                Map.of("monitor_checker", "csdp-api-error-rate"),
                "-15m",
                true);

        EvidenceResult result = adapter.collect(
                WORKSPACE_ID, request, incidentWithoutErrorCode());

        assertThat(result.status()).isEqualTo(EvidenceStatus.NORMAL);
        assertThat(result.observed()).containsExactlyEntriesOf(Map.of("event_count", 0));
        assertThat(result.observed())
                .doesNotContainKeys("latest_status", "latest_checker", "df_message", "df_title");
        assertThat(transport.body)
                .contains("csdp-api-error-rate")
                .doesNotContain("{{monitor_checker}}");
    }

    @Test
    void omitsOptionalMonitorCheckerAndStillQueriesWarningEvents() {
        CapturingTransport transport = new CapturingTransport(200, """
                {
                  "code": 200,
                  "success": true,
                  "content": {"data": [{"series": [{
                    "columns": ["time", "event_count", "latest_status", "latest_checker"],
                    "values": [[1753434723000, 2, "warning", "any-checker"]]
                  }]}]}
                }
                """);
        EvidenceProperties.Binding binding = binding(
                "E",
                "监控事件聚合巡检",
                "E::monitor:(count(*) as event_count,last(`df_status`) as latest_status,"
                        + "last(`df_monitor_checker_name`) as latest_checker) "
                        + "{ `df_status` IN ['critical', 'error', 'warning']"
                        + "{{?monitor_checker}} AND `df_monitor_checker_name` = '{{monitor_checker}}'"
                        + "{{/monitor_checker}} } "
                        + "[{{window_span}}::{{window_span}}]",
                Map.of(),
                1);
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                guanceConfig("monitor_event_scan", binding), objectMapper, transport, CLOCK);
        EvidenceRequest request = new EvidenceRequest(
                "EV-MONITOR-SCAN-OPTIONAL",
                "monitor_event_scan",
                "scan monitor events",
                Map.of(),
                "-15m",
                true);

        EvidenceResult result = adapter.collect(
                WORKSPACE_ID, request, incidentWithoutErrorCode());

        assertThat(result.status()).isEqualTo(EvidenceStatus.NORMAL);
        assertThat(result.observed()).containsEntry("event_count", 2);
        assertThat(transport.body)
                .contains("`df_status` IN ['critical', 'error', 'warning']")
                .doesNotContain("df_monitor_checker_name` =")
                .doesNotContain("{{monitor_checker}}")
                .doesNotContain("{{?monitor_checker}}");
    }

    @Test
    void mergesTheFourBoundedK8sSkillQueriesAndRejectsUnsafeTargets() throws Exception {
        CapturingTransport transport = new CapturingTransport(200, """
                {
                  "code": 200,
                  "success": true,
                  "content": {"data": [
                    {"series": [{
                      "columns": ["time", "pod_count", "container_count"],
                      "values": [[1753434723000, 3, 4]]
                    }]},
                    {"series": [{
                      "columns": ["time", "running_container_count"],
                      "values": [[1753434723000, 3]]
                    }]},
                    {"series": [{
                      "columns": ["time", "unhealthy_container_count"],
                      "values": [[1753434723000, 1]]
                    }]},
                    {"series": [{
                      "columns": ["time", "max_cpu_percent", "max_memory_percent"],
                      "values": [[1753434723000, 82.5, 76.25]]
                    }]}
                  ]}
                }
                """);
        EvidenceProperties.Binding binding = binding(
                "O+M", "K8s 工作负载健康", "unused", Map.of(), 1);
        binding.setQueryTemplate(null);
        binding.setQueryTemplates(List.of(
                "O::docker_containers:(count_distinct(`pod_name`) as pod_count,"
                        + "count(*) as container_count) "
                        + "{ `deployment` = '{{deployment}}' AND `namespace` = '{{namespace}}' }",
                "O::docker_containers:(count(*) as running_container_count) "
                        + "{ `deployment` = '{{deployment}}' AND `namespace` = '{{namespace}}' "
                        + "AND `state` = 'running' }",
                "O::docker_containers:(count(*) as unhealthy_container_count) "
                        + "{ `deployment` = '{{deployment}}' AND `namespace` = '{{namespace}}' "
                        + "AND `state` != 'running' }",
                "M::docker_containers:(max(`cpu_usage_percent`) as max_cpu_percent,"
                        + "max(`mem_used_percent`) as max_memory_percent) "
                        + "{ `deployment` = '{{deployment}}' AND `namespace` = '{{namespace}}' } "
                        + "[{{window_span}}::{{window_span}}]"));
        EvidenceProperties.Guance config = guanceConfig("k8s_workload_health", binding);
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                config, objectMapper, transport, CLOCK);
        EvidenceRequest request = new EvidenceRequest(
                "EV-K8S-HEALTH",
                "k8s_workload_health",
                "inspect approved workload",
                Map.of("deployment", "csdp-wechat", "namespace", "csdp"),
                "-15m",
                true);

        EvidenceResult result = adapter.collect(
                WORKSPACE_ID, request, incidentWithoutErrorCode());

        assertThat(result.status()).isEqualTo(EvidenceStatus.NORMAL);
        assertThat(result.observed()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "pod_count", 3,
                "container_count", 4,
                "running_container_count", 3,
                "unhealthy_container_count", 1,
                "max_cpu_percent", 82.5,
                "max_memory_percent", 76.25));
        JsonNode queries = objectMapper.readTree(transport.body).path("queries");
        assertThat(queries).hasSize(4);
        for (JsonNode query : queries) {
            assertThat(query.path("query").path("q").asText())
                    .contains("csdp-wechat", "csdp")
                    .doesNotContain("{{");
        }

        CapturingTransport multiRowTransport = new CapturingTransport(200, """
                {
                  "code": 200,
                  "success": true,
                  "content": {"data": [
                    {"series": [{
                      "columns": ["time", "pod_count", "container_count"],
                      "values": [
                        [1753434660000, 2, 3],
                        [1753434723000, 3, 4]
                      ]
                    }]},
                    {"series": [{
                      "columns": ["time", "running_container_count"],
                      "values": [[1753434723000, 3]]
                    }]},
                    {"series": [{
                      "columns": ["time", "unhealthy_container_count"],
                      "values": [[1753434723000, 1]]
                    }]},
                    {"series": [{
                      "columns": ["time", "max_cpu_percent", "max_memory_percent"],
                      "values": [[1753434723000, 82.5, 76.25]]
                    }]}
                  ]}
                }
                """);
        GuanceEvidenceAdapter multiRowAdapter = new GuanceEvidenceAdapter(
                config, objectMapper, multiRowTransport, CLOCK);

        assertThat(multiRowAdapter.collect(
                WORKSPACE_ID, request, incidentWithoutErrorCode()).status())
                .as("a scalar contract must not reinterpret the latest point as a window aggregate")
                .isEqualTo(EvidenceStatus.MISSING);

        CapturingTransport rejectedTransport = new CapturingTransport(200, "{}");
        GuanceEvidenceAdapter rejectedAdapter = new GuanceEvidenceAdapter(
                config, objectMapper, rejectedTransport, CLOCK);
        EvidenceRequest unsafe = new EvidenceRequest(
                "EV-K8S-UNSAFE",
                "k8s_workload_health",
                "reject unreviewed target",
                Map.of("deployment", "csdp-wechat", "namespace", "csdp' OR 1=1"),
                "-15m",
                true);

        assertThat(rejectedAdapter.collect(
                WORKSPACE_ID, unsafe, incidentWithoutErrorCode()).status())
                .isEqualTo(EvidenceStatus.MISSING);
        assertThat(rejectedTransport.calls.get()).isZero();
    }

    @Test
    void rejectsFieldPerSeriesScalarResponsesWithDifferentObservationTimes() {
        CapturingTransport transport = new CapturingTransport(200, """
                {
                  "code": 200,
                  "success": true,
                  "content": {"data": [{"series": [
                    {"columns": ["time", "match_count"],
                     "values": [[1753434723000, 2]]},
                    {"columns": ["time", "ps_id"],
                     "values": [[1753434723001, "safe-ps-001"]]},
                    {"columns": ["time", "sample_message"],
                     "values": [[1753434723000, "sendmsg failed"]]}
                  ]}]}
                }
                """);
        EvidenceProperties.Binding binding = binding(
                "L",
                "真实失败日志检索",
                "L::`csp-rpc-msg`:(match_count,ps_id,sample_message)",
                Map.of(),
                1);
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                guanceConfig("log_search", binding), objectMapper, transport, CLOCK);
        EvidenceRequest request = new EvidenceRequest(
                "EV-T7-SEARCH", "log_search", "sample real failure logs",
                Map.of("search_term", "message_send_failed"), "-24h", true);

        EvidenceResult result = adapter.collect(
                WORKSPACE_ID, request, incidentWithoutErrorCode());

        assertThat(result.status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(result.observed()).isEmpty();
    }

    @Test
    void sendsACompoundContrastContractAndMergesServerOwnedFeatureMetadata()
            throws Exception {
        CapturingTransport transport = new CapturingTransport(200, """
                {
                  "code": 200,
                  "success": true,
                  "content": {"data": [
                    {"series": [
                      {"columns": ["time", "failure_sample_count"],
                       "values": [[1753434723000, 2]]}
                    ]},
                    {"series": [
                      {"columns": ["time", "failure_match_count"],
                       "values": [[1753434723000, 2]]}
                    ]},
                    {"series": [
                      {"columns": ["time", "success_sample_count"],
                       "values": [[1753521123000, 14055]]}
                    ]},
                    {"series": [
                      {"columns": ["time", "success_match_count"],
                       "values": [[1753521123000, 0]]}
                    ]}
                  ]}
                }
                """);
        EvidenceProperties.Binding binding = binding(
                "L", "同窗口成功样本负对照", "unused", Map.of(), 1);
        binding.setQueryTemplate(null);
        binding.setQueryTemplates(List.of(
                "L::`csp-rpc-msg`:(count_distinct(`@trace_id`) as failure_sample_count) "
                        + "{ query_string(`message`, \"failed AND sendmsg\") } "
                        + "[{{window_span}}::{{window_span}}]",
                "L::`csp-rpc-msg`:(count_distinct(`@trace_id`) as failure_match_count) "
                        + "{ query_string(`message`, \"failed AND sendmsg\") "
                        + "AND message_length = 2875 } "
                        + "[{{window_span}}::{{window_span}}]",
                "L::`csp-rpc-msg`:(count_distinct(`@trace_id`) as success_sample_count) "
                        + "{ query_string(`message`, \"success AND sendmsg AND NOT failed\") } "
                        + "[{{window_span}}::{{window_span}}]",
                "L::`csp-rpc-msg`:(count_distinct(`@trace_id`) as success_match_count) "
                        + "{ query_string(`message`, \"success AND sendmsg AND NOT failed\") "
                        + "AND message_length = 2875 } "
                        + "[{{window_span}}::{{window_span}}]"));
        binding.setConstantFields(Map.of(
                "discriminating_feature", "message_length_eq_2875"));
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                guanceConfig("contrast_sample", binding), objectMapper, transport, CLOCK);
        EvidenceRequest request = new EvidenceRequest(
                "EV-T7-CONTRAST", "contrast_sample", "compare failure and success cohorts",
                Map.of(), "-24h", true);

        EvidenceResult result = adapter.collect(
                WORKSPACE_ID, request, incidentWithoutErrorCode());

        assertThat(result.status()).isEqualTo(EvidenceStatus.NORMAL);
        assertThat(result.observed()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "discriminating_feature", "message_length_eq_2875",
                "failure_sample_count", 2,
                "failure_match_count", 2,
                "success_sample_count", 14055,
                "success_match_count", 0));

        JsonNode queries = objectMapper.readTree(transport.body).path("queries");
        assertThat(queries).hasSize(4);
        for (JsonNode query : queries) {
            assertThat(query.path("query").path("q").asText())
                    .contains("count_distinct", "[24h::24h]")
                    .doesNotContain("{{");
            assertThat(query.path("query").path("limit").asInt()).isEqualTo(2);
        }
    }

    @Test
    void normalizesAndChronologicallyOrdersABoundedLogTraceBundle() throws Exception {
        CapturingTransport transport = new CapturingTransport(200, """
                {
                  "code": 200,
                  "success": true,
                  "content": {
                    "data": [{
                      "series": [{
                        "columns": ["time", "trace", "svc", "severity", "content", "elapsed"],
                        "values": [
                          [1753434723042, "synthetic-ps-001", "session-state", "ERROR", "concurrent write rejected", 42],
                          [1753434723000, "synthetic-ps-001", "session-api", "INFO", "message accepted", null]
                        ]
                      }]
                    }]
                  }
                }
                """);
        EvidenceProperties.Binding binding = binding(
                "L",
                "PS ID 全链路日志包",
                "L::session-log:(ps_id,service,status,message,duration_ms) {ps_id='{{ps_id}}'} [{{window}}]",
                Map.of(
                        "time", "timestamp",
                        "trace", "ps_id",
                        "svc", "service",
                        "severity", "level",
                        "content", "message",
                        "elapsed", "duration_ms"),
                2);
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                guanceConfig("log_trace_bundle", binding), objectMapper, transport, CLOCK);
        EvidenceRequest request = new EvidenceRequest(
                "EV-P6-2", "log_trace_bundle", "collect trace logs",
                Map.of("ps_id", "synthetic-ps-001"), "-15m", true);

        EvidenceResult result = adapter.collect(WORKSPACE_ID, request, incidentWithoutErrorCode());

        assertThat(result.status()).isEqualTo(EvidenceStatus.NORMAL);
        assertThat(result.source()).isEqualTo("guance:log_trace_bundle");
        assertThat(result.query()).isEmpty();
        assertThat(result.observed()).containsEntry("ps_id", "synthetic-ps-001");
        assertThat(result.observed().get("entries")).isEqualTo(List.of(
                Map.of(
                        "timestamp", 1753434723000L,
                        "service", "session-api",
                        "level", "INFO",
                        "message", "message accepted"),
                Map.of(
                        "timestamp", 1753434723042L,
                        "service", "session-state",
                        "level", "ERROR",
                        "message", "concurrent write rejected",
                        "duration_ms", 42)));
        JsonNode query = objectMapper.readTree(transport.body)
                .path("queries").path(0).path("query");
        assertThat(query.path("limit").asInt()).isEqualTo(3);
    }

    @Test
    void normalizesRealTraceRowsFromOneJsonRecordSeries() {
        CapturingTransport transport = new CapturingTransport(200, """
                {
                  "code": 200,
                  "success": true,
                  "content": {"data": [{"series": [{
                    "columns": ["time", "message"],
                    "values": [
                      [1753434723042, "{\\\"trace_id\\\":\\\"safe-ps-001\\\",\\\"level\\\":\\\"WARN\\\",\\\"msg\\\":\\\"sendmsg failed\\\",\\\"source\\\":\\\"csp-rpc-msg\\\"}"],
                      [1753434723000, "{\\\"trace_id\\\":\\\"safe-ps-001\\\",\\\"level\\\":\\\"INFO\\\",\\\"msg\\\":\\\"sendmsg accepted\\\",\\\"source\\\":\\\"csp-rpc-msg\\\"}"]
                    ]
                  }]}]}
                }
                """);
        EvidenceProperties.Binding binding = binding(
                "L",
                "真实关联 ID 日志链路",
                "L::`csp-rpc-msg`:(message) "
                        + "{ query_string(`message`, \"{{ps_id}}\") }",
                Map.of(
                        "time", "timestamp",
                        "message@trace_id", "ps_id",
                        "message@level", "level",
                        "message@msg", "message"),
                200);
        binding.setConstantFields(Map.of("service", "csp-rpc-msg"));
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                guanceConfig("log_trace_bundle", binding), objectMapper, transport, CLOCK);
        EvidenceRequest request = new EvidenceRequest(
                "EV-T7-TRACE", "log_trace_bundle", "collect real trace logs",
                Map.of("ps_id", "safe-ps-001"), "-24h", true);

        EvidenceResult result = adapter.collect(
                WORKSPACE_ID, request, incidentWithoutErrorCode());

        assertThat(result.status()).isEqualTo(EvidenceStatus.NORMAL);
        assertThat(result.observed()).containsEntry("ps_id", "safe-ps-001");
        assertThat(result.observed().get("entries")).isEqualTo(List.of(
                Map.of(
                        "timestamp", 1753434723000L,
                        "service", "csp-rpc-msg",
                        "level", "INFO",
                        "message", "sendmsg accepted"),
                Map.of(
                        "timestamp", 1753434723042L,
                        "service", "csp-rpc-msg",
                        "level", "WARN",
                        "message", "sendmsg failed")));
    }

    @Test
    void rejectsFieldPerSeriesTraceRowsEvenWhenTimestampsAndCountsMatch() {
        CapturingTransport transport = new CapturingTransport(200, """
                {
                  "code": 200,
                  "success": true,
                  "content": {"data": [{"series": [
                    {"columns": ["time", "@trace_id"],
                     "values": [[1753434723000, "safe-ps-001"],
                                [1753434723000, "safe-ps-001"]]},
                    {"columns": ["time", "@level"],
                     "values": [[1753434723000, "WARN"],
                                [1753434723000, "INFO"]]},
                    {"columns": ["time", "@msg"],
                     "values": [[1753434723000, "sendmsg failed"],
                                [1753434723000, "sendmsg accepted"]]},
                    {"columns": ["time", "service"],
                     "values": [[1753434723000, "csp-rpc-msg"],
                                [1753434723000, "csp-rpc-msg"]]}
                  ]}]}
                }
                """);
        EvidenceProperties.Binding binding = binding(
                "L",
                "真实关联 ID 日志链路",
                "L::`csp-rpc-msg`:(`@trace_id`,`@level`,`@msg`,`service`)",
                Map.of(
                        "time", "timestamp",
                        "@trace_id", "ps_id",
                        "@level", "level",
                        "@msg", "message"),
                200);
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                guanceConfig("log_trace_bundle", binding), objectMapper, transport, CLOCK);
        EvidenceRequest request = new EvidenceRequest(
                "EV-T7-TRACE", "log_trace_bundle", "collect real trace logs",
                Map.of("ps_id", "safe-ps-001"), "-24h", true);

        EvidenceResult result = adapter.collect(
                WORKSPACE_ID, request, incidentWithoutErrorCode());

        assertThat(result.status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(result.observed()).isEmpty();
    }

    @Test
    void rejectsCompoundQueryTemplatesForRowSetSignalsBeforeTransport() {
        CapturingTransport transport = new CapturingTransport(200, "{}");
        EvidenceProperties.Binding binding = binding(
                "L",
                "ambiguous trace binding",
                "unused",
                Map.of("time", "timestamp"),
                200);
        binding.setQueryTemplate(null);
        binding.setQueryTemplates(List.of(
                "L::logs:(message) {query_string(message, '{{ps_id}}')}",
                "L::logs:(message) {query_string(message, '{{ps_id}}')}"));
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                guanceConfig("log_trace_bundle", binding), objectMapper, transport, CLOCK);
        EvidenceRequest request = new EvidenceRequest(
                "EV-T7-TRACE",
                "log_trace_bundle",
                "reject compound rowset binding",
                Map.of("ps_id", "safe-ps-001"),
                "-24h",
                true);

        EvidenceResult result = adapter.collect(
                WORKSPACE_ID, request, incidentWithoutErrorCode());

        assertThat(result.status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(result.observed()).isEmpty();
        assertThat(transport.calls.get()).isZero();
    }

    @Test
    void keepsCanonicalTraceTimestampsNumericWithTheApplicationLongSerializer() {
        SimpleModule longAsString = new SimpleModule();
        longAsString.addSerializer(Long.class, ToStringSerializer.instance);
        longAsString.addSerializer(Long.TYPE, ToStringSerializer.instance);
        ObjectMapper applicationMapper = new ObjectMapper().registerModule(longAsString);
        CapturingTransport transport = new CapturingTransport(200, """
                {
                  "code": 200,
                  "success": true,
                  "content": {"data": [{"series": [{
                    "columns": ["time", "message"],
                    "values": [[1753434723000,
                      "{\\\"trace_id\\\":\\\"safe-ps-001\\\",\\\"level\\\":\\\"WARN\\\",\\\"msg\\\":\\\"sendmsg failed\\\",\\\"source\\\":\\\"csp-rpc-msg\\\"}"]]
                  }]}]}
                }
                """);
        EvidenceProperties.Binding binding = binding(
                "L",
                "真实关联 ID 日志链路",
                "L::`csp-rpc-msg`:(message)",
                Map.of(
                        "time", "timestamp",
                        "message@trace_id", "ps_id",
                        "message@level", "level",
                        "message@msg", "message",
                        "message@source", "service"),
                200);
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                guanceConfig("log_trace_bundle", binding),
                applicationMapper,
                transport,
                CLOCK);
        EvidenceRequest request = new EvidenceRequest(
                "EV-T7-TRACE", "log_trace_bundle", "collect real trace logs",
                Map.of("ps_id", "safe-ps-001"), "-24h", true);

        EvidenceResult result = adapter.collect(
                WORKSPACE_ID, request, incidentWithoutErrorCode());

        assertThat(result.status()).isEqualTo(EvidenceStatus.NORMAL);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries =
                (List<Map<String, Object>>) result.observed().get("entries");
        assertThat(entries).singleElement().satisfies(entry ->
                assertThat(entry.get("timestamp"))
                        .isInstanceOf(Number.class)
                        .isEqualTo(1753434723000L));
    }

    @Test
    void rejectsFieldPerSeriesTraceResponsesWithDifferentRowCounts() {
        CapturingTransport transport = new CapturingTransport(200, """
                {
                  "code": 200,
                  "success": true,
                  "content": {"data": [{"series": [
                    {"columns": ["time", "@trace_id"],
                     "values": [[1753434723000, "safe-ps-001"],
                                [1753434723000, "safe-ps-001"]]},
                    {"columns": ["time", "@level"],
                     "values": [[1753434723000, "WARN"]]},
                    {"columns": ["time", "@msg"],
                     "values": [[1753434723000, "sendmsg failed"]]},
                    {"columns": ["time", "service"],
                     "values": [[1753434723000, "csp-rpc-msg"]]}
                  ]}]}
                }
                """);
        EvidenceProperties.Binding binding = binding(
                "L",
                "真实关联 ID 日志链路",
                "L::`csp-rpc-msg`:(`@trace_id`,`@level`,`@msg`,`service`)",
                Map.of(
                        "time", "timestamp",
                        "@trace_id", "ps_id",
                        "@level", "level",
                        "@msg", "message"),
                200);
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                guanceConfig("log_trace_bundle", binding), objectMapper, transport, CLOCK);
        EvidenceRequest request = new EvidenceRequest(
                "EV-T7-TRACE", "log_trace_bundle", "collect real trace logs",
                Map.of("ps_id", "safe-ps-001"), "-24h", true);

        EvidenceResult result = adapter.collect(
                WORKSPACE_ID, request, incidentWithoutErrorCode());

        assertThat(result.status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(result.observed()).isEmpty();
    }

    @Test
    void rejectsALogTraceBundleForADifferentPsId() {
        CapturingTransport transport = new CapturingTransport(200, """
                {
                  "code": 200,
                  "success": true,
                  "content": {"data": [{"series": [{
                    "columns": ["time", "trace", "svc", "severity", "content"],
                    "values": [[1, "different-ps", "session-api", "ERROR", "wrong request"]]
                  }]}]}
                }
                """);
        EvidenceProperties.Binding binding = binding(
                "L",
                "PS ID 全链路日志包",
                "L::session-log:(ps_id,service,status,message) {ps_id='{{ps_id}}'} [{{window}}]",
                Map.of(
                        "time", "timestamp",
                        "trace", "ps_id",
                        "svc", "service",
                        "severity", "level",
                        "content", "message"),
                2);
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                guanceConfig("log_trace_bundle", binding), objectMapper, transport, CLOCK);
        EvidenceRequest request = new EvidenceRequest(
                "EV-P6-2", "log_trace_bundle", "collect trace logs",
                Map.of("ps_id", "synthetic-ps-001"), "-15m", true);

        EvidenceResult result = adapter.collect(WORKSPACE_ID, request, incidentWithoutErrorCode());

        assertThat(result.status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(result.observed()).isEmpty();
    }

    @Test
    void failsClosedWhenALogTraceBundleExceedsItsConfiguredBound() {
        CapturingTransport transport = new CapturingTransport(200, """
                {
                  "code": 200,
                  "success": true,
                  "content": {"data": [{"series": [{
                    "columns": ["time", "trace", "svc", "severity", "content"],
                    "values": [
                      [1, "synthetic-ps-001", "one", "INFO", "one"],
                      [2, "synthetic-ps-001", "two", "INFO", "two"],
                      [3, "synthetic-ps-001", "three", "ERROR", "three"]
                    ]
                  }]}]}
                }
                """);
        EvidenceProperties.Binding binding = binding(
                "L",
                "PS ID 全链路日志包",
                "L::session-log:(ps_id,service,status,message) {ps_id='{{ps_id}}'} [{{window}}]",
                Map.of(
                        "time", "timestamp",
                        "trace", "ps_id",
                        "svc", "service",
                        "severity", "level",
                        "content", "message"),
                2);
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                guanceConfig("log_trace_bundle", binding), objectMapper, transport, CLOCK);
        EvidenceRequest request = new EvidenceRequest(
                "EV-P6-2", "log_trace_bundle", "collect trace logs",
                Map.of("ps_id", "synthetic-ps-001"), "-15m", true);

        EvidenceResult result = adapter.collect(WORKSPACE_ID, request, incidentWithoutErrorCode());

        assertThat(result.status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(result.observed()).isEmpty();
    }

    @Test
    void failsClosedOnAnHttpError() {
        CapturingTransport transport = new CapturingTransport(503, "upstream unavailable");
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                guanceConfig(), objectMapper, transport, CLOCK);

        EvidenceResult result = adapter.collect(WORKSPACE_ID, request("-15m"), incident());

        assertThat(result.status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(result.source()).isEqualTo("guance:unavailable");
        assertThat(result.observed()).isEmpty();
    }

    @Test
    void distinguishesAValidHttpResponseWithoutCanonicalEvidenceFromTransportFailure() {
        CapturingTransport transport = new CapturingTransport(200, """
                {
                  "code": 200,
                  "success": true,
                  "content": {"data": [{"series": [{
                    "columns": ["time", "match_count"],
                    "values": [[1753434723000, 0]]
                  }]}]}
                }
                """);
        EvidenceProperties.Binding binding = binding(
                "L", "失败日志检索", "L::logs:(match_count)", Map.of(), 1);
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                guanceConfig("log_search", binding), objectMapper, transport, CLOCK);

        EvidenceResult result = adapter.collect(
                WORKSPACE_ID,
                new EvidenceRequest("EV-NO-EVIDENCE", "log_search", "search", Map.of(), "-24h", true),
                incidentWithoutErrorCode());

        assertThat(result.status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(result.source()).isEqualTo("guance:no_canonical_evidence");
        assertThat(result.observed()).isEmpty();
    }

    @Test
    void treatsAnHttpSuccessWithABusinessFailureAsUnavailable() {
        CapturingTransport transport = new CapturingTransport(200, """
                {
                  "code": 400,
                  "success": false,
                  "message": "query rejected"
                }
                """);
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                guanceConfig(), objectMapper, transport, CLOCK);

        EvidenceResult result = adapter.collect(WORKSPACE_ID, request("-15m"), incident());

        assertThat(result.status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(result.source()).isEqualTo("guance:unavailable");
        assertThat(result.observed()).isEmpty();
    }

    @Test
    void treatsAnHttpSuccessWithoutTheRequiredDataArrayAsUnavailable() {
        CapturingTransport transport = new CapturingTransport(200, """
                {
                  "code": 200,
                  "success": true,
                  "content": {}
                }
                """);
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                guanceConfig(), objectMapper, transport, CLOCK);

        EvidenceResult result = adapter.collect(WORKSPACE_ID, request("-15m"), incident());

        assertThat(result.status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(result.source()).isEqualTo("guance:unavailable");
        assertThat(result.observed()).isEmpty();
    }

    @Test
    void rejectsUnsafeTemplateValuesWithoutSendingARequest() {
        CapturingTransport transport = new CapturingTransport(200, "{}");
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                guanceConfig(), objectMapper, transport, CLOCK);

        EvidenceResult result = adapter.collect(
                WORKSPACE_ID, request("-15m"), incident("order-svc' OR true"));

        assertThat(result.status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(transport.calls.get()).isZero();
    }

    @Test
    void rejectsInvalidPerBindingQueryOptionsBeforeSendingARequest() {
        CapturingTransport transport = new CapturingTransport(200, "{}");
        EvidenceProperties.Guance config = guanceConfig();
        EvidenceProperties.QueryOptions options = new EvidenceProperties.QueryOptions();
        options.setTimeZone("not-a-time-zone");
        config.getBindings().get("log_count").setQueryOptions(options);
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                config, objectMapper, transport, CLOCK);

        EvidenceResult result = adapter.collect(
                WORKSPACE_ID, request("-15m"), incident());

        assertThat(result.status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(transport.calls.get()).isZero();
    }

    @Test
    void refusesToSendTheApiKeyOverPlainHttpByDefault() {
        CapturingTransport transport = new CapturingTransport(200, "{}");
        EvidenceProperties.Guance config = guanceConfig();
        config.setBaseUrl("http://guance.example");
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                config, objectMapper, transport, CLOCK);

        EvidenceResult result = adapter.collect(WORKSPACE_ID, request("-15m"), incident());

        assertThat(result.status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(transport.calls.get()).isZero();

        config.setAllowInsecureHttp(true);
        CapturingTransport explicitlyAllowed = new CapturingTransport(200, "{}");
        new GuanceEvidenceAdapter(config, objectMapper, explicitlyAllowed, CLOCK)
                .collect(WORKSPACE_ID, request("-15m"), incident());
        assertThat(explicitlyAllowed.calls.get()).isEqualTo(1);
    }

    @Test
    void failsClosedOnMissingWrongTypeOrAmbiguousCanonicalRows() {
        List<String> malformedResponses = List.of(
                """
                {"code":200,"success":true,"content":{"data":[{"series":[{
                  "columns":["time","total"],"values":[[1753434723000,148]]
                }]}]}}
                """,
                """
                {"code":200,"success":true,"content":{"data":[{"series":[{
                  "columns":["time","total","trace"],"values":[[1753434723000,"148","7f3a91c"]]
                }]}]}}
                """,
                """
                {"code":200,"success":true,"content":{"data":[{"series":[
                  {"columns":["time","total","trace"],"values":[[1753434723000,148,"7f3a91c"]]},
                  {"columns":["time","total","trace"],"values":[[1753434723000,149,"other"]]}
                ]}]}}
                """,
                """
                {"code":200,"success":true,"content":{"data":[{"series":[{
                  "columns":["time","total","trace"],
                  "values":[[1753434723000,148,"7f3a91c"],[1753434723000,149,"other"]]
                }]}]}}
                """);

        for (String response : malformedResponses) {
            GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                    guanceConfig(), objectMapper, new CapturingTransport(200, response), CLOCK);

            EvidenceResult result = adapter.collect(WORKSPACE_ID, request("-15m"), incident());

            assertThat(result.status()).isEqualTo(EvidenceStatus.MISSING);
            assertThat(result.observed()).isEmpty();
        }
    }

    @Test
    void reportsConfiguredButUnverifiedHealthHonestly() {
        GuanceEvidenceAdapter adapter = new GuanceEvidenceAdapter(
                guanceConfig(), objectMapper, new CapturingTransport(200, "{}"), CLOCK);

        assertThat(adapter.health().status()).isEqualTo(EvidenceSourceHealth.Status.DEGRADED);
        assertThat(adapter.health().verified()).isFalse();
        assertThat(adapter.health().detail()).contains("not live-verified");
    }

    private EvidenceProperties.Guance guanceConfig() {
        EvidenceProperties.Guance config = new EvidenceProperties.Guance();
        config.setEnabled(true);
        config.setBaseUrl("https://guance.example");
        config.setApiKey("secret-key");
        config.setQueryPath("/api/v1/df/query_data_v1");
        config.setTimeout(Duration.ofSeconds(3));

        EvidenceProperties.Binding binding = new EvidenceProperties.Binding();
        binding.setNamespace("L");
        binding.setSummary("错误码日志计数");
        binding.setQueryTemplate(
                "L::{{service}}:(count,trace) {error_code='{{error_code}}'} [{{window}}]");
        binding.setFieldAliases(Map.of("total", "count", "trace", "trace_id"));
        config.setBindings(Map.of("log_count", binding));
        config.setAssetBindings(List.of(assetBinding(
                WORKSPACE_ID,
                "CSDP",
                "order-svc",
                Map.of("log_count", "log_count"))));
        return config;
    }

    private EvidenceProperties.Guance guanceConfig(
            String signalKind,
            EvidenceProperties.Binding binding) {
        EvidenceProperties.Guance config = new EvidenceProperties.Guance();
        config.setEnabled(true);
        config.setBaseUrl("https://guance.example");
        config.setApiKey("secret-key");
        config.setQueryPath("/api/v1/df/query_data_v1");
        config.setTimeout(Duration.ofSeconds(3));
        config.setBindings(Map.of(signalKind, binding));
        config.setAssetBindings(List.of(assetBinding(
                WORKSPACE_ID,
                "CSDP",
                "csdp-session-service",
                Map.of(signalKind, signalKind))));
        return config;
    }

    private EvidenceProperties.Guance monitorConfig() {
        EvidenceProperties.Binding binding = new EvidenceProperties.Binding();
        binding.setSignalKind("monitor_event_scan");
        binding.setNamespace("E");
        binding.setSummary("精确监控事件");
        binding.setQueryTemplate(
                "E::monitor:(event_count,latest_status,latest_checker) "
                        + "{checker='{{monitor_checker}}'} [{{window}}]");
        binding.setAssetParameters(List.of("monitor_checker"));
        binding.setMaxRows(1);
        EvidenceProperties.Guance config = new EvidenceProperties.Guance();
        config.setEnabled(true);
        config.setBaseUrl("https://guance.example");
        config.setApiKey("secret-key");
        config.setQueryPath("/api/v1/df/query_data_v1");
        config.setTimeout(Duration.ofSeconds(3));
        config.setBindings(Map.of("monitor-binding", binding));
        return config;
    }

    private WorkspaceObservabilityAssets assets(WorkspaceObservabilityAsset asset) {
        return new WorkspaceObservabilityAssets() {
            @Override
            public java.util.Optional<WorkspaceObservabilityAsset> find(
                    long workspaceId, String system, String service) {
                return workspaceId == asset.workspaceId()
                                && asset.system().equalsIgnoreCase(system)
                                && asset.service().equalsIgnoreCase(service)
                        ? java.util.Optional.of(asset) : java.util.Optional.empty();
            }

            @Override
            public java.util.Set<String> activeBindingReferences(String signalKind) {
                String reference = asset.signalBindings().get(signalKind);
                return asset.enabled() && reference != null
                        ? java.util.Set.of(reference) : java.util.Set.of();
            }
        };
    }

    private EvidenceProperties.Binding binding(
            String namespace,
            String summary,
            String queryTemplate,
            Map<String, String> aliases,
            int maxRows) {
        EvidenceProperties.Binding binding = new EvidenceProperties.Binding();
        binding.setNamespace(namespace);
        binding.setSummary(summary);
        binding.setQueryTemplate(queryTemplate);
        binding.setFieldAliases(aliases);
        binding.setMaxRows(maxRows);
        return binding;
    }

    private EvidenceProperties.QueryOptions cloudDialQueryOptions() {
        EvidenceProperties.QueryOptions options = new EvidenceProperties.QueryOptions();
        options.setMaxPointCount(720);
        options.setInterval(10);
        options.setAlignTime(true);
        options.setSeriesLimit(20);
        options.setDisableSampling(false);
        options.setTimeZone("Asia/Shanghai");
        return options;
    }

    private EvidenceProperties.AssetBinding assetBinding(
            long workspaceId,
            String system,
            String service,
            Map<String, String> signalBindings) {
        EvidenceProperties.AssetBinding binding = new EvidenceProperties.AssetBinding();
        binding.setWorkspaceId(workspaceId);
        binding.setSystem(system);
        binding.setService(service);
        binding.setSignalBindings(signalBindings);
        return binding;
    }

    private EvidenceRequest request(String window) {
        return new EvidenceRequest(
                "EV-1", "log_count", "confirm",
                Map.of("service", "override-attempt", "error_code", "999999"), window, true);
    }

    private IncidentContext incident() {
        return incident("order-svc");
    }

    private IncidentContext incident(String service) {
        return incident("CSDP", service);
    }

    private IncidentContext incident(String system, String service) {
        return new IncidentContext(
                "inc-1", system, service, "903001", "订单创建超时",
                "P0", "订单创建成功率下降", "7f3a91c", NOW, "21:18",
                "alert_webhook", IncidentCompleteness.STRUCTURED, "code=903001");
    }

    private IncidentContext incidentWithoutErrorCode() {
        return new IncidentContext(
                "inc-p6", "CSDP", "csdp-session-service", null, "会话消息发送失败",
                "P1", "会话消息发送受阻", null, NOW, "18:00",
                "manual", IncidentCompleteness.SYMPTOM, "客户发送消息失败");
    }

    private static final class CapturingTransport implements EvidenceHttpTransport {
        @Override
        public Response get(
                java.net.URI uri,
                java.util.Map<String, String> headers,
                java.time.Duration timeout) {
            throw new UnsupportedOperationException(
                    "this double serves the POST-based Guance chain only");
        }

        private final int statusCode;
        private final String responseBody;
        private final AtomicInteger calls = new AtomicInteger();
        private URI uri;
        private Map<String, String> headers;
        private String body;
        private Duration timeout;

        private CapturingTransport(int statusCode, String responseBody) {
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }

        @Override
        public Response postJson(
                URI uri,
                Map<String, String> headers,
                String body,
                Duration timeout) {
            calls.incrementAndGet();
            this.uri = uri;
            this.headers = headers;
            this.body = body;
            this.timeout = timeout;
            return new Response(statusCode, responseBody);
        }
    }

    private static final class CredentialGuardedGuance extends EvidenceProperties.Guance {
        @Override
        public String getApiKey() {
            throw new AssertionError("credential must not be read before asset authorization");
        }
    }
}
