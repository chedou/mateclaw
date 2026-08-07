package vip.mate.troubleshooting.demo;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import vip.mate.troubleshooting.synthesis.RecordedPlaybookDraftInducer;

/** Loads only the test fixtures required by the troubleshooting HTTP smoke. */
@AutoConfiguration
@ConditionalOnProperty(prefix = "mateclaw.troubleshooting.demo", name = "enabled",
        havingValue = "true")
@EnableConfigurationProperties(TroubleshootingDemoProperties.class)
@Import({
        TroubleshootingDemoSeeder.class,
        RecordedPlaybookDraftInducer.class
})
public class TroubleshootingDemoFixtureAutoConfiguration {
}
