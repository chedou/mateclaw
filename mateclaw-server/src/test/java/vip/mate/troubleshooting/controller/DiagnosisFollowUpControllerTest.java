package vip.mate.troubleshooting.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import vip.mate.troubleshooting.followup.DiagnosisFollowUpIntent;
import vip.mate.troubleshooting.followup.DiagnosisFollowUpResult;
import vip.mate.troubleshooting.followup.DiagnosisFollowUpService;
import vip.mate.troubleshooting.followup.DiagnosisFollowUpStatus;
import vip.mate.troubleshooting.model.ConclusionType;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.EvidenceBasis;
import vip.mate.troubleshooting.service.TroubleshootingChatTranscriptService;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DiagnosisFollowUpControllerTest {

    @Test
    void bindsTheQuestionToThePathDiagnosisAndAuthenticatedActor() throws Exception {
        DiagnosisFollowUpService service = mock(DiagnosisFollowUpService.class);
        TroubleshootingChatTranscriptService transcripts = mock(TroubleshootingChatTranscriptService.class);
        when(service.respond(7L, "diag-1", "turn-test-0002", "为什么是这个原因？", "alice"))
                .thenReturn(new DiagnosisFollowUpResult(
                        "diag-1",
                        DiagnosisFollowUpStatus.ACTIVE,
                        DiagnosisFollowUpIntent.WHY,
                        ConclusionType.HYPOTHESIS,
                        EvidenceBasis.OBSERVED,
                        false,
                        "当前结论来自已记录证据。",
                        null));
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new DiagnosisFollowUpController(service, transcripts))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        new ObjectMapper().findAndRegisterModules()))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", "n/a", List.of()));
        try {
            mvc.perform(post("/api/v1/troubleshooting/diagnoses/diag-1/follow-ups")
                            .header("X-Workspace-Id", "7")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"为什么是这个原因？\","
                                    + "\"clientTurnId\":\"turn-test-0002\","
                                    + "\"chatConversationId\":\"conv-chat-1\","
                                    + "\"agentId\":\"2083128519379722242\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.diagnosisId").value("diag-1"))
                    .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.data.intent").value("WHY"))
                    .andExpect(jsonPath("$.data.conclusionType").value("HYPOTHESIS"))
                    .andExpect(jsonPath("$.data.evidenceBasis").value("OBSERVED"))
                    .andExpect(jsonPath("$.data.fixtureMode").value(false));
        } finally {
            SecurityContextHolder.clearContext();
        }
        org.mockito.InOrder order = inOrder(transcripts, service);
        order.verify(transcripts).begin(org.mockito.ArgumentMatchers.argThat(turn ->
                turn.workspaceId() == 7L
                        && "turn-test-0002".equals(turn.clientTurnId())
                        && "conv-chat-1".equals(turn.chatConversationId())));
        order.verify(service).respond(
                7L, "diag-1", "turn-test-0002", "为什么是这个原因？", "alice");
        order.verify(transcripts).persistFollowUp(
                7L, "turn-test-0002", "conv-chat-1", 2083128519379722242L, "alice",
                "为什么是这个原因？",
                new DiagnosisFollowUpResult(
                        "diag-1", DiagnosisFollowUpStatus.ACTIVE, DiagnosisFollowUpIntent.WHY,
                        ConclusionType.HYPOTHESIS, EvidenceBasis.OBSERVED, false,
                        "当前结论来自已记录证据。", null));
    }

    @Test
    void requiresMemberRoleForWritingAFollowUp() throws Exception {
        RequireWorkspaceRole role = DiagnosisFollowUpController.class
                .getDeclaredMethod(
                        "followUp",
                        String.class,
                        DiagnosisFollowUpController.FollowUpRequest.class,
                        Long.class)
                .getAnnotation(RequireWorkspaceRole.class);
        assertThat(role.value()).isEqualTo("member");
    }
}
