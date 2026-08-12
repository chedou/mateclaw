package vip.mate.troubleshooting.agent;

import java.util.List;

/**
 * Secret-free readiness for the OPEN_DISCOVERY night-time fallback.
 *
 * <p>Does not call a model or query Guance. Safe for the workbench so operators
 * know whether an unknown alert can enter the caged miss-path.</p>
 */
public record OpenDiscoveryReadiness(
        Status status,
        boolean agentEnabled,
        long configuredAgentId,
        String configuredAgentName,
        String agentBindingSource,
        boolean agentReady,
        int configuredPlanCount,
        int visiblePlanCount,
        boolean trueSourcePermitted,
        List<PlanSummary> plans,
        List<String> blockers,
        String nextAction) {

    public OpenDiscoveryReadiness {
        status = status == null ? Status.BLOCKED : status;
        configuredAgentName = configuredAgentName == null ? "" : configuredAgentName.trim();
        agentBindingSource = agentBindingSource == null || agentBindingSource.isBlank()
                ? "NONE"
                : agentBindingSource.trim();
        plans = List.copyOf(plans == null ? List.of() : plans);
        blockers = List.copyOf(blockers == null ? List.of() : blockers);
        nextAction = nextAction == null ? "" : nextAction.trim();
    }

    public enum Status {
        DISABLED,
        BLOCKED,
        READY_FOR_REHEARSAL,
        READY_FOR_BOUNDED_FALLBACK
    }

    public record PlanSummary(
            String scenarioKey,
            String system,
            boolean enabled,
            boolean visibleForRequestedSystem,
            List<String> permittedPlatforms,
            boolean includesTrueSource) {

        public PlanSummary {
            scenarioKey = scenarioKey == null ? "" : scenarioKey.trim();
            system = system == null ? "" : system.trim();
            permittedPlatforms = List.copyOf(
                    permittedPlatforms == null ? List.of() : permittedPlatforms);
        }
    }
}
