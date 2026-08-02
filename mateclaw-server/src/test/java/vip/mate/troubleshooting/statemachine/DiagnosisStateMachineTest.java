package vip.mate.troubleshooting.statemachine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import vip.mate.troubleshooting.model.NorthStarTimings;
import vip.mate.troubleshooting.model.PlaybookVersionRef;
import vip.mate.troubleshooting.model.RecommendedAction;
import vip.mate.troubleshooting.model.RouteMode;
import vip.mate.exception.MateClawException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosisStateMachineTest {

    private DiagnosisStateMachine stateMachine;

    @BeforeEach
    void setUp() {
        AtomicInteger sequence = new AtomicInteger();
        stateMachine = new DiagnosisStateMachine(
                Clock.fixed(Instant.parse("2026-07-25T01:02:03Z"), ZoneOffset.UTC),
                prefix -> prefix + "-" + sequence.incrementAndGet());
    }

    /**
     * A scenario Diagnosis cannot currently leave {@code NEEDS_INVESTIGATION}.
     *
     * <p>{@code initializeScenarioAwaitingEvidence} creates it {@code abstained}
     * on purpose — naming a scenario selects an evidence plan, it does not
     * assert a cause — and {@link DiagnosisStateMachine#confirm} refuses any
     * abstained diagnosis until new evidence arrives. Both halves are right.
     * Their conjunction is that <b>no scenario Diagnosis can ever be confirmed,
     * transferred to closure, or closed</b>, because nothing in the codebase
     * supplies that evidence: the aggregate has no transition for it (its only
     * mutations are confirm / transfer / actions / outcomes / close), and the
     * deployment-topology probe writes to its own run table rather than back to
     * the Diagnosis.</p>
     *
     * <p>This predates the generic scenario entry — the shipped topology
     * scenario is stuck the same way. Closing it means adding an
     * evidence-arrival transition to the {@code Diagnosis} aggregate and reusing
     * the deterministic decision logic that {@code DeterministicDiagnosisService}
     * already applies on the hit path (A9). That is a v4 §5.5 contract addition,
     * so it is recorded rather than improvised: see TODO T0.17.</p>
     *
     * <p>The test pins today's behaviour instead of failing, so the gap is
     * stated in code and a build stays honest about what does work.</p>
     */
    @Test
    void aScenarioDiagnosisIsStuckUntilAnEvidenceArrivalTransitionExists() {
        Diagnosis scenario = stateMachine.initializeScenarioAwaitingEvidence(
                new vip.mate.troubleshooting.model.ScenarioDiagnosisDraft(
                        "diag-scenario-1", "case-scenario-1", "run-scenario-1",
                        scenarioIncident(), "message_send_failed", scenarioPlaybook(),
                        new PlaybookVersionRef("playbook-scenario", 1),
                        "operator",
                        NorthStarTimings.concluded(
                                Instant.parse("2026-07-25T01:00:00Z"),
                                Instant.parse("2026-07-25T01:00:10Z"),
                                Instant.parse("2026-07-25T01:00:10Z")),
                        false, true,
                        java.util.List.of("取证尚未执行")));

        assertEquals(DiagnosisStatus.NEEDS_INVESTIGATION, scenario.status());
        assertTrue(scenario.abstained(), "指定场景不等于断言原因，弃权是对的");

        MateClawException blocked = assertThrows(
                MateClawException.class, () -> stateMachine.confirm(scenario, "operator"));
        assertTrue(blocked.getMessage().contains("requires new evidence"));

        assertTrue(
                java.util.Arrays.stream(Diagnosis.class.getDeclaredMethods())
                        .map(java.lang.reflect.Method::getName)
                        .noneMatch(name -> name.contains("evidenceRecorded")
                                || name.contains("reevaluated")),
                "一旦聚合有了证据到达的转移，这条断言就该失败——那正是修好的信号");
    }

    @Test
    void fullHumanControlledFlowNeverExecutesTheManualWrite() {
        Diagnosis diagnosis = stateMachine.confirm(readyDiagnosis(), "on-call");
        diagnosis = stateMachine.transfer(diagnosis, "DBA 值班", "携带完整证据", "on-call");
        diagnosis = stateMachine.approveAction(
                diagnosis, "restart-mongodb", "维护窗口已确认", "dba");

        RecommendedAction approved = action(diagnosis, "restart-mongodb");
        assertEquals(ApprovalStatus.APPROVED_NOT_EXECUTED, approved.approvalStatus());
        assertEquals(ExecutionStatus.BLOCKED, approved.executionStatus());
        assertTrue(diagnosis.pendingWrites().isEmpty());
        assertTrue(diagnosis.timeline().getLast().event().contains("系统未执行"));

        diagnosis = stateMachine.recordActionOutcome(
                diagnosis,
                "restart-mongodb",
                ActionOutcomeStatus.SUCCEEDED,
                "DBA 已在外部系统完成恢复",
                true,
                "dba");
        diagnosis = stateMachine.close(
                diagnosis,
                ClosureOutcome.RECOVERED,
                "业务探测恢复",
                true,
                "补充连接池预警阈值",
                true,
                "on-call");

        assertEquals(DiagnosisStatus.CLOSED, diagnosis.status());
        assertTrue(diagnosis.closure().recoveryVerified());
        assertEquals(1, diagnosis.knowledgeCandidates().size());
        assertEquals("DBA 值班", diagnosis.knowledgeCandidates().getFirst().ownerTeam());
        assertFalse(diagnosis.writeExecutionEnabled());
        assertEquals(ExecutionStatus.BLOCKED,
                action(diagnosis, "restart-mongodb").executionStatus());
    }

    @Test
    void abstainedDiagnosisCannotBeConfirmed() {
        Diagnosis ready = readyDiagnosis();
        Diagnosis abstained = Diagnosis.initial(
                ready.diagnosisId(),
                ready.caseId(),
                ready.runId(),
                ready.incident(),
                ready.routeMode(),
                DiagnosisStatus.NEEDS_INVESTIGATION,
                "等待人工深查",
                "证据不足",
                Confidence.LOW,
                true,
                ready.sopKey(),
                ready.sopTitle(),
                ready.sourcePlaybookVersionRef(),
                ready.evidence(),
                ready.triggeredSignals(),
                List.of(),
                null,
                ready.rehearsal(),
                ready.fixtureMode(),
                ready.warnings());

        MateClawException error = assertThrows(
                MateClawException.class,
                () -> stateMachine.confirm(abstained, "on-call"));

        assertEquals(409, error.getCode());
    }

    @Test
    void transferAndApprovalRejectSkippedConfirmation() {
        Diagnosis diagnosis = readyDiagnosis();

        assertThrows(
                MateClawException.class,
                () -> stateMachine.transfer(diagnosis, "DBA", "跳步", "on-call"));
        assertThrows(
                MateClawException.class,
                () -> stateMachine.approveAction(diagnosis, "restart-mongodb", "跳步", "dba"));
    }

    @Test
    void outcomeRequiresApprovalAndRecoveryVerificationRequiresSuccess() {
        Diagnosis confirmed = stateMachine.confirm(readyDiagnosis(), "on-call");

        assertThrows(
                MateClawException.class,
                () -> stateMachine.recordActionOutcome(
                        confirmed,
                        "restart-mongodb",
                        ActionOutcomeStatus.SUCCEEDED,
                        "尚未批准",
                        true,
                        "dba"));

        Diagnosis approved = stateMachine.approveAction(
                confirmed, "restart-mongodb", "窗口确认", "dba");
        assertThrows(
                MateClawException.class,
                () -> stateMachine.recordActionOutcome(
                        approved,
                        "restart-mongodb",
                        ActionOutcomeStatus.FAILED,
                        "失败",
                        true,
                        "dba"));
    }

    @Test
    void recoveredClosureRequiresVerifiedSuccessfulExternalOutcome() {
        Diagnosis confirmed = stateMachine.confirm(readyDiagnosis(), "on-call");
        Diagnosis approved = stateMachine.approveAction(
                confirmed, "restart-mongodb", "窗口确认", "dba");

        assertThrows(
                MateClawException.class,
                () -> stateMachine.close(
                        approved,
                        ClosureOutcome.RECOVERED,
                        "不能跳过结果登记",
                        true,
                        null,
                        false,
                        "on-call"));
    }

    @Test
    void executeEndpointContractIsAlwaysAConflict() {
        MateClawException error = assertThrows(
                MateClawException.class,
                () -> stateMachine.executeAction(readyDiagnosis(), "restart-mongodb", "dba"));

        assertEquals(409, error.getCode());
        assertTrue(error.getMessage().contains("not connected"));
    }

    @Test
    void firstHumanConfirmationRecordsHandoffAndAdoptionCostExactlyOnce() {
        Instant reportedAt = Instant.parse("2026-07-25T01:00:00Z");
        Instant readyAt = Instant.parse("2026-07-25T01:00:10Z");
        Instant conclusionAt = Instant.parse("2026-07-25T01:01:00Z");
        Diagnosis timed = readyDiagnosis(NorthStarTimings.concluded(
                reportedAt, readyAt, conclusionAt));

        Diagnosis confirmed = stateMachine.confirm(timed, "on-call");
        Diagnosis transferred = stateMachine.transfer(
                confirmed, "DBA", "携带证据", "on-call");

        assertEquals(Instant.parse("2026-07-25T01:02:03Z"), confirmed.timings().handoffAt());
        assertEquals(java.time.Duration.ofSeconds(63), confirmed.timings().adoptCost());
        assertEquals(confirmed.timings(), transferred.timings());
    }

    private Diagnosis readyDiagnosis() {
        return readyDiagnosis(NorthStarTimings.unrecorded());
    }

    private IncidentContext scenarioIncident() {
        return new IncidentContext(
                "inc-message-send", "CSDP", "csdp-session-service", null,
                "会话消息发送失败", "P1", "待确认", null,
                Instant.parse("2026-07-25T01:00:00Z"), null,
                "web:scenario", IncidentCompleteness.SYMPTOM, "会话消息发送失败");
    }

    private vip.mate.troubleshooting.model.SopEntry scenarioPlaybook() {
        return new vip.mate.troubleshooting.model.SopEntry(
                "playbook-scenario",
                vip.mate.troubleshooting.model.SopEntry.CURRENT_CONTRACT_VERSION,
                "CSDP", "scenario:message_send_failed", "csdp-session-service",
                "会话消息发送失败路径核查", "会话状态冲突", "message", "会话平台组",
                "approved", true,
                java.util.List.of(new vip.mate.troubleshooting.model.EvidenceRequest(
                        "SYNTH-LOG-SEARCH", "log_search", "定位失败请求",
                        Map.of("search_term", "message_send_failed"), "-15m", true)),
                java.util.List.of(new vip.mate.troubleshooting.model.AnomalyCriterion(
                        "send_failure_present", "SYNTH-LOG-SEARCH", "存在失败日志",
                        new vip.mate.troubleshooting.engine.Criterion.NumericGte(
                                "match_count", 1))),
                java.util.List.of(new vip.mate.troubleshooting.model.DiagnosisRule(
                        "RULE-MESSAGE-SEND-PATH",
                        java.util.List.of("send_failure_present"),
                        "消息发送路径异常待核查", "收敛到会话状态冲突特征",
                        Confidence.MEDIUM, false)),
                java.util.List.of());
    }

    private Diagnosis readyDiagnosis(NorthStarTimings timings) {
        IncidentContext incident = new IncidentContext(
                "inc-903001",
                "CSDP",
                "csdp-wechat",
                "903001",
                "数据库访问异常",
                "P0",
                "所有客户",
                "trace-903001",
                Instant.parse("2026-07-25T01:00:00Z"),
                "14:31",
                "alert_fixture",
                IncidentCompleteness.STRUCTURED,
                "fixture error_code=903001");
        EvidenceResult evidence = new EvidenceResult(
                "mongo-metrics",
                "fixture",
                "fixture://mongo",
                EvidenceStatus.ANOMALY,
                "connections saturated",
                Map.of("connections_current", 95, "connections_available", 5),
                "fixture",
                Instant.parse("2026-07-25T01:01:00Z"));
        RecommendedAction readonly = new RecommendedAction(
                "retain-evidence",
                ActionType.AUTO_READONLY,
                "保留取证结果",
                "只读",
                false,
                ApprovalStatus.NOT_REQUIRED,
                ExecutionStatus.COMPLETED);
        RecommendedAction write = RecommendedAction.manualWrite(
                "restart-mongodb",
                "按恢复方案重启 MongoDB",
                "仅允许外部人工执行");

        return Diagnosis.initial(
                "diag-903001",
                "case-903001",
                "run-903001-001",
                incident,
                RouteMode.DETERMINISTIC,
                vip.mate.troubleshooting.model.InvestigationMode.ERROR_CODE_PLAYBOOK,
                vip.mate.troubleshooting.model.RouteAuthority.EXPLICIT,
                vip.mate.troubleshooting.model.ConclusionType.LOCATED,
                timings,
                DiagnosisStatus.READY_FOR_HUMAN,
                "证据一致",
                "MongoDB 连接数饱和",
                Confidence.HIGH,
                false,
                "csdp:903001",
                "MongoDB 连接异常取证与处置",
                "DBA 值班",
                new PlaybookVersionRef("playbook-903001", 1),
                List.of(evidence),
                List.of("conn_saturated"),
                List.of(readonly, write),
                "DBA 值班",
                false,
                true,
                List.of("fixture only"),
                List.of());
    }

    private RecommendedAction action(Diagnosis diagnosis, String actionId) {
        return diagnosis.recommendedActions().stream()
                .filter(item -> item.actionId().equals(actionId))
                .findFirst()
                .orElseThrow();
    }
}
