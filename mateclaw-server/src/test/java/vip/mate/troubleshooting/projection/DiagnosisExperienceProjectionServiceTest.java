package vip.mate.troubleshooting.projection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.troubleshooting.model.Confidence;
import vip.mate.troubleshooting.model.ConclusionType;
import vip.mate.troubleshooting.model.CriterionOutcome;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.DiagnosisDerivation;
import vip.mate.troubleshooting.model.DiagnosisStatus;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.model.InvestigationMode;
import vip.mate.troubleshooting.model.NorthStarTimings;
import vip.mate.troubleshooting.model.RouteMode;
import vip.mate.troubleshooting.model.RouteAuthority;
import vip.mate.troubleshooting.service.DiagnosisDerivationService;
import vip.mate.troubleshooting.service.StoredDiagnosis;
import vip.mate.troubleshooting.service.TroubleshootingPersistenceService;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    private DiagnosisExperienceProjectionService service;

    @BeforeEach
    void setUp() {
        service = new DiagnosisExperienceProjectionService(persistence, derivationService);
    }

    @Test
    void projectsOneDiagnosisForBusinessAndDeveloperWithoutInventingImpact() {
        when(persistence.get(WORKSPACE_ID, DIAGNOSIS_ID))
                .thenReturn(new StoredDiagnosis(deterministicDiagnosis(), 2, false));
        when(derivationService.explain(WORKSPACE_ID, DIAGNOSIS_ID))
                .thenReturn(derivation());

        DiagnosisExperienceProjection result = service.project(WORKSPACE_ID, DIAGNOSIS_ID);

        DiagnosisExperienceProjection.BusinessSummary business = result.businessSummary();
        assertThat(business.conclusionType())
                .isEqualTo(ConclusionType.LOCATED);
        assertThat(business.problem()).isEqualTo("订单创建超时");
        assertThat(business.impact().functionScope()).isEqualTo("订单创建成功率下降");
        assertThat(business.impact().affectedCustomers()).isNull();
        assertThat(business.impact().affectedUsers()).isNull();
        assertThat(business.impact().blastRadius())
                .isEqualTo(DiagnosisExperienceProjection.BlastRadius.UNKNOWN);
        assertThat(business.timings().reportedAt()).isEqualTo(REPORTED_AT);
        assertThat(business.timings().readyAt()).isEqualTo(READY_AT);
        assertThat(business.timings().conclusionAt()).isEqualTo(NOW);
        assertThat(business.timings().intakeCost()).isEqualTo(java.time.Duration.ofSeconds(30));
        assertThat(business.timings().investigateCost())
                .isEqualTo(java.time.Duration.ofSeconds(164));
        assertThat(business.fixtureMode()).isTrue();

        DiagnosisExperienceProjection.DeveloperEvidenceView developer = result.developerEvidence();
        assertThat(developer.investigationMode())
                .isEqualTo(InvestigationMode.ERROR_CODE_PLAYBOOK);
        assertThat(developer.routeAuthority())
                .isEqualTo(RouteAuthority.EXPLICIT);
        assertThat(developer.playbookRef()).isEqualTo("csdp:903001");
        assertThat(developer.callChain().psId()).isEqualTo("synthetic-trace-903001");
        assertThat(developer.callChain().hops()).isEmpty();
        assertThat(developer.callChain().emptyReason()).contains("未保存 hop");
        assertThat(developer.steps())
                .extracting(DiagnosisExperienceProjection.EvidenceStep::tone)
                .contains(
                        DiagnosisExperienceProjection.StepTone.ANOMALY,
                        DiagnosisExperienceProjection.StepTone.EXCLUDED,
                        DiagnosisExperienceProjection.StepTone.UNEVALUATED);
        assertThat(developer.contrast().available()).isFalse();
        assertThat(developer.capabilityLimits()).anyMatch(item -> item.contains("生产变更"));
        assertThat(developer.fixtureMode()).isTrue();
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

        DiagnosisExperienceProjection result = service.project(WORKSPACE_ID, DIAGNOSIS_ID);

        assertThat(result.developerEvidence().investigationMode())
                .isEqualTo(InvestigationMode.SCENARIO_PLAYBOOK);
        assertThat(result.developerEvidence().routeAuthority())
                .isEqualTo(RouteAuthority.RULE_MATCHED);
    }

    @Test
    void rejectsPreciseImpactCountsWithoutEvidenceReferences() {
        assertThatThrownBy(() -> new DiagnosisExperienceProjection.ImpactView(
                "订单创建", 12, null,
                DiagnosisExperienceProjection.BlastRadius.MULTI_CUSTOMER,
                List.of(), NOW, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidenceRefs");
    }

    @Test
    void keepsConclusionConfidenceInvariantsAtTheProjectionBoundary() {
        DiagnosisExperienceProjection.ImpactView impact = new DiagnosisExperienceProjection.ImpactView(
                "订单创建", null, null,
                DiagnosisExperienceProjection.BlastRadius.UNKNOWN,
                List.of(), null, "未测量");
        DiagnosisExperienceProjection.NextStep next = new DiagnosisExperienceProjection.NextStep(
                "排除结论", "当前假设已排除", "这是排除不是定位");
        NorthStarTimings timings = NorthStarTimings.unrecorded();

        assertThatThrownBy(() -> new DiagnosisExperienceProjection.BusinessSummary(
                DIAGNOSIS_ID,
                ConclusionType.EXCLUDED,
                "已排除当前假设", "证据不支持当前假设。", Confidence.HIGH,
                "订单超时", impact, next, DiagnosisStatus.READY_FOR_HUMAN,
                timings, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EXCLUDED");
    }

    private Diagnosis deterministicDiagnosis() {
        return Diagnosis.initial(
                DIAGNOSIS_ID, "case-1", "run-1", incident(),
                RouteMode.DETERMINISTIC,
                InvestigationMode.ERROR_CODE_PLAYBOOK,
                RouteAuthority.EXPLICIT,
                ConclusionType.LOCATED,
                NorthStarTimings.concluded(REPORTED_AT, READY_AT, NOW),
                DiagnosisStatus.READY_FOR_HUMAN,
                "连接池利用率达到 100%", "Mongo 连接池打满", Confidence.HIGH, false,
                "csdp:903001", "订单服务 Mongo 连接池耗尽",
                List.of(new EvidenceResult(
                        "EV-2", "M", "M::mongodb:(...)", EvidenceStatus.ANOMALY,
                        "Mongo 连接池利用率达到 100%", Map.of("ratio", 1),
                        "recorded-replay", NOW)),
                List.of("pool_exhausted"), List.of(), "DBA 组",
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
