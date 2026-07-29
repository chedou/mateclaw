package vip.mate.troubleshooting.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.model.ActionOutcomeStatus;
import vip.mate.troubleshooting.model.ActionType;
import vip.mate.troubleshooting.model.ApprovalStatus;
import vip.mate.troubleshooting.model.ClosureOutcome;
import vip.mate.troubleshooting.model.Confidence;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.DiagnosisStatus;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.ExecutionStatus;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.model.KnowledgeCandidate;
import vip.mate.troubleshooting.model.RecommendedAction;
import vip.mate.troubleshooting.model.RouteMode;
import vip.mate.troubleshooting.statemachine.DiagnosisStateMachine;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The human-controlled lifecycle end to end: report -> confirm -> transfer ->
 * approve -> record outcome -> close -> knowledge candidate. Uses the real
 * state machine so the transition rules under test are the shipped ones.
 */
@ExtendWith(MockitoExtension.class)
class DiagnosisLifecycleServiceTest {

    private static final long WORKSPACE_ID = 7L;
    private static final String DIAGNOSIS_ID = "diag-1";
    private static final String WRITE_ACTION_ID = "act-write";
    private static final String ACTOR = "operator@example.com";
    private static final Instant NOW = Instant.parse("2026-07-25T09:12:03Z");

    @Mock
    private TroubleshootingPersistenceService persistence;

    private DiagnosisLifecycleService lifecycle;

    @BeforeEach
    void setUp() {
        lifecycle = new DiagnosisLifecycleService(persistence, new DiagnosisStateMachine());
    }

    @Test
    void confirmMovesAReadyDiagnosisForwardAndAppendsToTheTimeline() {
        stored(readyDiagnosis(), 3);

        lifecycle.confirm(WORKSPACE_ID, DIAGNOSIS_ID, ACTOR);

        Diagnosis saved = capturedUpdate(3);
        assertThat(saved.status()).isEqualTo(DiagnosisStatus.CONFIRMED);
        assertThat(saved.timeline()).hasSizeGreaterThan(readyDiagnosis().timeline().size());
        assertThat(saved.timeline().getLast().actor()).isEqualTo(ACTOR);
    }

    @Test
    void approvalAuthorizesAWriteWithoutExecutingIt() {
        stored(confirmedDiagnosis(), 4);

        lifecycle.approveAction(WORKSPACE_ID, DIAGNOSIS_ID, WRITE_ACTION_ID, "扩容窗口已批", ACTOR);

        RecommendedAction write = writeAction(capturedUpdate(4));
        assertThat(write.approvalStatus())
                .as("approval only authorizes; it must never mark the action executed")
                .isEqualTo(ApprovalStatus.APPROVED_NOT_EXECUTED);
        assertThat(write.executionStatus())
                .as("MateClaw has no production write executor, so the action stays blocked")
                .isEqualTo(ExecutionStatus.BLOCKED);
    }

    @Test
    void refusesToCloseAsRecoveredWhileAnApprovedWriteHasNoExternalOutcome() {
        Diagnosis approved = new DiagnosisStateMachine().approveAction(
                confirmedDiagnosis(), WRITE_ACTION_ID, "扩容窗口已批", ACTOR);
        stored(approved, 5);

        assertThatThrownBy(() -> lifecycle.close(
                WORKSPACE_ID, DIAGNOSIS_ID, ClosureOutcome.RECOVERED,
                "已恢复", true, null, false, ACTOR))
                .isInstanceOf(MateClawException.class);

        verify(persistence, never()).update(anyLong(), any(), anyInt());
        verify(persistence, never()).updateAndEnqueue(anyLong(), any(), anyInt(), any());
    }

    @Test
    void recordsAnExternalOutcomeAndThenAllowsARecoveredClosure() {
        DiagnosisStateMachine machine = new DiagnosisStateMachine();
        Diagnosis approved = machine.approveAction(
                confirmedDiagnosis(), WRITE_ACTION_ID, "扩容窗口已批", ACTOR);
        stored(approved, 5);

        lifecycle.recordOutcome(WORKSPACE_ID, DIAGNOSIS_ID, WRITE_ACTION_ID,
                ActionOutcomeStatus.SUCCEEDED, "已扩容至 4000", true, ACTOR);

        Diagnosis withOutcome = capturedUpdate(5);
        assertThat(withOutcome.actionOutcomes()).hasSize(1);
        assertThat(withOutcome.actionOutcomes().getLast().recoveryVerified()).isTrue();

        stored(withOutcome, 6);
        lifecycle.close(WORKSPACE_ID, DIAGNOSIS_ID, ClosureOutcome.RECOVERED,
                "连接池扩容后恢复", true, null, false, ACTOR);

        Diagnosis closed = capturedUpdate(6);
        assertThat(closed.status()).isEqualTo(DiagnosisStatus.CLOSED);
        assertThat(closed.closure()).isNotNull();
        assertThat(closed.closure().recoveryVerified()).isTrue();
    }

    @Test
    void closingWithSedimentationEnqueuesTheCandidateInTheSameTransaction() {
        stored(confirmedDiagnosis(), 4);

        lifecycle.close(WORKSPACE_ID, DIAGNOSIS_ID, ClosureOutcome.TRANSFERRED_OUT,
                "转 DBA 处置", false, "建议补充连接池阈值判据", true, ACTOR);

        ArgumentCaptor<Diagnosis> saved = ArgumentCaptor.forClass(Diagnosis.class);
        ArgumentCaptor<KnowledgeCandidate> candidate = ArgumentCaptor.forClass(KnowledgeCandidate.class);
        verify(persistence).updateAndEnqueue(
                eq(WORKSPACE_ID), saved.capture(), eq(4), candidate.capture());

        assertThat(saved.getValue().knowledgeCandidates()).hasSize(1);
        assertThat(candidate.getValue().sourceDiagnosisId()).isEqualTo(DIAGNOSIS_ID);
        assertThat(candidate.getValue().outcomeProof()).isNotNull();
        assertThat(candidate.getValue().outcomeProof().outcome())
                .isEqualTo(ClosureOutcome.TRANSFERRED_OUT);
        assertThat(candidate.getValue().outcomeProof().recoveryVerified()).isFalse();
        assertThat(candidate.getValue().outcomeProof().registeredBy()).isEqualTo(ACTOR);
        assertThat(candidate.getValue().outcomeProof().registeredAt())
                .isEqualTo(saved.getValue().closure().closedAt());
        assertThat(saved.getValue().closure().knowledgeCandidateId())
                .as("the closure must point at the candidate this transition produced")
                .isEqualTo(candidate.getValue().candidateId());
        verify(persistence, never()).update(anyLong(), any(), anyInt());
    }

    @Test
    void closingWithoutSedimentationDoesNotTouchTheOutbox() {
        stored(confirmedDiagnosis(), 4);

        lifecycle.close(WORKSPACE_ID, DIAGNOSIS_ID, ClosureOutcome.FALSE_POSITIVE,
                "告警误报", false, null, false, ACTOR);

        verify(persistence).update(eq(WORKSPACE_ID), any(), eq(4));
        verify(persistence, never()).updateAndEnqueue(anyLong(), any(), anyInt(), any());
    }

    @Test
    void rejectsDeveloperEvidenceAndCredentialsFromTheClosureSummary() {
        assertThatThrownBy(() -> lifecycle.close(
                WORKSPACE_ID,
                DIAGNOSIS_ID,
                ClosureOutcome.FALSE_POSITIVE,
                "DQL L::service:(*) token=top-secret",
                false,
                null,
                false,
                ACTOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("business-safe");

        verify(persistence, never()).update(anyLong(), any(), anyInt());
        verify(persistence, never()).updateAndEnqueue(anyLong(), any(), anyInt(), any());
    }

    @Test
    void rejectsForgedMentionsAndOversizedClosureSummaries() {
        assertThatThrownBy(() -> lifecycle.close(
                WORKSPACE_ID,
                DIAGNOSIS_ID,
                ClosureOutcome.FALSE_POSITIVE,
                "误报 <@all>",
                false,
                null,
                false,
                ACTOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("business-safe");
        assertThatThrownBy(() -> lifecycle.close(
                WORKSPACE_ID,
                DIAGNOSIS_ID,
                ClosureOutcome.FALSE_POSITIVE,
                "超长".repeat(300),
                false,
                null,
                false,
                ACTOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("500");

        verify(persistence, never()).update(anyLong(), any(), anyInt());
        verify(persistence, never()).updateAndEnqueue(anyLong(), any(), anyInt(), any());
    }

    @Test
    void refusesToConfirmAnAbstainedDiagnosisBecauseThereIsNoConclusionYet() {
        stored(abstainedDiagnosis(), 1);

        assertThatThrownBy(() -> lifecycle.confirm(WORKSPACE_ID, DIAGNOSIS_ID, ACTOR))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("abstained");

        verify(persistence, never()).update(anyLong(), any(), anyInt());
    }

    @Test
    void writesBackUnderTheVersionItRead() {
        stored(readyDiagnosis(), 11);

        lifecycle.confirm(WORKSPACE_ID, DIAGNOSIS_ID, ACTOR);

        verify(persistence).update(eq(WORKSPACE_ID), any(), eq(11));
    }

    // ---------- helpers ----------

    private void stored(Diagnosis diagnosis, int version) {
        when(persistence.get(WORKSPACE_ID, DIAGNOSIS_ID))
                .thenReturn(new StoredDiagnosis(diagnosis, version, false));
    }

    private Diagnosis capturedUpdate(int expectedVersion) {
        ArgumentCaptor<Diagnosis> saved = ArgumentCaptor.forClass(Diagnosis.class);
        verify(persistence).update(eq(WORKSPACE_ID), saved.capture(), eq(expectedVersion));
        return saved.getValue();
    }

    private RecommendedAction writeAction(Diagnosis diagnosis) {
        return diagnosis.recommendedActions().stream()
                .filter(action -> action.actionId().equals(WRITE_ACTION_ID))
                .findFirst()
                .orElseThrow();
    }

    private Diagnosis readyDiagnosis() {
        return Diagnosis.initial(
                DIAGNOSIS_ID, "case-1", "run-1", incident(),
                RouteMode.DETERMINISTIC, DiagnosisStatus.READY_FOR_HUMAN,
                "连接可用数归零", "Mongo 连接池打满", Confidence.HIGH, false,
                "CSDP:903001", "订单服务 Mongo 连接池耗尽",
                List.of(evidence()), List.of("pool_exhausted"),
                List.of(readOnlyAction(), manualWriteAction()),
                "DBA 组", false, true, List.of());
    }

    private Diagnosis confirmedDiagnosis() {
        return new DiagnosisStateMachine().confirm(readyDiagnosis(), ACTOR);
    }

    private Diagnosis abstainedDiagnosis() {
        return Diagnosis.initial(
                DIAGNOSIS_ID, "case-1", "run-1", incident(),
                RouteMode.DETERMINISTIC, DiagnosisStatus.NEEDS_INVESTIGATION,
                "SOP 尚未审核", "证据不足，暂不能确认根因。", Confidence.LOW, true,
                "CSDP:903001", "订单服务 Mongo 连接池耗尽",
                List.of(evidence()), List.of(), List.of(),
                null, false, true, List.of("SOP 仍为草案"));
    }

    private IncidentContext incident() {
        return new IncidentContext(
                "inc-1", "CSDP", "order-svc", "903001", "订单创建超时",
                "P0", "订单创建成功率下降", "7f3a91c", NOW, "21:18",
                "alert_webhook", IncidentCompleteness.STRUCTURED, "[ALERT] code=903001");
    }

    private EvidenceResult evidence() {
        return new EvidenceResult(
                "EV-2", "M", "M::mongodb:(connections_available) {host='csdp-mongo-03'} [-15m]",
                EvidenceStatus.ANOMALY, "Mongo 连接与慢查询",
                Map.of("connections_current", 2000, "connections_available", 0),
                "guance:metric", NOW);
    }

    private RecommendedAction readOnlyAction() {
        return new RecommendedAction(
                "act-read", ActionType.AUTO_READONLY, "复核连接池占用",
                "只读取证", false, ApprovalStatus.NOT_REQUIRED, ExecutionStatus.COMPLETED);
    }

    private RecommendedAction manualWriteAction() {
        return new RecommendedAction(
                WRITE_ACTION_ID, ActionType.MANUAL_WRITE, "扩容 mongos 连接池上限至 4000",
                "生产写操作，由有权限的人在平台外执行", true,
                ApprovalStatus.PENDING, ExecutionStatus.BLOCKED);
    }
}
