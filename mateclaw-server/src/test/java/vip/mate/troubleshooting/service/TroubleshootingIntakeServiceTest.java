package vip.mate.troubleshooting.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.engine.Criterion;
import vip.mate.troubleshooting.evidence.EvidenceSourceRouter;
import vip.mate.troubleshooting.model.AnomalyCriterion;
import vip.mate.troubleshooting.model.Confidence;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.DiagnosisRule;
import vip.mate.troubleshooting.model.DiagnosisStatus;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.model.RouteMode;
import vip.mate.troubleshooting.model.SopEntry;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Intake behaviour that the HTTP layer depends on: deterministic routing,
 * loud failures where a guess would be dishonest, and the fixture-mode flag
 * that stops a caller from claiming its evidence is MateClaw-verified.
 */
@ExtendWith(MockitoExtension.class)
class TroubleshootingIntakeServiceTest {

    private static final long WORKSPACE_ID = 7L;
    private static final Instant NOW = Instant.parse("2026-07-25T09:12:03Z");

    @Mock
    private TroubleshootingSopPersistenceService sopPersistence;

    @Mock
    private DeterministicDiagnosisService diagnosisService;

    @Mock
    private EvidenceSourceRouter evidenceRouter;

    private TroubleshootingIntakeService intake;

    @BeforeEach
    void setUp() {
        intake = new TroubleshootingIntakeService(
                sopPersistence, diagnosisService, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void routesAHitToTheDeterministicServiceAndReturnsWhatItStored() {
        SopEntry sop = sop();
        when(sopPersistence.find(WORKSPACE_ID, "CSDP", "903001")).thenReturn(sop);
        StoredDiagnosis stored = new StoredDiagnosis(diagnosis(), 1, true);
        when(diagnosisService.diagnoseAndPersist(
                anyLong(), any(), any(), any(), anyBoolean(), anyBoolean(), any()))
                .thenReturn(stored);

        StoredDiagnosis result = intake.report(
                WORKSPACE_ID, incident("903001", IncidentCompleteness.STRUCTURED), List.of(evidence()), false);

        assertThat(result).isSameAs(stored);
        verify(diagnosisService).diagnoseAndPersist(
                eq(WORKSPACE_ID), any(), eq(sop), eq(List.of(evidence())), eq(false), eq(true), eq(NOW));
    }

    @Test
    void marksEveryDiagnosisAsFixtureBackedWhileSourceAdaptersAreMissing() {
        when(sopPersistence.find(anyLong(), any(), any())).thenReturn(sop());
        when(diagnosisService.diagnoseAndPersist(
                anyLong(), any(), any(), any(), anyBoolean(), anyBoolean(), any()))
                .thenReturn(new StoredDiagnosis(diagnosis(), 1, true));

        intake.report(WORKSPACE_ID, incident("903001", IncidentCompleteness.STRUCTURED), List.of(), false);

        ArgumentCaptor<Boolean> fixtureMode = ArgumentCaptor.forClass(Boolean.class);
        verify(diagnosisService).diagnoseAndPersist(
                anyLong(), any(), any(), any(), anyBoolean(), fixtureMode.capture(), any());
        assertThat(fixtureMode.getValue())
                .as("no read-only source adapter exists yet, so evidence cannot be presented as verified")
                .isTrue();
    }

    @Test
    void fillsAMissingSopRequestThroughTheReadOnlyEvidenceRouter() {
        SopEntry sop = sop();
        IncidentContext incident = incident("903001", IncidentCompleteness.STRUCTURED);
        when(sopPersistence.find(WORKSPACE_ID, "CSDP", "903001")).thenReturn(sop);
        when(evidenceRouter.collect(sop.evidenceRequests().getFirst(), incident))
                .thenReturn(evidence());
        when(diagnosisService.diagnoseAndPersist(
                anyLong(), any(), any(), any(), anyBoolean(), anyBoolean(), any()))
                .thenReturn(new StoredDiagnosis(diagnosis(), 1, true));
        TroubleshootingIntakeService collectingIntake = new TroubleshootingIntakeService(
                sopPersistence, diagnosisService, evidenceRouter, Clock.fixed(NOW, ZoneOffset.UTC));

        collectingIntake.report(WORKSPACE_ID, incident, List.of(), false);

        verify(evidenceRouter).collect(sop.evidenceRequests().getFirst(), incident);
        verify(diagnosisService).diagnoseAndPersist(
                eq(WORKSPACE_ID), eq(incident), eq(sop), eq(List.of(evidence())),
                eq(false), eq(true), eq(NOW));
    }

    @Test
    void keepsCallerEvidenceAndDoesNotCollectItAgain() {
        SopEntry sop = sop();
        IncidentContext incident = incident("903001", IncidentCompleteness.STRUCTURED);
        when(sopPersistence.find(WORKSPACE_ID, "CSDP", "903001")).thenReturn(sop);
        when(diagnosisService.diagnoseAndPersist(
                anyLong(), any(), any(), any(), anyBoolean(), anyBoolean(), any()))
                .thenReturn(new StoredDiagnosis(diagnosis(), 1, true));
        TroubleshootingIntakeService collectingIntake = new TroubleshootingIntakeService(
                sopPersistence, diagnosisService, evidenceRouter, Clock.fixed(NOW, ZoneOffset.UTC));

        collectingIntake.report(WORKSPACE_ID, incident, List.of(evidence()), false);

        verifyNoInteractions(evidenceRouter);
        verify(diagnosisService).diagnoseAndPersist(
                eq(WORKSPACE_ID), eq(incident), eq(sop), eq(List.of(evidence())),
                eq(false), eq(true), eq(NOW));
    }

    @Test
    void passesRehearsalThroughSoDrillsStayOutOfDeduplication() {
        when(sopPersistence.find(anyLong(), any(), any())).thenReturn(sop());
        when(diagnosisService.diagnoseAndPersist(
                anyLong(), any(), any(), any(), anyBoolean(), anyBoolean(), any()))
                .thenReturn(new StoredDiagnosis(diagnosis(), 1, true));

        intake.report(WORKSPACE_ID, incident("903001", IncidentCompleteness.STRUCTURED), List.of(), true);

        verify(diagnosisService).diagnoseAndPersist(
                anyLong(), any(), any(), any(), eq(true), anyBoolean(), any());
    }

    @Test
    void treatsAnUnknownRouteAsAKnowledgeGapInsteadOfGuessing() {
        when(sopPersistence.find(WORKSPACE_ID, "CSDP", "999999")).thenReturn(null);

        assertThatThrownBy(() -> intake.report(
                WORKSPACE_ID, incident("999999", IncidentCompleteness.STRUCTURED), List.of(), false))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("no SOP registered")
                .extracting(e -> ((MateClawException) e).getCode())
                .isEqualTo(409);

        verify(diagnosisService, never()).diagnoseAndPersist(
                anyLong(), any(), any(), any(), anyBoolean(), anyBoolean(), any());
    }

    @Test
    void rejectsAnIncidentWithNoErrorCodeBecauseTheMissPathIsNotWired() {
        assertThatThrownBy(() -> intake.report(
                WORKSPACE_ID, incident(null, IncidentCompleteness.LOG), List.of(), false))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("no errorCode");

        verifyNoInteractions(sopPersistence, diagnosisService);
    }

    @Test
    void rejectsASymptomOnlyReportBecauseDeterministicRoutingCannotKeyOnIt() {
        assertThatThrownBy(() -> intake.report(
                WORKSPACE_ID, incident("903001", IncidentCompleteness.SYMPTOM), List.of(), false))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("SYMPTOM");

        verifyNoInteractions(sopPersistence, diagnosisService);
    }

    @Test
    void rejectsAMissingIncident() {
        assertThatThrownBy(() -> intake.report(WORKSPACE_ID, null, List.of(), false))
                .isInstanceOf(MateClawException.class)
                .extracting(e -> ((MateClawException) e).getCode())
                .isEqualTo(400);
    }

    // ---------- fixtures ----------

    private IncidentContext incident(String errorCode, IncidentCompleteness completeness) {
        return new IncidentContext(
                "inc-1", "CSDP", "order-svc", errorCode, "订单创建超时",
                "P0", "订单创建成功率下降", "7f3a91c", NOW, "21:18",
                "alert_webhook", completeness, "[ALERT] code=" + errorCode);
    }

    private SopEntry sop() {
        return new SopEntry(
                "sop-903001", SopEntry.CURRENT_CONTRACT_VERSION, "CSDP", "903001", "order-svc",
                "订单服务 Mongo 连接池耗尽", "连接池打满", "database", "DBA 组", "approved", true,
                List.of(new EvidenceRequest("EV-1", "log_count", "确认发生", Map.of(), "-15m", true)),
                List.of(new AnomalyCriterion("error_present", "EV-1", "错误码日志出现",
                        new Criterion.NumericGte("count", 1))),
                List.of(new DiagnosisRule("R-a", List.of("error_present"),
                        "Mongo 连接池打满", "连接可用数归零", Confidence.HIGH, false)),
                List.of());
    }

    private EvidenceResult evidence() {
        return new EvidenceResult(
                "EV-1", "L", "L::order-svc:(count) {error_code='903001'} [-15m]",
                EvidenceStatus.ANOMALY, "错误码日志计数", Map.of("count", 148),
                "guance:log", NOW);
    }

    private Diagnosis diagnosis() {
        return Diagnosis.initial(
                "diag-1", "case-1", "run-1",
                incident("903001", IncidentCompleteness.STRUCTURED),
                RouteMode.DETERMINISTIC, DiagnosisStatus.READY_FOR_HUMAN,
                "连接可用数归零", "Mongo 连接池打满", Confidence.HIGH, false,
                "CSDP:903001", "订单服务 Mongo 连接池耗尽",
                List.of(evidence()), List.of("error_present"), List.of(),
                null, false, true, List.of());
    }
}
