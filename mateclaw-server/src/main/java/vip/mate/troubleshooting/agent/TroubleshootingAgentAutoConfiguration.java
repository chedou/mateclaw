package vip.mate.troubleshooting.agent;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Enables the miss-path properties without turning the path on by default. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TroubleshootingAgentProperties.class)
public class TroubleshootingAgentAutoConfiguration {
}
