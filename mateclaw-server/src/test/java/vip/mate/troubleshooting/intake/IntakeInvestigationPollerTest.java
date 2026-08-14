package vip.mate.troubleshooting.intake;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.channel.ChannelManager;
import vip.mate.troubleshooting.model.BlastRadius;
import vip.mate.troubleshooting.model.Confidence;
import vip.mate.troubleshooting.model.ConclusionType;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.DiagnosisStatus;
import vip.mate.troubleshooting.model.NorthStarTimings;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.BusinessSummary;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.DeveloperEvidenceView;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.ImpactView;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.NextStep;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjectionService;
import vip.mate.troubleshooting.repository.TroubleshootingIntakeInvestigationMapper;
import vip.mate.troubleshooting.service.StoredDiagnosis;
import vip.mate.troubleshooting.service.TroubleshootingIntakeService;
import vip.mate.troubleshooting.service.TroubleshootingPersistenceService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IntakeInvestigationPollerTest {

    private static final Instant NOW = Instant.parse("2026-07-29T03:00:00Z");

    @Mock private TroubleshootingIntakeInvestigationMapper mapper;
    @Mock private TroubleshootingIntakeSessionService sessions;
    @Mock private TroubleshootingIntakeService intakeService;
    @Mock private TroubleshootingPersistenceService persistence;
    @Mock private DiagnosisExperienceProjectionService projectionService;
    @Mock private ChannelManager channelManager;

    private IntakeInvestigationPoller poller;

    @BeforeEach
    void setUp() {
        poller = new IntakeInvestigationPoller(
                mapper,
                sessions,
                intakeService,
                persistence,
                projectionService,
                channelManager,
                new TroubleshootingChannelSummaryRenderer("http://127.0.0.1:5173"),
                Clock.fixed(NOW, ZoneOffset.UTC),
                "test-worker");
    }

    @Test
    void createsOneDiagnosisThenRepliesThroughTheOriginalChannelRoute() {
        TroubleshootingIntakeInvestigationEntity candidate =
                row(IntakeInvestigationStatus.PENDING, 0, null);
        TroubleshootingIntakeInvestigationEntity claimed =
                row(IntakeInvestigationStatus.PROCESSING, 1, null);
        selectNormalThenTerminal(List.of(candidate), List.of());
        routeAvailable();
        when(mapper.claim(eq(1L), eq("test-worker"), any(), any(), eq(5))).thenReturn(1);
        when(mapper.selectById(1L)).thenReturn(claimed);
        StoredDiagnosis stored = new StoredDiagnosis(
                org.mockito.Mockito.mock(Diagnosis.class), 0, true);
        when(stored.diagnosis().diagnosisId()).thenReturn("diag-1");
        when(intakeService.report(readySession())).thenReturn(stored);
        when(mapper.attachDiagnosis(eq(1L), eq("test-worker"), eq("diag-1"), any()))
                .thenReturn(1);
        DiagnosisExperienceProjection projection = projection();
        when(projectionService.project(7L, "diag-1")).thenReturn(projection);
        when(mapper.markCompleted(eq(1L), eq("test-worker"), any())).thenReturn(1);

        poller.dispatchReady();

        InOrder order = inOrder(mapper, channelManager);
        order.verify(mapper).attachDiagnosis(eq(1L), eq("test-worker"), eq("diag-1"), any());
        order.verify(channelManager).sendToWorkspaceConversation(
                eq(7L),
                eq("wecom"),
                eq("wecom:99:group-1"),
                contains("/troubleshooting?diagnosisId=diag-1"));
        order.verify(mapper).markCompleted(eq(1L), eq("test-worker"), any());
    }

    @Test
    void notificationRetryReusesTheAlreadyLinkedDiagnosis() {
        TroubleshootingIntakeInvestigationEntity candidate =
                row(IntakeInvestigationStatus.FAILED, 1, "diag-1");
        TroubleshootingIntakeInvestigationEntity claimed =
                row(IntakeInvestigationStatus.PROCESSING, 2, "diag-1");
        selectNormalThenTerminal(List.of(candidate), List.of());
        routeAvailable();
        when(mapper.claim(eq(1L), eq("test-worker"), any(), any(), eq(5))).thenReturn(1);
        when(mapper.selectById(1L)).thenReturn(claimed);
        DiagnosisExperienceProjection projection = projection();
        when(projectionService.project(7L, "diag-1")).thenReturn(projection);
        when(mapper.markCompleted(eq(1L), eq("test-worker"), any())).thenReturn(1);

        poller.dispatchReady();

        verify(intakeService, never()).report(any(IntakeSession.class));
        verify(mapper, never()).attachDiagnosis(any(), any(), any(), any());
        verify(channelManager).sendToWorkspaceConversation(
                eq(7L), eq("wecom"), eq("wecom:99:group-1"), contains("diag-1"));
    }

    @Test
    void channelFailureReleasesTheLeaseWithoutLosingDiagnosisOwnership() {
        TroubleshootingIntakeInvestigationEntity candidate =
                row(IntakeInvestigationStatus.FAILED, 1, "diag-1");
        TroubleshootingIntakeInvestigationEntity claimed =
                row(IntakeInvestigationStatus.PROCESSING, 2, "diag-1");
        selectNormalThenTerminal(List.of(candidate), List.of());
        routeAvailable();
        when(mapper.claim(eq(1L), eq("test-worker"), any(), any(), eq(5))).thenReturn(1);
        when(mapper.selectById(1L)).thenReturn(claimed);
        DiagnosisExperienceProjection projection = projection();
        when(projectionService.project(7L, "diag-1")).thenReturn(projection);
        doThrow(new IllegalStateException("channel unavailable"))
                .when(channelManager)
                .sendToWorkspaceConversation(anyLong(), any(), any(), any());
        when(mapper.markFailed(any(), any(), any(), any(), any())).thenReturn(1);

        poller.dispatchReady();

        verify(mapper).markFailed(
                eq(1L),
                eq("test-worker"),
                eq("channel unavailable"),
                any(),
                any());
        verify(mapper, never()).markCompleted(any(), any(), any());
    }

    @Test
    void followerLeavesTheTaskUnclaimedForTheChannelLeader() {
        TroubleshootingIntakeInvestigationEntity candidate =
                row(IntakeInvestigationStatus.PENDING, 0, null);
        selectNormalThenTerminal(List.of(candidate), List.of());
        when(sessions.getReadyDispatch(7L, "intake-1")).thenReturn(readyDispatch());

        poller.dispatchReady();

        verify(mapper, never()).claim(any(), any(), any(), any(), anyInt());
        verify(mapper, never()).claimTerminal(any(), any(), any(), any(), anyInt());
        verify(intakeService, never()).report(any());
    }

    @Test
    void fifthNormalFailureTransitionsToDurableTerminalDelivery() {
        TroubleshootingIntakeInvestigationEntity candidate =
                row(IntakeInvestigationStatus.FAILED, 4, null);
        TroubleshootingIntakeInvestigationEntity claimed =
                row(IntakeInvestigationStatus.PROCESSING, 5, null);
        selectNormalThenTerminal(List.of(candidate), List.of());
        routeAvailable();
        when(mapper.claim(eq(1L), eq("test-worker"), any(), any(), eq(5))).thenReturn(1);
        when(mapper.selectById(1L)).thenReturn(claimed);
        when(intakeService.report(any(IntakeSession.class)))
                .thenThrow(new IllegalStateException("agent unavailable"));
        when(mapper.markTerminalPending(any(), any(), any(), any(), any())).thenReturn(1);

        poller.dispatchReady();

        verify(mapper).markTerminalPending(
                eq(1L), eq("test-worker"), eq("agent unavailable"), any(), any());
        verify(mapper, never()).markFailed(any(), any(), any(), any(), any());
        verify(channelManager, never()).sendToWorkspaceConversation(
                anyLong(), any(), any(), contains("系统已停止自动判断"));
    }

    @Test
    void terminalPendingIsDeliveredAndCompletedDurably() {
        TroubleshootingIntakeInvestigationEntity candidate =
                row(IntakeInvestigationStatus.TERMINAL_PENDING, 5, null);
        TroubleshootingIntakeInvestigationEntity claimed =
                row(IntakeInvestigationStatus.TERMINAL_PROCESSING, 5, null);
        claimed.setTerminalAttempts(1);
        selectNormalThenTerminal(List.of(), List.of(candidate));
        routeAvailable();
        when(mapper.claimTerminal(eq(1L), eq("test-worker"), any(), any(), eq(5)))
                .thenReturn(1);
        when(mapper.selectById(1L)).thenReturn(claimed);
        when(mapper.markTerminalCompleted(eq(1L), eq("test-worker"), any())).thenReturn(1);

        poller.dispatchReady();

        verify(channelManager).sendToWorkspaceConversation(
                eq(7L),
                eq("wecom"),
                eq("wecom:99:group-1"),
                contains("系统已停止自动判断"));
        verify(channelManager).sendToWorkspaceConversation(
                eq(7L),
                eq("wecom"),
                eq("wecom:99:group-1"),
                contains("未执行任何生产变更"));
        verify(mapper).markTerminalCompleted(eq(1L), eq("test-worker"), any());
    }

    @Test
    void terminalDeliveryFailureIsRescheduledWithoutAHardRetryCap() {
        TroubleshootingIntakeInvestigationEntity candidate =
                row(IntakeInvestigationStatus.TERMINAL_PENDING, 5, "diag-1");
        TroubleshootingIntakeInvestigationEntity claimed =
                row(IntakeInvestigationStatus.TERMINAL_PROCESSING, 5, "diag-1");
        claimed.setTerminalAttempts(3);
        selectNormalThenTerminal(List.of(), List.of(candidate));
        routeAvailable();
        when(mapper.claimTerminal(eq(1L), eq("test-worker"), any(), any(), eq(5)))
                .thenReturn(1);
        when(mapper.selectById(1L)).thenReturn(claimed);
        DiagnosisExperienceProjection projection = projection();
        when(projectionService.project(7L, "diag-1")).thenReturn(projection);
        doThrow(new IllegalStateException("platform rejected message"))
                .when(channelManager)
                .sendToWorkspaceConversation(anyLong(), any(), any(), any());
        when(mapper.rescheduleTerminal(any(), any(), any(), any(), any())).thenReturn(1);

        poller.dispatchReady();

        verify(mapper).rescheduleTerminal(
                eq(1L),
                eq("test-worker"),
                eq("platform rejected message"),
                any(),
                any());
        verify(mapper, never()).markTerminalCompleted(any(), any(), any());
    }

    @Test
    void expiredFifthProcessingLeaseWithLinkedDiagnosisRedeliversBusinessSummary() {
        TroubleshootingIntakeInvestigationEntity abandoned =
                row(IntakeInvestigationStatus.PROCESSING, 5, "diag-1");
        TroubleshootingIntakeInvestigationEntity claimed =
                row(IntakeInvestigationStatus.TERMINAL_PROCESSING, 5, "diag-1");
        claimed.setTerminalAttempts(1);
        selectNormalThenTerminal(List.of(), List.of(abandoned));
        routeAvailable();
        when(mapper.claimTerminal(eq(1L), eq("test-worker"), any(), any(), eq(5)))
                .thenReturn(1);
        when(mapper.selectById(1L)).thenReturn(claimed);
        DiagnosisExperienceProjection projection = projection();
        when(projectionService.project(7L, "diag-1")).thenReturn(projection);
        when(mapper.markTerminalCompleted(eq(1L), eq("test-worker"), any())).thenReturn(1);

        poller.dispatchReady();

        verify(mapper, never()).claim(any(), any(), any(), any(), anyInt());
        verify(mapper).claimTerminal(eq(1L), eq("test-worker"), any(), any(), eq(5));
        verify(channelManager).sendToWorkspaceConversation(
                eq(7L), eq("wecom"), eq("wecom:99:group-1"), contains("diag-1"));
        verify(channelManager, never()).sendToWorkspaceConversation(
                anyLong(), any(), any(), contains("系统已停止自动判断"));
        verify(mapper).markTerminalCompleted(eq(1L), eq("test-worker"), any());
    }

    @Test
    void expiredFifthLeaseRecoversDiagnosisPersistedBeforeTaskLink() {
        TroubleshootingIntakeInvestigationEntity abandoned =
                row(IntakeInvestigationStatus.PROCESSING, 5, null);
        TroubleshootingIntakeInvestigationEntity claimed =
                row(IntakeInvestigationStatus.TERMINAL_PROCESSING, 5, null);
        claimed.setTerminalAttempts(1);
        selectNormalThenTerminal(List.of(), List.of(abandoned));
        routeAvailable();
        when(mapper.claimTerminal(eq(1L), eq("test-worker"), any(), any(), eq(5)))
                .thenReturn(1);
        when(mapper.selectById(1L)).thenReturn(claimed);
        StoredDiagnosis existing = new StoredDiagnosis(
                org.mockito.Mockito.mock(Diagnosis.class), 0, false);
        when(existing.diagnosis().diagnosisId()).thenReturn("diag-1");
        when(persistence.findByIntakeSessionId(7L, "intake-1"))
                .thenReturn(Optional.of(existing));
        when(mapper.attachDiagnosis(eq(1L), eq("test-worker"), eq("diag-1"), any()))
                .thenReturn(1);
        DiagnosisExperienceProjection projection = projection();
        when(projectionService.project(7L, "diag-1")).thenReturn(projection);
        when(mapper.markTerminalCompleted(eq(1L), eq("test-worker"), any())).thenReturn(1);

        poller.dispatchReady();

        verify(intakeService, never()).report(any(IntakeSession.class));
        verify(mapper).attachDiagnosis(eq(1L), eq("test-worker"), eq("diag-1"), any());
        verify(channelManager).sendToWorkspaceConversation(
                eq(7L), eq("wecom"), eq("wecom:99:group-1"), contains("diag-1"));
        verify(channelManager, never()).sendToWorkspaceConversation(
                anyLong(), any(), any(), contains("系统已停止自动判断"));
    }

    @Test
    void historicalReadyReconciliationRunsOncePerProcess() {
        when(mapper.selectList(any())).thenReturn(List.of());

        poller.dispatchReady();
        poller.dispatchReady();

        verify(sessions, times(1)).reconcileReadyInvestigations();
    }

    private void selectNormalThenTerminal(
            List<TroubleshootingIntakeInvestigationEntity> normal,
            List<TroubleshootingIntakeInvestigationEntity> terminal) {
        when(mapper.selectList(any())).thenReturn(normal, terminal);
    }

    private void routeAvailable() {
        when(sessions.getReadyDispatch(7L, "intake-1")).thenReturn(readyDispatch());
        when(channelManager.canSendToWorkspaceConversation(
                7L, "wecom", "wecom:99:group-1")).thenReturn(true);
    }

    private ReadyIntakeDispatch readyDispatch() {
        return new ReadyIntakeDispatch(readySession(), "wecom:99:group-1");
    }

    private TroubleshootingIntakeInvestigationEntity row(
            IntakeInvestigationStatus status,
            int attempts,
            String diagnosisId) {
        TroubleshootingIntakeInvestigationEntity row =
                new TroubleshootingIntakeInvestigationEntity();
        row.setId(1L);
        row.setWorkspaceId(7L);
        row.setIntakeSessionId("intake-1");
        row.setDiagnosisId(diagnosisId);
        row.setStatus(status);
        row.setClaimedBy(status == IntakeInvestigationStatus.PROCESSING
                        || status == IntakeInvestigationStatus.TERMINAL_PROCESSING
                ? "test-worker"
                : null);
        row.setAttempts(attempts);
        row.setTerminalAttempts(0);
        return row;
    }

    private IntakeSession readySession() {
        return new IntakeSessionReducer().start(
                "intake-1",
                new IntakeMessageEnvelope(
                        7L,
                        "wecom",
                        "msg-1",
                        "group-1",
                        "user-1",
                        "现象: 会话消息发送失败\n系统: CSDP\n服务: csdp-wechat\n"
                                + "客户ID: tenant-42\n发生时间: 2026-07-29 10:00:00\n"
                                + "错误码: 903001",
                        List.of(),
                        Instant.parse("2026-07-29T02:00:00Z")));
    }

    private DiagnosisExperienceProjection projection() {
        BusinessSummary business = new BusinessSummary(
                "diag-1",
                ConclusionType.LOCATED,
                "已定位会话消息发送失败",
                "会话服务异常",
                "证据指向会话服务异常。",
                null,
                Confidence.MEDIUM,
                "会话消息发送失败",
                new ImpactView(
                        "csdp-wechat", null, null, BlastRadius.UNKNOWN,
                        List.of(), null, "人数待确认"),
                new NextStep(
                        "人工复核",
                        "请值班开发核对证据。",
                        "仅完成只读取证，未执行任何生产变更。"),
                DiagnosisStatus.READY_FOR_HUMAN,
                NorthStarTimings.concluded(
                        NOW.minusSeconds(60), NOW.minusSeconds(50), NOW),
                true);
        DeveloperEvidenceView developer = org.mockito.Mockito.mock(DeveloperEvidenceView.class);
        when(developer.diagnosisId()).thenReturn("diag-1");
        return new DiagnosisExperienceProjection(business, developer);
    }
}
