package vip.mate.agent.graph;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import vip.mate.agent.AgentToolSet;
import vip.mate.llm.chatmodel.ThinkingLevelHolder;
import vip.mate.workspace.conversation.ConversationService;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static vip.mate.agent.graph.state.MateClawStateKeys.MESSAGES;
import static vip.mate.agent.graph.state.MateClawStateKeys.MAX_ITERATIONS;
import static vip.mate.agent.graph.state.MateClawStateKeys.WORKSPACE_BASE_PATH;

class StateGraphReActAgentIsolationTest {

    @Test
    @SuppressWarnings("unchecked")
    void isolatedInvocationSkipsHistoryMediaAndWorkspacePath() throws Exception {
        ConversationService conversations = mock(ConversationService.class);
        IsolatedAgent agent = new IsolatedAgent(conversations);

        Method buildInitialState = StateGraphReActAgent.class.getDeclaredMethod(
                "buildInitialState", String.class, String.class);
        buildInitialState.setAccessible(true);
        Map<String, Object> state = (Map<String, Object>) buildInitialState.invoke(
                agent, "single scoped request", "triage-1");

        assertThat((List<Message>) state.get(MESSAGES))
                .singleElement()
                .isInstanceOf(UserMessage.class)
                .extracting(Message::getText)
                .isEqualTo("single scoped request");
        assertThat(state.get(WORKSPACE_BASE_PATH)).isEqualTo("");
        verifyNoInteractions(conversations);
    }

    @Test
    @SuppressWarnings("unchecked")
    void isolatedInvocationIgnoresAmbientThinkingIterationOverride() throws Exception {
        IsolatedAgent agent = new IsolatedAgent(mock(ConversationService.class));
        Method buildInitialState = StateGraphReActAgent.class.getDeclaredMethod(
                "buildInitialState", String.class, String.class);
        buildInitialState.setAccessible(true);

        ThinkingLevelHolder.set("high");
        try {
            Map<String, Object> state = (Map<String, Object>) buildInitialState.invoke(
                    agent, "single scoped request", "triage-1");
            assertThat(state.get(MAX_ITERATIONS)).isEqualTo(7);
        } finally {
            ThinkingLevelHolder.clear();
        }
    }

    private static final class IsolatedAgent extends StateGraphReActAgent {
        private IsolatedAgent(ConversationService conversations) {
            super(null, conversations, null, null, null,
                    AgentToolSet.fromCallbacks(List.of(), List.of()));
            isolatedInvocation = true;
            systemPrompt = "scoped identity";
            workspaceBasePath = "/workspace/secret";
            agentId = "42";
            maxIterations = 7;
        }
    }
}
