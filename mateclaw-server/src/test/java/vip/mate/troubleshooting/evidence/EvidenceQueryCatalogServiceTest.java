package vip.mate.troubleshooting.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvidenceQueryCatalogServiceTest {

    @Test
    void describesServerOwnedGuanceContractsWithoutExposingDqlOrCredentials() throws Exception {
        EvidenceProperties properties = configuredProperties();
        GuanceEvidenceAdapter adapter = adapter(properties);
        EvidenceRouteService routes = mock(EvidenceRouteService.class);
        GuanceEvidenceAcceptanceService acceptance = mock(GuanceEvidenceAcceptanceService.class);
        when(routes.list(7L, null)).thenReturn(List.of());
        when(acceptance.inspect(7L, "CSDP", "session-svc"))
                .thenReturn(new GuanceEvidenceAcceptanceView(
                        GuanceEvidenceAcceptanceView.Status.NOT_ACCEPTED,
                        "CSDP",
                        "session-svc",
                        "binding-fingerprint",
                        null,
                        List.of("owner acceptance not recorded")));

        EvidenceQueryCatalogView catalog = new EvidenceQueryCatalogService(
                properties, routes, List.of(adapter), adapter, acceptance)
                .inspect(7L);

        assertThat(catalog.contractVersion()).isEqualTo("evidence-query-catalog.v1");
        assertThat(catalog.sources()).singleElement().satisfies(source -> {
            assertThat(source.platform()).isEqualTo("guance");
            assertThat(source.endpointStatus()).isEqualTo("CONFIGURED");
            assertThat(source.credentialStatus()).isEqualTo("CONFIGURED");
            assertThat(source.supportedSignals()).contains("log_search");
        });
        assertThat(catalog.systems()).singleElement().satisfies(system -> {
            assertThat(system.system()).isEqualTo("CSDP");
            assertThat(system.modules()).singleElement().satisfies(module -> {
                assertThat(module.service()).isEqualTo("session-svc");
                assertThat(module.acceptance().status()).isEqualTo("NOT_ACCEPTED");
                assertThat(module.contracts()).singleElement().satisfies(contract -> {
                    assertThat(contract.signalKind()).isEqualTo("log_search");
                    assertThat(contract.scenario()).isEqualTo("会话消息发送失败");
                    assertThat(contract.question()).contains("失败请求");
                    assertThat(contract.endpoint()).satisfies(endpoint -> {
                        assertThat(endpoint.operationKind()).isEqualTo("DF_QUERY_DATA_V1");
                        assertThat(endpoint.method()).isEqualTo("POST");
                        assertThat(endpoint.path()).isEqualTo("/api/v1/df/query_data_v1");
                        assertThat(endpoint.qtype()).isEqualTo("dql");
                    });
                    assertThat(contract.parameters())
                            .extracting(EvidenceQueryCatalogView.ParameterView::name)
                            .containsExactly("occurred_at", "window", "ps_id");
                    assertThat(contract.canonicalOutputs())
                            .containsExactlyInAnyOrder(
                                    "match_count", "ps_id", "sample_message");
                    assertThat(contract.route().origin()).isEqualTo("DEPLOYMENT");
                    assertThat(contract.route().platforms()).containsExactly("guance");
                    assertThat(contract.binding().status()).isEqualTo("READY_FOR_VALIDATION");
                    assertThat(contract.runnable()).isTrue();
                });
            });
        });

        String serialized = new ObjectMapper().writeValueAsString(catalog);
        assertThat(serialized)
                .doesNotContain("runtime-secret")
                .doesNotContain("L::logs")
                .doesNotContain("queryTemplate")
                .doesNotContain("apiKey")
                .doesNotContain("baseUrl");
    }

    @Test
    void keepsAnExplicitWorkspaceDisableDistinctFromDeploymentFallback() {
        EvidenceProperties properties = configuredProperties();
        GuanceEvidenceAdapter adapter = adapter(properties);
        EvidenceRouteService routes = mock(EvidenceRouteService.class);
        GuanceEvidenceAcceptanceService acceptance = mock(GuanceEvidenceAcceptanceService.class);
        when(routes.list(7L, null)).thenReturn(List.of(new EvidenceRouteView(
                "csdp",
                "log_search",
                List.of(),
                List.of(),
                "admin",
                "maintenance window",
                Instant.parse("2026-08-04T01:00:00Z"))));
        when(acceptance.inspect(7L, "CSDP", "session-svc"))
                .thenReturn(new GuanceEvidenceAcceptanceView(
                        GuanceEvidenceAcceptanceView.Status.NOT_ACCEPTED,
                        "CSDP",
                        "session-svc",
                        "binding-fingerprint",
                        null,
                        List.of()));

        EvidenceQueryCatalogView.ContractView contract = new EvidenceQueryCatalogService(
                properties, routes, List.of(adapter), adapter, acceptance)
                .inspect(7L)
                .systems().getFirst()
                .modules().getFirst()
                .contracts().getFirst();

        assertThat(contract.route().origin()).isEqualTo("WORKSPACE");
        assertThat(contract.route().explicitlyDisabled()).isTrue();
        assertThat(contract.route().platforms()).isEmpty();
        assertThat(contract.route().reason()).isEqualTo("maintenance window");
        assertThat(contract.runnable()).isFalse();
        assertThat(contract.blockers()).contains("当前 Workspace 明确停用了该证据路由");
    }

    @Test
    void marksContractsBlockedWhenTheEffectiveWorkspaceAssetIsDisabled() {
        EvidenceProperties properties = configuredProperties();
        GuanceEvidenceAdapter adapter = adapter(properties);
        EvidenceRouteService routes = mock(EvidenceRouteService.class);
        GuanceEvidenceAcceptanceService acceptance = mock(GuanceEvidenceAcceptanceService.class);
        ObservabilityAssetService assets = mock(ObservabilityAssetService.class);
        when(routes.list(7L, null)).thenReturn(List.of());
        when(assets.catalog(7L)).thenReturn(new ObservabilityAssetCatalogView(
                7L,
                List.of(new ObservabilityAssetView(
                        "asset-disabled", "WORKSPACE", 7L,
                        "CSDP", "session-svc", "CSDP session service",
                        "guance", "production", null, null, null,
                        false,
                        Map.of("log_search", "csdp-search"),
                        Map.of(), 2, "owner", "maintenance",
                        Instant.parse("2026-08-07T00:00:00Z"))),
                List.of()));
        when(acceptance.inspect(7L, "CSDP", "session-svc"))
                .thenReturn(new GuanceEvidenceAcceptanceView(
                        GuanceEvidenceAcceptanceView.Status.NOT_ACCEPTED,
                        "CSDP",
                        "session-svc",
                        "binding-fingerprint",
                        null,
                        List.of()));

        EvidenceQueryCatalogView.ModuleView module = new EvidenceQueryCatalogService(
                properties, routes, List.of(adapter), adapter, acceptance, assets)
                .inspect(7L)
                .systems().getFirst()
                .modules().getFirst();

        assertThat(module.status()).isEqualTo("BLOCKED");
        assertThat(module.runnableContracts()).isZero();
        assertThat(module.blockers()).contains("系统观测资产已停用");
        assertThat(module.contracts()).singleElement().satisfies(contract -> {
            assertThat(contract.runnable()).isFalse();
            assertThat(contract.blockers()).contains("系统观测资产已停用");
        });
    }

    @Test
    void exposesTheSanitizedK8sSkillContractAsApprovedTargetParameters() throws Exception {
        EvidenceProperties properties = configuredProperties();
        EvidenceProperties.Binding workload = new EvidenceProperties.Binding();
        workload.setNamespace("O+M");
        workload.setSummary("Kubernetes 工作负载状态与资源聚合");
        workload.setQueryTemplates(List.of(
                "O::docker_containers:(count(*) as pod_count) "
                        + "{deployment='{{deployment}}' AND namespace='{{namespace}}'}",
                "O::docker_containers:(count(*) as container_count) "
                        + "{deployment='{{deployment}}' AND namespace='{{namespace}}'}",
                "O::docker_containers:(count(*) as running_container_count,"
                        + "count(*) as unhealthy_container_count) "
                        + "{deployment='{{deployment}}' AND namespace='{{namespace}}'}",
                "M::docker_containers:(max(cpu_usage_percent) as max_cpu_percent,"
                        + "max(mem_used_percent) as max_memory_percent) "
                        + "{deployment='{{deployment}}' AND namespace='{{namespace}}'}"));
        workload.setMaxRows(1);
        properties.getGuance().setBindings(Map.of("csdp-k8s-health", workload));
        EvidenceProperties.AssetBinding asset = new EvidenceProperties.AssetBinding();
        asset.setWorkspaceId(7L);
        asset.setSystem("CSDP");
        asset.setService("session-svc");
        asset.setSignalBindings(Map.of("k8s_workload_health", "csdp-k8s-health"));
        properties.getGuance().setAssetBindings(List.of(asset));
        properties.setRoutes(Map.of(
                "CSDP", Map.of("k8s_workload_health", List.of("guance"))));

        GuanceEvidenceAdapter adapter = adapter(properties);
        EvidenceRouteService routes = mock(EvidenceRouteService.class);
        GuanceEvidenceAcceptanceService acceptance = mock(GuanceEvidenceAcceptanceService.class);
        when(routes.list(7L, null)).thenReturn(List.of());
        when(acceptance.inspect(7L, "CSDP", "session-svc"))
                .thenReturn(new GuanceEvidenceAcceptanceView(
                        GuanceEvidenceAcceptanceView.Status.NOT_ACCEPTED,
                        "CSDP",
                        "session-svc",
                        "binding-fingerprint",
                        null,
                        List.of()));

        EvidenceQueryCatalogView.ContractView contract = new EvidenceQueryCatalogService(
                properties, routes, List.of(adapter), adapter, acceptance)
                .inspect(7L)
                .systems().getFirst()
                .modules().getFirst()
                .contracts().getFirst();

        assertThat(contract.signalKind()).isEqualTo("k8s_workload_health");
        assertThat(contract.scenario()).isEqualTo("K8s 工作负载健康");
        assertThat(contract.question()).contains("Deployment", "CPU", "内存");
        assertThat(contract.parameters())
                .extracting(EvidenceQueryCatalogView.ParameterView::name)
                .containsExactly("occurred_at", "window", "deployment", "namespace");
        assertThat(contract.parameters())
                .filteredOn(parameter -> Set.of("deployment", "namespace")
                        .contains(parameter.name()))
                .allSatisfy(parameter -> {
                    assertThat(parameter.source()).isEqualTo("EVIDENCE_REQUEST_TARGET");
                    assertThat(parameter.description()).contains("已审核排障方案");
                });
        assertThat(contract.canonicalOutputs()).containsExactlyInAnyOrder(
                "pod_count",
                "container_count",
                "running_container_count",
                "unhealthy_container_count",
                "max_cpu_percent",
                "max_memory_percent");
        assertThat(contract.budget().queryCount()).isEqualTo(4);
        assertThat(contract.budget().maxRows()).isEqualTo(1);
        assertThat(contract.runnable()).isTrue();

        assertThat(new ObjectMapper().writeValueAsString(contract))
                .doesNotContain("docker_containers", "{{deployment}}", "queryTemplates");
    }

    private EvidenceProperties configuredProperties() {
        EvidenceProperties properties = new EvidenceProperties();
        EvidenceProperties.Guance guance = properties.getGuance();
        guance.setEnabled(true);
        guance.setBaseUrl("https://guance.example");
        guance.setApiKey("runtime-secret");
        guance.setQueryPath("/api/v1/df/query_data_v1");

        EvidenceProperties.Binding binding = new EvidenceProperties.Binding();
        binding.setNamespace("L");
        binding.setSummary("CSDP SendMsg 失败日志检索");
        binding.setScenario("会话消息发送失败");
        binding.setQuestion("哪些失败请求需要继续追踪？");
        binding.setFixedConditions(List.of("日志源=csp-rpc-msg", "消息包含 failed AND sendmsg"));
        binding.setQueryTemplate("L::logs:(count,ps_id,message) {ps_id='{{ps_id}}'}");
        binding.setMaxRows(1);
        guance.setBindings(Map.of("csdp-search", binding));

        EvidenceProperties.AssetBinding asset = new EvidenceProperties.AssetBinding();
        asset.setWorkspaceId(7L);
        asset.setSystem("CSDP");
        asset.setService("session-svc");
        asset.setSignalBindings(Map.of("log_search", "csdp-search"));
        guance.setAssetBindings(List.of(asset));
        properties.setRoutes(Map.of(
                "CSDP", Map.of("log_search", List.of("guance"))));
        return properties;
    }

    private GuanceEvidenceAdapter adapter(EvidenceProperties properties) {
        return new GuanceEvidenceAdapter(
                properties.getGuance(),
                new ObjectMapper(),
                mock(EvidenceHttpTransport.class),
                Clock.systemUTC());
    }
}
