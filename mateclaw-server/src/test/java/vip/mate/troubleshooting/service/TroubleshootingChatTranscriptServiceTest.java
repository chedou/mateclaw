package vip.mate.troubleshooting.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.troubleshooting.followup.DiagnosisFollowUpIntent;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.MessageEntity;
import vip.mate.troubleshooting.repository.TroubleshootingChatTurnMapper;
import vip.mate.troubleshooting.model.TroubleshootingChatTurnEntity;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TroubleshootingChatTranscriptServiceTest {

    @Mock private ConversationService conversations;
    @Mock private TroubleshootingChatTurnMapper turns;

    @Test
    void persistsOneSafeUserAssistantPairInTheExistingConversationTables() {
        TroubleshootingChatTranscriptService service =
                new TroubleshootingChatTranscriptService(conversations, new ObjectMapper(), turns);
        MessageEntity user = new MessageEntity();
        user.setId(11L);
        user.setConversationId("conv-chat-1");
        user.setRole("user");
        user.setStatus("completed");
        MessageEntity assistant = new MessageEntity();
        assistant.setId(12L);
        assistant.setConversationId("conv-chat-1");
        assistant.setRole("assistant");
        assistant.setStatus("generating");
        org.mockito.Mockito.when(conversations.saveMessage(
                eq("conv-chat-1"), eq("user"), eq("排障请求已提交（原文未保存）"),
                anyList(), eq("completed"),
                eq(0), eq(0), isNull(), isNull(), any())).thenReturn(user);
        org.mockito.Mockito.when(conversations.saveMessage(
                eq("conv-chat-1"), eq("assistant"), any(), anyList(), eq("generating"),
                eq(0), eq(0), isNull(), isNull(), any())).thenReturn(assistant);
        AtomicReference<TroubleshootingChatTurnEntity> stored = new AtomicReference<>();
        org.mockito.Mockito.when(turns.insert(any(TroubleshootingChatTurnEntity.class)))
                .thenAnswer(invocation -> {
                    stored.set(invocation.getArgument(0));
                    return 1;
                });
        org.mockito.Mockito.when(turns.findForUpdate(7L, "conv-chat-1", "turn-00000001"))
                .thenAnswer(invocation -> stored.get());
        org.mockito.Mockito.when(conversations.getMessage(11L)).thenReturn(user);
        org.mockito.Mockito.when(conversations.getMessage(12L)).thenReturn(assistant);
        org.mockito.Mockito.doAnswer(invocation -> {
            long id = invocation.getArgument(0);
            MessageEntity target = id == 11L ? user : assistant;
            target.setContent(invocation.getArgument(4));
            target.setStatus(invocation.getArgument(6));
            target.setMetadata(invocation.getArgument(7));
            return null;
        }).when(conversations).replaceTroubleshootingMessage(
                any(Long.class), any(), any(), anyList(), any(), anyList(), any(), any());

        var turn = new TroubleshootingChatTranscriptService.TranscriptTurn(
                7L,
                "turn-00000001",
                "conv-chat-1",
                2083128519379722242L,
                "alice",
                "排障告警（已规范化）\n系统：CSDP\n服务：csdp-wechat",
                "当前定位到：ITGW 策略拦截。",
                "web-conversation-1",
                "intake-1",
                "diag-1",
                null,
                null);
        service.persist(turn);

        verify(conversations).getOrCreateConversation(
                "conv-chat-1", 2083128519379722242L, "alice", 7L);
        verify(conversations).replaceTroubleshootingMessage(
                eq(11L), eq("conv-chat-1"), eq("user"), eq(List.of("completed")),
                eq("排障告警（已规范化）\n系统：CSDP\n服务：csdp-wechat"),
                anyList(), eq("completed"), org.mockito.ArgumentMatchers.contains(
                        "\"diagnosisId\":\"diag-1\""));
        verify(conversations).replaceTroubleshootingMessage(
                eq(12L), eq("conv-chat-1"), eq("assistant"),
                eq(List.of("generating", "failed")), eq("当前定位到：ITGW 策略拦截。"),
                anyList(), eq("completed"), org.mockito.ArgumentMatchers.contains(
                        "\"intakeSessionId\":\"intake-1\""));

        ArgumentCaptor<TroubleshootingChatTurnEntity> ledger =
                ArgumentCaptor.forClass(TroubleshootingChatTurnEntity.class);
        verify(turns).insert(ledger.capture());
        service.persist(turn);

        verify(conversations, org.mockito.Mockito.times(2)).saveMessage(
                eq("conv-chat-1"), any(), any(), anyList(), any(),
                eq(0), eq(0), isNull(), isNull(), any());
        verify(conversations, org.mockito.Mockito.times(2)).replaceTroubleshootingMessage(
                any(Long.class), any(), any(), anyList(), any(), anyList(), eq("completed"), any());
    }

    @Test
    void marksAnInterruptedPendingTurnRetryableWithoutLosingItsTurnId() {
        TroubleshootingChatTranscriptService service =
                new TroubleshootingChatTranscriptService(conversations, new ObjectMapper(), turns);
        var pending = new TroubleshootingChatTranscriptService.PendingTurn(
                7L, "turn-retry-0001", "conv-chat-1", 2083128519379722242L, "alice");
        TroubleshootingChatTurnEntity row = new TroubleshootingChatTurnEntity();
        row.setWorkspaceId(7L);
        row.setConversationId("conv-chat-1");
        row.setClientTurnId("turn-retry-0001");
        row.setAgentId(2083128519379722242L);
        row.setUserMessageId(21L);
        row.setAssistantMessageId(22L);
        MessageEntity user = new MessageEntity();
        user.setConversationId("conv-chat-1");
        user.setRole("user");
        user.setStatus("completed");
        MessageEntity assistant = new MessageEntity();
        assistant.setConversationId("conv-chat-1");
        assistant.setRole("assistant");
        assistant.setStatus("generating");
        org.mockito.Mockito.when(turns.findForUpdate(7L, "conv-chat-1", "turn-retry-0001"))
                .thenReturn(row);
        org.mockito.Mockito.when(conversations.getMessage(21L)).thenReturn(user);
        org.mockito.Mockito.when(conversations.getMessage(22L)).thenReturn(assistant);

        service.fail(pending);

        verify(conversations).replaceTroubleshootingMessage(
                eq(21L), eq("conv-chat-1"), eq("user"), eq(List.of("completed")),
                eq("排障请求已提交（原文未保存）"), anyList(), eq("completed"),
                org.mockito.ArgumentMatchers.contains("\"transcriptStatus\":\"FAILED_RETRYABLE\""));
        verify(conversations).replaceTroubleshootingMessage(
                eq(22L), eq("conv-chat-1"), eq("assistant"),
                eq(List.of("generating", "failed")),
                org.mockito.ArgumentMatchers.contains("请重新发送上一条问题"),
                anyList(), eq("failed"),
                org.mockito.ArgumentMatchers.contains("\"clientTurnId\":\"turn-retry-0001\""));
    }

    @Test
    void normalizesDuplicateDiagnosisPromptsToTheWinningTranscriptPayload() {
        var created = new TroubleshootingChatTranscriptService.TranscriptTurn(
                7L, "turn-test-0004", "conv-chat-1", 2083128519379722242L, "alice",
                "排障告警（已规范化）", "当前定位到：策略拦截。",
                "web-conv-1", "intake-1", "diag-1", null, null);
        var duplicate = new TroubleshootingChatTranscriptService.TranscriptTurn(
                7L, "turn-test-0004", "conv-chat-1", 2083128519379722242L, "alice",
                "排障告警（已规范化）", "已汇合到既有排障单。\n当前定位到：策略拦截。",
                "web-conv-1", "intake-1", "diag-1", null, null);

        assertThat(duplicate.assistantContent()).isEqualTo(created.assistantContent());
    }

    @Test
    void neverCopiesSupplementalEvidenceBodiesIntoChatHistory() {
        TroubleshootingChatTranscriptService service =
                new TroubleshootingChatTranscriptService(conversations, new ObjectMapper(), turns);

        String safe = service.safeFollowUpQuestion(
                DiagnosisFollowUpIntent.SUPPLEMENTAL_EVIDENCE,
                "补充证据：工单 T2026081000378，Authorization=secret，联系人张三");

        assertThat(safe)
                .isEqualTo("补充证据：已提交一条待验证事实摘要（原文未保存）")
                .doesNotContain("T2026081000378", "secret", "张三");

        assertThat(service.safeFollowUpQuestion(
                DiagnosisFollowUpIntent.WHY,
                "为什么工单 T2026081000378 张三的原始日志是这个原因？"))
                .isEqualTo("追问：为什么是这个原因")
                .doesNotContain("T2026081000378", "张三", "原始日志");
    }
}
