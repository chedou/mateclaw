package vip.mate.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import vip.mate.channel.leader.ChannelLeaderElection;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.channel.model.ChannelSessionEntity;
import vip.mate.channel.service.ChannelService;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChannelManagerWorkspaceConversationTest {

    private ChannelService channelService;
    private ChannelSessionStore sessionStore;
    private ChannelMessageRouter messageRouter;
    private ChannelManager manager;

    @BeforeEach
    void setUp() {
        channelService = mock(ChannelService.class);
        sessionStore = mock(ChannelSessionStore.class);
        messageRouter = mock(ChannelMessageRouter.class);
        manager = new ChannelManager(
                channelService,
                messageRouter,
                sessionStore,
                new ObjectMapper(),
                mock(vip.mate.tool.document.GeneratedFileCache.class),
                mock(vip.mate.channel.notification.ApprovalNotificationService.class),
                mock(vip.mate.channel.wecom.cards.WeComCardDispatcher.class),
                mock(vip.mate.channel.wecom.WeComKeepaliveScheduler.class),
                mock(vip.mate.channel.feishu.FeishuMediaUploader.class),
                mock(vip.mate.channel.media.GeneratedFileScrubber.class),
                mock(vip.mate.channel.feishu.FeishuStreamingCardManager.class),
                mock(vip.mate.channel.feishu.cards.FeishuCardDispatcher.class),
                mock(vip.mate.channel.feishu.FeishuClientFactory.class),
                mock(vip.mate.stt.SttService.class),
                mock(ChannelLeaderElection.class),
                mock(vip.mate.workspace.core.service.ChatUploadLocationResolver.class));
    }

    @AfterEach
    void tearDown() {
        manager.destroy();
    }

    @Test
    void resolvesAnExactDeliveryConversationOnlyOnItsWorkspaceLeader() {
        ChannelSessionEntity session = session("wecom:99:group-1", 99L, "group-1");
        when(sessionStore.getSession("wecom:99:group-1")).thenReturn(session);
        when(channelService.getChannel(99L)).thenReturn(channel(99L, 7L));
        ChannelAdapter adapter = activeAdapter(99L);

        assertTrue(manager.canSendToWorkspaceConversation(
                7L, "wecom", "wecom:99:group-1"));
        manager.sendToWorkspaceConversation(
                7L, "wecom", "wecom:99:group-1", "investigation complete");

        verify(adapter).proactiveSend(
                eq("group-1"), eq("investigation complete"), any(DeliveryOptions.class));
    }

    @Test
    void forwardsDeliveryOptionsToTheResolvedWorkspaceRoute() {
        ChannelSessionEntity session = session("wecom:99:user-1", 99L, "user-1");
        when(sessionStore.getSession("wecom:99:user-1")).thenReturn(session);
        when(channelService.getChannel(99L)).thenReturn(channel(99L, 7L));
        ChannelAdapter adapter = activeAdapter(99L);
        DeliveryOptions options = DeliveryOptions.mentioningUsers(List.of("user-1"));

        manager.sendToWorkspaceConversation(
                7L,
                "wecom",
                "wecom:99:user-1",
                "incident closed",
                options);

        verify(adapter).proactiveSend(
                eq("user-1"), eq("incident closed"), same(options));
    }

    @Test
    void groupConversationRequiresTheAdaptersCurrentReplyContext() {
        ChannelSessionEntity session = session("wecom:99:group-1", 99L, "group-1");
        when(sessionStore.getSession("wecom:99:group-1")).thenReturn(session);
        when(channelService.getChannel(99L)).thenReturn(channel(99L, 7L));
        ChannelAdapter adapter = activeAdapter(99L);
        when(adapter.isProactiveDeliveryReady(anyString(), any(DeliveryOptions.class)))
                .thenReturn(false);

        assertFalse(manager.canSendToWorkspaceConversation(
                7L, "wecom", "wecom:99:group-1"));
        assertThrows(
                IllegalStateException.class,
                () -> manager.sendToWorkspaceConversation(
                        7L, "wecom", "wecom:99:group-1", "must stay durable"));

        verify(adapter, org.mockito.Mockito.never()).proactiveSend(
                anyString(), anyString(), any(DeliveryOptions.class));
    }

    @Test
    void groupConversationAddsReplyContextRequirementBeforeSending() {
        ChannelSessionEntity session = session("wecom:99:group-1", 99L, "group-1");
        when(sessionStore.getSession("wecom:99:group-1")).thenReturn(session);
        when(channelService.getChannel(99L)).thenReturn(channel(99L, 7L));
        ChannelAdapter adapter = activeAdapter(99L);

        manager.sendToWorkspaceConversation(
                7L, "wecom", "wecom:99:group-1", "incident closed");

        org.mockito.ArgumentCaptor<DeliveryOptions> options =
                org.mockito.ArgumentCaptor.forClass(DeliveryOptions.class);
        verify(adapter).proactiveSend(
                eq("group-1"), eq("incident closed"), options.capture());
        assertTrue(options.getValue().requiresReplyContext());
    }

    @Test
    void singleConversationDoesNotRequireAGroupReplyContext() {
        ChannelSessionEntity session = session("wecom:99:user-1", 99L, "user-1");
        session.setSenderId("user-1");
        when(sessionStore.getSession("wecom:99:user-1")).thenReturn(session);
        when(channelService.getChannel(99L)).thenReturn(channel(99L, 7L));
        ChannelAdapter adapter = activeAdapter(99L);

        manager.sendToWorkspaceConversation(
                7L, "wecom", "wecom:99:user-1", "incident closed");

        org.mockito.ArgumentCaptor<DeliveryOptions> options =
                org.mockito.ArgumentCaptor.forClass(DeliveryOptions.class);
        verify(adapter).proactiveSend(
                eq("user-1"), eq("incident closed"), options.capture());
        assertFalse(options.getValue().requiresReplyContext());
    }

    @Test
    void exactDeliveryConversationDoesNotFallBackToLegacyTargetScanning() {
        ChannelSessionEntity exact = session("wecom:99:group-1", 99L, "group-1");
        ChannelSessionEntity legacyCollision =
                session("wecom:100:other-group", 100L, "wecom:99:group-1");
        when(sessionStore.getSession("wecom:99:group-1")).thenReturn(exact);
        when(sessionStore.listByChannelType("wecom"))
                .thenReturn(List.of(exact, legacyCollision));
        when(channelService.getChannel(99L)).thenReturn(channel(99L, 7L));
        when(channelService.getChannel(100L)).thenReturn(channel(100L, 7L));
        activeAdapter(99L);
        activeAdapter(100L);

        assertTrue(manager.canSendToWorkspaceConversation(
                7L, "wecom", "wecom:99:group-1"));
    }

    @Test
    void resolvesALegacyRawConversationWithoutChangingItsIntakeIdentity() {
        ChannelSessionEntity session = session("wecom:99:group-1", 99L, "group-1");
        when(sessionStore.getSession("group-1")).thenReturn(null);
        when(sessionStore.listByChannelType("wecom")).thenReturn(List.of(session));
        when(channelService.getChannel(99L)).thenReturn(channel(99L, 7L));
        activeAdapter(99L);

        assertTrue(manager.canSendToWorkspaceConversation(7L, "wecom", "group-1"));
    }

    @Test
    void followerDoesNotAdvertiseAConversationItCannotDeliver() {
        ChannelSessionEntity session = session("wecom:99:group-1", 99L, "group-1");
        when(sessionStore.listByChannelType("wecom")).thenReturn(List.of(session));
        when(channelService.getChannel(99L)).thenReturn(channel(99L, 7L));

        assertFalse(manager.canSendToWorkspaceConversation(7L, "wecom", "group-1"));
    }

    @Test
    void workspaceMismatchFailsClosed() {
        ChannelSessionEntity session = session("wecom:99:group-1", 99L, "group-1");
        when(sessionStore.listByChannelType("wecom")).thenReturn(List.of(session));
        when(channelService.getChannel(99L)).thenReturn(channel(99L, 8L));
        activeAdapter(99L);

        assertFalse(manager.canSendToWorkspaceConversation(7L, "wecom", "group-1"));
    }

    @Test
    void ambiguousRawConversationFailsClosedInsteadOfPickingAChannel() {
        ChannelSessionEntity first = session("wecom:99:group-1", 99L, "group-1");
        ChannelSessionEntity second = session("wecom:100:group-1", 100L, "group-1");
        when(sessionStore.listByChannelType("wecom")).thenReturn(List.of(first, second));
        when(channelService.getChannel(99L)).thenReturn(channel(99L, 7L));
        when(channelService.getChannel(100L)).thenReturn(channel(100L, 7L));
        activeAdapter(99L);
        activeAdapter(100L);

        assertFalse(manager.canSendToWorkspaceConversation(7L, "wecom", "group-1"));
        assertThrows(
                IllegalStateException.class,
                () -> manager.sendToWorkspaceConversation(
                        7L, "wecom", "group-1", "must not leak"));
    }

    @SuppressWarnings("unchecked")
    private ChannelAdapter activeAdapter(Long channelId) {
        ChannelAdapter adapter = mock(ChannelAdapter.class);
        when(adapter.getChannelType()).thenReturn("wecom");
        when(adapter.getDisplayName()).thenReturn("test-wecom");
        when(adapter.isRunning()).thenReturn(true);
        when(adapter.supportsProactiveSend()).thenReturn(true);
        when(adapter.isProactiveDeliveryReady(anyString(), any(DeliveryOptions.class)))
                .thenReturn(true);
        Map<Long, ChannelAdapter> active =
                (Map<Long, ChannelAdapter>) ReflectionTestUtils.getField(manager, "activeAdapters");
        active.put(channelId, adapter);
        return adapter;
    }

    private ChannelEntity channel(Long id, Long workspaceId) {
        ChannelEntity channel = new ChannelEntity();
        channel.setId(id);
        channel.setWorkspaceId(workspaceId);
        channel.setChannelType("wecom");
        channel.setEnabled(true);
        return channel;
    }

    private ChannelSessionEntity session(String conversationId, Long channelId, String targetId) {
        ChannelSessionEntity session = new ChannelSessionEntity();
        session.setConversationId(conversationId);
        session.setChannelType("wecom");
        session.setChannelId(channelId);
        session.setTargetId(targetId);
        session.setSenderId("user-1");
        return session;
    }
}
