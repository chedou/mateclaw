package vip.mate.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import vip.mate.agent.model.AgentEntity;
import vip.mate.exception.MateClawException;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HardScopedAgentPolicyTest {

    @Test
    void usesOnlyTheAgentIdentityWithoutAmbientPromptBlocks() {
        AgentEntity entity = new AgentEntity();
        entity.setSystemPrompt("  read-only triage identity  ");

        assertThat(HardScopedAgentPolicy.systemPrompt(entity))
                .isEqualTo("read-only triage identity");
    }

    @Test
    void rejectsProviderNativeSearchAndPlanExecute() {
        assertThatThrownBy(() -> HardScopedAgentPolicy.validate(true, false))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("native search");
        assertThatThrownBy(() -> HardScopedAgentPolicy.validate(false, true))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("ReAct");
    }

    @Test
    void disablesProviderFailoverWithoutEvenResolvingTheChain() {
        AtomicBoolean resolved = new AtomicBoolean();

        List<String> chain = HardScopedAgentPolicy.fallbackChain(true, () -> {
            resolved.set(true);
            return List.of("fallback-provider");
        });

        assertThat(chain).isEmpty();
        assertThat(resolved).isFalse();
    }

    @Test
    void rejectsAnUnavailablePrimaryProviderInsteadOfSelectingAnotherOne() {
        assertThatThrownBy(() ->
                HardScopedAgentPolicy.requireConfiguredProvider(false, "missing API key"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("missing API key");

        HardScopedAgentPolicy.requireConfiguredProvider(true, "unused");
    }

    @Test
    void requiresOneExplicitUnambiguousPrimaryModel() {
        assertThatThrownBy(() -> HardScopedAgentPolicy.requireSinglePrimaryModel("", 0))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("explicit model");
        assertThatThrownBy(() -> HardScopedAgentPolicy.requireSinglePrimaryModel("qwen", 0))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("not available");
        assertThatThrownBy(() -> HardScopedAgentPolicy.requireSinglePrimaryModel("qwen", 2))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("ambiguous");

        HardScopedAgentPolicy.requireSinglePrimaryModel("qwen", 1);
    }

    @Test
    void requiresTheFinalHardScopedToolSetToMatchExactly() {
        ToolCallback evidenceTool = callback("collect_troubleshooting_evidence");
        AgentToolSet exact = AgentToolSet.fromCallbacks(
                List.of(), List.of(evidenceTool));

        HardScopedAgentPolicy.requireExactToolSet(
                exact, Set.of("collect_troubleshooting_evidence"));

        assertThatThrownBy(() -> HardScopedAgentPolicy.requireExactToolSet(
                AgentToolSet.fromCallbacks(List.of(), List.of()),
                Set.of("collect_troubleshooting_evidence")))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("required tools unavailable");
        assertThatThrownBy(() -> HardScopedAgentPolicy.requireExactToolSet(
                exact, Set.of("other_tool")))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("required tools unavailable");
    }

    private static ToolCallback callback(String name) {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        when(callback.getToolDefinition()).thenReturn(definition);
        return callback;
    }
}
