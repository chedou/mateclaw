package vip.mate.agent;

import vip.mate.agent.model.AgentEntity;
import vip.mate.exception.MateClawException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** Fail-closed policy shared by invocation-level hard-scoped Agent graphs. */
final class HardScopedAgentPolicy {

    private static final String FALLBACK_SYSTEM_PROMPT =
            "You are operating in an isolated, invocation-scoped Agent context.";

    private HardScopedAgentPolicy() {
    }

    static String systemPrompt(AgentEntity entity) {
        String identity = entity == null ? null : entity.getSystemPrompt();
        return identity == null || identity.isBlank()
                ? FALLBACK_SYSTEM_PROMPT
                : identity.trim();
    }

    static void validate(boolean providerNativeSearchEnabled, boolean planExecute) {
        if (providerNativeSearchEnabled) {
            throw new MateClawException(
                    "err.agent.hard_scope_native_search",
                    "Hard-scoped Agent model must have provider native search disabled");
        }
        if (planExecute) {
            throw new MateClawException(
                    "err.agent.hard_scope_react_only",
                    "Hard-scoped Agent invocation requires a ReAct Agent");
        }
    }

    static void requireConfiguredProvider(boolean configured, String reason) {
        if (!configured) {
            throw providerUnavailable(reason);
        }
    }

    static void requireSinglePrimaryModel(String configuredModelName, int matchCount) {
        if (configuredModelName == null || configuredModelName.isBlank()) {
            throw providerUnavailable("an explicit model is required");
        }
        if (matchCount == 0) {
            throw providerUnavailable(
                    "configured model is disabled, missing, or not available: "
                            + configuredModelName);
        }
        if (matchCount != 1) {
            throw providerUnavailable(
                    "configured model name is ambiguous across providers: "
                            + configuredModelName);
        }
    }

    /**
     * Requires every requested hard-scope runtime name to resolve exactly once.
     * An empty, missing, or unexpectedly broadened final set is a configuration
     * error and must fail before a model can be called.
     */
    static void requireExactToolSet(
            AgentToolSet toolSet,
            Set<String> requiredTools) {
        Set<String> actual = toolSet == null
                ? Set.of()
                : new LinkedHashSet<>(toolSet.callbackByName().keySet());
        Set<String> required = requiredTools == null
                ? Set.of()
                : new LinkedHashSet<>(requiredTools);
        if (required.isEmpty() || !actual.equals(required)) {
            throw new MateClawException(
                    "err.agent.hard_scope_tool_unavailable",
                    "Hard-scoped Agent required tools unavailable or ambiguous");
        }
    }

    static MateClawException providerUnavailable(String reason) {
        return new MateClawException(
                "err.agent.hard_scope_provider_unavailable",
                "Hard-scoped Agent primary provider is unavailable: " + reason);
    }

    static <T> List<T> fallbackChain(
            boolean hardScoped,
            Supplier<List<T>> normalChain) {
        return hardScoped ? List.of() : normalChain.get();
    }
}
