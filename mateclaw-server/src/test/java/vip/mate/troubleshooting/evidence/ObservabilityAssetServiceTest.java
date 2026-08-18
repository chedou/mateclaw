package vip.mate.troubleshooting.evidence;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.model.TroubleshootingEvidenceContractEntity;
import vip.mate.troubleshooting.model.TroubleshootingObservabilityAssetEntity;
import vip.mate.troubleshooting.repository.TroubleshootingEvidenceContractMapper;
import vip.mate.troubleshooting.repository.TroubleshootingObservabilityAssetMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The workspace registry that connects business modules to reviewed query contracts. */
class ObservabilityAssetServiceTest {

    private static final long WORKSPACE_ID = 7L;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<Long, TroubleshootingObservabilityAssetEntity> rows =
            new LinkedHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private ObservabilityAssetService service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                TroubleshootingObservabilityAssetEntity.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                TroubleshootingEvidenceContractEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = newService(properties());
    }

    private ObservabilityAssetService newService(EvidenceProperties properties) {
        TroubleshootingEvidenceContractMapper contractMapper =
                mock(TroubleshootingEvidenceContractMapper.class);
        when(contractMapper.selectList(any())).thenReturn(List.of());
        EvidenceContractService contracts = new EvidenceContractService(
                contractMapper, properties, objectMapper);
        return new ObservabilityAssetService(
                assetMapper(), properties, contracts, objectMapper);
    }

    @Test
    @DisplayName("声明后成为该 workspace + 系统 + 模块的精确运行时资产")
    void declarationBecomesTheExactRuntimeAsset() {
        ObservabilityAssetView declared = service.declare(
                WORKSPACE_ID,
                declaration(null, true,
                        Map.of("log_search", "csdp-log-search"), Map.of()),
                "admin");

        WorkspaceObservabilityAsset runtime = service.find(
                WORKSPACE_ID, "CSDP", "SESSION-SERVICE").orElseThrow();

        assertThat(declared.origin()).isEqualTo("WORKSPACE");
        assertThat(declared.version()).isEqualTo(1);
        assertThat(runtime.system()).isEqualTo("csdp");
        assertThat(runtime.service()).isEqualTo("session-service");
        assertThat(runtime.signalBindings())
                .containsEntry("log_search", "csdp-log-search");
        assertThat(service.find(WORKSPACE_ID + 1, "csdp", "session-service"))
                .as("资产不能泄漏到另一个 workspace")
                .isEmpty();
    }

    @Test
    @DisplayName("系统资产不能把本地告警事实绑定成外部查询")
    void assetCannotBindAnIncidentReportedSignal() {
        assertThatThrownBy(() -> service.declare(
                WORKSPACE_ID,
                declaration(null, true,
                        Map.of("incident_reported_external_http_failure", "reported-alert"),
                        Map.of()),
                "admin"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("incident_reported_external_http_failure");
        assertThatThrownBy(() -> service.declare(
                WORKSPACE_ID,
                declaration(null, true,
                        Map.of("incident_reported_business_policy_rejection", "reported-policy"),
                        Map.of()),
                "admin"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("incident_reported_business_policy_rejection");
    }

    @Test
    @DisplayName("更新追加不可变版本，并拒绝基于旧版本覆盖")
    void updateAppendsARevisionAndUsesOptimisticVersioning() {
        service.declare(
                WORKSPACE_ID,
                declaration(null, true,
                        Map.of("log_search", "csdp-log-search"), Map.of()),
                "admin");

        ObservabilityAssetView updated = service.declare(
                WORKSPACE_ID,
                declaration(1, false,
                        Map.of("log_search", "csdp-log-search"), Map.of()),
                "owner");

        assertThat(updated.version()).isEqualTo(2);
        assertThat(updated.enabled()).isFalse();
        assertThat(rows.values()).hasSize(2)
                .extracting(TroubleshootingObservabilityAssetEntity::getVersion)
                .containsExactly(1, 2);
        assertThatThrownBy(() -> service.declare(
                WORKSPACE_ID,
                declaration(1, true,
                        Map.of("log_search", "csdp-log-search"), Map.of()),
                "stale-editor"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("version");
    }

    @Test
    @DisplayName("合同声明为资产所有的查询维度时，资产必须提供精确安全值")
    void requiredAssetParametersMustBeDeclared() {
        assertThatThrownBy(() -> service.declare(
                WORKSPACE_ID,
                declaration(null, true,
                        Map.of("monitor_event_scan", "csdp-monitor-events"), Map.of()),
                "admin"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("monitor_checker");

        assertThatThrownBy(() -> service.declare(
                WORKSPACE_ID,
                declaration(null, true,
                        Map.of("monitor_event_scan", "csdp-monitor-events"),
                        Map.of("monitor_checker", "all' OR true")),
                "admin"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("safe");

        ObservabilityAssetView declared = service.declare(
                WORKSPACE_ID,
                declaration(null, true,
                        Map.of("monitor_event_scan", "csdp-monitor-events"),
                        Map.of("monitor_checker", "csdp-session-error-rate")),
                "admin");

        assertThat(declared.parameters())
                .containsEntry("monitor_checker", "csdp-session-error-rate");

        EvidenceProperties undeclaredProperties = properties();
        undeclaredProperties.getGuance().getBindings().get("csdp-log-search")
                .setQueryTemplate(
                        "L::logs:(count(*)) {tenant='{{tenant}}'} "
                                + "[{{window_span}}::{{window_span}}]");
        ObservabilityAssetService undeclaredService = newService(undeclaredProperties);
        assertThatThrownBy(() -> undeclaredService.declare(
                WORKSPACE_ID,
                declaration(null, true,
                        Map.of("log_search", "csdp-log-search"),
                        Map.of("tenant", "csdp")),
                "admin"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("asset-owned");
    }

    @Test
    @DisplayName("只能绑定部署中已审核且信号类型一致的查询合同")
    void onlyReviewedContractsForTheSameSignalCanBeBound() {
        assertThatThrownBy(() -> service.declare(
                WORKSPACE_ID,
                declaration(null, true,
                        Map.of("log_search", "missing-contract"), Map.of()),
                "admin"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("missing-contract");

        assertThatThrownBy(() -> service.declare(
                WORKSPACE_ID,
                declaration(null, true,
                        Map.of("log_search", "csdp-monitor-events"),
                        Map.of("monitor_checker", "csdp-session-error-rate")),
                "admin"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("signal");

        EvidenceProperties legacyProperties = properties();
        EvidenceProperties.Binding legacy = new EvidenceProperties.Binding();
        legacy.setQueryTemplate("L::logs:(count(*)) [{{window_span}}::{{window_span}}]");
        legacy.setMaxRows(1);
        Map<String, EvidenceProperties.Binding> bindings = new LinkedHashMap<>(
                legacyProperties.getGuance().getBindings());
        bindings.put("legacy-without-signal", legacy);
        legacyProperties.getGuance().setBindings(bindings);
        ObservabilityAssetService strictService = newService(legacyProperties);

        assertThatThrownBy(() -> strictService.declare(
                WORKSPACE_ID,
                declaration(null, true,
                        Map.of("log_search", "legacy-without-signal"), Map.of()),
                "admin"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("signal");

        EvidenceProperties crossContractProperties = properties();
        EvidenceProperties.Binding logSearch = crossContractProperties.getGuance()
                .getBindings().get("csdp-log-search");
        logSearch.setQueryTemplate(
                "L::logs:(count(*)) {namespace='{{namespace}}'}");
        EvidenceProperties.Binding monitor = crossContractProperties.getGuance()
                .getBindings().get("csdp-monitor-events");
        monitor.setAssetParameters(List.of("namespace"));
        Map<String, String> selected = new LinkedHashMap<>();
        selected.put("log_search", "csdp-log-search");
        selected.put("monitor_event_scan", "csdp-monitor-events");
        ObservabilityAssetService crossContractService = newService(crossContractProperties);
        assertThatThrownBy(() -> crossContractService.declare(
                WORKSPACE_ID,
                declaration(null, true, selected, Map.of("namespace", "csdp")),
                "admin"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("invalid asset parameter");

        EvidenceProperties mixedTemplateProperties = properties();
        EvidenceProperties.Binding mixed = mixedTemplateProperties.getGuance()
                .getBindings().get("csdp-log-search");
        mixed.setQueryTemplates(List.of("L::logs:(count(*))"));
        ObservabilityAssetService mixedTemplateService = newService(mixedTemplateProperties);
        assertThatThrownBy(() -> mixedTemplateService.declare(
                WORKSPACE_ID,
                declaration(null, true,
                        Map.of("log_search", "csdp-log-search"), Map.of()),
                "admin"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("either one query template");

        EvidenceProperties mixedCaseProperties = properties();
        EvidenceProperties.Binding mixedCase = mixedCaseProperties.getGuance()
                .getBindings().get("csdp-monitor-events");
        mixedCase.setQueryTemplate(
                "E::monitor:(count(*)) {namespace='{{Namespace}}'}");
        mixedCase.setAssetParameters(List.of("namespace"));
        ObservabilityAssetService mixedCaseService = newService(mixedCaseProperties);
        assertThatThrownBy(() -> mixedCaseService.declare(
                WORKSPACE_ID,
                declaration(null, true,
                        Map.of("monitor_event_scan", "csdp-monitor-events"),
                        Map.of("namespace", "csdp")),
                "admin"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("lowercase");
    }

    @Test
    @DisplayName("顶层资产范围与同名查询参数必须唯一且不得携带密钥")
    void metadataParametersCannotDivergeAndTextCannotContainSecrets() {
        EvidenceProperties configured = properties();
        EvidenceProperties.Binding workload = new EvidenceProperties.Binding();
        workload.setSignalKind("k8s_workload_health");
        workload.setQueryTemplate(
                "O::pod:(count(*)) {namespace='{{namespace}}'}");
        workload.setAssetParameters(List.of("namespace"));
        workload.setMaxRows(1);
        Map<String, EvidenceProperties.Binding> bindings = new LinkedHashMap<>(
                configured.getGuance().getBindings());
        bindings.put("csdp-k8s", workload);
        configured.getGuance().setBindings(bindings);
        ObservabilityAssetService strictService = newService(configured);

        assertThatThrownBy(() -> strictService.declare(
                WORKSPACE_ID,
                declaration(null, true,
                        Map.of("k8s_workload_health", "csdp-k8s"),
                        Map.of("namespace", "another-namespace")),
                "admin"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("namespace");

        ObservabilityAssetDeclaration leaked = new ObservabilityAssetDeclaration(
                "CSDP", "session-service", "CSDP 会话服务", "guance",
                "prod", null, null, null, true,
                Map.of("log_search", "csdp-log-search"), Map.of(), null,
                "api_key=do-not-store-this-value");
        assertThatThrownBy(() -> strictService.declare(
                WORKSPACE_ID, leaked, "admin"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("secret");

        ObservabilityAssetDeclaration rawQuery = new ObservabilityAssetDeclaration(
                "CSDP", "session-service", "CSDP 会话服务", "guance",
                "prod", null, null, null, true,
                Map.of("log_search", "csdp-log-search"), Map.of(), null,
                "DQL L::logs:(message) 不应进入资产表");
        assertThatThrownBy(() -> strictService.declare(
                WORKSPACE_ID, rawQuery, "admin"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("DQL");

        ObservabilityAssetDeclaration endpoint = new ObservabilityAssetDeclaration(
                "CSDP", "session-service", "CSDP 会话服务", "guance",
                "https://guance.example.test", null, null, null, true,
                Map.of("log_search", "csdp-log-search"), Map.of(), null,
                "登记真实资产");
        assertThatThrownBy(() -> strictService.declare(
                WORKSPACE_ID, endpoint, "admin"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("endpoint");

        assertThatThrownBy(() -> strictService.declare(
                WORKSPACE_ID,
                declaration(null, true,
                        Map.of("monitor_event_scan", "csdp-monitor-events"),
                        Map.of("monitor_checker", "api-key:do-not-store-this-value")),
                "admin"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("secret");
    }

    @Test
    @DisplayName("目录回显部署资产，Workspace 声明后用其覆盖而不重复")
    void catalogMergesDeploymentFallbackAndWorkspaceOverride() {
        ObservabilityAssetCatalogView before = service.catalog(WORKSPACE_ID);
        assertThat(before.assets()).singleElement()
                .satisfies(asset -> {
                    assertThat(asset.origin()).isEqualTo("DEPLOYMENT");
                    assertThat(asset.system()).isEqualTo("csdp");
                    assertThat(asset.service()).isEqualTo("session-service");
                });
        assertThat(before.contracts())
                .extracting(ObservabilityAssetCatalogView.ContractOption::contractRef)
                .containsExactlyInAnyOrder("csdp-log-search", "csdp-monitor-events");

        service.declare(
                WORKSPACE_ID,
                declaration(null, true,
                        Map.of("log_search", "csdp-log-search"), Map.of()),
                "admin");

        assertThat(service.catalog(WORKSPACE_ID).assets()).singleElement()
                .satisfies(asset -> assertThat(asset.origin()).isEqualTo("WORKSPACE"));
    }

    private ObservabilityAssetDeclaration declaration(
            Integer expectedVersion,
            boolean enabled,
            Map<String, String> signalBindings,
            Map<String, String> parameters) {
        return new ObservabilityAssetDeclaration(
                "CSDP",
                "session-service",
                "CSDP 会话服务",
                "guance",
                "prod",
                "cn-south-1",
                "csdp-prod",
                "csdp",
                enabled,
                signalBindings,
                parameters,
                expectedVersion,
                enabled ? "接入真实 Guance 取证" : "暂停该资产取证");
    }

    private EvidenceProperties properties() {
        EvidenceProperties properties = new EvidenceProperties();
        EvidenceProperties.Binding logSearch = new EvidenceProperties.Binding();
        logSearch.setSignalKind("log_search");
        logSearch.setScenario("失败日志检索");
        logSearch.setQuestion("哪些失败请求需要继续追踪？");
        logSearch.setQueryTemplate("L::logs:(count(*)) [{{window_span}}::{{window_span}}]");
        logSearch.setMaxRows(1);

        EvidenceProperties.Binding monitor = new EvidenceProperties.Binding();
        monitor.setSignalKind("monitor_event_scan");
        monitor.setScenario("监控告警巡检");
        monitor.setQuestion("是否触发了精确监控规则？");
        monitor.setQueryTemplate(
                "E::monitor:(count(*)) {checker='{{monitor_checker}}'}");
        monitor.setAssetParameters(List.of("monitor_checker"));
        monitor.setMaxRows(1);

        properties.getGuance().setBindings(Map.of(
                "csdp-log-search", logSearch,
                "csdp-monitor-events", monitor));
        EvidenceProperties.AssetBinding deployed = new EvidenceProperties.AssetBinding();
        deployed.setWorkspaceId(WORKSPACE_ID);
        deployed.setSystem("CSDP");
        deployed.setService("session-service");
        deployed.setSignalBindings(Map.of(
                "log_search", "csdp-log-search",
                "monitor_event_scan", "csdp-monitor-events"));
        properties.getGuance().setAssetBindings(List.of(deployed));
        return properties;
    }

    @SuppressWarnings("unchecked")
    private TroubleshootingObservabilityAssetMapper assetMapper() {
        TroubleshootingObservabilityAssetMapper mapper =
                mock(TroubleshootingObservabilityAssetMapper.class);
        when(mapper.insert(any(TroubleshootingObservabilityAssetEntity.class)))
                .thenAnswer((Answer<Integer>) call -> {
                    TroubleshootingObservabilityAssetEntity entity = call.getArgument(0);
                    entity.setId(ids.incrementAndGet());
                    rows.put(entity.getId(), entity);
                    return 1;
                });
        when(mapper.selectList(any())).thenAnswer((Answer<Object>) call ->
                matching(call.getArgument(0)));
        return mapper;
    }

    private List<TroubleshootingObservabilityAssetEntity> matching(Object wrapper) {
        Map<String, Object> bound = bound(wrapper);
        return rows.values().stream()
                .filter(row -> !containsNumber(bound, row.getWorkspaceId())
                        ? bound.values().stream().noneMatch(Number.class::isInstance)
                        : true)
                .filter(row -> stringTerms(bound).isEmpty()
                        || stringTerms(bound).contains(row.getSystem())
                        || stringTerms(bound).contains(row.getService()))
                .toList();
    }

    private boolean containsNumber(Map<String, Object> bound, Number expected) {
        return bound.values().stream()
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .anyMatch(value -> value.longValue() == expected.longValue());
    }

    private List<String> stringTerms(Map<String, Object> bound) {
        return bound.values().stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
    }

    private Map<String, Object> bound(Object wrapper) {
        if (!(wrapper instanceof AbstractWrapper<?, ?, ?> typed)) {
            return Map.of();
        }
        typed.getSqlSegment();
        Map<String, Object> parameters = typed.getParamNameValuePairs();
        return parameters == null ? Map.of() : parameters;
    }
}
