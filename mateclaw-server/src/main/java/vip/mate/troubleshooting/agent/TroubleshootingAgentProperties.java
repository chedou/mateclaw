package vip.mate.troubleshooting.agent;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Fail-closed configuration for deterministic-route misses. */
@Getter
@Setter
@ConfigurationProperties(prefix = "mateclaw.troubleshooting.agent")
public class TroubleshootingAgentProperties {

    /** Explicit rollout switch. A missing setting keeps the LLM path off. */
    private boolean enabled;

    /** Existing MateClaw Agent dedicated to troubleshooting triage. */
    private long agentId;

    /** Maximum iteration setting accepted on the configured Agent row. */
    private int maxIterations = 6;

    /** Maximum evidence-tool calls allowed during one triage session. */
    private int maxEvidenceRequests = 6;

    /** Hard character budget for the complete initial model prompt. */
    private int maxPromptChars = 32_000;
}
