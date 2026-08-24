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
import java.util.concurrent.atomic.AtomicReference;

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
                        "张三工单 T2026081000378 消息发不出去了",
                        List.of(),
                        NOW),
                true);
        when(sessions.acceptConversation(any(), eq(true)))
                .thenReturn(IntakeDecision.from(awaiting, false, false));
        when(sessions.get(1L, "intake-1")).thenReturn(awaiting);

        ConversationIntakeService.ConversationTurnResult result = service.turn(
                1L, "admin", "conv-1", "张三工单 T2026081000378 消息发不出去了", true);

        assertThat(result.status()).isEqualTo(IntakeSessionStatus.AWAITING_INPUT.name());
        assertThat(result.diagnosisId()).isNull();
        assertThat(result.rehearsal()).isTrue();
        assertThat(result.prompt()).contains("还需要");
        assertThat(result.transcriptUserMessage())
                .contains("现象：已识别", "原文未保存")
                .doesNotContain("张三", "T2026081000378");
        ArgumentCaptor<IntakeMessageEnvelope> envelope = ArgumentCaptor.forClass(IntakeMessageEnvelope.class);
        verify(sessions).acceptConversation(envelope.capture(), eq(true));
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
                                NOW),
                        true),
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
        when(sessions.acceptConversation(any(), eq(true)))
                .thenReturn(IntakeDecision.from(ready, false, false));
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

    @Test
    @DisplayName("无 SOP/错误码的正式对话仍生成通用排障单并保留追问上下文")
    void formalGenericTurnWithoutErrorCodeReturnsDiagnosisAndFollowUpContext() {
        IntakeSession ready = new IntakeSession(
                "intake-generic-formal",
                IntakeSession.CURRENT_CONTRACT_VERSION,
                1L,
                TroubleshootingIntakeSources.WEB_CONVERSATION,
                "conv-generic-formal",
                "admin",
                IntakeSessionStatus.READY,
                "会话创建失败",
                "CSDP",
                "csdp-task",
                "未知",
                null,
                null,
                NOW.minusSeconds(60),
                List.of(),
                List.of(),
                NOW,
                NOW,
                NOW,
                List.of()).withRehearsal(false);
        when(sessions.acceptConversation(any(), eq(false)))
                .thenReturn(IntakeDecision.from(ready, false, false));
        when(sessions.get(1L, "intake-generic-formal")).thenReturn(ready);
        Diagnosis diagnosis = mock(Diagnosis.class);
        when(diagnosis.diagnosisId()).thenReturn("diag-generic-formal");
        when(intakeService.report(ready, false))
                .thenReturn(new StoredDiagnosis(diagnosis, 1, true));
        BusinessSummary summary = mock(BusinessSummary.class);
        DiagnosisExperienceProjection projection = mock(DiagnosisExperienceProjection.class);
        when(projection.businessSummary()).thenReturn(summary);
        when(projectionService.project(1L, "diag-generic-formal"))
                .thenReturn(projection);
        when(summaryRenderer.render(summary)).thenReturn(
                "当前结论：通用只读调查已完成\n还缺：下游返回码");

        ConversationIntakeService.ConversationTurnResult result = service.turn(
                1L,
                "admin",
                "conv-generic-formal",
                "系统: CSDP\n服务: csdp-task\n客户ID: 未知\n发生时间: 2026-08-12 13:59:00\n现象: 会话创建失败",
                false);

        assertThat(result.status()).isEqualTo(IntakeSessionStatus.READY.name());
        assertThat(result.diagnosisId()).isEqualTo("diag-generic-formal");
        assertThat(result.rehearsal()).isFalse();
        assertThat(result.prompt())
                .contains("当前结论：通用只读调查已完成")
                .contains("已生成正式排障单：diag-generic-formal")
                .contains("打开排障详情")
                .contains("为什么是这个原因", "还缺什么", "结束排障");
        assertThat(result.transcriptUserMessage())
                .contains("排障告警（已规范化）", "系统：CSDP", "服务：csdp-task")
                .doesNotContain("错误码：");
        verify(intakeService).report(ready, false);
        verify(summaryRenderer).render(summary);
    }

    @Test
    @DisplayName("页面重开时从服务端 IntakeSession 恢复已锁定模式")
    void restoresLockedModeFromServerSession() {
        IntakeSession formal = new IntakeSessionReducer().start(
                "intake-formal-awaiting",
                new IntakeMessageEnvelope(
                        1L,
                        TroubleshootingIntakeSources.WEB_CONVERSATION,
                        "msg-formal-1",
                        "conv-formal-awaiting",
                        "admin",
                        "会话创建失败",
                        List.of(),
                        NOW),
                false);
        when(sessions.getConversationMode(
                1L, "conv-formal-awaiting", "admin")).thenReturn(formal);

        ConversationIntakeService.ConversationModeResult result = service.mode(
                1L, "admin", "conv-formal-awaiting");

        assertThat(result.conversationId()).isEqualTo("conv-formal-awaiting");
        assertThat(result.intakeSessionId()).isEqualTo("intake-formal-awaiting");
        assertThat(result.status()).isEqualTo("AWAITING_INPUT");
        assertThat(result.rehearsal()).isFalse();
    }

    @Test
    @DisplayName("客户端 turn id 会绑定操作者与会话，不能跨会话碰撞收据")
    void scopesClientTurnIdToReporterAndConversation() {
        IntakeSession awaiting = new IntakeSessionReducer().start(
                "intake-scoped-turn",
                new IntakeMessageEnvelope(
                        1L,
                        TroubleshootingIntakeSources.WEB_CONVERSATION,
                        "msg-1",
                        "conv-a",
                        "alice",
                        "会话创建失败",
                        List.of(),
                        NOW),
                false);
        when(sessions.acceptConversation(
                        any(),
                        eq(false),
                        eq("web-msg-shared-turn-id")))
                .thenReturn(IntakeDecision.from(awaiting, false, false));
        when(sessions.get(1L, "intake-scoped-turn")).thenReturn(awaiting);

        service.turn(1L, "alice", "conv-a", "shared-turn-id", "会话创建失败", false);
        service.turn(1L, "bob", "conv-b", "shared-turn-id", "会话创建失败", false);

        ArgumentCaptor<IntakeMessageEnvelope> envelopes =
                ArgumentCaptor.forClass(IntakeMessageEnvelope.class);
        verify(sessions, org.mockito.Mockito.times(2))
                .acceptConversation(
                        envelopes.capture(),
                        eq(false),
                        eq("web-msg-shared-turn-id"));
        List<IntakeMessageEnvelope> values = envelopes.getAllValues();
        assertThat(values.get(0).sourceMessageId())
                .isNotEqualTo(values.get(1).sourceMessageId())
                .doesNotContain("alice", "conv-a", "shared-turn-id");
        assertThat(values.get(1).sourceMessageId())
                .doesNotContain("bob", "conv-b", "shared-turn-id");
    }

    @Test
    @DisplayName("完整 ITGW 告警一轮进入已审核规则并返回原因结论")
    void fullItgwAlertReturnsADiagnosisAndCauseInOneTurn() {
        String alert = """
                客服数字化(WECHAT)-【ITGW访问失败】-事件
                ■【紧急】2026-08-07 17:12:00 (r/e4d3f5)
                集群：sz3-s-k8s
                服务：csdp-wechat
                数量：6
                异常：ITGW访问失败【904003】
                说明：异常事件
                """;
        AtomicReference<IntakeSession> storedSession = new AtomicReference<>();
        when(sessions.acceptConversation(any(), eq(true))).thenAnswer(call -> {
            IntakeMessageEnvelope envelope = call.getArgument(0);
            IntakeSession parsed = new IntakeSessionReducer().start(
                    "intake-itgw", envelope, true);
            IntakeSession ready = new IntakeSession(
                    parsed.intakeSessionId(), parsed.contractVersion(), parsed.workspaceId(),
                    parsed.source(), parsed.conversationRef(), parsed.reporterRef(),
                    IntakeSessionStatus.READY, parsed.symptom(), "CSDP", parsed.service(),
                    parsed.customerRef(), parsed.errorCode(), parsed.traceId(), parsed.occurredAt(),
                    parsed.attachments(), List.of(), parsed.reportedAt(), parsed.lastMessageAt(),
                    parsed.lastMessageAt(), parsed.timeline()).withRehearsal(true);
            storedSession.set(ready);
            return IntakeDecision.from(ready, false, false);
        });
        when(sessions.getReady(1L, "intake-itgw"))
                .thenAnswer(call -> storedSession.get());
        Diagnosis diagnosis = mock(Diagnosis.class);
        when(diagnosis.diagnosisId()).thenReturn("diag-itgw");
        when(intakeService.report(any(IntakeSession.class), eq(true)))
                .thenReturn(new StoredDiagnosis(diagnosis, 1, true));
        BusinessSummary summary = mock(BusinessSummary.class);
        DiagnosisExperienceProjection projection = mock(DiagnosisExperienceProjection.class);
        when(projection.businessSummary()).thenReturn(summary);
        when(projectionService.project(1L, "diag-itgw")).thenReturn(projection);
        when(summaryRenderer.render(summary)).thenReturn(
                "结论：ITGW 内容安全策略拦截请求\n正式工作台：/troubleshooting?diagnosisId=diag-itgw");

        ConversationIntakeService.ConversationTurnResult result = service.turn(
                1L, "admin", null, alert, true);

        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.diagnosisId()).isEqualTo("diag-itgw");
        assertThat(result.prompt()).contains("ITGW 内容安全策略拦截请求");
        ArgumentCaptor<IntakeSession> reported = ArgumentCaptor.forClass(IntakeSession.class);
        verify(intakeService).report(reported.capture(), eq(true));
        assertThat(reported.getValue())
                .extracting(
                        IntakeSession::system,
                        IntakeSession::service,
                        IntakeSession::errorCode)
                .containsExactly("CSDP", "csdp-wechat", "904003");
    }

    @Test
    @DisplayName("粘贴 iCare 移动端完结拒绝报文后一轮返回脱敏原因")
    void fullIcareMobileFinishRejectionReturnsTheBusinessReasonInOneTurn() {
        String alert = """
                {"url":"https://it-gw.sangfor.com/icare/api/sf-icare-openapi/openapi/case/workOrderPhase/channel/updateFinish?time=1787020784&app=CSDP",
                 "header":{"Authorization":"Bearer not-persisted"},
                 "content":{"loginPrmUserName":"某某","workOrderId":"T0000000001"},
                 "error":"移动端不支持该操作【工单涉及变更单】；请到PC端操作"}
                """;
        AtomicReference<IntakeSession> storedSession = new AtomicReference<>();
        when(sessions.acceptConversation(any(), eq(true))).thenAnswer(call -> {
            IntakeMessageEnvelope envelope = call.getArgument(0);
            IntakeSession ready = new IntakeSessionReducer().start(
                    "intake-icare-mobile-finish", envelope, true);
            storedSession.set(ready);
            return IntakeDecision.from(ready, false, false);
        });
        when(sessions.getReady(1L, "intake-icare-mobile-finish"))
                .thenAnswer(call -> storedSession.get());
        Diagnosis diagnosis = mock(Diagnosis.class);
        when(diagnosis.diagnosisId()).thenReturn("diag-icare-mobile-finish");
        when(intakeService.report(any(IntakeSession.class), eq(true)))
                .thenReturn(new StoredDiagnosis(diagnosis, 1, true));
        BusinessSummary summary = mock(BusinessSummary.class);
        DiagnosisExperienceProjection projection = mock(DiagnosisExperienceProjection.class);
        when(projection.businessSummary()).thenReturn(summary);
        when(projectionService.project(1L, "diag-icare-mobile-finish"))
                .thenReturn(projection);
        when(summaryRenderer.render(summary)).thenReturn(
                "原因：工单关联变更单，iCare 禁止在移动端完结\n"
                        + "下一步：改用 PC 端完结");

        ConversationIntakeService.ConversationTurnResult result = service.turn(
                1L, "admin", null, alert, true);

        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.diagnosisId()).isEqualTo("diag-icare-mobile-finish");
        assertThat(result.prompt())
                .contains("工单关联变更单")
                .contains("改用 PC 端");
        assertThat(result.transcriptUserMessage())
                .contains("排障告警（已规范化）", "CSDP", "sf-icare-openapi")
                .doesNotContain("token", "Authorization", "T0000000001", "某某");
        ArgumentCaptor<IntakeSession> reported = ArgumentCaptor.forClass(IntakeSession.class);
        verify(intakeService).report(reported.capture(), eq(true));
        assertThat(reported.getValue())
                .extracting(
                        IntakeSession::system,
                        IntakeSession::service,
                        IntakeSession::symptom,
                        IntakeSession::customerRef)
                .containsExactly(
                        "CSDP",
                        "sf-icare-openapi",
                        "工单涉及变更单，iCare 禁止在移动端完结",
                        "未知");
        assertThat(reported.getValue().normalizedFactKind())
                .isEqualTo(vip.mate.troubleshooting.intake.NormalizedIncidentFactKind
                        .ICARE_MOBILE_CHANGE_ORDER_FINISH_REJECTED);
        assertThat(reported.getValue().symptom())
                .doesNotContain("token", "Authorization", "T0000000001", "某某");
    }

    @Test
    @DisplayName("粘贴 iCare 回访结果缺失报文后一轮返回明确原因")
    void fullIcareMissingRevisitResultReturnsTheReasonInOneTurn() {
        String alert = """
                {"url":"https://it-gw.sangfor.com/icare/api/sf-icare-openapi/openapi/case/workOrderPhase/channel/updateFinish?app=CSDP&time=1787042438",
                 "header":{"Authorization":"Bearer not-persisted"},
                 "content":{"loginPrmUserName":"某某","workOrderId":"T0000000002","syncCustomerDetail":"long customer text","revisitResult":""},
                 "error":"当前工单需要填写回访信息，不能完结"}
                """;
        AtomicReference<IntakeSession> storedSession = new AtomicReference<>();
        when(sessions.acceptConversation(any(), eq(true))).thenAnswer(call -> {
            IntakeMessageEnvelope envelope = call.getArgument(0);
            IntakeSession ready = new IntakeSessionReducer().start(
                    "intake-icare-revisit-required", envelope, true);
            storedSession.set(ready);
            return IntakeDecision.from(ready, false, false);
        });
        when(sessions.getReady(1L, "intake-icare-revisit-required"))
                .thenAnswer(call -> storedSession.get());
        Diagnosis diagnosis = mock(Diagnosis.class);
        when(diagnosis.diagnosisId()).thenReturn("diag-icare-revisit-required");
        when(intakeService.report(any(IntakeSession.class), eq(true)))
                .thenReturn(new StoredDiagnosis(diagnosis, 1, true));
        BusinessSummary summary = mock(BusinessSummary.class);
        DiagnosisExperienceProjection projection = mock(DiagnosisExperienceProjection.class);
        when(projection.businessSummary()).thenReturn(summary);
        when(projectionService.project(1L, "diag-icare-revisit-required"))
                .thenReturn(projection);
        when(summaryRenderer.render(summary)).thenReturn(
                "明确原因：回访结果未填写，iCare 拒绝完结\n"
                        + "下一步：补全回访表单后重试");

        ConversationIntakeService.ConversationTurnResult result = service.turn(
                1L, "admin", null, alert, true);

        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.diagnosisId()).isEqualTo("diag-icare-revisit-required");
        assertThat(result.prompt()).contains("回访结果未填写").contains("补全回访表单");
        ArgumentCaptor<IntakeSession> reported = ArgumentCaptor.forClass(IntakeSession.class);
        verify(intakeService).report(reported.capture(), eq(true));
        assertThat(reported.getValue().normalizedFactKind())
                .isEqualTo(vip.mate.troubleshooting.intake.NormalizedIncidentFactKind
                        .ICARE_REQUIRED_REVISIT_RESULT_MISSING);
        assertThat(reported.getValue().symptom())
                .doesNotContain("token", "Authorization", "T0000000002", "某某", "customer text");
    }
}
