package vip.mate.troubleshooting.intake;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.channel.ChannelManager;
import vip.mate.channel.DeliveryOptions;
import vip.mate.troubleshooting.model.BlastRadius;
import vip.mate.troubleshooting.model.ClosureOutcome;
import vip.mate.troubleshooting.model.ClosureRecord;
import vip.mate.troubleshooting.model.Confidence;
import vip.mate.troubleshooting.model.ConclusionType;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.DiagnosisStatus;
import vip.mate.troubleshooting.model.NorthStarTimings;
import vip.mate.troubleshooting.model.TroubleshootingDiagnosisEntity;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.BusinessSummary;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.DeveloperEvidenceView;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.ImpactView;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.NextStep;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjectionService;
import vip.mate.troubleshooting.repository.TroubleshootingDiagnosisMapper;
import vip.mate.troubleshooting.service.StoredDiagnosis;
import vip.mate.troubleshooting.service.TroubleshootingPersistenceService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClosureNotificationPollerTest {

    private static final Instant NOW = Instant.parse("2026-07-29T04:00:00Z");

    @Mock private TroubleshootingDiagnosisMapper mapper;
    @Mock private TroubleshootingIntakeSessionService sessions;
    @Mock private TroubleshootingPersistenceService persistence;
    @Mock private DiagnosisExperienceProjectionService projectionService;
    @Mock private ChannelManager channelManager;

    private ClosureNotificationPoller poller;

    @BeforeEach
    void setUp() {
        poller = new ClosureNotificationPoller(
                mapper,
                sessions,
                persistence,
                projectionService,
                channelManager,
                new TroubleshootingClosureNotificationRenderer("http://127.0.0.1:5173"),
                Clock.fixed(NOW, ZoneOffset.UTC),
                "test-closure-worker");
    }

    @Test
    void sendsTheFinalOutcomeToTheExactRouteAndMentionsTheOriginalReporter() {
        TroubleshootingDiagnosisEntity candidate = row("PENDING", 0);
        TroubleshootingDiagnosisEntity claimed = row("PROCESSING", 1);
        when(mapper.selectList(any())).thenReturn(List.of(candidate));
        routeAvailable();
        when(mapper.claimClosureNotification(eq(1L), eq("test-closure-worker"), any(), any()))
                .thenReturn(1);
        when(mapper.selectById(1L)).thenReturn(claimed);
        StoredDiagnosis closed = closedDiagnosis();
        when(persistence.get(7L, "diag-1")).thenReturn(closed);
        DiagnosisExperienceProjection projected = projection();
        when(projectionService.project(7L, "diag-1")).thenReturn(projected);
        when(mapper.markClosureNotificationCompleted(
                eq(1L), eq("test-closure-worker"), any())).thenReturn(1);

        poller.dispatchClosures();

        ArgumentCaptor<DeliveryOptions> options = ArgumentCaptor.forClass(DeliveryOptions.class);
        InOrder order = inOrder(channelManager, mapper);
        order.verify(channelManager).sendToWorkspaceConversation(
                eq(7L),
                eq("wecom"),
                eq("wecom:99:group-1"),
                contains("处理结果：连接池扩容后恢复"),
                options.capture());
        assertThat(options.getValue().mentionUserIds()).containsExactly("user-1");
        order.verify(mapper).markClosureNotificationCompleted(
                eq(1L), eq("test-closure-worker"), any());
    }

    @Test
    void followerLeavesTheNotificationForTheChannelLeader() {
        when(mapper.selectList(any())).thenReturn(List.of(row("PENDING", 0)));
        when(sessions.getReadyDispatch(7L, "intake-1")).thenReturn(readyDispatch());

        poller.dispatchClosures();

        verify(mapper, never()).claimClosureNotification(any(), any(), any(), any());
        verify(persistence, never()).get(anyLong(), any());
    }

    @Test
    void rejectedPlatformAckIsPersistedForRetryWithoutAHardAttemptCap() {
        TroubleshootingDiagnosisEntity candidate = row("FAILED", 41);
        TroubleshootingDiagnosisEntity claimed = row("PROCESSING", 42);
        when(mapper.selectList(any())).thenReturn(List.of(candidate));
        routeAvailable();
        when(mapper.claimClosureNotification(eq(1L), eq("test-closure-worker"), any(), any()))
                .thenReturn(1);
        when(mapper.selectById(1L)).thenReturn(claimed);
        StoredDiagnosis closed = closedDiagnosis();
        when(persistence.get(7L, "diag-1")).thenReturn(closed);
        DiagnosisExperienceProjection projected = projection();
        when(projectionService.project(7L, "diag-1")).thenReturn(projected);
        doThrow(new IllegalStateException("platform rejected message"))
                .when(channelManager)
                .sendToWorkspaceConversation(anyLong(), any(), any(), any(), any());
        when(mapper.markClosureNotificationFailed(any(), any(), any(), any(), any()))
                .thenReturn(1);

        poller.dispatchClosures();

        verify(mapper).markClosureNotificationFailed(
                eq(1L),
                eq("test-closure-worker"),
                eq("platform rejected message"),
                any(),
                any());
        verify(mapper, never()).markClosureNotificationCompleted(any(), any(), any());
    }

    private void routeAvailable() {
        when(sessions.getReadyDispatch(7L, "intake-1")).thenReturn(readyDispatch());
        when(channelManager.canSendToWorkspaceConversation(
                7L, "wecom", "wecom:99:group-1")).thenReturn(true);
    }

    private ReadyIntakeDispatch readyDispatch() {
        return new ReadyIntakeDispatch(readySession(), "wecom:99:group-1");
    }

    private TroubleshootingDiagnosisEntity row(String status, int attempts) {
        TroubleshootingDiagnosisEntity row = new TroubleshootingDiagnosisEntity();
        row.setId(1L);
        row.setWorkspaceId(7L);
        row.setDiagnosisId("diag-1");
        row.setSourceIntakeSessionId("intake-1");
        row.setClosureNotificationStatus(status);
        row.setClosureNotificationAttempts(attempts);
        row.setClosureNotificationClaimedBy("PROCESSING".equals(status)
                ? "test-closure-worker"
                : null);
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

    private StoredDiagnosis closedDiagnosis() {
        Diagnosis diagnosis = org.mockito.Mockito.mock(Diagnosis.class);
        when(diagnosis.status()).thenReturn(DiagnosisStatus.CLOSED);
        when(diagnosis.closure()).thenReturn(closure());
        return new StoredDiagnosis(diagnosis, 7, false);
    }

    private ClosureRecord closure() {
        return new ClosureRecord(
                ClosureOutcome.RECOVERED,
                "连接池扩容后恢复",
                true,
                null,
                null,
                "operator@example.com",
                NOW.minusSeconds(10));
    }

    private DiagnosisExperienceProjection projection() {
        BusinessSummary business = new BusinessSummary(
                "diag-1",
                ConclusionType.LOCATED,
                "已定位会话消息发送失败",
                "证据指向会话服务异常。",
                Confidence.MEDIUM,
                "会话消息发送失败",
                new ImpactView(
                        "csdp-wechat", null, null, BlastRadius.UNKNOWN,
                        List.of(), null, "人数待确认"),
                new NextStep(
                        "人工复核",
                        "请值班开发核对证据。",
                        "仅完成只读取证，未执行任何生产变更。"),
                DiagnosisStatus.CLOSED,
                NorthStarTimings.concluded(
                        NOW.minusSeconds(60), NOW.minusSeconds(50), NOW.minusSeconds(30)),
                true);
        DeveloperEvidenceView developer = org.mockito.Mockito.mock(DeveloperEvidenceView.class);
        when(developer.diagnosisId()).thenReturn("diag-1");
        return new DiagnosisExperienceProjection(business, developer);
    }
}
