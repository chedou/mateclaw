package vip.mate.troubleshooting.intake;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.channel.ChannelAdapter;
import vip.mate.channel.ChannelMessage;
import vip.mate.channel.ChannelMessagePreRouteDeliveryException;
import vip.mate.channel.model.ChannelEntity;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
class WeComTroubleshootingIntakeHandlerTest {

    @Mock private TroubleshootingIntakeSessionService intakeService;
    @Mock private ChannelAdapter adapter;

    private WeComTroubleshootingIntakeHandler handler;

    @BeforeEach
    void setUp() {
        handler = new WeComTroubleshootingIntakeHandler(
                intakeService,
                new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void onlyClaimsExplicitlyEnabledWeComChannels() {
        ChannelMessage message = message();
        ChannelEntity disabled = channel("{}");
        ChannelEntity enabled = channel("{\"troubleshooting_intake_enabled\":true}");
        when(adapter.getChannelType()).thenReturn("wecom", "wecom", "feishu");

        assertFalse(handler.supports(message, adapter, disabled));
        assertTrue(handler.supports(message, adapter, enabled));
        assertFalse(handler.supports(message, adapter, enabled));
    }

    @Test
    void persistsTheEnvelopeAndRepliesInsideTheExistingChannelPath() {
        ChannelMessage message = message();
        ChannelEntity enabled = channel("{\"troubleshooting_intake_enabled\":true}");
        when(intakeService.accept(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new IntakeDecision(
                        "intake-1",
                        IntakeSessionStatus.AWAITING_INPUT,
                        List.of("system"),
                        "已收到报障，还需要：系统",
                        false,
                        false));

        handler.handle(message, adapter, enabled);

        verify(intakeService).accept(org.mockito.ArgumentMatchers.argThat(envelope ->
                envelope.workspaceId() == 7L
                        && envelope.source().equals("wecom")
                        && envelope.sourceMessageId().equals("msg-1")
                        && envelope.conversationRef().equals("group-1")
                        && envelope.reporterRef().equals("user-1")));
        verify(adapter).renderAndSend(org.mockito.ArgumentMatchers.eq("group-1"), contains("还需要"));
    }

    @Test
    void reportsDeliveryFailureWithoutReclassifyingThePersistedIntakeAsLost() {
        ChannelMessage message = message();
        ChannelEntity enabled = channel("{\"troubleshooting_intake_enabled\":true}");
        when(intakeService.accept(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new IntakeDecision(
                        "intake-1",
                        IntakeSessionStatus.AWAITING_INPUT,
                        List.of("system"),
                        "已收到报障，还需要：系统",
                        false,
                        false));
        doThrow(new IllegalStateException("channel unavailable"))
                .when(adapter).renderAndSend("group-1", "已收到报障，还需要：系统");

        ChannelMessagePreRouteDeliveryException error = assertThrows(
                ChannelMessagePreRouteDeliveryException.class,
                () -> handler.handle(message, adapter, enabled));

        assertTrue(error.getMessage().contains("persisted"));
        verify(intakeService).accept(org.mockito.ArgumentMatchers.any());
    }

    private ChannelMessage message() {
        return ChannelMessage.builder()
                .messageId("msg-1")
                .channelType("wecom")
                .senderId("user-1")
                .chatId("group-1")
                .replyToken("group-1")
                .content("会话消息发送失败")
                .contentParts(List.of())
                .timestamp(LocalDateTime.of(2026, 7, 29, 10, 0))
                .build();
    }

    private ChannelEntity channel(String configJson) {
        ChannelEntity entity = new ChannelEntity();
        entity.setId(99L);
        entity.setWorkspaceId(7L);
        entity.setChannelType("wecom");
        entity.setConfigJson(configJson);
        entity.setEnabled(true);
        return entity;
    }
}
