package vip.mate.troubleshooting.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(EvidenceAutoConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void composesEveryAdapterAndKeepsThemDisabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(EvidenceSourceRouter.class);
            EvidenceSourceRouter router = context.getBean(EvidenceSourceRouter.class);
            assertThat(router.health())
                    .extracting(EvidenceSourceHealth::platform)
                    .as("每加一个适配器都要在这里点名——默认全关是这条断言的重点")
                    .containsExactlyInAnyOrder(
                            "guance", "recorded-replay", "prometheus", "elasticsearch");
            assertThat(router.health())
                    .allMatch(health -> health.status() == EvidenceSourceHealth.Status.DISABLED);
        });
    }

    @Test
    void bindsTheBundledReplaySwitchWithoutEnablingGuance() {
        contextRunner
                .withPropertyValues(
                        "mateclaw.troubleshooting.evidence.recorded-replay.enabled=true")
                .run(context -> {
                    EvidenceSourceRouter router = context.getBean(EvidenceSourceRouter.class);
                    assertThat(router.health())
                            .anyMatch(health -> health.platform().equals("recorded-replay")
                                    && health.status() == EvidenceSourceHealth.Status.READY)
                            .anyMatch(health -> health.platform().equals("guance")
                                    && health.status() == EvidenceSourceHealth.Status.DISABLED);
                });
    }

    @Test
    void keepsGuanceDegradedWhenGlobalCredentialsHaveNoAssetAuthorization() {
        contextRunner
                .withPropertyValues(
                        "mateclaw.troubleshooting.evidence.guance.enabled=true",
                        "mateclaw.troubleshooting.evidence.guance.base-url=https://guance.example",
                        "mateclaw.troubleshooting.evidence.guance.api-key=runtime-secret",
                        "mateclaw.troubleshooting.evidence.guance.bindings.csdp-log-count.namespace=L",
                        "mateclaw.troubleshooting.evidence.guance.bindings.csdp-log-count.query-template=L::logs:(count) [{{window}}]")
                .run(context -> {
                    EvidenceSourceRouter router = context.getBean(EvidenceSourceRouter.class);
                    assertThat(router.health())
                            .anyMatch(health -> health.platform().equals("guance")
                                    && health.status() == EvidenceSourceHealth.Status.DEGRADED
                                    && health.detail().contains("authorization"));
                });
    }

    @Test
    void bindsAnExactAssetAuthorizationWithoutClaimingLiveVerification() {
        contextRunner
                .withPropertyValues(
                        "mateclaw.troubleshooting.evidence.guance.enabled=true",
                        "mateclaw.troubleshooting.evidence.guance.base-url=https://guance.example",
                        "mateclaw.troubleshooting.evidence.guance.api-key=runtime-secret",
                        "mateclaw.troubleshooting.evidence.guance.bindings.csdp-log-count.namespace=L",
                        "mateclaw.troubleshooting.evidence.guance.bindings.csdp-log-count.query-template=L::logs:(count,trace_id) [{{window}}]",
                        "mateclaw.troubleshooting.evidence.guance.asset-bindings[0].workspace-id=7",
                        "mateclaw.troubleshooting.evidence.guance.asset-bindings[0].system=CSDP",
                        "mateclaw.troubleshooting.evidence.guance.asset-bindings[0].service=order-svc",
                        "mateclaw.troubleshooting.evidence.guance.asset-bindings[0].signal-bindings.log_count=csdp-log-count")
                .run(context -> {
                    EvidenceProperties properties = context.getBean(EvidenceProperties.class);
                    assertThat(properties.getGuance().getAssetBindings()).singleElement()
                            .satisfies(binding -> {
                                assertThat(binding.getWorkspaceId()).isEqualTo(7L);
                                assertThat(binding.getSystem()).isEqualTo("CSDP");
                                assertThat(binding.getService()).isEqualTo("order-svc");
                                assertThat(binding.getSignalBindings())
                                        .containsEntry("log_count", "csdp-log-count");
                            });

                    EvidenceSourceHealth health = context.getBean(EvidenceSourceRouter.class)
                            .health().stream()
                            .filter(candidate -> candidate.platform().equals("guance"))
                            .findFirst()
                            .orElseThrow();
                    assertThat(health.status()).isEqualTo(EvidenceSourceHealth.Status.DEGRADED);
                    assertThat(health.verified()).isFalse();
                    assertThat(health.detail()).contains("authorized", "not live-verified");
                    assertThat(health.toString()).doesNotContain("runtime-secret");
                });
    }

    @Test
    void keepsBundledAssetAuthorizationEmptyWithoutAnExplicitPilotProfile() {
        contextRunner
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues(
                        "spring.profiles.active=",
                        "mateclaw.troubleshooting.evidence.guance.enabled=false",
                        "mateclaw.troubleshooting.evidence.guance.api-key=")
                .run(context -> {
                    EvidenceProperties properties = context.getBean(EvidenceProperties.class);

                    assertThat(properties.getGuance().getAssetBindings()).isEmpty();
                    assertThat(properties.getRoutes()).doesNotContainKey("csp-deployment");
                });
    }

    @Test
    void rejectsThePilotProfileWithoutAnExplicitWorkspaceId() {
        contextRunner
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues(
                        "spring.profiles.active=csp-clouddial-pilot",
                        "MATECLAW_TROUBLESHOOTING_CSP_WORKSPACE_ID=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void bindsTheBundledCspCloudDialPilotWithoutEnablingItsCredential() {
        contextRunner
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues(
                        "spring.profiles.active=csp-clouddial-pilot",
                        "MATECLAW_TROUBLESHOOTING_CSP_WORKSPACE_ID=1",
                        "mateclaw.troubleshooting.evidence.guance.enabled=false",
                        "mateclaw.troubleshooting.evidence.guance.api-key=",
                        "mateclaw.troubleshooting.evidence.guance.allow-insecure-http=false")
                .run(context -> {
                    EvidenceProperties properties = context.getBean(EvidenceProperties.class);

                    assertThat(properties.getRoutes())
                            .containsKey("csp-deployment");
                    assertThat(properties.getRoutes().get("csp-deployment"))
                            .containsEntry("synthetic_probe", List.of("guance"));

                    EvidenceProperties.Guance guance = properties.getGuance();
                    assertThat(guance.isEnabled()).isFalse();
                    assertThat(guance.isAllowInsecureHttp()).isFalse();
                    assertThat(guance.getApiKey()).isBlank();
                    assertThat(guance.getBaseUrl())
                            .isEqualTo("http://df-openapi.prd.sangfor.com");
                    assertThat(guance.getAssetBindings()).singleElement()
                            .satisfies(asset -> {
                                assertThat(asset.getWorkspaceId()).isEqualTo(1L);
                                assertThat(asset.getSystem()).isEqualTo("csp-deployment");
                                assertThat(asset.getService()).isEqualTo("csp-prm-miniapp");
                                assertThat(asset.getSignalBindings()).containsEntry(
                                        "synthetic_probe",
                                        "csp-prm-miniapp-synthetic-probe");
                            });

                    assertThat(guance.getBindings())
                            .containsKey("csp-prm-miniapp-synthetic-probe");
                    EvidenceProperties.Binding binding = guance.getBindings()
                            .get("csp-prm-miniapp-synthetic-probe");
                    assertThat(binding.getNamespace()).isEqualTo("D");
                    assertThat(binding.getScenario()).isEqualTo("部署拓扑拨测分析");
                    assertThat(binding.getFixedConditions())
                            .contains("拨测任务=客服数字化平台-首页-可用性监控");
                    assertThat(binding.getMaxRows()).isEqualTo(20);
                    assertThat(binding.getQueryTemplate())
                            .contains("http_dial_testing", "客服数字化平台-首页-可用性监控");
                    assertThat(binding.getQueryOptions()).satisfies(options -> {
                        assertThat(options.getMaxPointCount()).isEqualTo(720);
                        assertThat(options.getInterval()).isEqualTo(10);
                        assertThat(options.isAlignTime()).isTrue();
                        assertThat(options.getSeriesLimit()).isEqualTo(20);
                        assertThat(options.isDisableSampling()).isFalse();
                        assertThat(options.getTimeZone()).isEqualTo("Asia/Shanghai");
                    });
                    assertThat(binding.getFieldAliases())
                            .containsEntry("url", "target_url")
                            .containsEntry("name", "probe_name");
                });
    }

    @Test
    void bindsTheBundledCsdpGuanceEvidencePilotWithoutEmbeddingItsCredential() {
        contextRunner
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues(
                        "spring.profiles.active=csdp-guance-evidence-pilot",
                        "MATECLAW_TROUBLESHOOTING_CSDP_WORKSPACE_ID=1",
                        "mateclaw.troubleshooting.evidence.guance.enabled=false",
                        "mateclaw.troubleshooting.evidence.guance.api-key=",
                        "mateclaw.troubleshooting.evidence.guance.allow-insecure-http=false")
                .run(context -> {
                    EvidenceProperties properties = context.getBean(EvidenceProperties.class);

                    assertThat(properties.getRoutes().get("CSDP"))
                            .containsEntry("log_search", List.of("guance"))
                            .containsEntry("log_trace_bundle", List.of("guance"))
                            .containsEntry("contrast_sample", List.of("guance"))
                            .containsEntry("error_log_scan", List.of("guance"))
                            .containsEntry("monitor_event_scan", List.of("guance"))
                            .containsEntry("k8s_workload_health", List.of("guance"))
                            .containsEntry("k8s_pod_status", List.of("guance"))
                            .containsEntry("k8s_node_status", List.of("guance"))
                            .containsEntry("host_status", List.of("guance"));

                    EvidenceProperties.Guance guance = properties.getGuance();
                    assertThat(guance.isEnabled()).isFalse();
                    assertThat(guance.isAllowInsecureHttp()).isFalse();
                    assertThat(guance.getApiKey()).isBlank();
                    assertThat(guance.getTransport()).isEqualTo("native-curl");
                    assertThat(guance.getTimeout()).isEqualTo(java.time.Duration.ofSeconds(45));
                    assertThat(context.getBean(EvidenceHttpTransport.class))
                            .isInstanceOf(NativeCurlEvidenceHttpTransport.class);
                    assertThat(guance.getBindings())
                            .containsKeys(
                                    "guance-service-pod-status",
                                    "guance-service-node-status",
                                    "guance-service-host-status",
                                    "csdp-k8s-workload-health");
                    assertThat(guance.getAssetBindings()).hasSize(3);
                    assertThat(guance.getAssetBindings().get(0)).satisfies(asset -> {
                                assertThat(asset.getWorkspaceId()).isEqualTo(1L);
                                assertThat(asset.getSystem()).isEqualTo("CSDP");
                                assertThat(asset.getService()).isEqualTo("csdp-session-service");
                                assertThat(asset.getSignalBindings())
                                        .containsEntry("log_search", "csdp-message-send-log-search")
                                        .containsEntry(
                                                "log_trace_bundle",
                                                "csdp-message-send-trace-bundle")
                                        .containsEntry(
                                                "contrast_sample",
                                                "csdp-message-send-contrast")
                                        .containsEntry(
                                                "error_log_scan",
                                                "csdp-application-error-scan")
                                        .containsEntry(
                                                "monitor_event_scan",
                                                "csdp-monitor-event-scan")
                                        .containsEntry(
                                                "k8s_workload_health",
                                                "csdp-k8s-workload-health")
                                        .containsEntry(
                                                "k8s_pod_status",
                                                "guance-service-pod-status")
                                        .containsEntry(
                                                "k8s_node_status",
                                                "guance-service-node-status")
                                        .containsEntry(
                                                "host_status",
                                                "guance-service-host-status");
                            });
                    assertThat(guance.getAssetBindings().get(1)).satisfies(asset -> {
                        assertThat(asset.getWorkspaceId()).isEqualTo(1L);
                        assertThat(asset.getSystem()).isEqualTo("CSDP");
                        assertThat(asset.getService()).isEqualTo("csdp-task");
                        assertThat(asset.getSignalBindings())
                                .containsEntry(
                                        "log_search",
                                        "csdp-cti-create-conversation-log-search")
                                .containsEntry(
                                        "log_trace_bundle",
                                        "csdp-cti-create-conversation-trace-bundle")
                                .containsEntry(
                                        "contrast_sample",
                                        "csdp-cti-create-conversation-contrast");
                    });
                    assertThat(guance.getAssetBindings().get(2)).satisfies(asset -> {
                        assertThat(asset.getWorkspaceId()).isEqualTo(1L);
                        assertThat(asset.getSystem()).isEqualTo("CSDP");
                        assertThat(asset.getService()).isEqualTo("csdp-wechat");
                        assertThat(asset.getSignalBindings())
                                .containsEntry(
                                        "log_search",
                                        "csdp-itgw-access-log-search")
                                .containsEntry(
                                        "log_trace_bundle",
                                        "csdp-itgw-access-trace-bundle")
                                .containsEntry(
                                        "contrast_sample",
                                        "csdp-itgw-access-contrast");
                    });

                    assertThat(guance.getBindings())
                            .containsKeys(
                                    "csdp-message-send-log-search",
                                    "csdp-message-send-trace-bundle",
                                    "csdp-message-send-contrast",
                                    "csdp-application-error-scan",
                                    "csdp-monitor-event-scan",
                                    "csdp-k8s-workload-health",
                                    "csdp-cti-create-conversation-log-search",
                                    "csdp-cti-create-conversation-trace-bundle",
                                    "csdp-cti-create-conversation-contrast",
                                    "csdp-itgw-access-log-search",
                                    "csdp-itgw-access-trace-bundle",
                                    "csdp-itgw-access-contrast");
                    EvidenceProperties.Binding ctiSearch = guance.getBindings()
                            .get("csdp-cti-create-conversation-log-search");
                    assertThat(ctiSearch.getQueryTemplate())
                            .contains(
                                    "csdp-task",
                                    "@code",
                                    "701018",
                                    "@trace_id")
                            .doesNotContain("{{window_span}}")
                            .doesNotContain("{{search_term}}");
                    assertThat(ctiSearch.getQueryOptions()).satisfies(options -> {
                        assertThat(options.getMaxPointCount()).isEqualTo(1);
                        assertThat(options.getInterval()).isEqualTo(900);
                        assertThat(options.isAlignTime()).isFalse();
                    });
                    EvidenceProperties.Binding ctiTrace = guance.getBindings()
                            .get("csdp-cti-create-conversation-trace-bundle");
                    assertThat(ctiTrace.getQueryTemplate())
                            .contains("csdp-task", "{{ps_id}}")
                            .doesNotContain("{{search_term}}");
                    assertThat(ctiTrace.getFieldAliases())
                            .containsEntry("message@trace_id", "ps_id")
                            .containsEntry("message@level", "level")
                            .containsEntry("message@msg", "message");
                    EvidenceProperties.Binding ctiContrast = guance.getBindings()
                            .get("csdp-cti-create-conversation-contrast");
                    assertThat(ctiContrast.getQueryTemplates()).hasSize(4);
                    assertThat(ctiContrast.getQueryTemplates().get(0))
                            .contains("{{exclude_ps_id}}", "query_string");
                    assertThat(ctiContrast.getQueryTemplates().get(1))
                            .contains("{{exclude_ps_id}}", "@code", "701022");
                    assertThat(ctiContrast.getQueryTemplates().get(2))
                            .contains("@msg", "errCode", "@stack_trace", "CreateConversation");
                    assertThat(ctiContrast.getQueryTemplates())
                            .allMatch(query -> !query.contains("{{window_span}}"));
                    assertThat(ctiContrast.getQueryOptions()).satisfies(options -> {
                        assertThat(options.getMaxPointCount()).isEqualTo(1);
                        assertThat(options.getInterval()).isEqualTo(900);
                        assertThat(options.isAlignTime()).isFalse();
                    });
                    assertThat(ctiContrast.getConstantFields())
                            .containsEntry(
                                    "discriminating_feature",
                                    "inner_701022_on_failed_trace");
                    EvidenceProperties.Binding itgwSearch = guance.getBindings()
                            .get("csdp-itgw-access-log-search");
                    assertThat(itgwSearch.getQueryTemplate())
                            .contains("csdp-wechat", "@code", "904003", "@trace_id")
                            .doesNotContain("{{window_span}}", "{{search_term}}");
                    assertThat(itgwSearch.getQueryOptions()).satisfies(options -> {
                        assertThat(options.getMaxPointCount()).isEqualTo(1);
                        assertThat(options.getInterval()).isEqualTo(900);
                        assertThat(options.isAlignTime()).isFalse();
                    });
                    EvidenceProperties.Binding itgwTrace = guance.getBindings()
                            .get("csdp-itgw-access-trace-bundle");
                    assertThat(itgwTrace.getQueryTemplate())
                            .contains("csdp-wechat", "{{ps_id}}")
                            .doesNotContain("{{search_term}}");
                    assertThat(itgwTrace.getFieldAliases())
                            .containsEntry("message@trace_id", "ps_id")
                            .containsEntry("message@level", "level")
                            .containsEntry("message@msg", "message");
                    EvidenceProperties.Binding itgwContrast = guance.getBindings()
                            .get("csdp-itgw-access-contrast");
                    assertThat(itgwContrast.getQueryTemplates()).hasSize(4);
                    assertThat(itgwContrast.getQueryTemplates().get(0))
                            .contains("csdp-wechat", "904003");
                    assertThat(itgwContrast.getQueryTemplates().get(1))
                            .contains("csdp-wechat", "904003", "敏感词");
                    assertThat(itgwContrast.getQueryTemplates().get(2))
                            .contains("csdp-wechat", "workOrderPhase", "StatusCode");
                    assertThat(itgwContrast.getQueryTemplates().get(3))
                            .contains("csdp-wechat", "workOrderPhase", "StatusCode", "敏感词");
                    assertThat(itgwContrast.getQueryOptions()).satisfies(options -> {
                        assertThat(options.getMaxPointCount()).isEqualTo(1);
                        assertThat(options.getInterval()).isEqualTo(900);
                        assertThat(options.isAlignTime()).isFalse();
                    });
                    assertThat(itgwContrast.getConstantFields())
                            .containsEntry(
                                    "discriminating_feature",
                                    "itgw_content_policy_blocked");
                    assertThat(guance.getBindings().get("csdp-message-send-log-search")
                            .getQueryTemplate())
                            .contains(
                                    "csp-rpc-msg",
                                    "query_string",
                                    "failed AND sendmsg",
                                    "@trace_id")
                            .doesNotContain("{{window_span}}");
                    assertThat(guance.getBindings().get("csdp-message-send-log-search")
                            .getQuestion()).contains("SendMsg 失败请求");
                    EvidenceProperties.Binding traceBinding = guance.getBindings()
                            .get("csdp-message-send-trace-bundle");
                    assertThat(traceBinding.getQueryTemplate())
                            .contains("(message)", "query_string", "{{ps_id}}")
                            .doesNotContain("LIMIT");
                    assertThat(traceBinding.getFieldAliases())
                            .containsEntry("time", "timestamp")
                            .containsEntry("message@trace_id", "ps_id")
                            .containsEntry("message@level", "level")
                            .containsEntry("message@msg", "message")
                            .doesNotContainKey("message@source");
                    assertThat(traceBinding.getConstantFields())
                            .containsEntry("service", "csp-rpc-msg");
                    EvidenceProperties.Binding contrastBinding = guance.getBindings()
                            .get("csdp-message-send-contrast");
                    assertThat(contrastBinding.getQueryTemplate()).isNull();
                    assertThat(contrastBinding.getQueryTemplates())
                            .hasSize(4)
                            .allSatisfy(query -> assertThat(query)
                                    .contains("count_distinct", "@trace_id")
                                    .doesNotContain("{{window_span}}"));
                    assertThat(contrastBinding.getQueryTemplates().get(0))
                            .contains("failed AND sendmsg");
                    assertThat(contrastBinding.getQueryTemplates().get(1))
                            .contains("failed AND sendmsg", "message_length = 2011");
                    assertThat(contrastBinding.getQueryTemplates().get(2))
                            .contains("success AND sendmsg AND NOT failed");
                    assertThat(contrastBinding.getQueryTemplates().get(3))
                            .contains(
                                    "success AND sendmsg AND NOT failed",
                                    "message_length = 2011");
                    assertThat(contrastBinding.getConstantFields())
                            .containsEntry(
                                    "discriminating_feature",
                                    "message_length_eq_2011");
                    EvidenceProperties.Binding errorScan = guance.getBindings()
                            .get("csdp-application-error-scan");
                    assertThat(errorScan.getNamespace()).isEqualTo("L");
                    assertThat(errorScan.getQueryTemplate())
                            .contains(
                                    "error_count",
                                    "affected_trace_count",
                                    "latest_trace_id",
                                    "level:ERROR",
                                    "{{window_span}}")
                            .doesNotContain("content", "host");
                    assertThat(errorScan.getQueryOptions().isDisableSampling()).isTrue();

                    EvidenceProperties.Binding monitorScan = guance.getBindings()
                            .get("csdp-monitor-event-scan");
                    assertThat(monitorScan.getNamespace()).isEqualTo("E");
                    assertThat(monitorScan.getQueryTemplate())
                            .contains(
                                    "E::monitor",
                                    "event_count",
                                    "latest_status",
                                    "latest_checker",
                                    "{{monitor_checker}}")
                            .doesNotContain("df_message", "df_title");

                    EvidenceProperties.Binding workload = guance.getBindings()
                            .get("csdp-k8s-workload-health");
                    assertThat(workload.getNamespace()).isEqualTo("O+M");
                    assertThat(workload.getMaxRows()).isEqualTo(1);
                    assertThat(workload.getQueryTemplate()).isNull();
                    assertThat(workload.getQueryTemplates())
                            .hasSize(4)
                            .allSatisfy(query -> assertThat(query)
                                    .contains("docker_containers", "{{deployment}}", "{{namespace}}"));
                    assertThat(workload.getQueryTemplates().get(3))
                            .contains("max_cpu_percent", "max_memory_percent");
                });
    }
}
