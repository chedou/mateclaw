package vip.mate.troubleshooting.projection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.troubleshooting.agent.OpenDiscoveryRunAudit;
import vip.mate.troubleshooting.agent.OpenDiscoveryRunAuditService;
import vip.mate.troubleshooting.evidence.ScenarioEvidenceRunAudit;
import vip.mate.troubleshooting.evidence.ScenarioEvidenceRunAuditService;
import vip.mate.troubleshooting.model.ActionType;
import vip.mate.troubleshooting.model.ApprovalStatus;
import vip.mate.troubleshooting.model.BlastRadius;
import vip.mate.troubleshooting.model.Confidence;
import vip.mate.troubleshooting.model.ConclusionType;
import vip.mate.troubleshooting.model.CriterionOutcome;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.DiagnosisDerivation;
import vip.mate.troubleshooting.model.DiagnosisStatus;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.ExecutionStatus;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.model.IncidentImpact;
import vip.mate.troubleshooting.model.InvestigationMode;
import vip.mate.troubleshooting.model.KnowledgeEvidenceGrade;
import vip.mate.troubleshooting.model.NorthStarTimings;
import vip.mate.troubleshooting.model.PlaybookVersionRef;
import vip.mate.troubleshooting.model.RecommendedAction;
import vip.mate.troubleshooting.model.RouteSemanticsProvenance;
import vip.mate.troubleshooting.model.RouteMode;
import vip.mate.troubleshooting.model.RouteAuthority;
import vip.mate.troubleshooting.model.SopEntry;
import vip.mate.troubleshooting.model.TimelineEvent;
import vip.mate.troubleshooting.deployment.DeploymentTopologyScenarioPolicy;
import vip.mate.troubleshooting.service.DiagnosisDerivationService;
import vip.mate.troubleshooting.service.StoredDiagnosis;
import vip.mate.troubleshooting.service.TroubleshootingPersistenceService;
import vip.mate.troubleshooting.service.TroubleshootingPlaybookVersionService;
import vip.mate.troubleshooting.synthesis.DeterministicLogTraceCompressor;
import vip.mate.troubleshooting.synthesis.ApprovedPlaybookVersion;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiagnosisExperienceProjectionServiceTest {

    private static final long WORKSPACE_ID = 1L;
    private static final String DIAGNOSIS_ID = "diag-1";
    private static final Instant NOW = Instant.parse("2026-07-28T12:43:14Z");
    private static final Instant REPORTED_AT = Instant.parse("2026-07-28T12:40:00Z");
    private static final Instant READY_AT = Instant.parse("2026-07-28T12:40:30Z");

    @Mock
    private TroubleshootingPersistenceService persistence;

    @Mock
    private DiagnosisDerivationService derivationService;

    @Mock
    private DeploymentTopologyScenarioPolicy topologyScenarioPolicy;

    @Mock
    private TroubleshootingPlaybookVersionService playbookVersions;

    @Mock
    private ScenarioEvidenceRunAuditService runAudits;

    @Mock
    private OpenDiscoveryRunAuditService openDiscoveryRuns;

    @Mock
    private SystemOnboardingGapService onboardingGaps;

    private DiagnosisExperienceProjectionService service;

    @BeforeEach
    void setUp() {
        service = new DiagnosisExperienceProjectionService(
                persistence,
                derivationService,
                new CanonicalEvidenceViewProjector(new DeterministicLogTraceCompressor()),
                topologyScenarioPolicy,
                playbookVersions,
                new InvestigationTraceProjector(),
                runAudits,
                openDiscoveryRuns,
                onboardingGaps);
    }

    @Test
    void projectsOneDiagnosisForBusinessAndDeveloperWithoutInventingImpact() {
        SopEntry frozenPlaybook = mock(SopEntry.class);
        when(frozenPlaybook.routingKey()).thenReturn("csdp:903001");
        when(frozenPlaybook.contractVersion()).thenReturn(SopEntry.CURRENT_CONTRACT_VERSION);
        when(frozenPlaybook.evidenceRequests()).thenReturn(List.of(new EvidenceRequest(
                "EV-2", "metric", "检查 Mongo 连接池利用率",
                Map.of("service", "mongodb"), "5m", true)));
        ApprovedPlaybookVersion frozenVersion = mock(ApprovedPlaybookVersion.class);
        when(frozenVersion.selectorKey()).thenReturn("csdp:903001");
        when(frozenVersion.playbook()).thenReturn(frozenPlaybook);
        when(frozenVersion.knowledgeEvidenceGrade())
                .thenReturn(KnowledgeEvidenceGrade.AUTHORED_FIXTURE);
        when(persistence.get(WORKSPACE_ID, DIAGNOSIS_ID))
                .thenReturn(new StoredDiagnosis(deterministicDiagnosis(), 2, false));
        when(derivationService.explain(WORKSPACE_ID, DIAGNOSIS_ID))
                .thenReturn(derivation());
        when(playbookVersions.findByRef(
                WORKSPACE_ID, new PlaybookVersionRef("playbook-903001", 3)))
                .thenReturn(Optional.of(frozenVersion));
        DiagnosisExperienceProjection result = service.project(WORKSPACE_ID, DIAGNOSIS_ID);

        DiagnosisExperienceProjection.BusinessSummary business = result.businessSummary();
        assertThat(business.conclusionType())
                .isEqualTo(ConclusionType.LOCATED);
        assertThat(business.problem()).isEqualTo("订单创建超时");
        assertThat(business.impact().functionScope()).isEqualTo("订单创建成功率下降");
        assertThat(business.impact().affectedCustomers()).isNull();
        assertThat(business.impact().affectedUsers()).isNull();
        assertThat(business.impact().blastRadius())
                .isEqualTo(BlastRadius.UNKNOWN);
        assertThat(business.impact().evidenceRefs()).containsExactly("EV-1");
        assertThat(business.impact().observedAt()).isEqualTo(NOW);
        assertThat(business.impact().note())
                .contains("148 条")
                .contains("不是客户数或用户数");
        assertThat(business.timings().reportedAt()).isEqualTo(REPORTED_AT);
        assertThat(business.timings().readyAt()).isEqualTo(READY_AT);
        assertThat(business.timings().conclusionAt()).isEqualTo(NOW);
        assertThat(business.timings().intakeCost()).isEqualTo(java.time.Duration.ofSeconds(30));
        assertThat(business.timings().investigateCost())
                .isEqualTo(java.time.Duration.ofSeconds(164));
        assertThat(business.fixtureMode()).isTrue();
        assertThat(business.rootCause()).isEqualTo("Mongo 连接池打满");
        assertThat(business.headline()).isEqualTo("已定位到出问题的环节");
        assertThat(business.narrative()).isEqualTo("连接池利用率达到 100%");
        assertThat(business.keyEvidence()).isNull();

        DiagnosisExperienceProjection.DeveloperEvidenceView developer = result.developerEvidence();
        assertThat(developer.investigationMode())
                .isEqualTo(InvestigationMode.ERROR_CODE_PLAYBOOK);
        verify(runAudits, never()).latest(WORKSPACE_ID, DIAGNOSIS_ID);
        assertThat(developer.routeSemanticsProvenance())
                .isEqualTo(RouteSemanticsProvenance.PERSISTED);
        assertThat(developer.routeAuthority())
                .isEqualTo(RouteAuthority.EXPLICIT);
        assertThat(developer.playbookRef())
                .isEqualTo("csdp:903001 · playbook-903001@v3");
        assertThat(developer.knowledgeEvidenceGrade())
                .as("903001 的夹具身份必须跟随冻结版本出现在开发证据台")
                .isEqualTo(KnowledgeEvidenceGrade.AUTHORED_FIXTURE);
        assertThat(developer.scenarioAffordances()).isEmpty();
        assertThat(developer.callChain().psId()).isEqualTo("synthetic-trace-903001");
        assertThat(developer.callChain().hops()).hasSize(1);
        assertThat(developer.callChain().hops().getFirst().service()).isEqualTo("mongo.find");
        assertThat(developer.callChain().hops().getFirst().duration()).isEqualTo("3001 ms");
        assertThat(developer.callChain().hops().getFirst().anomalous()).isTrue();
        assertThat(developer.callChain().emptyReason()).isNull();
        assertThat(developer.capabilityLimits())
                .anyMatch(item -> item.contains("仅保存异常 hop") && item.contains("完整调用链"));
        assertThat(developer.steps())
                .extracting(DiagnosisExperienceProjection.EvidenceStep::tone)
                .contains(
                        DiagnosisExperienceProjection.StepTone.ANOMALY,
                        DiagnosisExperienceProjection.StepTone.EXCLUDED,
                        DiagnosisExperienceProjection.StepTone.UNEVALUATED);
        assertThat(developer.investigationTrace().stages()).hasSize(7);
        assertThat(developer.investigationTrace().adapterAttempts()).hasSize(3);
        assertThat(developer.investigationTrace().evidenceContracts())
                .extracting(InvestigationTraceView.EvidenceContractView::requestId)
                .containsExactly("EV-2");
        assertThat(developer.contrast().available()).isFalse();
        assertThat(developer.capabilityLimits()).anyMatch(item -> item.contains("改生产环境"));
        assertThat(developer.fixtureMode()).isTrue();
    }

    @Test
    void withholdsObservedLogTextFromEveryDeveloperCriterionProjection() {
        DiagnosisDerivation rawLogDerivation = new DiagnosisDerivation(
                DIAGNOSIS_ID, "csdp:903001", true, null,
                List.of(new DiagnosisDerivation.CriterionEvaluation(
                        "send_failed", "EV-1", "日志命中失败特征", "contains_and_in",
                        "sample_message ∋ send failed",
                        "sample_message=\"raw customer body token=do-not-leak\"",
                        CriterionOutcome.SATISFIED, EvidenceStatus.ANOMALY)),
                List.of());
        when(persistence.get(WORKSPACE_ID, DIAGNOSIS_ID))
                .thenReturn(new StoredDiagnosis(deterministicDiagnosis(), 0, true));
        when(derivationService.explain(WORKSPACE_ID, DIAGNOSIS_ID))
                .thenReturn(rawLogDerivation);

        DiagnosisExperienceProjection.DeveloperEvidenceView developer =
                service.project(WORKSPACE_ID, DIAGNOSIS_ID).developerEvidence();

        assertThat(developer.steps())
                .filteredOn(step -> step.kind()
                        == DiagnosisExperienceProjection.EvidenceStepKind.CRITERION)
                .singleElement()
                .satisfies(step -> assertThat(step.detail())
                        .contains("判据结果=SATISFIED", "沿证据引用查看安全字段")
                        .doesNotContain("raw customer body", "do-not-leak"));
        assertThat(developer.investigationTrace().evidenceRelation().nodes())
                .noneMatch(node -> node.detail().contains("raw customer body")
                        || node.detail().contains("do-not-leak"));
    }

    @Test
    void projectsBoundedCanonicalTraceAndNegativeControlWithoutNewStorage() {
        when(persistence.get(WORKSPACE_ID, DIAGNOSIS_ID))
                .thenReturn(new StoredDiagnosis(evidenceRichDiagnosis(), 0, true));
        when(derivationService.explain(WORKSPACE_ID, DIAGNOSIS_ID))
                .thenReturn(derivation());

        DiagnosisExperienceProjection result = service.project(WORKSPACE_ID, DIAGNOSIS_ID);

        DiagnosisExperienceProjection.DeveloperEvidenceView developer = result.developerEvidence();
        assertThat(developer.callChain().psId())
                .isEqualTo("synthetic-ps-message-send-001");
        assertThat(developer.callChain().hops())
                .extracting(DiagnosisExperienceProjection.Hop::service)
                .containsExactly("session-api", "session-state", "session-api");
        assertThat(developer.callChain().hops())
                .extracting(DiagnosisExperienceProjection.Hop::duration)
                .containsExactly("未记录", "42 ms", "87 ms");
        assertThat(developer.callChain().hops())
                .extracting(DiagnosisExperienceProjection.Hop::anomalous)
                .containsExactly(false, true, true);
        assertThat(developer.callChain().emptyReason()).isNull();

        assertThat(developer.contrast().available()).isTrue();
        assertThat(developer.contrast().featureCode()).isEqualTo("session_state_conflict");
        assertThat(developer.contrast().failedRequests())
                .isEqualTo(new DiagnosisExperienceProjection.ComparisonGroupView(100, 92));
        assertThat(developer.contrast().normalRequests())
                .isEqualTo(new DiagnosisExperienceProjection.ComparisonGroupView(100, 3));
        assertThat(developer.contrast().note())
                .isEqualTo("失败请求与正常请求的结构化对照已记录。");
        assertThat(developer.contrast().evidenceRefs())
                .containsExactly("SYNTH-CONTRAST-SAMPLE");
        assertThat(developer.failureBreakdown().available()).isFalse();
        assertThat(result.businessSummary().rootCause())
                .isEqualTo("session-state 并发状态写入冲突");
        assertThat(result.businessSummary().narrative())
                .isEqualTo("会话状态写入冲突");
        assertThat(result.businessSummary().keyEvidence())
                .isEqualTo("异常 92/100 命中同一特征，正常 3/100。");
        assertThat(developer.capabilityLimits())
                .noneMatch(item -> item.contains("尚未保存完整调用链 hop 和成功样本对照"));
    }

    @Test
    void projectsCtiRequestGroupsAsParallelCluesRatherThanOneRootCause() {
        CanonicalEvidenceViewProjector projector = new CanonicalEvidenceViewProjector(
                new DeterministicLogTraceCompressor());

        DiagnosisExperienceProjection.FailureBreakdownView breakdown =
                projector.project(ctiFailurePatternDiagnosis()).failureBreakdown();

        assertThat(breakdown.available()).isTrue();
        assertThat(breakdown.totalRequests()).isEqualTo(2);
        assertThat(breakdown.groups())
                .extracting(DiagnosisExperienceProjection.FailureGroupView::label)
                .containsExactly(
                        "创建会话时缺少必填业务编码",
                        "下游创建会话时未找到所需记录或缓存");
        assertThat(breakdown.unclassifiedRequests()).isZero();
        assertThat(breakdown.note()).contains("相关线索", "人工确认");
    }

    @Test
    void distinguishesPersistedMissingContrastFromAContrastThatWasNeverSaved() {
        when(persistence.get(WORKSPACE_ID, DIAGNOSIS_ID))
                .thenReturn(new StoredDiagnosis(
                        evidenceRichDiagnosisWithMissingContrast(), 0, true));
        when(derivationService.explain(WORKSPACE_ID, DIAGNOSIS_ID))
                .thenReturn(derivation());

        DiagnosisExperienceProjection.DeveloperEvidenceView developer =
                service.project(WORKSPACE_ID, DIAGNOSIS_ID).developerEvidence();

        assertThat(developer.callChain().hops()).hasSize(3);
        assertThat(developer.contrast().available()).isFalse();
        assertThat(developer.contrast().note())
                .contains("已发起采集")
                .contains("MISSING")
                .contains("contrastAvailable=false")
                .doesNotContain("未保存同窗口成功样本对照");
        assertThat(developer.contrast().evidenceRefs())
                .containsExactly("ONLINE-CONTRAST-SAMPLE");
        assertThat(developer.capabilityLimits())
                .anyMatch(item -> item.contains("采集已执行") && item.contains("MISSING"));
    }

    @Test
    void projectsStructuredImpactOnlyWhenCanonicalEvidenceReproducesEveryMeasuredFact() {
        when(persistence.get(WORKSPACE_ID, DIAGNOSIS_ID))
                .thenReturn(new StoredDiagnosis(structuredImpactDiagnosis(2), 0, true));
        when(derivationService.explain(WORKSPACE_ID, DIAGNOSIS_ID))
                .thenReturn(derivation());

        DiagnosisExperienceProjection.ImpactView impact = service.project(
                WORKSPACE_ID, DIAGNOSIS_ID).businessSummary().impact();

        assertThat(impact.functionScope()).isEqualTo("消息发送功能");
        assertThat(impact.affectedCustomers()).isEqualTo(2);
        assertThat(impact.affectedUsers()).isEqualTo(15);
        assertThat(impact.blastRadius()).isEqualTo(BlastRadius.MULTI_CUSTOMER);
        assertThat(impact.evidenceRefs()).containsExactly("EV-IMPACT");
        assertThat(impact.observedAt()).isEqualTo(NOW);
        assertThat(impact.note()).isEqualTo("同窗口两个客户出现同类失败");
    }

    @Test
    void refusesStructuredImpactWhenReferencedEvidenceDoesNotMatchTheClaim() {
        when(persistence.get(WORKSPACE_ID, DIAGNOSIS_ID))
                .thenReturn(new StoredDiagnosis(structuredImpactDiagnosis(3), 0, true));
        when(derivationService.explain(WORKSPACE_ID, DIAGNOSIS_ID))
                .thenReturn(derivation());

        DiagnosisExperienceProjection.ImpactView impact = service.project(
                WORKSPACE_ID, DIAGNOSIS_ID).businessSummary().impact();

        assertThat(impact.affectedCustomers()).isNull();
        assertThat(impact.affectedUsers()).isNull();
        assertThat(impact.blastRadius()).isEqualTo(BlastRadius.UNKNOWN);
        assertThat(impact.evidenceRefs()).isEmpty();
        assertThat(impact.observedAt()).isNull();
        assertThat(impact.note()).contains("引用未命中").contains("未展示未证实数字");
    }

    @Test
    void refusesStructuredImpactWhenAnyReferenceIsNotCanonicalImpactEvidence() {
        when(persistence.get(WORKSPACE_ID, DIAGNOSIS_ID))
                .thenReturn(new StoredDiagnosis(
                        structuredImpactDiagnosis(2, true), 0, true));
        when(derivationService.explain(WORKSPACE_ID, DIAGNOSIS_ID))
                .thenReturn(derivation());

        DiagnosisExperienceProjection.ImpactView impact = service.project(
                WORKSPACE_ID, DIAGNOSIS_ID).businessSummary().impact();

        assertThat(impact.affectedCustomers()).isNull();
        assertThat(impact.affectedUsers()).isNull();
        assertThat(impact.blastRadius()).isEqualTo(BlastRadius.UNKNOWN);
        assertThat(impact.evidenceRefs()).isEmpty();
        assertThat(impact.note()).contains("引用未命中").contains("未展示未证实数字");
    }

    @Test
    void refusesStructuredImpactWhenCanonicalReferencesContradictEachOther() {
        when(persistence.get(WORKSPACE_ID, DIAGNOSIS_ID))
                .thenReturn(new StoredDiagnosis(
                        structuredImpactDiagnosisWithConflict(), 0, true));
        when(derivationService.explain(WORKSPACE_ID, DIAGNOSIS_ID))
                .thenReturn(derivation());

        DiagnosisExperienceProjection.ImpactView impact = service.project(
                WORKSPACE_ID, DIAGNOSIS_ID).businessSummary().impact();

        assertThat(impact.affectedCustomers()).isNull();
        assertThat(impact.affectedUsers()).isNull();
        assertThat(impact.blastRadius()).isEqualTo(BlastRadius.UNKNOWN);
        assertThat(impact.evidenceRefs()).isEmpty();
        assertThat(impact.note()).contains("引用未命中").contains("未展示未证实数字");
    }

    @Test
    void derivesScalarHopAnomalyFromObservedStatusInsteadOfTheEvidenceEnvelope() {
        when(persistence.get(WORKSPACE_ID, DIAGNOSIS_ID))
                .thenReturn(new StoredDiagnosis(
                        deterministicDiagnosis("ok", EvidenceStatus.ANOMALY), 2, false));
        when(derivationService.explain(WORKSPACE_ID, DIAGNOSIS_ID))
                .thenReturn(derivation());

        DiagnosisExperienceProjection result = service.project(WORKSPACE_ID, DIAGNOSIS_ID);

        assertThat(result.developerEvidence().callChain().hops()).isEmpty();
        assertThat(result.developerEvidence().capabilityLimits())
                .anyMatch(item -> item.contains("尚未保存可复算的完整调用链 hop"));
    }

    @Test
    void abstainsInsteadOfTurningMissingEvidenceIntoARootCause() {
        when(persistence.get(WORKSPACE_ID, DIAGNOSIS_ID))
                .thenReturn(new StoredDiagnosis(abstainedDiagnosis(), 0, true));

        DiagnosisExperienceProjection result = service.project(WORKSPACE_ID, DIAGNOSIS_ID);

        assertThat(result.businessSummary().conclusionType())
                .isEqualTo(ConclusionType.INSUFFICIENT_EVIDENCE);
        assertThat(result.businessSummary().confidence()).isEqualTo(Confidence.LOW);
        assertThat(result.businessSummary().nextStep().label()).isEqualTo("下一步");
        assertThat(result.businessSummary().nextStep().text()).doesNotContain("Mongo 连接池打满");
        assertThat(result.businessSummary().nextStep().capabilityBoundary()).contains("证据不足");
        assertThat(result.developerEvidence().investigationMode())
                .isEqualTo(InvestigationMode.OPEN_DISCOVERY);
        assertThat(result.developerEvidence().routeAuthority())
                .isEqualTo(RouteAuthority.MODEL_PROPOSED);
    }

    /**
     * An unonboarded system collects nothing, so telling the reporter to bring
     * more logs points them at evidence no configured path would have read.
     */
    @Test
    void anUnonboardedSystemAsksForConfigurationRatherThanMoreEvidence() {
        when(persistence.get(WORKSPACE_ID, DIAGNOSIS_ID))
                .thenReturn(new StoredDiagnosis(abstainedDiagnosis(), 0, true));
        when(onboardingGaps.inspect(eq(WORKSPACE_ID), any())).thenReturn(List.of(
                new SystemOnboardingGap(
                        SystemOnboardingGapKind.EVIDENCE_ROUTE,
                        "这个系统没有声明取证路由",
                        "没有显式声明就不会有默认源",
                        "工作区管理员")));

        DiagnosisExperienceProjection result = service.project(WORKSPACE_ID, DIAGNOSIS_ID);

        assertThat(result.businessSummary().nextStep().label()).isEqualTo("先完成系统接入");
        assertThat(result.businessSummary().nextStep().text())
                .contains("这个系统没有声明取证路由")
                .contains("工作区管理员");
        assertThat(result.businessSummary().nextStep().text())
                .as("the reporter cannot close a configuration gap by adding logs")
                .doesNotContain("补齐缺失的日志");
        assertThat(result.businessSummary().narrative()).contains("配置缺口");
    }

    @Test
    void aFullyOnboardedSystemKeepsTheOrdinaryEvidenceShortageWording() {
        when(persistence.get(WORKSPACE_ID, DIAGNOSIS_ID))
                .thenReturn(new StoredDiagnosis(abstainedDiagnosis(), 0, true));
        when(onboardingGaps.inspect(eq(WORKSPACE_ID), any())).thenReturn(List.of());

        DiagnosisExperienceProjection result = service.project(WORKSPACE_ID, DIAGNOSIS_ID);

        assertThat(result.businessSummary().nextStep().label()).isEqualTo("下一步");
        assertThat(result.businessSummary().nextStep().text()).contains("补齐缺失的日志");
    }

    @Test
    void successfulZeroMatchQueriesAreNotMisreportedAsMissingSystemOnboarding() {
        Diagnosis queriedButUnproven = abstainedDiagnosis().evidenceRecorded(
                ConclusionType.INSUFFICIENT_EVIDENCE,
                "",
                "现有证据不足以支持候选方向",
                Confidence.LOW,
                List.of(new EvidenceResult(
                        "EV-ZERO", "L", "", EvidenceStatus.NORMAL,
                        "查询成功但没有匹配记录",
                        Map.of("error_count", 0, "affected_trace_count", 0),
                        "guance:error_log_scan", NOW)),
                List.of(), List.of(), List.of(), List.of(new TimelineEvent(
                        NOW, "只读取证完成但未形成根因", "system", "done")));
        when(persistence.get(WORKSPACE_ID, DIAGNOSIS_ID))
                .thenReturn(new StoredDiagnosis(queriedButUnproven, 0, true));
        DiagnosisExperienceProjection result = service.project(WORKSPACE_ID, DIAGNOSIS_ID);

        assertThat(result.businessSummary().narrative())
                .contains("查询成功")
                .contains("没有找到支持当前候选的匹配记录")
                .doesNotContain("没有接入到可取证的状态");
        assertThat(result.businessSummary().nextStep().label()).isEqualTo("下一步");
        verify(onboardingGaps, never()).inspect(anyLong(), any());
    }

    @Test
    void loadsTheImmutableOpenDiscoveryRunIntoTheDeveloperTrace() {
        when(persistence.get(WORKSPACE_ID, DIAGNOSIS_ID))
                .thenReturn(new StoredDiagnosis(abstainedDiagnosis(), 0, true));
        when(openDiscoveryRuns.latest(WORKSPACE_ID, DIAGNOSIS_ID))
                .thenReturn(Optional.of(new OpenDiscoveryRunAudit(
                        "run-1",
                        DIAGNOSIS_ID,
                        List.of("message_send_failed"),
                        "message_send_failed",
                        List.of("log_search", "log_trace_bundle", "contrast_sample"),
                        6,
                        6,
                        2,
                        java.time.Duration.ofSeconds(20),
                        OpenDiscoveryRunAudit.StopReason.CORE_EVIDENCE_INCOMPLETE,
                        List.of("ONLINE-LOG-SEARCH"),
                        READY_AT,
                        READY_AT.plusSeconds(3),
                        "agent:88")));

        DiagnosisExperienceProjection result = service.project(WORKSPACE_ID, DIAGNOSIS_ID);

        assertThat(result.developerEvidence().investigationTrace().stages())
                .filteredOn(stage -> stage.key()
                        == InvestigationTraceView.StageKey.PLAYBOOK_ROUTE)
                .singleElement()
                .satisfies(stage -> assertThat(stage.summary())
                        .contains("受限调查").contains("message_send_failed"));
        assertThat(result.developerEvidence().investigationTrace().stopReason().message())
                .contains("核心证据链不完整");
        verify(runAudits, never()).latest(WORKSPACE_ID, DIAGNOSIS_ID);
    }

    @Test
    void projectsExcludedAsAReviewableExclusionRatherThanAbstentionOrLocation() {
        when(persistence.get(WORKSPACE_ID, DIAGNOSIS_ID))
                .thenReturn(new StoredDiagnosis(excludedDiagnosis(), 0, true));
        when(derivationService.explain(WORKSPACE_ID, DIAGNOSIS_ID))
                .thenReturn(derivation());

        DiagnosisExperienceProjection result = service.project(WORKSPACE_ID, DIAGNOSIS_ID);

        assertThat(result.businessSummary().conclusionType()).isEqualTo(ConclusionType.EXCLUDED);
        assertThat(result.businessSummary().confidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(result.businessSummary().nextStep().label()).isEqualTo("排除结论");
        assertThat(result.businessSummary().nextStep().capabilityBoundary())
                .contains("这是排除不是定位");
    }

    @Test
    void usesStoredScenarioModeAndRuleAuthorityInsteadOfGuessingFromLegacyRouteMode() {
        when(persistence.get(WORKSPACE_ID, DIAGNOSIS_ID))
                .thenReturn(new StoredDiagnosis(scenarioDiagnosis(), 0, true));
        when(derivationService.explain(WORKSPACE_ID, DIAGNOSIS_ID))
                .thenReturn(derivation());
        when(runAudits.latest(WORKSPACE_ID, DIAGNOSIS_ID)).thenReturn(Optional.of(
                new ScenarioEvidenceRunAudit(
                        "scenario-evidence-run-1",
                        DIAGNOSIS_ID,
                        new PlaybookVersionRef("playbook-slow-api", 2),
                        DiagnosisStatus.READY_FOR_HUMAN,
                        ConclusionType.LOCATED,
                        List.of("EV-1", "EV-2"),
                        NOW.plusSeconds(10),
                        NOW.plusSeconds(15),
                        "alice")));

        DiagnosisExperienceProjection result = service.project(WORKSPACE_ID, DIAGNOSIS_ID);

        assertThat(result.developerEvidence().investigationMode())
                .isEqualTo(InvestigationMode.SCENARIO_PLAYBOOK);
        assertThat(result.developerEvidence().routeSemanticsProvenance())
                .isEqualTo(RouteSemanticsProvenance.PERSISTED);
        assertThat(result.developerEvidence().routeAuthority())
                .isEqualTo(RouteAuthority.RULE_MATCHED);
        assertThat(result.developerEvidence().scenarioAffordances()).isEmpty();
        assertThat(result.developerEvidence().investigationTrace().stages())
                .filteredOn(stage -> stage.key()
                        == InvestigationTraceView.StageKey.EVIDENCE_COLLECTION)
                .singleElement()
                .satisfies(stage -> assertThat(stage.duration())
                        .isEqualTo(java.time.Duration.ofSeconds(5)));
    }

    @Test
    void currentScenarioPlaybookStillExplainsWhenLegacyRouteModeClaimsFallback() {
        when(persistence.get(WORKSPACE_ID, DIAGNOSIS_ID))
                .thenReturn(new StoredDiagnosis(
                        deterministicDiagnosisWithSemantics(
                                RouteMode.LLM_FALLBACK,
                                InvestigationMode.SCENARIO_PLAYBOOK,
                                RouteAuthority.RULE_MATCHED,
                                ConclusionType.LOCATED,
                                Confidence.MEDIUM,
                                "scenario:message-send-failed",
                                "会话消息发送失败",
                                new PlaybookVersionRef("playbook-message-send-failed", 1)),
                        0,
                        true));
        when(derivationService.explain(WORKSPACE_ID, DIAGNOSIS_ID))
                .thenReturn(derivation());

        DiagnosisExperienceProjection projection = service.project(WORKSPACE_ID, DIAGNOSIS_ID);

        assertThat(projection.developerEvidence().investigationMode())
                .isEqualTo(InvestigationMode.SCENARIO_PLAYBOOK);
        assertThat(projection.developerEvidence().routeAuthority())
                .isEqualTo(RouteAuthority.RULE_MATCHED);
        assertThat(projection.developerEvidence().routeSemanticsProvenance())
                .isEqualTo(RouteSemanticsProvenance.PERSISTED);
        assertThat(projection.developerEvidence().steps())
                .extracting(DiagnosisExperienceProjection.EvidenceStep::kind)
                .contains(DiagnosisExperienceProjection.EvidenceStepKind.CRITERION);
        verify(derivationService).explain(WORKSPACE_ID, DIAGNOSIS_ID);
    }

    @Test
    void currentOpenDiscoveryNeverDerivesEvenWhenLegacyRouteModeClaimsDeterministic() {
        when(persistence.get(WORKSPACE_ID, DIAGNOSIS_ID))
                .thenReturn(new StoredDiagnosis(
                        deterministicDiagnosisWithSemantics(
                                RouteMode.DETERMINISTIC,
                                InvestigationMode.OPEN_DISCOVERY,
                                RouteAuthority.MODEL_PROPOSED,
                                ConclusionType.HYPOTHESIS,
                                Confidence.MEDIUM,
                                null,
                                null,
                                null),
                        0,
                        true));

        DiagnosisExperienceProjection projection = service.project(WORKSPACE_ID, DIAGNOSIS_ID);

        assertThat(projection.developerEvidence().investigationMode())
                .isEqualTo(InvestigationMode.OPEN_DISCOVERY);
        assertThat(projection.developerEvidence().routeAuthority())
                .isEqualTo(RouteAuthority.MODEL_PROPOSED);
        assertThat(projection.developerEvidence().routeSemanticsProvenance())
                .isEqualTo(RouteSemanticsProvenance.PERSISTED);
        assertThat(projection.developerEvidence().capabilityLimits())
                .anyMatch(item -> item.contains("开放调查") && item.contains("标准排障方案"));
        assertThat(projection.developerEvidence().steps())
                .extracting(DiagnosisExperienceProjection.EvidenceStep::kind)
                .doesNotContain(DiagnosisExperienceProjection.EvidenceStepKind.CRITERION);
        verify(derivationService, never()).explain(WORKSPACE_ID, DIAGNOSIS_ID);
    }

    @Test
    void reportedHttpFailureNamesTheDirectFailurePointWithoutClaimingTheUpstreamCause() {
        when(persistence.get(WORKSPACE_ID, DIAGNOSIS_ID))
                .thenReturn(new StoredDiagnosis(icareReportedHypothesisDiagnosis(), 0, true));

        DiagnosisExperienceProjection projection = service.project(WORKSPACE_ID, DIAGNOSIS_ID);

        assertThat(projection.businessSummary().conclusionType())
                .isEqualTo(ConclusionType.HYPOTHESIS);
        assertThat(projection.businessSummary().rootCause())
                .contains("直接失败点")
                .contains("上游为何返回 502 尚未定位");
        assertThat(projection.businessSummary().keyEvidence())
                .contains("接口调用返回 HTTP 502")
                .contains("不能证明上游为什么返回 502");
        assertThat(projection.businessSummary().nextStep().label())
                .isEqualTo("继续核对 502 上游原因");
        assertThat(projection.businessSummary().nextStep().text())
                .contains("iCare 产品映射接口")
                .contains("上游日志与健康状态");
    }

    @Test
    void reportedMobileFinishPolicyShowsTheReasonAndSafeManualNextStep() {
        when(persistence.get(WORKSPACE_ID, DIAGNOSIS_ID))
                .thenReturn(new StoredDiagnosis(icareMobileFinishPolicyDiagnosis(), 0, true));

        DiagnosisExperienceProjection.BusinessSummary summary =
                service.project(WORKSPACE_ID, DIAGNOSIS_ID).businessSummary();

        assertThat(summary.evidenceBasis())
                .isEqualTo(DiagnosisExperienceProjection.EvidenceBasis.REPORTED);
        assertThat(summary.rootCause())
                .contains("工单关联变更单")
                .contains("移动端完结");
        assertThat(summary.keyEvidence())
                .contains("iCare 返回的业务提示")
                .contains("不允许在移动端完结");
        assertThat(summary.nextStep().label()).isEqualTo("改用 PC 端完结");
        assertThat(summary.nextStep().text())
                .contains("不要在移动端重试")
                .contains("iCare 技术支持");
        assertThat(summary.nextStep().capabilityBoundary())
                .contains("不会代替你提交完结操作")
                .contains("尚未验证 PC 端结果");
    }

    @Test
    void projectsTheServerResolvedTopologyToolRequirement() {
        Diagnosis diagnosis = scenarioDiagnosis();
        when(persistence.get(WORKSPACE_ID, DIAGNOSIS_ID))
                .thenReturn(new StoredDiagnosis(diagnosis, 0, true));
        when(derivationService.explain(WORKSPACE_ID, DIAGNOSIS_ID))
                .thenReturn(derivation());
        when(topologyScenarioPolicy.requiresProbe(WORKSPACE_ID, diagnosis)).thenReturn(true);

        DiagnosisExperienceProjection result = service.project(WORKSPACE_ID, DIAGNOSIS_ID);

        assertThat(result.developerEvidence()
                .requiresScenario(DeploymentTopologyScenarioPolicy.SCENARIO_KEY)).isTrue();
    }

    @Test
    void listsEveryRecommendedActionAndWhoPerformsIt() {
        Diagnosis located = Diagnosis.initial(
                DIAGNOSIS_ID, "case-1", "run-1", incident(),
                RouteMode.DETERMINISTIC,
                InvestigationMode.ERROR_CODE_PLAYBOOK,
                RouteAuthority.EXPLICIT,
                ConclusionType.LOCATED,
                NorthStarTimings.concluded(REPORTED_AT, READY_AT, NOW),
                DiagnosisStatus.READY_FOR_HUMAN,
                "对照显示工单升级路由更慢", "工单升级链路同步等待外部 IT 网关",
                Confidence.MEDIUM, false,
                "csdp:scenario:url_slow_request", "URL 慢请求",
                new PlaybookVersionRef("playbook-url-slow", 2),
                List.of(), List.of(),
                List.of(
                        new RecommendedAction(
                                "USR-A1", ActionType.AUTO_READONLY,
                                "只读复核慢请求路由分布",
                                "只读查看同窗口聚合对照，不执行任何生产变更。",
                                false, ApprovalStatus.NOT_REQUIRED, ExecutionStatus.PENDING),
                        new RecommendedAction(
                                "USR-A2", ActionType.HUMAN_CONTACT,
                                "向 IT 网关 owner 核实 upToCtiV2 基线延迟",
                                "由网关 owner 在平台之外核查；平台只提供证据。",
                                false, ApprovalStatus.NOT_REQUIRED, ExecutionStatus.PENDING)),
                "客服组", true, true, List.of(), List.of());
        when(persistence.get(WORKSPACE_ID, DIAGNOSIS_ID))
                .thenReturn(new StoredDiagnosis(located, 0, true));
        when(derivationService.explain(WORKSPACE_ID, DIAGNOSIS_ID))
                .thenReturn(derivation());

        DiagnosisExperienceProjection.NextStep next =
                service.project(WORKSPACE_ID, DIAGNOSIS_ID).businessSummary().nextStep();

        assertThat(next.label()).isEqualTo("定位结果");
        assertThat(next.text())
                .contains("1. 只读复核慢请求路由分布")
                .contains("系统可代做")
                .contains("2. 向 IT 网关 owner 核实 upToCtiV2 基线延迟")
                .contains("需人去联系确认");
    }

    @Test
    void refusesToNameACauseWhenTheConclusionIsAnAbstention() {
        DiagnosisExperienceProjection.ImpactView impact = new DiagnosisExperienceProjection.ImpactView(
                "订单创建", null, null,
                BlastRadius.UNKNOWN,
                List.of(), null, "未测量");
        DiagnosisExperienceProjection.NextStep next = new DiagnosisExperienceProjection.NextStep(
                "下一步", "补齐证据", "证据不足，系统已弃权且没有给出根因");
        assertThatThrownBy(() -> new DiagnosisExperienceProjection.BusinessSummary(
                DIAGNOSIS_ID,
                ConclusionType.INSUFFICIENT_EVIDENCE,
                "证据不足，系统已停止自动判断",
                "Mongo 连接池打满",
                "关键证据缺失。",
                null,
                Confidence.LOW,
                "订单超时", impact, next, DiagnosisStatus.READY_FOR_HUMAN,
                NorthStarTimings.unrecorded(), false,
                DiagnosisExperienceProjection.EvidenceBasis.OBSERVED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not name a root cause");
    }

    @Test
    void rejectsPreciseImpactCountsWithoutEvidenceReferences() {
        assertThatThrownBy(() -> new DiagnosisExperienceProjection.ImpactView(
                "订单创建", 12, null,
                BlastRadius.MULTI_CUSTOMER,
                List.of(), NOW, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidenceRefs");
    }

    @Test
    void keepsConclusionConfidenceInvariantsAtTheProjectionBoundary() {
        DiagnosisExperienceProjection.ImpactView impact = new DiagnosisExperienceProjection.ImpactView(
                "订单创建", null, null,
                BlastRadius.UNKNOWN,
                List.of(), null, "未测量");
        DiagnosisExperienceProjection.NextStep next = new DiagnosisExperienceProjection.NextStep(
                "排除结论", "当前假设已排除", "这是排除不是定位");
        NorthStarTimings timings = NorthStarTimings.unrecorded();

        assertThatThrownBy(() -> new DiagnosisExperienceProjection.BusinessSummary(
                DIAGNOSIS_ID,
                ConclusionType.EXCLUDED,
                "已排除当前假设", null, "证据不支持当前假设。", null, Confidence.HIGH,
                "订单超时", impact, next, DiagnosisStatus.READY_FOR_HUMAN,
                timings, false,
                DiagnosisExperienceProjection.EvidenceBasis.OBSERVED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EXCLUDED");
    }

    private Diagnosis deterministicDiagnosis() {
        return deterministicDiagnosis("timeout", EvidenceStatus.NORMAL);
    }

    private Diagnosis deterministicDiagnosisWithSemantics(
            RouteMode routeMode,
            InvestigationMode investigationMode,
            RouteAuthority routeAuthority,
            ConclusionType conclusionType,
            Confidence confidence,
            String sopKey,
            String sopTitle,
            PlaybookVersionRef sourcePlaybookVersionRef) {
        return new Diagnosis(
                DIAGNOSIS_ID,
                Diagnosis.CURRENT_CONTRACT_VERSION,
                "case-1",
                "run-1",
                incident(),
                routeMode,
                investigationMode,
                routeAuthority,
                conclusionType,
                DiagnosisStatus.READY_FOR_HUMAN,
                "route semantics override",
                "route semantics override",
                confidence,
                conclusionType == ConclusionType.INSUFFICIENT_EVIDENCE,
                sopKey,
                sopTitle,
                routeAuthority == RouteAuthority.RULE_MATCHED ? "API 组" : null,
                sourcePlaybookVersionRef,
                List.of(
                        new EvidenceResult(
                                "EV-1", "L", "L::order-svc:(count,trace_id)",
                                EvidenceStatus.ANOMALY,
                                "错误码日志计数", Map.of(
                                        "count", "148",
                                        "trace_id", "synthetic-trace-903001"),
                                "recorded-replay", NOW),
                        new EvidenceResult(
                                "EV-2", "M", "M::mongodb:(...)", EvidenceStatus.ANOMALY,
                                "Mongo 连接池利用率达到 100%", Map.of("ratio", 1),
                                "recorded-replay", NOW),
                        new EvidenceResult(
                                "EV-3", "T", "T::order-svc:(...)", EvidenceStatus.NORMAL,
                                "失败调用链定位", Map.of(
                                "failed_hop", "mongo.find",
                                        "status", "timeout",
                                        "duration_ms", "3001"),
                                "recorded-replay", NOW)),
                investigationMode == InvestigationMode.OPEN_DISCOVERY
                        ? List.of("EV-1")
                        : List.of(),
                List.of("pool_exhausted"),
                List.of(),
                List.of(),
                "DBA 组",
                List.of(),
                List.of(),
                null,
                List.of(),
                List.of(),
                NorthStarTimings.concluded(REPORTED_AT, READY_AT, NOW),
                true,
                true,
                false,
                List.of("Recorded replay fixture"));
    }

    private Diagnosis deterministicDiagnosis(
            String observedStatus,
            EvidenceStatus traceEvidenceStatus) {
        return Diagnosis.initial(
                DIAGNOSIS_ID, "case-1", "run-1", incident(),
                RouteMode.DETERMINISTIC,
                InvestigationMode.ERROR_CODE_PLAYBOOK,
                RouteAuthority.EXPLICIT,
                ConclusionType.LOCATED,
                NorthStarTimings.concluded(REPORTED_AT, READY_AT, NOW),
                DiagnosisStatus.READY_FOR_HUMAN,
                "连接池利用率达到 100%", "Mongo 连接池打满", Confidence.HIGH, false,
                "csdp:903001", "订单服务 Mongo 连接池耗尽", "DBA 组",
                new PlaybookVersionRef("playbook-903001", 3),
                List.of(
                        new EvidenceResult(
                                "EV-1", "L", "L::order-svc:(count,trace_id)",
                                EvidenceStatus.ANOMALY,
                                "错误码日志计数", Map.of(
                                        "count", "148",
                                        "trace_id", "synthetic-trace-903001"),
                                "recorded-replay", NOW),
                        new EvidenceResult(
                                "EV-2", "M", "M::mongodb:(...)", EvidenceStatus.ANOMALY,
                                "Mongo 连接池利用率达到 100%", Map.of("ratio", 1),
                                "recorded-replay", NOW),
                        new EvidenceResult(
                                "EV-3", "T", "T::order-svc:(...)", traceEvidenceStatus,
                                "失败调用链定位", Map.of(
                                        "failed_hop", "mongo.find",
                                        "status", observedStatus,
                                        "duration_ms", "3001"),
                                "recorded-replay", NOW)),
                List.of("pool_exhausted"), List.of(), "DBA 组",
                true, true, List.of("Recorded replay fixture"), List.of());
    }

    private Diagnosis evidenceRichDiagnosis() {
        EvidenceResult contrast = new EvidenceResult(
                "SYNTH-CONTRAST-SAMPLE", "L", "recorded:contrast-sample",
                EvidenceStatus.NORMAL, "同窗口成功请求与失败请求的结构化对照",
                Map.of(
                        "discriminating_feature", "session_state_conflict",
                        "failure_sample_count", "100",
                        "failure_match_count", "92",
                        "success_sample_count", "100",
                        "success_match_count", "3"),
                "recorded-replay:message-send-failed", NOW);
        return evidenceRichDiagnosis(contrast);
    }

    private Diagnosis evidenceRichDiagnosisWithMissingContrast() {
        EvidenceResult contrast = new EvidenceResult(
                "ONLINE-CONTRAST-SAMPLE", "UNKNOWN", "",
                EvidenceStatus.MISSING, "all configured evidence sources unavailable",
                Map.of(), "router:unavailable", NOW);
        return evidenceRichDiagnosis(contrast);
    }

    private Diagnosis evidenceRichDiagnosis(EvidenceResult contrast) {
        IncidentContext incident = new IncidentContext(
                "incident-1", "CSDP", "csdp-session-service", null,
                "会话消息发送失败", "P2", "消息发送功能受影响",
                null, NOW, "18m", "manual",
                IncidentCompleteness.LOG, "message send failed");
        EvidenceResult trace = new EvidenceResult(
                "SYNTH-TRACE-BUNDLE", "L", "recorded:trace-bundle",
                EvidenceStatus.ANOMALY, "PS ID 全链路日志包",
                Map.of(
                        "ps_id", "synthetic-ps-message-send-001",
                        "entries", List.of(
                                Map.of(
                                        "timestamp", "1753002781000",
                                        "service", "session-api",
                                        "level", "INFO",
                                        "message", "message accepted"),
                                Map.of(
                                        "timestamp", "1753002781042",
                                        "service", "session-state",
                                        "level", "ERROR",
                                        "message", "concurrent state write rejected",
                                        "duration_ms", "42"),
                                Map.of(
                                        "timestamp", "1753002781087",
                                        "service", "session-api",
                                        "level", "ERROR",
                                        "message", "message send failed",
                                        "duration_ms", "87"))),
                "recorded-replay:message-send-failed", NOW);
        return Diagnosis.initial(
                DIAGNOSIS_ID, "case-1", "run-1", incident,
                RouteMode.DETERMINISTIC,
                InvestigationMode.SCENARIO_PLAYBOOK,
                RouteAuthority.RULE_MATCHED,
                ConclusionType.LOCATED,
                NorthStarTimings.concluded(REPORTED_AT, READY_AT, NOW),
                DiagnosisStatus.READY_FOR_HUMAN,
                "会话状态写入冲突", "session-state 并发状态写入冲突",
                Confidence.MEDIUM, false,
                "scenario:message-send-failed", "会话消息发送失败",
                new PlaybookVersionRef("playbook-message-send-failed", 1),
                List.of(trace, contrast), List.of("session_state_conflict"),
                List.of(), "会话研发组",
                true, true, List.of("Recorded replay fixture"), List.of());
    }

    private Diagnosis ctiFailurePatternDiagnosis() {
        IncidentContext incident = new IncidentContext(
                "incident-cti", "CSDP", "csdp-task", "701018",
                "CTI创建会话失败", "P1", "会话创建受影响",
                null, NOW, "15m", "monitoring",
                IncidentCompleteness.LOG, "cti create conversation failed");
        EvidenceResult failurePatterns = new EvidenceResult(
                "CTI-FAILURE-PATTERNS", "L", "withheld",
                EvidenceStatus.ANOMALY, "request-level failure pattern counts",
                Map.of(
                        "failure_request_count", 2,
                        "classified_failure_request_count", 2,
                        "missing_required_code_request_count", 1,
                        "downstream_record_not_found_request_count", 1),
                "recorded-replay:cti-create-conversation", NOW);
        return Diagnosis.initial(
                "diag-cti", "case-cti", "run-cti", incident,
                RouteMode.DETERMINISTIC,
                InvestigationMode.SCENARIO_PLAYBOOK,
                RouteAuthority.RULE_MATCHED,
                ConclusionType.HYPOTHESIS,
                NorthStarTimings.concluded(REPORTED_AT, READY_AT, NOW),
                DiagnosisStatus.READY_FOR_HUMAN,
                "CTI 会话创建失败", "已观测到外层失败",
                Confidence.LOW, false,
                "scenario:cti-create-conversation", "CTI 创建会话失败",
                new PlaybookVersionRef("playbook-cti", 2),
                List.of(failurePatterns), List.of("cti_create_conversation_failure_present"),
                List.of(), "CSDP TASK 负责团队",
                true, false, List.of(), List.of());
    }

    private Diagnosis icareReportedHypothesisDiagnosis() {
        IncidentContext incident = new IncidentContext(
                "incident-502", "CSDP", "csdp-wechat", null,
                "调用接口异常（HTTP 502 · get_icare_product_mapping）",
                "P1", "影响待确认", null, NOW, null, "channel:web:conversation",
                IncidentCompleteness.STRUCTURED, null);
        EvidenceResult reported = new EvidenceResult(
                "open-discovery-icare-product-mapping-reported",
                "incident_reported_external_http_failure", "",
                EvidenceStatus.ANOMALY,
                "告警明确记录产品映射接口返回 HTTP 502",
                Map.of(
                        "failure_count", 1,
                        "http_status", "502",
                        "operation", "get_icare_product_mapping",
                        "evidence_grade", "REPORTED"),
                "incident-report:normalized", NOW);
        Diagnosis pending = Diagnosis.initial(
                DIAGNOSIS_ID, "case-502", "run-502", incident,
                RouteMode.LLM_FALLBACK,
                InvestigationMode.OPEN_DISCOVERY,
                RouteAuthority.POLICY_PROPOSED,
                ConclusionType.INSUFFICIENT_EVIDENCE,
                NorthStarTimings.concluded(REPORTED_AT, READY_AT, READY_AT),
                DiagnosisStatus.NEEDS_INVESTIGATION,
                "等待受限调查",
                "",
                Confidence.LOW, true,
                null, null, null,
                List.of(), List.of(), List.of(), "CSDP WECHAT 负责团队",
                true, false, List.of(), List.of());
        return pending.evidenceRecorded(
                ConclusionType.HYPOTHESIS,
                "直接失败点：iCare 产品映射外部接口返回 HTTP 502（上游为何返回 502 尚未定位）",
                "告警支持直接失败点，但上游原因尚未定位",
                Confidence.LOW,
                List.of(reported),
                List.of("icare-product-mapping-502-present"),
                List.of(), List.of(), List.of(new TimelineEvent(
                        NOW, "受限调查形成待确认假设", "system", "done")));
    }

    private Diagnosis icareMobileFinishPolicyDiagnosis() {
        IncidentContext incident = new IncidentContext(
                "incident-mobile-finish", "CSDP", "sf-icare-openapi", null,
                vip.mate.troubleshooting.investigation.ReviewedIncidentPolicy
                        .ICARE_MOBILE_CHANGE_ORDER_FINISH_REJECTED_TITLE,
                "P2", "影响待确认", null, NOW, null, "channel:web:conversation",
                IncidentCompleteness.STRUCTURED, null);
        EvidenceResult reported = new EvidenceResult(
                "open-discovery-icare-mobile-finish-reported",
                "incident_reported_business_policy_rejection", "",
                EvidenceStatus.ANOMALY,
                "iCare 已明确拒绝移动端完结",
                Map.of(
                        "failure_count", 1,
                        "operation", "updateFinish",
                        "policy_code", "mobile_change_order_finish_forbidden",
                        "client_surface", "MOBILE",
                        "change_order_linked", true,
                        "recommended_channel", "PC",
                        "evidence_grade", "REPORTED"),
                "incident-report:normalized", NOW);
        Diagnosis pending = Diagnosis.initial(
                DIAGNOSIS_ID, "case-mobile-finish", "run-mobile-finish", incident,
                RouteMode.BOUNDED_DISCOVERY,
                InvestigationMode.OPEN_DISCOVERY,
                RouteAuthority.POLICY_PROPOSED,
                ConclusionType.INSUFFICIENT_EVIDENCE,
                NorthStarTimings.concluded(REPORTED_AT, READY_AT, READY_AT),
                DiagnosisStatus.NEEDS_INVESTIGATION,
                "等待受限调查", "", Confidence.LOW, true,
                null, null, null, List.of(), List.of(), List.of(), "iCare 负责团队",
                true, false, List.of(), List.of());
        return pending.evidenceRecorded(
                ConclusionType.HYPOTHESIS,
                "直接失败原因：工单关联变更单，iCare 禁止在移动端完结",
                "告警中的 iCare 业务提示已明确说明本次拒绝条件。",
                Confidence.MEDIUM,
                List.of(reported),
                List.of("icare-mobile-change-order-finish-policy-present"),
                List.of(), List.of(), List.of(new TimelineEvent(
                        NOW, "受限调查形成待确认假设", "system", "done")));
    }

    private Diagnosis structuredImpactDiagnosis(int evidenceCustomerCount) {
        return structuredImpactDiagnosis(evidenceCustomerCount, (EvidenceResult) null);
    }

    private Diagnosis structuredImpactDiagnosis(
            int evidenceCustomerCount,
            boolean includeUnrelatedReference) {
        EvidenceResult extraEvidence = includeUnrelatedReference
                ? new EvidenceResult(
                        "EV-LOG", "L", "", EvidenceStatus.NORMAL,
                        "事件量证据，不是影响面证据",
                        Map.of("count", 148, "trace_id", "trace-impact"),
                        "recorded-replay", NOW)
                : null;
        return structuredImpactDiagnosis(evidenceCustomerCount, extraEvidence);
    }

    private Diagnosis structuredImpactDiagnosisWithConflict() {
        EvidenceResult conflict = new EvidenceResult(
                "EV-CONFLICT", "L", "", EvidenceStatus.NORMAL,
                "与主引用矛盾的结构化影响面",
                Map.of(
                        "function_scope", "消息发送功能",
                        "affected_customers", 999,
                        "blast_radius", "MULTI_CUSTOMER",
                        "observed_at", NOW.toEpochMilli()),
                "recorded-replay", NOW);
        return structuredImpactDiagnosis(2, conflict);
    }

    private Diagnosis structuredImpactDiagnosis(
            int evidenceCustomerCount,
            EvidenceResult extraEvidence) {
        List<String> evidenceRefs = extraEvidence == null
                ? List.of("EV-IMPACT")
                : List.of("EV-IMPACT", extraEvidence.queryId());
        IncidentContext incident = new IncidentContext(
                "incident-impact", "CSDP", "csdp-session-service", "903001",
                "会话消息发送失败", "P2",
                new IncidentImpact(
                        "消息发送功能",
                        2,
                        15,
                        BlastRadius.MULTI_CUSTOMER,
                        evidenceRefs,
                        NOW,
                        "同窗口两个客户出现同类失败"),
                null, NOW, "18m", "manual",
                IncidentCompleteness.STRUCTURED, "message send failed");
        EvidenceResult impactEvidence = new EvidenceResult(
                "EV-IMPACT", "L", "", EvidenceStatus.NORMAL,
                "结构化影响面",
                Map.of(
                        "function_scope", "消息发送功能",
                        "affected_customers", evidenceCustomerCount,
                        "affected_users", 15,
                        "blast_radius", "MULTI_CUSTOMER",
                        "observed_at", NOW.toEpochMilli()),
                "recorded-replay", NOW);
        List<EvidenceResult> evidence = extraEvidence == null
                ? List.of(impactEvidence)
                : List.of(impactEvidence, extraEvidence);
        return Diagnosis.initial(
                DIAGNOSIS_ID, "case-impact", "run-impact", incident,
                RouteMode.DETERMINISTIC,
                InvestigationMode.ERROR_CODE_PLAYBOOK,
                RouteAuthority.EXPLICIT,
                ConclusionType.LOCATED,
                NorthStarTimings.concluded(REPORTED_AT, READY_AT, NOW),
                DiagnosisStatus.READY_FOR_HUMAN,
                "消息发送失败", "会话状态冲突", Confidence.MEDIUM, false,
                "csdp:903001", "会话消息发送失败",
                new PlaybookVersionRef("playbook-903001", 3),
                evidence, List.of(), List.of(), "会话研发组",
                true, true, List.of("Recorded replay fixture"), List.of());
    }

    private Diagnosis excludedDiagnosis() {
        return Diagnosis.initial(
                DIAGNOSIS_ID, "case-1", "run-1", incident(),
                RouteMode.DETERMINISTIC,
                InvestigationMode.ERROR_CODE_PLAYBOOK,
                RouteAuthority.EXPLICIT,
                ConclusionType.EXCLUDED,
                NorthStarTimings.concluded(REPORTED_AT, READY_AT, NOW),
                DiagnosisStatus.READY_FOR_HUMAN,
                "当前候选根因已被反证", "候选根因均不成立", Confidence.MEDIUM, false,
                "csdp:903001", "订单服务 Mongo 连接池耗尽",
                new PlaybookVersionRef("playbook-903001", 3),
                List.of(new EvidenceResult(
                        "EV-2", "M", "M::mongodb:(...)", EvidenceStatus.NORMAL,
                        "Mongo 连接池未耗尽", Map.of("ratio", 0.2),
                        "recorded-replay", NOW)),
                List.of(), List.of(), null,
                true, true, List.of("Recorded replay fixture"), List.of());
    }

    private Diagnosis scenarioDiagnosis() {
        return Diagnosis.initial(
                DIAGNOSIS_ID, "case-1", "run-1", incident(),
                RouteMode.DETERMINISTIC,
                InvestigationMode.SCENARIO_PLAYBOOK,
                RouteAuthority.RULE_MATCHED,
                ConclusionType.LOCATED,
                NorthStarTimings.concluded(REPORTED_AT, READY_AT, NOW),
                DiagnosisStatus.READY_FOR_HUMAN,
                "慢接口场景规则命中", "下游依赖慢", Confidence.MEDIUM, false,
                "scenario:slow-api", "慢接口场景 Playbook",
                new PlaybookVersionRef("playbook-slow-api", 2),
                List.of(), List.of(), List.of(), "API 组",
                true, true, List.of("Recorded replay fixture"), List.of());
    }

    private Diagnosis abstainedDiagnosis() {
        return Diagnosis.initial(
                DIAGNOSIS_ID, "case-1", "run-1", incident(),
                RouteMode.LLM_FALLBACK, DiagnosisStatus.NEEDS_INVESTIGATION,
                "证据不足，已停止自动判断", "Mongo 连接池打满", Confidence.LOW, true,
                null, null,
                List.of(new EvidenceResult(
                        "EV-MISSING", "T", "", EvidenceStatus.MISSING,
                        "调用链证据未取得", Map.of(), "guance:unavailable", NOW)),
                List.of(), List.of(), null,
                false, false, List.of("调用链数据源不可用"));
    }

    private IncidentContext incident() {
        return new IncidentContext(
                "incident-1", "CSDP", "order-service", "903001",
                "订单创建超时", "P1", "订单创建成功率下降",
                "synthetic-trace-903001", NOW, "18m", "alert_webhook",
                IncidentCompleteness.STRUCTURED, "[ALERT] code=903001");
    }

    private DiagnosisDerivation derivation() {
        return new DiagnosisDerivation(
                DIAGNOSIS_ID, "csdp:903001", true, null,
                List.of(
                        new DiagnosisDerivation.CriterionEvaluation(
                                "pool_exhausted", "EV-2", "连接池耗尽", "ratio_gt",
                                "ratio > 0.95", "1 > 0.95", CriterionOutcome.SATISFIED,
                                EvidenceStatus.ANOMALY),
                        new DiagnosisDerivation.CriterionEvaluation(
                                "node_down", "EV-2", "节点不可达", "boolean_equals",
                                "reachable = false", "true != false", CriterionOutcome.EXCLUDED,
                                EvidenceStatus.NORMAL),
                        new DiagnosisDerivation.CriterionEvaluation(
                                "db_hop_failure", "EV-3", "失败跳落在数据库", "contains",
                                "failed_hop contains mongo", "证据缺失，判据未执行",
                                CriterionOutcome.UNEVALUATED, EvidenceStatus.MISSING)),
                List.of());
    }
}
