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
    void composesBothAdaptersAndKeepsThemDisabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(EvidenceSourceRouter.class);
            EvidenceSourceRouter router = context.getBean(EvidenceSourceRouter.class);
            assertThat(router.health())
                    .extracting(EvidenceSourceHealth::platform)
                    .containsExactlyInAnyOrder("guance", "recorded-replay");
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
}
