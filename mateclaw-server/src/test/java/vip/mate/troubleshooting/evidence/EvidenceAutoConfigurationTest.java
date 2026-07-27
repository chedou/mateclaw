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
}
