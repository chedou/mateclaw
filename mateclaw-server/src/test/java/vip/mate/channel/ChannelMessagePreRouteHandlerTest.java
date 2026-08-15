package vip.mate.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import vip.mate.agent.AgentService;
import vip.mate.approval.ApprovalWorkflowService;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.channel.notification.ApprovalNotificationService;
import vip.mate.channel.service.ChannelService;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.memory.event.ConversationCompletionPublisher;
import vip.mate.tts.TtsService;
import vip.mate.workspace.conversation.ConversationService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChannelMessagePreRouteHandlerTest {

    @Mock private AgentService agentService;
    @Mock private ConversationService conversationService;
    @Mock private ChannelService channelService;
    @Mock private ChannelSessionStore channelSessionStore;
    @Mock private ApprovalWorkflowService approvalService;
    @Mock private ApprovalNotificationService approvalNotificationService;
    @Mock private ConversationCompletionPublisher completionPublisher;
    @Mock private TtsService ttsService;
    @Mock private ChatStreamTracker streamTracker;
    @Mock private ChannelChatOriginFactory chatOriginFactory;
    @Mock private ChannelErrorClassifier errorClassifier;
    @Mock private ChannelAdapter adapter;
    @Mock private ChannelMessagePreRouteHandler handler;

    private final InboundMessageDeduplicator inboundMessageDeduplicator =
            new InboundMessageDeduplicator(new ChannelDedupProperties());
    private ChannelMessageRouter router;
    private ChannelMessage message;
    private ChannelEntity channel;

    @BeforeEach
    void setUp() {
        router = new ChannelMessageRouter(
                agentService,
                conversationService,
                channelService,
                channelSessionStore,
                approvalService,
                approvalNotificationService,
                completionPublisher,
                ttsService,
                new ObjectMapper(),
                streamTracker,
                chatOriginFactory,
                errorClassifier,
                inboundMessageDeduplicator);
        ReflectionTestUtils.setField(router, "preRouteHandlers", List.of(handler));
        message = ChannelMessage.builder()
                .messageId("msg-1")
                .channelType("wecom")
                .senderId("user-1")
                .senderName("User One")
                .chatId("group-1")
                .replyToken("group-1")
                .content("报障")
                .build();
        channel = new ChannelEntity();
        channel.setId(42L);
        channel.setWorkspaceId(7L);
        channel.setEnabled(true);
    }

    @Test
    void claimedMessageStopsAtTheDomainHandler() {
        when(handler.supports(message, adapter, channel)).thenReturn(true);

        assertTrue(router.dispatchPreRouteHandler(message, adapter, channel));

        verify(handler).handle(message, adapter, channel);
        verify(adapter, never()).renderAndSend(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void claimedHandlerFailureIsAcknowledgedAndStillFailsClosed() {
        when(adapter.getChannelType()).thenReturn("wecom");
        when(handler.supports(message, adapter, channel)).thenReturn(true);
        doThrow(new IllegalStateException("db unavailable"))
                .when(handler).handle(message, adapter, channel);

        assertTrue(router.dispatchPreRouteHandler(message, adapter, channel));

        verify(adapter).renderAndSend(
                org.mockito.ArgumentMatchers.eq("group-1"),
                contains("未启动 Agent"));
    }

    @Test
    void persistedIntakeWithReplyFailureDoesNotClaimThatPersistenceFailed() {
        when(adapter.getChannelType()).thenReturn("wecom");
        when(handler.supports(message, adapter, channel)).thenReturn(true);
        doThrow(new ChannelMessagePreRouteDeliveryException(
                "intake persisted but acknowledgement failed"))
                .when(handler).handle(message, adapter, channel);

        assertTrue(router.dispatchPreRouteHandler(message, adapter, channel));

        verify(adapter, never()).renderAndSend(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void unclaimedMessageFallsThroughToTheExistingRouter() {
        when(handler.supports(message, adapter, channel)).thenReturn(false);

        assertFalse(router.dispatchPreRouteHandler(message, adapter, channel));

        verify(handler, never()).handle(message, adapter, channel);
    }

    @Test
    void claimedMessageStillPersistsItsOriginalChannelRoute() {
        when(channelService.getChannel(42L)).thenReturn(channel);
        when(adapter.getChannelType()).thenReturn("wecom");
        when(handler.supports(message, adapter, channel)).thenReturn(true);

        router.enqueue(message, adapter, channel);

        verify(channelSessionStore).saveOrUpdate(
                "wecom:42:group-1",
                "wecom",
                "group-1",
                "user-1",
                "User One",
                42L);
        verify(handler).handle(message, adapter, channel);
    }
}
