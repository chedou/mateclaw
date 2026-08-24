package vip.mate.troubleshooting.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import vip.mate.troubleshooting.service.ConversationIntakeService;
import vip.mate.troubleshooting.service.TroubleshootingChatTranscriptService;
import vip.mate.i18n.I18nService;

import java.util.List;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ConversationIntakeControllerTest {

    @Test
    void exposesTheLockedConversationModeForPageRestoration() throws Exception {
        ConversationIntakeService intake = mock(ConversationIntakeService.class);
        TroubleshootingChatTranscriptService transcripts = mock(TroubleshootingChatTranscriptService.class);
        when(intake.mode(7L, "alice", "conv-formal-awaiting"))
                .thenReturn(new ConversationIntakeService.ConversationModeResult(
                        "conv-formal-awaiting",
                        "intake-formal-awaiting",
                        "AWAITING_INPUT",
                        false));
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new ConversationIntakeController(intake, transcripts))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        new ObjectMapper().findAndRegisterModules()))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", "n/a", List.of()));
        try {
            mvc.perform(get("/api/v1/troubleshooting/conversation/mode")
                            .header("X-Workspace-Id", "7")
                            .param("conversationId", "conv-formal-awaiting"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.intakeSessionId")
                            .value("intake-formal-awaiting"))
                    .andExpect(jsonPath("$.data.rehearsal").value(false));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void createsTheSafePendingTranscriptBeforeDomainWorkCanFail() {
        ConversationIntakeService intake = mock(ConversationIntakeService.class);
        TroubleshootingChatTranscriptService transcripts = mock(TroubleshootingChatTranscriptService.class);
        when(intake.turn(7L, "alice", null, "turn-test-0003", "原始告警", true))
                .thenThrow(new vip.mate.exception.MateClawException(
                        "err.test", 409, "domain rejected"));
        ConversationIntakeController controller =
                new ConversationIntakeController(intake, transcripts);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", "n/a", List.of()));
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.turn(
                    new ConversationIntakeController.ConversationTurnRequest(
                            null, "turn-test-0003", "conv-chat-1",
                            "2083128519379722242", "原始告警", true),
                    7L)).isInstanceOf(vip.mate.exception.MateClawException.class);
        } finally {
            SecurityContextHolder.clearContext();
        }

        org.mockito.InOrder order = inOrder(transcripts, intake);
        order.verify(transcripts).begin(argThat(turn ->
                "turn-test-0003".equals(turn.clientTurnId())));
        order.verify(intake).turn(7L, "alice", null, "turn-test-0003", "原始告警", true);
        order.verify(transcripts).fail(argThat(turn ->
                "turn-test-0003".equals(turn.clientTurnId())));
        verifyNoMoreInteractions(transcripts);
    }

    @Test
    void persistsTheSafeTurnIntoTheOriginChatConversation() throws Exception {
        ConversationIntakeService intake = mock(ConversationIntakeService.class);
        TroubleshootingChatTranscriptService transcripts = mock(TroubleshootingChatTranscriptService.class);
        when(intake.turn(7L, "alice", null, "turn-test-0001", "ITGW 访问失败", true))
                .thenReturn(new ConversationIntakeService.ConversationTurnResult(
                        "web-conv-1", "intake-1", "READY", List.of(),
                        "当前定位到：ITGW 策略拦截。", false, false,
                        "diag-1", true, true,
                        "排障告警（已规范化）\n系统：CSDP\n服务：csdp-wechat"));
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new ConversationIntakeController(intake, transcripts))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        new ObjectMapper().findAndRegisterModules()))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", "n/a", List.of()));
        try {
            mvc.perform(post("/api/v1/troubleshooting/conversation/turns")
                            .header("X-Workspace-Id", "7")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"text":"ITGW 访问失败","rehearsal":true,
                                     "clientTurnId":"turn-test-0001",
                                     "chatConversationId":"conv-chat-1",
                                     "agentId":"2083128519379722242"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.diagnosisId").value("diag-1"))
                    .andExpect(jsonPath("$.data.transcriptUserMessage").doesNotExist());
        } finally {
            SecurityContextHolder.clearContext();
        }

        org.mockito.InOrder order = inOrder(transcripts, intake);
        order.verify(transcripts).begin(argThat(turn ->
                turn.workspaceId() == 7L
                        && "turn-test-0001".equals(turn.clientTurnId())
                        && "conv-chat-1".equals(turn.chatConversationId())
                        && turn.agentId() == 2083128519379722242L));
        order.verify(intake).turn(
                7L, "alice", null, "turn-test-0001", "ITGW 访问失败", true);
        order.verify(transcripts).persist(argThat(turn ->
                turn.workspaceId() == 7L
                        && "conv-chat-1".equals(turn.chatConversationId())
                        && turn.agentId() == 2083128519379722242L
                        && "alice".equals(turn.actorRef())
                        && "diag-1".equals(turn.diagnosisId())
                        && turn.userContent().contains("已规范化")));
    }

    @Test
    void rejectsAnIncompleteTranscriptTargetBeforeCreatingADiagnosis() throws Exception {
        ConversationIntakeService intake = mock(ConversationIntakeService.class);
        TroubleshootingChatTranscriptService transcripts = mock(TroubleshootingChatTranscriptService.class);
        I18nService i18n = mock(I18nService.class);
        when(i18n.msg(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new ConversationIntakeController(intake, transcripts))
                .setControllerAdvice(new vip.mate.exception.GlobalExceptionHandler(i18n))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        new ObjectMapper().findAndRegisterModules()))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", "n/a", List.of()));
        try {
            mvc.perform(post("/api/v1/troubleshooting/conversation/turns")
                            .header("X-Workspace-Id", "7")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"text":"ITGW 访问失败","rehearsal":true,
                                     "chatConversationId":"conv-chat-1"}
                                    """))
                    .andExpect(status().isBadRequest());
        } finally {
            SecurityContextHolder.clearContext();
        }
        verifyNoInteractions(intake, transcripts);
    }
}
