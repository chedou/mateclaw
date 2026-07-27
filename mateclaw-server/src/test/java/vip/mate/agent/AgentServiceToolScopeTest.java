package vip.mate.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.memory.MemoryProperties;
import vip.mate.memory.identity.MemoryOwnerResolver;
import vip.mate.memory.lifecycle.MemoryLifecycleMediator;
import vip.mate.memory.service.MemoryRecallTracker;
import vip.mate.llm.chatmodel.ThinkingLevelHolder;
import vip.mate.workspace.conversation.repository.ConversationMapper;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentServiceToolScopeTest {

    private static final long AGENT_ID = 42L;
    private static final Set<String> READ_ONLY_SCOPE =
            Set.of("collect_troubleshooting_evidence");

    @Mock private AgentMapper agentMapper;
    @Mock private AgentGraphBuilder graphBuilder;
    @Mock private MemoryRecallTracker recallTracker;
    @Mock private MemoryLifecycleMediator lifecycleMediator;
    @Mock private ConversationMapper conversationMapper;
    @Mock private BaseAgent normalAgent;
    @Mock private BaseAgent scopedAgent;

    private MemoryProperties memoryProperties;
    private AgentService service;

    @BeforeEach
    void setUp() {
        memoryProperties = new MemoryProperties();
        memoryProperties.setLifecycleMediatorEnabled(false);
        service = new AgentService(
                agentMapper,
                graphBuilder,
                recallTracker,
                lifecycleMediator,
                memoryProperties,
                new MemoryOwnerResolver(),
                conversationMapper);

        AgentEntity entity = new AgentEntity();
        entity.setId(AGENT_ID);
        entity.setEnabled(true);
        when(agentMapper.selectById(AGENT_ID)).thenReturn(entity);
        when(graphBuilder.build(
                any(AgentEntity.class), isNull(), isNull(), eq(READ_ONLY_SCOPE)))
                .thenReturn(scopedAgent);
        when(scopedAgent.chat(any(), any())).thenReturn("scoped");
    }

    @Test
    void hardToolScopeUsesAnIsolatedGraphCacheEntry() {
        when(graphBuilder.build(any(AgentEntity.class), isNull(), isNull()))
                .thenReturn(normalAgent);
        when(normalAgent.chat(any(), any())).thenReturn("normal");

        assertThat(service.chat(AGENT_ID, "hello", "conv-1")).isEqualTo("normal");
        memoryProperties.setLifecycleMediatorEnabled(true);

        ChatOrigin origin = ChatOrigin.web("triage-1", "troubleshooting", 7L, null);
        assertThat(service.chatWithToolAllowlist(
                AGENT_ID, "triage", "triage-1", origin, READ_ONLY_SCOPE))
                .isEqualTo("scoped");
        assertThat(service.chatWithToolAllowlist(
                AGENT_ID, "triage again", "triage-1", origin, READ_ONLY_SCOPE))
                .isEqualTo("scoped");

        verify(graphBuilder).build(any(AgentEntity.class), isNull(), isNull());
        verify(graphBuilder, times(1)).build(
                any(AgentEntity.class), isNull(), isNull(), eq(READ_ONLY_SCOPE));
        verify(recallTracker, times(1)).trackRecalls(AGENT_ID, "hello");
        verify(conversationMapper, times(1)).selectOne(any());
        verifyNoInteractions(lifecycleMediator);
    }

    @Test
    void hardToolScopeClearsAndRestoresAmbientThinkingLevel() {
        when(scopedAgent.chat("triage", "triage-1")).thenAnswer(invocation -> {
            assertThat(ThinkingLevelHolder.get()).isNull();
            return "scoped";
        });
        ThinkingLevelHolder.set("high");
        try {
            assertThat(service.chatWithToolAllowlist(
                    AGENT_ID,
                    "triage",
                    "triage-1",
                    ChatOrigin.web("triage-1", "troubleshooting", 7L, null),
                    READ_ONLY_SCOPE))
                    .isEqualTo("scoped");
            assertThat(ThinkingLevelHolder.get()).isEqualTo("high");
        } finally {
            ThinkingLevelHolder.clear();
        }
    }
}
