package vip.mate.troubleshooting;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.engine.Criterion;
import vip.mate.troubleshooting.engine.CriterionEvaluator;
import vip.mate.troubleshooting.model.ActionOutcomeStatus;
import vip.mate.troubleshooting.model.ActionType;
import vip.mate.troubleshooting.model.AnomalyCriterion;
import vip.mate.troubleshooting.model.ApprovalStatus;
import vip.mate.troubleshooting.model.ClosureOutcome;
import vip.mate.troubleshooting.model.Confidence;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.DiagnosisRule;
import vip.mate.troubleshooting.model.DiagnosisStatus;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.ExecutionStatus;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.model.KnowledgePublicationStatus;
import vip.mate.troubleshooting.model.RecommendedAction;
import vip.mate.troubleshooting.model.RouteMode;
import vip.mate.troubleshooting.model.SopEntry;
import vip.mate.troubleshooting.model.TroubleshootingDiagnosisEntity;
import vip.mate.troubleshooting.model.TroubleshootingKnowledgeOutboxEntity;
import vip.mate.troubleshooting.model.TroubleshootingSopEntity;
import vip.mate.troubleshooting.repository.TroubleshootingDiagnosisMapper;
import vip.mate.troubleshooting.repository.TroubleshootingKnowledgeOutboxMapper;
import vip.mate.troubleshooting.repository.TroubleshootingSopMapper;
import vip.mate.troubleshooting.service.DiagnosisLifecycleService;
import vip.mate.troubleshooting.service.DeterministicDiagnosisService;
import vip.mate.troubleshooting.service.StoredDiagnosis;
import vip.mate.troubleshooting.service.TroubleshootingIntakeService;
import vip.mate.troubleshooting.service.TroubleshootingPersistenceService;
import vip.mate.troubleshooting.service.TroubleshootingSopPersistenceService;
import vip.mate.troubleshooting.statemachine.DiagnosisStateMachine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The 903001 slice, run end to end on one aggregate.
 *
 * <p>Every other test in this package mocks its neighbours, which leaves the
 * seams between them unverified: whether a SOP authored the way the knowledge
 * base authors one actually drives the evaluator to the intended conclusion,
 * whether that conclusion survives a serialization round-trip, and whether the
 * lifecycle can be walked from report to knowledge candidate without a
 * transition refusing a state its predecessor produced. This test wires the
 * real evaluator, the real deterministic service, the real state machine, the
 * real persistence service and the real intake service together and walks the
 * whole path.</p>
 *
 * <p><b>What it does not cover.</b> Mappers are backed by in-memory maps rather
 * than a database, so this proves the domain composes — not that the SQL runs.
 * Schema and column mapping are covered by {@code TroubleshootingMigrationTest}
 * and the persistence unit tests. Evidence is authored by hand in this test so
 * the domain slice stays deterministic; P3 adapters have their own contract
 * tests, while the unverified 903001 bindings keep the pipeline in
 * {@code fixtureMode}.</p>
 */
class Vertical903001Test {

    private static final long WORKSPACE_ID = 1L;
    private static final String ACTOR = "duty-engineer";
    private static final Instant OCCURRED_AT = Instant.parse("2026-07-26T09:12:03Z");
    private static final java.util.regex.Pattern PARAM_TOKEN =
            java.util.regex.Pattern.compile("paramNameValuePairs\\.(\\w+)");

    private final Map<String, TroubleshootingSopEntity> sopRows = new LinkedHashMap<>();
    private final Map<String, TroubleshootingDiagnosisEntity> diagnosisRows = new LinkedHashMap<>();
    private final List<TroubleshootingKnowledgeOutboxEntity> outboxRows = new ArrayList<>();

    private TroubleshootingIntakeService intake;
    private DiagnosisLifecycleService lifecycle;
    private TroubleshootingSopPersistenceService sopPersistence;

    @BeforeAll
    static void initTableInfo() {
        // LambdaQueryWrapper resolves column names from TableInfo, which Spring
        // would populate at startup; tests bootstrap it themselves.
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, TroubleshootingSopEntity.class);
        TableInfoHelper.initTableInfo(assistant, TroubleshootingDiagnosisEntity.class);
        TableInfoHelper.initTableInfo(assistant, TroubleshootingKnowledgeOutboxEntity.class);
    }

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper()
                .findAndRegisterModules();

        sopPersistence = new TroubleshootingSopPersistenceService(sopMapper(), objectMapper);
        TroubleshootingPersistenceService persistence = new TroubleshootingPersistenceService(
                diagnosisMapper(), outboxMapper(), objectMapper);
        DiagnosisStateMachine stateMachine = new DiagnosisStateMachine();
        DeterministicDiagnosisService diagnosisService = new DeterministicDiagnosisService(
                new CriterionEvaluator(), stateMachine, persistence);

        intake = new TroubleshootingIntakeService(sopPersistence, diagnosisService);
        lifecycle = new DiagnosisLifecycleService(persistence, stateMachine);
    }

    @Test
    @DisplayName("903001: report through to a queued knowledge candidate")
    void walksTheWholeSlice() {
        sopPersistence.register(WORKSPACE_ID, sop903001());

        // --- report ------------------------------------------------------
        StoredDiagnosis reported = intake.report(
                WORKSPACE_ID, incident(), evidence(), false);
        Diagnosis diagnosis = reported.diagnosis();

        assertThat(reported.created()).isTrue();
        assertThat(diagnosis.routeMode()).isEqualTo(RouteMode.DETERMINISTIC);
        assertThat(diagnosis.status()).isEqualTo(DiagnosisStatus.READY_FOR_HUMAN);
        assertThat(diagnosis.abstained()).isFalse();

        // The authored criteria must actually fire on realistic observations,
        // and the intended rule — not a neighbouring one — must win.
        assertThat(diagnosis.triggeredSignals())
                .containsExactlyInAnyOrder("pool_exhausted", "db_hop_failure",
                        "slow_query_spike", "error_present");
        assertThat(diagnosis.triggeredSignals())
                .as("the host answered its probe, so the outage hypothesis stays excluded")
                .doesNotContain("node_unreachable");
        assertThat(diagnosis.rootCause()).contains("连接池");
        assertThat(diagnosis.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(diagnosis.warnings())
                .as("no source adapter has verified this evidence yet")
                .anyMatch(w -> w.contains("fixture"));

        String diagnosisId = diagnosis.diagnosisId();

        // --- a retrying alert source must not open a second case ---------
        StoredDiagnosis replay = intake.report(WORKSPACE_ID, incident(), evidence(), false);
        assertThat(replay.created()).isFalse();
        assertThat(replay.diagnosis().diagnosisId()).isEqualTo(diagnosisId);
        assertThat(diagnosisRows).hasSize(1);

        // --- confirm, transfer -------------------------------------------
        StoredDiagnosis confirmed = lifecycle.confirm(WORKSPACE_ID, diagnosisId, ACTOR);
        assertThat(confirmed.diagnosis().status()).isEqualTo(DiagnosisStatus.CONFIRMED);

        StoredDiagnosis transferred = lifecycle.transfer(
                WORKSPACE_ID, diagnosisId, "DBA 组", "连接池已打满，需评估扩容窗口", ACTOR);
        assertThat(transferred.diagnosis().status()).isEqualTo(DiagnosisStatus.TRANSFERRED);
        assertThat(transferred.diagnosis().transfers()).hasSize(1);

        // --- a recovered closure is refused until the write is accounted for
        assertThatThrownBy(() -> lifecycle.close(
                WORKSPACE_ID, diagnosisId, ClosureOutcome.RECOVERED,
                "已恢复", true, null, true, ACTOR))
                .as("an approved write with no verified outcome must block a recovered closure")
                .isInstanceOf(MateClawException.class);

        // --- approve (authorize only), then record what a human did -------
        StoredDiagnosis approved = lifecycle.approveAction(
                WORKSPACE_ID, diagnosisId, "act-scale-pool", "扩容窗口 22:00 已批", ACTOR);
        RecommendedAction write = writeAction(approved.diagnosis());
        assertThat(write.approvalStatus()).isEqualTo(ApprovalStatus.APPROVED_NOT_EXECUTED);
        assertThat(write.executionStatus())
                .as("MateClaw has no production write executor")
                .isEqualTo(ExecutionStatus.BLOCKED);
        assertThat(approved.diagnosis().writeExecutionEnabled()).isFalse();

        StoredDiagnosis recorded = lifecycle.recordOutcome(
                WORKSPACE_ID, diagnosisId, "act-scale-pool",
                ActionOutcomeStatus.SUCCEEDED, "mongos 连接池上限调至 4000", true, ACTOR);
        assertThat(recorded.diagnosis().actionOutcomes()).hasSize(1);

        // --- close, sedimenting a reviewable candidate --------------------
        StoredDiagnosis closed = lifecycle.close(
                WORKSPACE_ID, diagnosisId, ClosureOutcome.RECOVERED,
                "扩容后连接可用数恢复，错误归零", true,
                "建议把连接池阈值判据写进 SOP", true, ACTOR);

        assertThat(closed.diagnosis().status()).isEqualTo(DiagnosisStatus.CLOSED);
        assertThat(closed.diagnosis().closure().recoveryVerified()).isTrue();
        assertThat(closed.diagnosis().knowledgeCandidates()).hasSize(1);

        // The lesson must be queued for review, never applied to the SOP directly.
        assertThat(outboxRows).hasSize(1);
        assertThat(outboxRows.getFirst().getStatus())
                .isEqualTo(KnowledgePublicationStatus.PENDING);
        assertThat(outboxRows.getFirst().getWorkspaceId()).isEqualTo(WORKSPACE_ID);
        assertThat(sopPersistence.find(WORKSPACE_ID, "CSDP", "903001").status())
                .as("a single incident must not rewrite approved knowledge")
                .isEqualTo("approved");

        // --- the timeline is an append-only account of who did what -------
        List<String> events = closed.diagnosis().timeline().stream()
                .map(t -> t.event())
                .toList();
        assertThat(events).hasSizeGreaterThanOrEqualTo(7);
        assertThat(events.getFirst()).contains("故障上下文已接收");
        assertThat(events).anyMatch(e -> e.contains("确定性路由命中 csdp:903001"));
        assertThat(events).anyMatch(e -> e.contains("人工确认"));
        assertThat(events).anyMatch(e -> e.contains("结构化转派至 DBA 组"));
        assertThat(events).anyMatch(e -> e.contains("系统未执行"));
        assertThat(events).anyMatch(e -> e.contains("恢复验证通过"));
        assertThat(events).anyMatch(e -> e.contains("关闭归档"));
        assertThat(closed.diagnosis().timeline())
                .allMatch(t -> "done".equals(t.status()) || "current".equals(t.status()));
    }

    @Test
    @DisplayName("903001: a degraded collection abstains instead of guessing")
    void abstainsWhenRequiredEvidenceIsMissing() {
        sopPersistence.register(WORKSPACE_ID, sop903001());

        List<EvidenceResult> degraded = List.of(
                new EvidenceResult("EV-2", "M", "", EvidenceStatus.MISSING,
                        "取证失败", Map.of(), "guance:unavailable", OCCURRED_AT),
                logEvidence());

        Diagnosis diagnosis = intake.report(WORKSPACE_ID, incident(), degraded, false).diagnosis();

        assertThat(diagnosis.abstained()).isTrue();
        assertThat(diagnosis.status()).isEqualTo(DiagnosisStatus.NEEDS_INVESTIGATION);
        assertThat(diagnosis.confidence()).isEqualTo(Confidence.LOW);
        assertThat(diagnosis.recommendedActions())
                .as("an abstained diagnosis must not suggest a recovery action")
                .isEmpty();
        assertThat(diagnosis.warnings()).anyMatch(w -> w.contains("EV-2"));

        assertThatThrownBy(() -> lifecycle.confirm(
                WORKSPACE_ID, diagnosis.diagnosisId(), ACTOR))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("abstained");
    }

    @Test
    @DisplayName("an unregistered error code is reported as a knowledge gap")
    void refusesAnUnknownRoute() {
        assertThatThrownBy(() -> intake.report(WORKSPACE_ID, incident(), evidence(), false))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("no SOP registered");
    }

    // ================= the 903001 knowledge entry =================

    private SopEntry sop903001() {
        return new SopEntry(
                "sop-csdp-903001", SopEntry.CURRENT_CONTRACT_VERSION,
                "CSDP", "903001", "order-svc",
                "订单服务 Mongo 连接池耗尽", "连接池打满导致 DB 调用排队超时",
                "database", "DBA 组", "approved", true,
                List.of(
                        new EvidenceRequest("EV-1", "log_count", "确认故障正在发生",
                                Map.of("service", "order-svc", "error_code", "903001"), "-15m", true),
                        new EvidenceRequest("EV-2", "metric", "连接池与慢查询水位",
                                Map.of("host", "csdp-mongo-03"), "-15m", true),
                        new EvidenceRequest("EV-3", "trace", "定位失败跳",
                                Map.of("trace_id", "7f3a91c"), null, false)),
                List.of(
                        new AnomalyCriterion("pool_exhausted", "EV-2", "连接可用数占比归零",
                                new Criterion.RatioOfSumGt(
                                        "connections_current", "connections_available", 0.95)),
                        new AnomalyCriterion("slow_query_spike", "EV-2", "慢查询超基线 3 倍",
                                new Criterion.MultipleGt("slow_query_count", "baseline_slow", 3)),
                        new AnomalyCriterion("node_unreachable", "EV-2", "实例探活失败",
                                new Criterion.BooleanEquals("reachable", false)),
                        new AnomalyCriterion("error_present", "EV-1", "错误码日志出现",
                                new Criterion.NumericGte("count", 1)),
                        new AnomalyCriterion("db_hop_failure", "EV-3", "失败跳落在 DB 层",
                                new Criterion.ContainsAndIn("failed_hop", "mongo",
                                        "status", List.of("timeout", "error")))),
                List.of(
                        // Ordered most specific first: the outage rule must be
                        // reachable but must not win when the host is answering.
                        new DiagnosisRule("R-903001-outage",
                                List.of("node_unreachable", "error_present"),
                                "Mongo 实例不可达（宕机或网络分区）",
                                "实例探活失败且错误持续。", Confidence.HIGH, false),
                        new DiagnosisRule("R-903001-pool",
                                List.of("pool_exhausted", "db_hop_failure"),
                                "Mongo 连接池打满，order-svc 数据库调用排队超时",
                                "连接可用数归零，失败跳定位在 mongo.find。", Confidence.HIGH, false)),
                List.of(
                        new RecommendedAction("act-inspect", ActionType.AUTO_READONLY,
                                "复核连接池占用与慢查询 Top-N", "只读取证",
                                false, ApprovalStatus.NOT_REQUIRED, ExecutionStatus.PENDING),
                        new RecommendedAction("act-notify", ActionType.HUMAN_CONTACT,
                                "通知 DBA 组评估扩容窗口", "携完整上下文转派",
                                false, ApprovalStatus.NOT_REQUIRED, ExecutionStatus.PENDING),
                        new RecommendedAction("act-scale-pool", ActionType.MANUAL_WRITE,
                                "扩容 mongos 连接池上限至 4000",
                                "生产写操作，由有权限的人在平台外执行后登记结果",
                                true, ApprovalStatus.PENDING, ExecutionStatus.BLOCKED)));
    }

    private IncidentContext incident() {
        return new IncidentContext(
                "inc-903001-0912", "CSDP", "order-svc", "903001",
                "订单创建大面积超时", "P0", "订单创建成功率下降，客服工单激增",
                "7f3a91c", OCCURRED_AT, "21:18", "alert_webhook",
                IncidentCompleteness.STRUCTURED,
                "[ALERT] code=903001 svc=order-svc msg=\"Mongo connection pool exhausted\"");
    }

    private List<EvidenceResult> evidence() {
        return List.of(
                logEvidence(),
                new EvidenceResult("EV-2", "M",
                        "M::mongodb:(connections_current,connections_available,slow_query_count,"
                                + "baseline_slow,reachable) {host='csdp-mongo-03'} [-15m]",
                        EvidenceStatus.ANOMALY, "Mongo 连接与慢查询",
                        Map.of("connections_current", 2000,
                                "connections_available", 0,
                                "slow_query_count", 37,
                                "baseline_slow", 6,
                                "reachable", true),
                        "guance:metric", OCCURRED_AT),
                new EvidenceResult("EV-3", "T",
                        "T::order-svc:(failed_hop,status,duration_ms) {trace_id='7f3a91c'}",
                        EvidenceStatus.ANOMALY, "失败调用链定位",
                        Map.of("failed_hop", "mongo.find",
                                "status", "timeout",
                                "duration_ms", 30012),
                        "guance:trace", OCCURRED_AT));
    }

    private EvidenceResult logEvidence() {
        return new EvidenceResult("EV-1", "L",
                "L::order-svc:(count,trace_id) {error_code='903001'} [-15m]",
                EvidenceStatus.ANOMALY, "错误码日志计数",
                Map.of("count", 148, "trace_id", "7f3a91c"),
                "guance:log", OCCURRED_AT);
    }

    private RecommendedAction writeAction(Diagnosis diagnosis) {
        return diagnosis.recommendedActions().stream()
                .filter(a -> a.actionType() == ActionType.MANUAL_WRITE)
                .findFirst()
                .orElseThrow();
    }

    // ================= in-memory mappers =================
    // Real serialization, real dedup keys, real optimistic versions; only the
    // SQL is stubbed. Schema fidelity is TroubleshootingMigrationTest's job.

    private TroubleshootingSopMapper sopMapper() {
        TroubleshootingSopMapper mapper = mock(TroubleshootingSopMapper.class);
        AtomicLong ids = new AtomicLong(1);
        when(mapper.insert(any(TroubleshootingSopEntity.class))).thenAnswer((Answer<Integer>) call -> {
            TroubleshootingSopEntity entity = call.getArgument(0);
            entity.setId(ids.getAndIncrement());
            sopRows.put(entity.getRouteKey(), entity);
            return 1;
        });
        when(mapper.selectOne(any())).thenAnswer((Answer<TroubleshootingSopEntity>) call ->
                sopRows.values().stream()
                        .filter(row -> matchesTarget(call.getArgument(0), row.getRouteKey()))
                        .findFirst()
                        .orElse(null));
        return mapper;
    }

    private TroubleshootingDiagnosisMapper diagnosisMapper() {
        TroubleshootingDiagnosisMapper mapper = mock(TroubleshootingDiagnosisMapper.class);
        AtomicLong ids = new AtomicLong(1);
        when(mapper.insert(any(TroubleshootingDiagnosisEntity.class)))
                .thenAnswer((Answer<Integer>) call -> {
                    TroubleshootingDiagnosisEntity entity = call.getArgument(0);
                    entity.setId(ids.getAndIncrement());
                    diagnosisRows.put(entity.getDiagnosisId(), entity);
                    return 1;
                });
        when(mapper.selectOne(any())).thenAnswer((Answer<TroubleshootingDiagnosisEntity>) call ->
                diagnosisRows.values().stream()
                        .filter(row -> matchesTarget(call.getArgument(0), row.getDiagnosisId())
                                || matchesTarget(call.getArgument(0), row.getDedupKey()))
                        .findFirst()
                        .orElse(null));
        // Production updates by wrapper only (entity is null), gated on the
        // version it read. The fake honours that gate: a stale expectedVersion
        // must change zero rows so the optimistic-lock conflict still surfaces.
        when(mapper.update(any(), any())).thenAnswer((Answer<Integer>) call -> {
            Map<String, Object> bound = boundParams(call.getArgument(1));
            TroubleshootingDiagnosisEntity row = diagnosisRows.values().stream()
                    .filter(candidate -> bound.containsValue(candidate.getDiagnosisId()))
                    .findFirst()
                    .orElse(null);
            if (row == null || !bound.containsValue(row.getVersion())) {
                return 0;
            }
            Map<String, Object> assignments = setValues(call.getArgument(1));
            Object status = assignments.get("status");
            Object aggregate = assignments.get("aggregate_json");
            Object version = assignments.get("version");
            if (status != null) {
                row.setStatus(String.valueOf(status));
            }
            if (aggregate != null) {
                row.setAggregateJson(String.valueOf(aggregate));
            }
            if (version instanceof Integer next) {
                row.setVersion(next);
            }
            return 1;
        });
        return mapper;
    }

    private TroubleshootingKnowledgeOutboxMapper outboxMapper() {
        TroubleshootingKnowledgeOutboxMapper mapper =
                mock(TroubleshootingKnowledgeOutboxMapper.class);
        AtomicLong ids = new AtomicLong(1);
        when(mapper.insert(any(TroubleshootingKnowledgeOutboxEntity.class)))
                .thenAnswer((Answer<Integer>) call -> {
                    TroubleshootingKnowledgeOutboxEntity entity = call.getArgument(0);
                    entity.setId(ids.getAndIncrement());
                    outboxRows.add(entity);
                    return 1;
                });
        when(mapper.selectOne(any())).thenAnswer((Answer<TroubleshootingKnowledgeOutboxEntity>)
                call -> outboxRows.stream()
                        .filter(row -> matchesTarget(call.getArgument(0), row.getCandidateId()))
                        .findFirst()
                        .orElse(null));
        when(mapper.selectCount(any())).thenAnswer((Answer<Long>) call -> (long) outboxRows.size());
        return mapper;
    }

    /**
     * Crude wrapper matching: the bound parameters carry the value the caller
     * filtered on, which is enough to route a lookup in these fakes without
     * reimplementing MyBatis-Plus.
     */
    /** Bound parameter values of a wrapper, materializing the SQL segment first. */
    private Map<String, Object> boundParams(Object wrapper) {
        if (!(wrapper instanceof AbstractWrapper<?, ?, ?> typed)) {
            return Map.of();
        }
        typed.getSqlSegment();
        Map<String, Object> params = typed.getParamNameValuePairs();
        return params == null ? Map.of() : params;
    }

    /** Column -> value for the SET clause of an update wrapper. */
    private Map<String, Object> setValues(Object wrapper) {
        if (!(wrapper instanceof LambdaUpdateWrapper<?> update)) {
            return Map.of();
        }
        Map<String, Object> params = boundParams(update);
        Map<String, Object> assignments = new LinkedHashMap<>();
        for (String fragment : update.getSqlSet().split(",")) {
            String[] halves = fragment.split("=", 2);
            if (halves.length != 2) {
                continue;
            }
            String column = halves[0].trim();
            java.util.regex.Matcher token = PARAM_TOKEN.matcher(halves[1]);
            if (token.find()) {
                assignments.put(column, params.get(token.group(1)));
            }
        }
        return assignments;
    }

    private boolean matchesTarget(Object wrapper, String candidate) {
        if (candidate == null || !(wrapper instanceof AbstractWrapper<?, ?, ?> typed)) {
            return false;
        }
        // Bound parameters are materialized lazily, so ask for the SQL segment
        // first or the map comes back empty.
        typed.getSqlSegment();
        Map<String, Object> params = typed.getParamNameValuePairs();
        return params != null && params.values().stream()
                .anyMatch(value -> candidate.equals(String.valueOf(value)));
    }
}
