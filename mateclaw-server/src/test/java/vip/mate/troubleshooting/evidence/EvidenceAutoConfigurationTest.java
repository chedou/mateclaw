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
                            .containsEntry("contrast_sample", List.of("guance"));

                    EvidenceProperties.Guance guance = properties.getGuance();
                    assertThat(guance.isEnabled()).isFalse();
                    assertThat(guance.isAllowInsecureHttp()).isFalse();
                    assertThat(guance.getApiKey()).isBlank();
                    assertThat(guance.getTransport()).isEqualTo("native-curl");
                    assertThat(guance.getTimeout()).isEqualTo(java.time.Duration.ofSeconds(45));
                    assertThat(context.getBean(EvidenceHttpTransport.class))
                            .isInstanceOf(NativeCurlEvidenceHttpTransport.class);
                    assertThat(guance.getAssetBindings()).singleElement()
                            .satisfies(asset -> {
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
                                                "csdp-message-send-contrast");
                            });

                    assertThat(guance.getBindings())
                            .containsKeys(
                                    "csdp-message-send-log-search",
                                    "csdp-message-send-trace-bundle",
                                    "csdp-message-send-contrast");
                    assertThat(guance.getBindings().get("csdp-message-send-log-search")
                            .getQueryTemplate())
                            .contains(
                                    "csp-rpc-msg",
                                    "query_string",
                                    "failed AND sendmsg",
                                    "@trace_id",
                                    "{{window_span}}");
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
                                    .contains("count_distinct", "@trace_id", "{{window_span}}"));
                    assertThat(contrastBinding.getQueryTemplates().get(0))
                            .contains("failed AND sendmsg");
                    assertThat(contrastBinding.getQueryTemplates().get(1))
                            .contains("failed AND sendmsg", "message_length = 2875");
                    assertThat(contrastBinding.getQueryTemplates().get(2))
                            .contains("success AND sendmsg AND NOT failed");
                    assertThat(contrastBinding.getQueryTemplates().get(3))
                            .contains(
                                    "success AND sendmsg AND NOT failed",
                                    "message_length = 2875");
                    assertThat(contrastBinding.getConstantFields())
                            .containsEntry(
                                    "discriminating_feature",
                                    "message_length_eq_2875");
                });
    }
}
