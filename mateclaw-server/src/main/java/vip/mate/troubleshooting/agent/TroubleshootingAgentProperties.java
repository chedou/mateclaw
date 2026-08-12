package vip.mate.troubleshooting.agent;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /** Wall-clock budget for one synchronous miss-path investigation. */
    private Duration triageTimeout = Duration.ofSeconds(20);

    /**
     * Server-owned, approved scenario plans visible to the miss-path Agent.
     * The model may select only the map key; every executable field below is
     * resolved by the server after workspace and system checks.
     */
    private Map<String, ScenarioEvidencePlan> approvedScenarioPlans = new LinkedHashMap<>();

    /**
     * Optional platforms merged into every approved plan at resolve time.
     * Example: set {@code MATECLAW_TROUBLESHOOTING_OPEN_DISCOVERY_EXTRA_PLATFORMS=guance}
     * after a true-source window so night-time fallback can leave recorded-replay.
     */
    private List<String> extraPermittedPlatforms = List.of();

    @Getter
    @Setter
    public static class ScenarioEvidencePlan {
        private boolean enabled;
        private String system;
        private String searchTerm;
        private String window = "-15m";
        private List<Long> workspaceIds = List.of();
        private List<String> permittedPlatforms = List.of();
    }
}
