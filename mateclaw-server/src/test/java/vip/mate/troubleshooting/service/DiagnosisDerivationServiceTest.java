package vip.mate.troubleshooting.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.engine.Criterion;
import vip.mate.troubleshooting.engine.CriterionEvaluator;
import vip.mate.troubleshooting.engine.CriterionRenderer;
import vip.mate.troubleshooting.model.AnomalyCriterion;
import vip.mate.troubleshooting.model.Confidence;
import vip.mate.troubleshooting.model.CriterionOutcome;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.DiagnosisDerivation;
import vip.mate.troubleshooting.model.DiagnosisRule;
import vip.mate.troubleshooting.model.DiagnosisStatus;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.model.InvestigationMode;
import vip.mate.troubleshooting.model.NorthStarTimings;
import vip.mate.troubleshooting.model.PlaybookVersionRef;
import vip.mate.troubleshooting.model.RouteAuthority;
import vip.mate.troubleshooting.model.RouteMode;
import vip.mate.troubleshooting.model.SopEntry;
import vip.mate.troubleshooting.synthesis.ApprovedPlaybookVersion;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The derivation exists so an operator can check the machine rather than trust
 * it, which puts two burdens on it: it must distinguish a hypothesis that was
 * ruled out from one that was never tested, and it must refuse to narrate a
 * past decision it can no longer reconstruct.
 */
@ExtendWith(MockitoExtension.class)
class DiagnosisDerivationServiceTest {

    private static final long WORKSPACE_ID = 1L;
    private static final String DIAGNOSIS_ID = "diag-1";
    private static final Instant NOW = Instant.parse("2026-07-26T09:12:03Z");
    private static final PlaybookVersionRef PLAYBOOK_REF =
            new PlaybookVersionRef("playbook-903001", 3);

    @Mock
    private TroubleshootingPersistenceService persistence;

    @Mock
    private TroubleshootingPlaybookVersionService playbookVersions;

    private DiagnosisDerivationService service;

    @BeforeEach
    void setUp() {
        service = new DiagnosisDerivationService(
                persistence, playbookVersions, new CriterionEvaluator(), new CriterionRenderer());
    }

    @Test
    void separatesAnExcludedHypothesisFromAnUntestedOne() {
        given(diagnosis(List.of("pool_exhausted"), evidenceWithMissingTrace()), sop());

        DiagnosisDerivation derivation = service.explain(WORKSPACE_ID, DIAGNOSIS_ID);

        assertThat(outcome(derivation, "pool_exhausted")).isEqualTo(CriterionOutcome.SATISFIED);
        assertThat(outcome(derivation, "node_unreachable"))
                .as("the host answered, so the outage hypothesis really is off the table")
                .isEqualTo(CriterionOutcome.EXCLUDED);
        assertThat(outcome(derivation, "db_hop_failure"))
                .as("the trace never arrived, so nothing was ruled out here")
                .isEqualTo(CriterionOutcome.UNEVALUATED);
    }

    @Test
    void showsTheArithmeticThatDecidedEachCriterion() {
        given(diagnosis(List.of("pool_exhausted"), evidenceWithMissingTrace()), sop());

        DiagnosisDerivation derivation = service.explain(WORKSPACE_ID, DIAGNOSIS_ID);
        DiagnosisDerivation.CriterionEvaluation pool = evaluation(derivation, "pool_exhausted");

        assertThat(pool.expression())
                .isEqualTo("connections_current ÷ (connections_current + connections_available) > 0.95");
        assertThat(pool.substitution())
                .as("an operator must be able to check the verdict by eye")
                .isEqualTo("2000 ÷ (2000 + 0) = 1 > 0.95");

        assertThat(evaluation(derivation, "node_unreachable").substitution())
                .isEqualTo("reachable=true ≠ false");
        assertThat(evaluation(derivation, "db_hop_failure").substitution())
                .isEqualTo("证据缺失，判据未执行");
    }

    @Test
    void tellsTheOperatorWhichLosingRulesAreStillOpen() {
        given(diagnosis(List.of("pool_exhausted"), evidenceWithMissingTrace()), sop());

        DiagnosisDerivation derivation = service.explain(WORKSPACE_ID, DIAGNOSIS_ID);

        DiagnosisDerivation.RuleEvaluation outage = rule(derivation, "R-outage");
        assertThat(outage.fired()).isFalse();
        assertThat(outage.unsatisfiedByExclusion())
                .as("excluded means the operator can stop suspecting it")
                .containsExactly("node_unreachable");

        DiagnosisDerivation.RuleEvaluation pool = rule(derivation, "R-pool");
        assertThat(pool.fired()).isFalse();
        assertThat(pool.unsatisfiedByGap())
                .as("blocked only by missing evidence, so this conclusion is still open")
                .containsExactly("db_hop_failure");
    }

    @Test
    void flagsARequiredSignalNoCriterionCanEverProduce() {
        SopEntry withGap = new SopEntry(
                "sop-1", SopEntry.CURRENT_CONTRACT_VERSION, "CSDP", "903001", "order-svc",
                "订单服务 Mongo 连接池耗尽", "连接池打满", "database", "DBA 组", "approved", true,
                List.of(metricRequest()),
                List.of(poolCriterion()),
                List.of(new DiagnosisRule("R-index", List.of("pool_exhausted", "index_missing"),
                        "缺失索引导致慢查询堆积", "", Confidence.MEDIUM, false)),
                List.of());
        given(diagnosis(List.of("pool_exhausted"), List.of(metricEvidence())), withGap);

        DiagnosisDerivation derivation = service.explain(WORKSPACE_ID, DIAGNOSIS_ID);

        assertThat(rule(derivation, "R-index").undefinedSignals())
                .as("a rule requiring a signal nothing produces can never fire — a SOP gap")
                .containsExactly("index_missing");
    }

    @Test
    void refusesToNarrateAStoredDecisionThatDisagreesWithItsFrozenPlaybook() {
        // A mismatch against the frozen version is an integrity failure, not normal version drift.
        given(diagnosis(List.of("pool_exhausted", "db_hop_failure"), List.of(metricEvidence())), sop());

        DiagnosisDerivation derivation = service.explain(WORKSPACE_ID, DIAGNOSIS_ID);

        assertThat(derivation.faithful())
                .as("frozen rules disagree with what the aggregate recorded")
                .isFalse();
        assertThat(derivation.note())
                .contains("冻结 Playbook 版本")
                .contains("db_hop_failure");
    }

    @Test
    void reportsWhenTheExactPlaybookVersionBehindTheDiagnosisIsGone() {
        when(persistence.get(WORKSPACE_ID, DIAGNOSIS_ID)).thenReturn(
                new StoredDiagnosis(diagnosis(List.of(), List.of(metricEvidence())), 1, false));

        assertThatThrownBy(() -> service.explain(WORKSPACE_ID, DIAGNOSIS_ID))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("exact Playbook version");
    }

    @Test
    void legacyDiagnosisWithoutAnExactVersionFailsClosedWithoutReadingCurrentKnowledge() {
        Diagnosis legacy = Diagnosis.initial(
                DIAGNOSIS_ID, "case-1", "run-1",
                incident(),
                RouteMode.DETERMINISTIC,
                DiagnosisStatus.READY_FOR_HUMAN,
                "legacy", "legacy", Confidence.HIGH, false,
                "csdp:903001", "legacy SOP",
                List.of(metricEvidence()), List.of("pool_exhausted"), List.of(),
                null, false, true, List.of());
        when(persistence.get(WORKSPACE_ID, DIAGNOSIS_ID))
                .thenReturn(new StoredDiagnosis(legacy, 1, false));

        assertThatThrownBy(() -> service.explain(WORKSPACE_ID, DIAGNOSIS_ID))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("did not freeze");
        verify(playbookVersions, never()).findByRef(anyLong(), any());
    }

    // ---------- helpers ----------

    private void given(Diagnosis diagnosis, SopEntry sop) {
        when(persistence.get(WORKSPACE_ID, DIAGNOSIS_ID))
                .thenReturn(new StoredDiagnosis(diagnosis, 1, false));
        when(playbookVersions.findByRef(WORKSPACE_ID, PLAYBOOK_REF))
                .thenReturn(Optional.of(version(sop)));
    }

    private CriterionOutcome outcome(DiagnosisDerivation derivation, String signal) {
        return evaluation(derivation, signal).outcome();
    }

    private DiagnosisDerivation.CriterionEvaluation evaluation(
            DiagnosisDerivation derivation, String signal) {
        return derivation.criteria().stream()
                .filter(c -> c.signal().equals(signal))
                .findFirst()
                .orElseThrow();
    }

    private DiagnosisDerivation.RuleEvaluation rule(DiagnosisDerivation derivation, String ruleId) {
        return derivation.rules().stream()
                .filter(r -> r.ruleId().equals(ruleId))
                .findFirst()
                .orElseThrow();
    }

    private SopEntry sop() {
        return new SopEntry(
                "sop-1", SopEntry.CURRENT_CONTRACT_VERSION, "CSDP", "903001", "order-svc",
                "订单服务 Mongo 连接池耗尽", "连接池打满", "database", "DBA 组", "approved", true,
                List.of(metricRequest(),
                        new EvidenceRequest("EV-3", "trace", "定位失败跳",
                                Map.of("trace_id", "7f3a91c"), null, false)),
                List.of(poolCriterion(),
                        new AnomalyCriterion("node_unreachable", "EV-2", "实例探活失败",
                                new Criterion.BooleanEquals("reachable", false)),
                        new AnomalyCriterion("db_hop_failure", "EV-3", "失败跳落在 DB 层",
                                new Criterion.ContainsAndIn("failed_hop", "mongo",
                                        "status", List.of("timeout", "error")))),
                List.of(
                        new DiagnosisRule("R-outage", List.of("node_unreachable"),
                                "Mongo 实例不可达", "", Confidence.HIGH, false),
                        new DiagnosisRule("R-pool", List.of("pool_exhausted", "db_hop_failure"),
                                "Mongo 连接池打满", "", Confidence.HIGH, false)),
                List.of());
    }

    private EvidenceRequest metricRequest() {
        return new EvidenceRequest("EV-2", "metric", "连接池水位",
                Map.of("host", "csdp-mongo-03"), "-15m", true);
    }

    private AnomalyCriterion poolCriterion() {
        return new AnomalyCriterion("pool_exhausted", "EV-2", "连接可用数占比归零",
                new Criterion.RatioOfSumGt(
                        "connections_current", "connections_available", 0.95));
    }

    private EvidenceResult metricEvidence() {
        return new EvidenceResult("EV-2", "M", "M::mongodb:(...)", EvidenceStatus.ANOMALY,
                "Mongo 连接", Map.of(
                        "connections_current", 2000,
                        "connections_available", 0,
                        "reachable", true),
                "guance:metric", NOW);
    }

    private List<EvidenceResult> evidenceWithMissingTrace() {
        return List.of(metricEvidence(),
                new EvidenceResult("EV-3", "T", "", EvidenceStatus.MISSING, "取证失败",
                        Map.of(), "guance:unavailable", NOW));
    }

    private Diagnosis diagnosis(List<String> triggeredSignals, List<EvidenceResult> evidence) {
        return Diagnosis.initial(
                DIAGNOSIS_ID, "case-1", "run-1",
                incident(),
                RouteMode.DETERMINISTIC,
                InvestigationMode.ERROR_CODE_PLAYBOOK,
                RouteAuthority.EXPLICIT,
                vip.mate.troubleshooting.model.ConclusionType.LOCATED,
                NorthStarTimings.unrecorded(),
                DiagnosisStatus.READY_FOR_HUMAN,
                "连接可用数归零", "Mongo 连接池打满", Confidence.HIGH, false,
                "csdp:903001", "订单服务 Mongo 连接池耗尽", "DBA 组", PLAYBOOK_REF,
                evidence, triggeredSignals, List.of(), null, false, true, List.of(), List.of());
    }

    private IncidentContext incident() {
        return new IncidentContext(
                "inc-1", "CSDP", "order-svc", "903001", "订单创建超时",
                "P0", "成功率下降", "7f3a91c", NOW, "21:18", "alert_webhook",
                IncidentCompleteness.STRUCTURED, "[ALERT] code=903001");
    }

    private ApprovedPlaybookVersion version(SopEntry sop) {
        return new ApprovedPlaybookVersion(
                PLAYBOOK_REF.playbookId(),
                PLAYBOOK_REF.playbookVersion(),
                sop.routingKey(),
                "APPROVED",
                "MANUAL",
                "manual-903001",
                "review-903001",
                2,
                "reviewer",
                "fixed replay passed",
                null,
                sop,
                NOW,
                NOW);
    }
}
