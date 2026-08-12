package vip.mate.troubleshooting.projection;

import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.agent.OpenDiscoveryRunAudit;
import vip.mate.troubleshooting.evidence.ScenarioEvidenceRunAudit;
import vip.mate.troubleshooting.engine.Criterion;
import vip.mate.troubleshooting.model.ActionType;
import vip.mate.troubleshooting.model.AnomalyCriterion;
import vip.mate.troubleshooting.model.ApprovalStatus;
import vip.mate.troubleshooting.model.Confidence;
import vip.mate.troubleshooting.model.ConclusionType;
import vip.mate.troubleshooting.model.CriterionOutcome;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.DiagnosisDerivation;
import vip.mate.troubleshooting.model.DiagnosisRule;
import vip.mate.troubleshooting.model.DiagnosisStatus;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.ExecutionStatus;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.model.InvestigationMode;
import vip.mate.troubleshooting.model.NorthStarTimings;
import vip.mate.troubleshooting.model.PlaybookVersionRef;
import vip.mate.troubleshooting.model.RecommendedAction;
import vip.mate.troubleshooting.model.RouteAuthority;
import vip.mate.troubleshooting.model.RouteMode;
import vip.mate.troubleshooting.model.SopEntry;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvestigationTraceProjectorTest {

    private static final Instant REPORTED_AT = Instant.parse("2026-07-28T12:40:00Z");
    private static final Instant READY_AT = Instant.parse("2026-07-28T12:40:30Z");
    private static final Instant CONCLUSION_AT = Instant.parse("2026-07-28T12:43:14Z");

    private final InvestigationTraceProjector projector = new InvestigationTraceProjector();

    @Test
    void projectsSevenImmutableStagesAndAnEvidenceToConclusionLineage() {
        InvestigationTraceView view = projector.project(
                deterministicDiagnosis(), frozenPlaybook(), derivation());

        assertThat(view.stages())
                .extracting(InvestigationTraceView.StageView::key)
                .containsExactly(
                        InvestigationTraceView.StageKey.INCIDENT,
                        InvestigationTraceView.StageKey.PLAYBOOK_ROUTE,
                        InvestigationTraceView.StageKey.EVIDENCE_CONTRACT,
                        InvestigationTraceView.StageKey.ADAPTER_SELECTION,
                        InvestigationTraceView.StageKey.EVIDENCE_COLLECTION,
                        InvestigationTraceView.StageKey.CRITERION_EVALUATION,
                        InvestigationTraceView.StageKey.CONCLUSION);
        assertThat(view.stages().getFirst().duration()).isEqualTo(Duration.ofSeconds(30));
        assertThat(view.stages().get(3).duration()).isNull();
        assertThat(view.investigationDuration()).isEqualTo(Duration.ofSeconds(164));
        assertThat(view.stages().getLast().startedAt()).isNull();
        assertThat(view.stages().getLast().duration()).isNull();
        assertThat(view.stages().getLast().fields())
                .anySatisfy(field -> {
                    assertThat(field.label()).isEqualTo("计时口径");
                    assertThat(field.value()).contains("进入调查").contains("形成结论");
                });

        assertThat(view.evidenceContracts())
                .extracting(InvestigationTraceView.EvidenceContractView::requestId)
                .containsExactly("EV-2");
        assertThat(view.evidenceContracts().getFirst().target())
                .containsEntry("service", "mongodb")
                .containsEntry("apiKey", "<REDACTED>")
                .containsEntry("credentials", "<REDACTED>");
        assertThatThrownBy(() -> view.evidenceContracts().getFirst().target().put("secret", "x"))
                .isInstanceOf(UnsupportedOperationException.class);

        InvestigationTraceView.AdapterAttemptView attempt = view.adapterAttempts().getFirst();
        assertThat(attempt.evidenceRef()).isEqualTo("EV-2");
        assertThat(attempt.requestId()).isEqualTo("EV-2");
        assertThat(attempt.signalKind()).isEqualTo("metric");
        assertThat(attempt.adapterSource()).isEqualTo("recorded-replay");
        assertThat(attempt.query()).isEqualTo("未记录");
        assertThat(attempt.observed())
                .containsEntry("connections_current", 100)
                .containsEntry("connections_available", 0);
        assertThat(attempt.duration()).isNull();
        assertThat(attempt.historyStatus())
                .isEqualTo(InvestigationTraceView.AttemptHistoryStatus.FINAL_RESULT_ONLY);

        assertThat(view.stopReason().code())
                .isEqualTo(InvestigationTraceView.StopReasonCode.CONCLUSION_RECORDED);
        assertThat(view.evidenceRelation().edges())
                .anySatisfy(edge -> {
                    assertThat(edge.fromNodeId()).isEqualTo("evidence:EV-2");
                    assertThat(edge.toNodeId()).isEqualTo("criterion:pool_exhausted");
                    assertThat(edge.relation()).isEqualTo(InvestigationTraceView.RelationType.SUPPORTS);
                })
                .anySatisfy(edge -> {
                    assertThat(edge.fromNodeId()).isEqualTo("criterion:pool_exhausted");
                    assertThat(edge.toNodeId()).isEqualTo("rule:pool-exhausted");
                    assertThat(edge.relation()).isEqualTo(InvestigationTraceView.RelationType.SUPPORTS);
                })
                .anySatisfy(edge -> {
                    assertThat(edge.fromNodeId()).isEqualTo("rule:pool-exhausted");
                    assertThat(edge.toNodeId()).isEqualTo("conclusion:diag-1");
                    assertThat(edge.relation()).isEqualTo(InvestigationTraceView.RelationType.SUPPORTS);
                });
        assertThatThrownBy(() -> view.stages().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void projectsTheLatestImmutableEvidenceRunTimingOnTheCollectionStage() {
        ScenarioEvidenceRunAudit run = new ScenarioEvidenceRunAudit(
                "scenario-evidence-run-1",
                "diag-1",
                new PlaybookVersionRef("playbook-903001", 3),
                DiagnosisStatus.READY_FOR_HUMAN,
                ConclusionType.LOCATED,
                List.of("EV-2"),
                CONCLUSION_AT.plusSeconds(10),
                CONCLUSION_AT.plusSeconds(17),
                "alice");

        InvestigationTraceView view = projector.project(
                deterministicDiagnosis(), frozenPlaybook(), derivation(), run);

        InvestigationTraceView.StageView collection =
                stage(view, InvestigationTraceView.StageKey.EVIDENCE_COLLECTION);
        assertThat(collection.startedAt()).isEqualTo(CONCLUSION_AT.plusSeconds(10));
        assertThat(collection.completedAt()).isEqualTo(CONCLUSION_AT.plusSeconds(17));
        assertThat(collection.duration()).isEqualTo(Duration.ofSeconds(7));
        assertThat(collection.fields()).anySatisfy(field -> {
            assertThat(field.label()).isEqualTo("本次运行编号");
            assertThat(field.value()).isEqualTo("scenario-evidence-run-1");
        });
        assertThat(view.investigationDuration())
                .as("首次结论的北极星耗时不得被后续取证改写")
                .isEqualTo(Duration.ofSeconds(164));
    }

    @Test
    void marksUnavailableFactsAsUnrecordedAndExplainsFailClosedStop() {
        InvestigationTraceView view = projector.project(abstainedDiagnosis(), null, null);

        assertThat(view.stages()).hasSize(7);
        assertThat(stage(view, InvestigationTraceView.StageKey.EVIDENCE_CONTRACT).status())
                .isEqualTo(InvestigationTraceView.StageStatus.UNRECORDED);
        assertThat(stage(view, InvestigationTraceView.StageKey.EVIDENCE_CONTRACT).summary())
                .isEqualTo("未记录");
        assertThat(stage(view, InvestigationTraceView.StageKey.CRITERION_EVALUATION).summary())
                .isEqualTo("未记录");
        assertThat(stage(view, InvestigationTraceView.StageKey.PLAYBOOK_ROUTE).fields())
                .anySatisfy(field -> {
                    assertThat(field.label()).isEqualTo("冻结 Playbook");
                    assertThat(field.value()).isEqualTo("未记录");
                });
        assertThat(view.stopReason().code())
                .isEqualTo(InvestigationTraceView.StopReasonCode.ABSTAINED);
        assertThat(view.stopReason().message()).contains("更细停止原因未记录");
        assertThat(view.adapterAttempts()).singleElement().satisfies(attempt -> {
            assertThat(attempt.status()).isEqualTo(EvidenceStatus.MISSING);
            assertThat(attempt.duration()).isNull();
            assertThat(attempt.requestId()).isEqualTo("未记录");
        });
        assertThat(view.evidenceRelation().available()).isFalse();
        assertThat(view.evidenceRelation().emptyReason()).isEqualTo("未记录");
    }

    @Test
    void projectsTheBoundedOpenDiscoveryPlanBudgetsAndPreciseStopReason() {
        OpenDiscoveryRunAudit run = new OpenDiscoveryRunAudit(
                "run-2",
                "diag-2",
                List.of("message_send_failed"),
                "message_send_failed",
                "a".repeat(64),
                List.of("log_search", "log_trace_bundle", "contrast_sample"),
                6,
                6,
                3,
                Duration.ofSeconds(20),
                OpenDiscoveryRunAudit.StopReason.CORE_EVIDENCE_INCOMPLETE,
                List.of("ONLINE-LOG-SEARCH", "ONLINE-TRACE-BUNDLE"),
                READY_AT,
                READY_AT.plusSeconds(4),
                "agent:88");

        InvestigationTraceView view = projector.project(
                abstainedDiagnosis(), null, null, null, run);

        InvestigationTraceView.StageView route =
                stage(view, InvestigationTraceView.StageKey.PLAYBOOK_ROUTE);
        assertThat(route.status()).isEqualTo(InvestigationTraceView.StageStatus.COMPLETED);
        assertThat(route.summary()).contains("受限调查").contains("message_send_failed");
        assertThat(route.fields())
                .anySatisfy(field -> {
                    assertThat(field.label()).isEqualTo("本次选中的调查计划");
                    assertThat(field.value()).isEqualTo("message_send_failed");
                })
                .anySatisfy(field -> {
                    assertThat(field.label()).isEqualTo("调查计划指纹");
                    assertThat(field.value()).isEqualTo("a".repeat(64));
                })
                .anySatisfy(field -> {
                    assertThat(field.label()).isEqualTo("最多推理轮次");
                    assertThat(field.value()).isEqualTo("6");
                })
                .anySatisfy(field -> {
                    assertThat(field.label()).isEqualTo("总时长上限");
                    assertThat(field.value()).isEqualTo("PT20S");
                });

        InvestigationTraceView.StageView contract =
                stage(view, InvestigationTraceView.StageKey.EVIDENCE_CONTRACT);
        assertThat(contract.status()).isEqualTo(InvestigationTraceView.StageStatus.PARTIAL);
        assertThat(contract.summary()).contains("计划查 3 类只读数据");
        assertThat(contract.fields()).anySatisfy(field -> {
            assertThat(field.label()).isEqualTo("计划查询的数据");
            assertThat(field.value())
                    .isEqualTo("log_search → log_trace_bundle → contrast_sample");
        });

        InvestigationTraceView.StageView collection =
                stage(view, InvestigationTraceView.StageKey.EVIDENCE_COLLECTION);
        assertThat(collection.startedAt()).isNull();
        assertThat(collection.completedAt()).isNull();
        assertThat(collection.duration()).isNull();
        assertThat(collection.fields())
                .anySatisfy(field -> {
                    assertThat(field.label()).isEqualTo("实际发起的只读查询");
                    assertThat(field.value()).isEqualTo("3");
                })
                .anySatisfy(field -> {
                    assertThat(field.label()).isEqualTo("只读查询上限");
                    assertThat(field.value()).isEqualTo("6");
                });
        InvestigationTraceView.StageView conclusion =
                stage(view, InvestigationTraceView.StageKey.CONCLUSION);
        assertThat(conclusion.fields()).anySatisfy(field -> {
            assertThat(field.label()).isEqualTo("受限调查总耗时");
            assertThat(field.value()).isEqualTo("PT4S");
        });
        assertThat(view.stopReason().code())
                .isEqualTo(InvestigationTraceView.StopReasonCode.EVIDENCE_MISSING);
        assertThat(view.stopReason().message()).contains("核心证据链不完整");
    }

    @Test
    void detectsAnEntirelyAbsentRequiredRequestWithoutGuessingFromFreeText() {
        InvestigationTraceView view = projector.project(
                abstainedDiagnosis(), frozenPlaybook(), null);

        assertThat(view.stopReason().code())
                .isEqualTo(InvestigationTraceView.StopReasonCode.EVIDENCE_MISSING);
        assertThat(view.stopReason().evidenceRefs()).containsExactly("EV-2");
        assertThat(view.stopReason().message()).contains("EV-2");
        assertThat(stage(view, InvestigationTraceView.StageKey.EVIDENCE_COLLECTION).status())
                .isEqualTo(InvestigationTraceView.StageStatus.PARTIAL);
        assertThat(stage(view, InvestigationTraceView.StageKey.EVIDENCE_COLLECTION).fields())
                .anySatisfy(field -> {
                    assertThat(field.label()).isEqualTo("缺失必需请求");
                    assertThat(field.value()).isEqualTo("EV-2");
                });
    }

    @Test
    void withholdsAConclusionLineageWhenFrozenPlaybookRebuildIsNotFaithful() {
        DiagnosisDerivation drifted = new DiagnosisDerivation(
                "diag-1", "csdp:903001", false,
                "冻结判据重算与当时触发信号不一致",
                derivation().criteria(), derivation().rules());

        InvestigationTraceView view = projector.project(
                deterministicDiagnosis(), frozenPlaybook(), drifted);

        assertThat(stage(view, InvestigationTraceView.StageKey.CRITERION_EVALUATION).status())
                .isEqualTo(InvestigationTraceView.StageStatus.PARTIAL);
        assertThat(view.evidenceRelation().available()).isFalse();
        assertThat(view.evidenceRelation().emptyReason()).contains("不一致");
        assertThat(view.evidenceRelation().edges())
                .noneMatch(edge -> edge.toNodeId().equals("conclusion:diag-1"));
    }

    @Test
    void exposesOnlyWhitelistedCanonicalFactsAndNeverRawLogBodies() {
        EvidenceResult rawTrace = new EvidenceResult(
                "TRACE-1", "L", "D::logs:(message) { raw query }", EvidenceStatus.ANOMALY,
                "raw customer log body", Map.of(
                        "ps_id", "ps-1",
                        "entries", List.of(Map.of(
                                "timestamp", 1_722_171_200_000L,
                                "service", "order-service",
                                "level", "ERROR",
                                "message", "raw customer log body token=do-not-leak",
                                "duration_ms", 42))),
                "guance:log_trace_bundle", CONCLUSION_AT);

        DiagnosisDerivation rawLogDerivation = new DiagnosisDerivation(
                "diag-1", null, true, null,
                List.of(new DiagnosisDerivation.CriterionEvaluation(
                        "send_failed", "TRACE-1", "日志命中失败特征", "contains_and_in",
                        "sample_message ∋ send failed",
                        "sample_message=\"raw customer log body token=do-not-leak\"",
                        CriterionOutcome.SATISFIED, EvidenceStatus.ANOMALY)),
                List.of());
        InvestigationTraceView view = projector.project(
                deterministicDiagnosisWithEvidence(List.of(rawTrace), List.of()),
                null,
                rawLogDerivation);

        assertThat(view.adapterAttempts()).singleElement().satisfies(attempt -> {
            assertThat(attempt.query()).isEqualTo("未记录");
            assertThat(attempt.summary()).isEqualTo("log_trace_bundle 证据 · ANOMALY");
            assertThat(attempt.observed())
                    .containsEntry("ps_id", "ps-1")
                    .containsEntry("entry_count", 1)
                    .doesNotContainKeys("entries", "message");
            assertThat(attempt.toString()).doesNotContain("raw customer log body", "raw query");
        });
        assertThat(stage(view, InvestigationTraceView.StageKey.INCIDENT).fields())
                .filteredOn(field -> field.label().equals("原始输入"))
                .singleElement()
                .satisfies(field -> assertThat(field.value()).contains("不进入该投影"));
        assertThat(view.evidenceRelation().nodes())
                .filteredOn(node -> node.kind() == InvestigationTraceView.RelationNodeKind.CRITERION)
                .singleElement()
                .satisfies(node -> {
                    assertThat(node.detail())
                            .contains("判据结果=SATISFIED", "沿证据引用查看安全字段")
                            .doesNotContain("raw customer log body", "do-not-leak");
                });
    }

    @Test
    void exposesSanitizedImportedSkillFactsInTheTraceAndEvidenceRelation() {
        List<EvidenceResult> skillEvidence = List.of(
                new EvidenceResult(
                        "EV-ERROR", "L", "", EvidenceStatus.ANOMALY,
                        "aggregate error scan", Map.of(
                                "error_count", 12,
                                "affected_trace_count", 7,
                                "latest_trace_id", "trace-007"),
                        "guance:error_log_scan", CONCLUSION_AT),
                new EvidenceResult(
                        "EV-MONITOR", "E", "", EvidenceStatus.ANOMALY,
                        "aggregate monitor scan", Map.of(
                                "event_count", 2,
                                "latest_status", "critical",
                                "latest_checker", "csdp-api-error-rate"),
                        "guance:monitor_event_scan", CONCLUSION_AT),
                new EvidenceResult(
                        "EV-K8S", "O+M", "", EvidenceStatus.NORMAL,
                        "aggregate workload health", Map.of(
                                "pod_count", 3,
                                "container_count", 4,
                                "running_container_count", 3,
                                "unhealthy_container_count", 1,
                                "max_cpu_percent", 82.5,
                                "max_memory_percent", 76.25),
                        "guance:k8s_workload_health", CONCLUSION_AT));

        InvestigationTraceView view = projector.project(
                deterministicDiagnosisWithEvidence(skillEvidence, List.of()), null, null);

        assertThat(view.adapterAttempts())
                .extracting(InvestigationTraceView.AdapterAttemptView::signalKind)
                .containsExactly(
                        "error_log_scan", "monitor_event_scan", "k8s_workload_health");
        assertThat(view.adapterAttempts().get(0).observed())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "error_count", 12,
                        "affected_trace_count", 7,
                        "latest_trace_id", "trace-007"));
        assertThat(view.adapterAttempts().get(1).observed())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "event_count", 2,
                        "latest_status", "critical",
                        "latest_checker", "csdp-api-error-rate"));
        assertThat(view.adapterAttempts().get(2).observed())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "pod_count", 3,
                        "container_count", 4,
                        "running_container_count", 3,
                        "unhealthy_container_count", 1,
                        "max_cpu_percent", 82.5,
                        "max_memory_percent", 76.25));
        assertThat(view.evidenceRelation().nodes())
                .filteredOn(node -> node.kind()
                        == InvestigationTraceView.RelationNodeKind.EVIDENCE)
                .extracting(InvestigationTraceView.RelationNode::label)
                .containsExactly(
                        "error_log_scan 证据 · ANOMALY",
                        "monitor_event_scan 证据 · ANOMALY",
                        "k8s_workload_health 证据 · NORMAL");
    }

    @Test
    void keepsLegacyDerivedRouteSemanticsExplicitlyUnrecorded() {
        InvestigationTraceView view = projector.project(legacyDiagnosis(), null, null);

        InvestigationTraceView.StageView route = stage(
                view, InvestigationTraceView.StageKey.PLAYBOOK_ROUTE);
        assertThat(route.status()).isEqualTo(InvestigationTraceView.StageStatus.PARTIAL);
        assertThat(route.summary()).contains("旧合同未持久化");
        assertThat(route.fields())
                .anySatisfy(field -> {
                    assertThat(field.label()).isEqualTo("调查模式");
                    assertThat(field.value()).isEqualTo("未记录");
                })
                .anySatisfy(field -> {
                    assertThat(field.label()).isEqualTo("路由权威");
                    assertThat(field.value()).isEqualTo("未记录");
                })
                .anySatisfy(field -> {
                    assertThat(field.label()).isEqualTo("兼容路由模式");
                    assertThat(field.value()).isEqualTo("DETERMINISTIC");
                });
    }

    private InvestigationTraceView.StageView stage(
            InvestigationTraceView view,
            InvestigationTraceView.StageKey key) {
        return view.stages().stream()
                .filter(stage -> stage.key() == key)
                .findFirst()
                .orElseThrow();
    }

    private Diagnosis deterministicDiagnosis() {
        return deterministicDiagnosisWithEvidence(
                List.of(new EvidenceResult(
                        "EV-2", "M", "M::mongodb:(pool_ratio)", EvidenceStatus.ANOMALY,
                        "Mongo 连接池利用率达到 100%", Map.of(
                                "reachable", true,
                                "connections_current", 100,
                                "connections_available", 0,
                                "slow_query_count", 12,
                                "baseline_slow", 1),
                        "recorded-replay", CONCLUSION_AT)),
                List.of("pool_exhausted"));
    }

    private Diagnosis deterministicDiagnosisWithEvidence(
            List<EvidenceResult> evidence,
            List<String> triggeredSignals) {
        IncidentContext incident = new IncidentContext(
                "incident-1", "CSDP", "order-service", "903001",
                "订单创建超时", "P1", "订单创建成功率下降",
                "ps-1", CONCLUSION_AT, "18m", "alert_webhook",
                IncidentCompleteness.STRUCTURED, "code=903001");
        return Diagnosis.initial(
                "diag-1", "case-1", "run-1", incident,
                RouteMode.DETERMINISTIC,
                InvestigationMode.ERROR_CODE_PLAYBOOK,
                RouteAuthority.EXPLICIT,
                ConclusionType.LOCATED,
                NorthStarTimings.concluded(REPORTED_AT, READY_AT, CONCLUSION_AT),
                DiagnosisStatus.READY_FOR_HUMAN,
                "连接池利用率达到 100%", "Mongo 连接池打满", Confidence.HIGH, false,
                "csdp:903001", "订单服务 Mongo 连接池耗尽", "DBA 组",
                new PlaybookVersionRef("playbook-903001", 3),
                evidence,
                triggeredSignals, List.of(), "DBA 组",
                true, true, List.of("Recorded replay fixture"), List.of());
    }

    private Diagnosis abstainedDiagnosis() {
        IncidentContext incident = new IncidentContext(
                "incident-2", "CSDP", "order-service", null,
                "订单创建超时", "P2", "影响未记录", null,
                CONCLUSION_AT, null, "manual",
                IncidentCompleteness.SYMPTOM, null);
        return Diagnosis.initial(
                "diag-2", "case-2", "run-2", incident,
                RouteMode.LLM_FALLBACK, DiagnosisStatus.NEEDS_INVESTIGATION,
                "证据不足，已停止自动判断", "", Confidence.LOW, true,
                null, null,
                List.of(new EvidenceResult(
                        "EV-MISSING", "T", "", EvidenceStatus.MISSING,
                        "调用链证据未取得", Map.of(), "guance:unavailable", CONCLUSION_AT)),
                List.of(), List.of(), null,
                false, false, List.of("调用链数据源不可用"));
    }

    private Diagnosis legacyDiagnosis() {
        IncidentContext incident = new IncidentContext(
                "incident-legacy", "CSDP", "order-service", "903001",
                "订单创建超时", "P1", "影响未记录", null,
                CONCLUSION_AT, null, "manual",
                IncidentCompleteness.STRUCTURED, null);
        return new Diagnosis(
                "diag-legacy", "1.4", "case-legacy", "run-legacy", incident,
                RouteMode.DETERMINISTIC, null, null, null,
                DiagnosisStatus.READY_FOR_HUMAN,
                "legacy summary", "legacy root cause", Confidence.HIGH, false,
                "csdp:903001", "legacy playbook", null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(), null,
                List.of(), List.of(), null, List.of(), List.of(), null,
                true, true, false, List.of());
    }

    private SopEntry frozenPlaybook() {
        return new SopEntry(
                "playbook-903001", SopEntry.CURRENT_CONTRACT_VERSION,
                "CSDP", "903001", "order-service", "订单服务 Mongo 连接池耗尽",
                "Mongo 连接池打满", "database", "DBA 组", "approved", true,
                List.of(new EvidenceRequest(
                        "EV-2", "metric", "检查 Mongo 连接池利用率",
                        Map.of(
                                "service", "mongodb",
                                "apiKey", "must-not-leak",
                                "credentials", Map.of("value", "must-not-leak")),
                        "5m", true)),
                List.of(new AnomalyCriterion(
                        "pool_exhausted", "EV-2", "连接池耗尽",
                        new Criterion.RatioOfSumGt("used", "max", 0.95))),
                List.of(new DiagnosisRule(
                        "pool-exhausted", List.of("pool_exhausted"),
                        "Mongo 连接池打满", "连接池利用率超过阈值", Confidence.HIGH, false)),
                List.of(new RecommendedAction(
                        "contact-dba", ActionType.HUMAN_CONTACT, "联系 DBA", "携带取证结果",
                        false, ApprovalStatus.NOT_REQUIRED, ExecutionStatus.PENDING)));
    }

    private DiagnosisDerivation derivation() {
        return new DiagnosisDerivation(
                "diag-1", "csdp:903001", true, null,
                List.of(new DiagnosisDerivation.CriterionEvaluation(
                        "pool_exhausted", "EV-2", "连接池耗尽", "ratio_gt",
                        "used / max > 0.95", "1 > 0.95", CriterionOutcome.SATISFIED,
                        EvidenceStatus.ANOMALY)),
                List.of(new DiagnosisDerivation.RuleEvaluation(
                        "pool-exhausted", List.of("pool_exhausted"),
                        "Mongo 连接池打满", Confidence.HIGH, true,
                        List.of(), List.of(), List.of())));
    }
}
