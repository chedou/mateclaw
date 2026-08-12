package vip.mate.troubleshooting.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.troubleshooting.intake.IntakeDecision;
import vip.mate.troubleshooting.intake.IntakeMessageEnvelope;
import vip.mate.troubleshooting.intake.IntakeSession;
import vip.mate.troubleshooting.intake.IntakeSessionReducer;
import vip.mate.troubleshooting.intake.IntakeSessionStatus;
import vip.mate.troubleshooting.intake.TroubleshootingChannelSummaryRenderer;
import vip.mate.troubleshooting.intake.TroubleshootingIntakeSessionService;
import vip.mate.troubleshooting.intake.TroubleshootingIntakeSources;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.BusinessSummary;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjectionService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationIntakeServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-12T14:00:00Z");

    @Mock private TroubleshootingIntakeSessionService sessions;
    @Mock private TroubleshootingIntakeService intakeService;
    @Mock private DiagnosisExperienceProjectionService projectionService;
    @Mock private TroubleshootingChannelSummaryRenderer summaryRenderer;

    private ConversationIntakeService service;

    @BeforeEach
    void setUp() {
        service = new ConversationIntakeService(
                sessions,
                intakeService,
                projectionService,
                summaryRenderer,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("不完整对话先补问，不立刻建 Diagnosis")
    void incompleteTurnStaysInAwaitingInput() {
        IntakeSession awaiting = new IntakeSessionReducer().start(
                "intake-1",
                new IntakeMessageEnvelope(
                        1L,
                        TroubleshootingIntakeSources.WEB_CONVERSATION,
                        "msg-1",
                        "conv-1",
                        "admin",
                        "消息发不出去了",
                        List.of(),
                        NOW));
        when(sessions.accept(any())).thenReturn(IntakeDecision.from(awaiting, false, false));

        ConversationIntakeService.ConversationTurnResult result = service.turn(
                1L, "admin", "conv-1", "消息发不出去了", true);

        assertThat(result.status()).isEqualTo(IntakeSessionStatus.AWAITING_INPUT.name());
        assertThat(result.diagnosisId()).isNull();
        assertThat(result.rehearsal()).isTrue();
        assertThat(result.prompt()).contains("还需要");
        ArgumentCaptor<IntakeMessageEnvelope> envelope = ArgumentCaptor.forClass(IntakeMessageEnvelope.class);
        verify(sessions).accept(envelope.capture());
        assertThat(envelope.getValue().source()).isEqualTo(TroubleshootingIntakeSources.WEB_CONVERSATION);
        assertThat(envelope.getValue().conversationRef()).isEqualTo("conv-1");
    }

    @Test
    @DisplayName("资料齐后同步建单，并在对话里回写业务结论摘要")
    void readyTurnReturnsChannelSummaryInPrompt() {
        IntakeSession ready = new IntakeSessionReducer().accept(
                new IntakeSessionReducer().start(
                        "intake-2",
                        new IntakeMessageEnvelope(
                                1L,
                                TroubleshootingIntakeSources.WEB_CONVERSATION,
                                "msg-1",
                                "conv-2",
                                "admin",
                                "现象: ITGW失败",
                                List.of(),
                                NOW)),
                new IntakeMessageEnvelope(
                        1L,
                        TroubleshootingIntakeSources.WEB_CONVERSATION,
                        "msg-2",
                        "conv-2",
                        "admin",
                        """
                        系统: CSDP
                        服务: csdp-wechat
                        客户ID: 未知
                        发生时间: 2026-08-07 17:12:00
                        错误码: 904003
                        """,
                        List.of(),
                        NOW.plusSeconds(30)));
        assertThat(ready.status()).isEqualTo(IntakeSessionStatus.READY);
        when(sessions.accept(any())).thenReturn(IntakeDecision.from(ready, false, false));
        when(sessions.getReady(1L, "intake-2")).thenReturn(ready);
        Diagnosis diagnosis = mock(Diagnosis.class);
        when(diagnosis.diagnosisId()).thenReturn("diag-ready");
        when(intakeService.report(ready, true)).thenReturn(new StoredDiagnosis(diagnosis, 1, true));

        BusinessSummary summary = mock(BusinessSummary.class);
        DiagnosisExperienceProjection projection = mock(DiagnosisExperienceProjection.class);
        when(projection.businessSummary()).thenReturn(summary);
        when(projectionService.project(1L, "diag-ready")).thenReturn(projection);
        when(summaryRenderer.render(summary)).thenReturn(
                "[INSUFFICIENT_EVIDENCE · LOW] 标题\n结论：证据不足\n正式工作台：/troubleshooting?diagnosisId=diag-ready");

        ConversationIntakeService.ConversationTurnResult result = service.turn(
                1L, "admin", "conv-2",
                "系统: CSDP\n服务: csdp-wechat\n客户ID: 未知\n发生时间: 2026-08-07 17:12:00\n错误码: 904003",
                true);

        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.diagnosisId()).isEqualTo("diag-ready");
        assertThat(result.created()).isTrue();
        assertThat(result.rehearsal()).isTrue();
        assertThat(result.prompt()).contains("结论：证据不足");
        assertThat(result.prompt()).contains("正式工作台");
        verify(intakeService).report(eq(ready), eq(true));
        verify(summaryRenderer).render(summary);
    }
}
