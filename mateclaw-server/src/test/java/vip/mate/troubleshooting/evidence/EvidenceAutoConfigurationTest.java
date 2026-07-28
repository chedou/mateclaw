package vip.mate.troubleshooting.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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
}
