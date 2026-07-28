package vip.mate.troubleshooting.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.TroubleshootingSecretRedactor;
import vip.mate.troubleshooting.agent.TroubleshootingAgentTriageService;
import vip.mate.troubleshooting.engine.Criterion;
import vip.mate.troubleshooting.evidence.EvidenceSourceRouter;
import vip.mate.troubleshooting.model.AnomalyCriterion;
import vip.mate.troubleshooting.model.BlastRadius;
import vip.mate.troubleshooting.model.Confidence;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.DiagnosisRule;
import vip.mate.troubleshooting.model.DiagnosisStatus;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.model.IncidentImpact;
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

    @Mock
    private TroubleshootingAgentTriageService agentTriageService;

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
                anyLong(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any()))
                .thenReturn(stored);

        StoredDiagnosis result = intake.report(
                WORKSPACE_ID, incident("903001", IncidentCompleteness.STRUCTURED), List.of(evidence()), false);

        assertThat(result).isSameAs(stored);
        verify(diagnosisService).diagnoseAndPersist(
                eq(WORKSPACE_ID), any(), eq(sop), eq(List.of(evidence())),
                eq(false), eq(true), eq(NOW), eq(NOW));
    }

    @Test
    void keepsProtocolArrivalSeparateFromTheReadyTimestamp() {
        Instant reportedAt = NOW.minusSeconds(9);
        SopEntry sop = sop();
        IncidentContext incident = incident("903001", IncidentCompleteness.STRUCTURED);
        when(sopPersistence.find(WORKSPACE_ID, "CSDP", "903001")).thenReturn(sop);
        when(diagnosisService.diagnoseAndPersist(
                anyLong(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any()))
                .thenReturn(new StoredDiagnosis(diagnosis(), 1, true));

        intake.report(WORKSPACE_ID, incident, List.of(evidence()), false, reportedAt);

        verify(diagnosisService).diagnoseAndPersist(
                WORKSPACE_ID, incident, sop, List.of(evidence()), false, true,
                reportedAt, NOW);
    }

    @Test
    void deterministicHitNeverCallsTheAgentMissPath() {
        SopEntry sop = sop();
        IncidentContext incident = incident("903001", IncidentCompleteness.STRUCTURED);
        when(sopPersistence.find(WORKSPACE_ID, "CSDP", "903001")).thenReturn(sop);
        when(diagnosisService.diagnoseAndPersist(
                anyLong(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any()))
                .thenReturn(new StoredDiagnosis(diagnosis(), 1, true));
        TroubleshootingIntakeService wired = new TroubleshootingIntakeService(
                sopPersistence,
                diagnosisService,
                evidenceRouter,
                agentTriageService,
                Clock.fixed(NOW, ZoneOffset.UTC));

        wired.report(WORKSPACE_ID, incident, List.of(evidence()), false);

        verifyNoInteractions(agentTriageService);
    }

    @Test
    void delegatesAnUnknownRouteToTheReadOnlyAgentPath() {
        IncidentContext incident = incident("999999", IncidentCompleteness.STRUCTURED);
        StoredDiagnosis stored = new StoredDiagnosis(diagnosis(), 1, true);
        when(sopPersistence.find(WORKSPACE_ID, "CSDP", "999999")).thenReturn(null);
        when(agentTriageService.triage(
                WORKSPACE_ID,
                incident,
                List.of(evidence()),
                true,
                "no SOP registered for CSDP:999999",
                NOW,
                NOW))
                .thenReturn(stored);
        TroubleshootingIntakeService wired = new TroubleshootingIntakeService(
                sopPersistence,
                diagnosisService,
                evidenceRouter,
                agentTriageService,
                Clock.fixed(NOW, ZoneOffset.UTC));

        StoredDiagnosis result = wired.report(
                WORKSPACE_ID, incident, List.of(evidence()), true);

        assertThat(result).isSameAs(stored);
        verifyNoInteractions(diagnosisService, evidenceRouter);
    }

    @Test
    void delegatesAMissingErrorCodeToTheReadOnlyAgentPath() {
        IncidentContext incident = incident(null, IncidentCompleteness.LOG);
        StoredDiagnosis stored = new StoredDiagnosis(diagnosis(), 1, true);
        when(agentTriageService.triage(
                WORKSPACE_ID,
                incident,
                List.of(),
                false,
                "incident carries no errorCode; deterministic routing needs one",
                NOW,
                NOW))
                .thenReturn(stored);
        TroubleshootingIntakeService wired = new TroubleshootingIntakeService(
                sopPersistence,
                diagnosisService,
                evidenceRouter,
                agentTriageService,
                Clock.fixed(NOW, ZoneOffset.UTC));

        StoredDiagnosis result = wired.report(WORKSPACE_ID, incident, List.of(), false);

        assertThat(result).isSameAs(stored);
        verifyNoInteractions(sopPersistence, diagnosisService, evidenceRouter);
    }

    @Test
    void delegatesASymptomOnlyReportToTheReadOnlyAgentPath() {
        IncidentContext incident = incident("903001", IncidentCompleteness.SYMPTOM);
        StoredDiagnosis stored = new StoredDiagnosis(diagnosis(), 1, true);
        when(agentTriageService.triage(
                WORKSPACE_ID,
                incident,
                List.of(evidence()),
                true,
                "incident completeness is SYMPTOM; deterministic routing needs a structured report",
                NOW,
                NOW))
                .thenReturn(stored);
        TroubleshootingIntakeService wired = new TroubleshootingIntakeService(
                sopPersistence,
                diagnosisService,
                evidenceRouter,
                agentTriageService,
                Clock.fixed(NOW, ZoneOffset.UTC));

        StoredDiagnosis result = wired.report(
                WORKSPACE_ID, incident, List.of(evidence()), true);

        assertThat(result).isSameAs(stored);
        verifyNoInteractions(sopPersistence, diagnosisService, evidenceRouter);
    }

    @Test
    void marksEveryDiagnosisAsFixtureBackedWhileSourceAdaptersAreMissing() {
        when(sopPersistence.find(anyLong(), any(), any())).thenReturn(sop());
        when(diagnosisService.diagnoseAndPersist(
                anyLong(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any()))
                .thenReturn(new StoredDiagnosis(diagnosis(), 1, true));

        intake.report(WORKSPACE_ID, incident("903001", IncidentCompleteness.STRUCTURED), List.of(), false);

        ArgumentCaptor<Boolean> fixtureMode = ArgumentCaptor.forClass(Boolean.class);
        verify(diagnosisService).diagnoseAndPersist(
                anyLong(), any(), any(), any(), anyBoolean(), fixtureMode.capture(), any(), any());
        assertThat(fixtureMode.getValue())
                .as("no read-only source adapter exists yet, so evidence cannot be presented as verified")
                .isTrue();
    }

    @Test
    void fillsAMissingSopRequestThroughTheReadOnlyEvidenceRouter() {
        SopEntry sop = sop();
        IncidentContext incident = incident("903001", IncidentCompleteness.STRUCTURED);
        when(sopPersistence.find(WORKSPACE_ID, "CSDP", "903001")).thenReturn(sop);
        when(evidenceRouter.collect(
                WORKSPACE_ID, sop.evidenceRequests().getFirst(), incident))
                .thenReturn(evidence());
        when(diagnosisService.diagnoseAndPersist(
                anyLong(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any()))
                .thenReturn(new StoredDiagnosis(diagnosis(), 1, true));
        TroubleshootingIntakeService collectingIntake = new TroubleshootingIntakeService(
                sopPersistence, diagnosisService, evidenceRouter, Clock.fixed(NOW, ZoneOffset.UTC));

        collectingIntake.report(WORKSPACE_ID, incident, List.of(), false);

        verify(evidenceRouter).collect(
                WORKSPACE_ID, sop.evidenceRequests().getFirst(), incident);
        verify(diagnosisService).diagnoseAndPersist(
                eq(WORKSPACE_ID), eq(incident), eq(sop), eq(List.of(evidence())),
                eq(false), eq(true), eq(NOW), eq(NOW));
    }

    @Test
    void keepsCallerEvidenceAndDoesNotCollectItAgain() {
        SopEntry sop = sop();
        IncidentContext incident = incident("903001", IncidentCompleteness.STRUCTURED);
        when(sopPersistence.find(WORKSPACE_ID, "CSDP", "903001")).thenReturn(sop);
        when(diagnosisService.diagnoseAndPersist(
                anyLong(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any()))
                .thenReturn(new StoredDiagnosis(diagnosis(), 1, true));
        TroubleshootingIntakeService collectingIntake = new TroubleshootingIntakeService(
                sopPersistence, diagnosisService, evidenceRouter, Clock.fixed(NOW, ZoneOffset.UTC));

        collectingIntake.report(WORKSPACE_ID, incident, List.of(evidence()), false);

        verifyNoInteractions(evidenceRouter);
        verify(diagnosisService).diagnoseAndPersist(
                eq(WORKSPACE_ID), eq(incident), eq(sop), eq(List.of(evidence())),
                eq(false), eq(true), eq(NOW), eq(NOW));
    }

    @Test
    void redactsNestedEvidenceBeforeDeterministicDiagnosisPersistence() {
        SopEntry sop = sop();
        IncidentContext incident = incident("903001", IncidentCompleteness.STRUCTURED);
        EvidenceResult unsafe = new EvidenceResult(
                "EV-1", "L", "query", EvidenceStatus.ANOMALY, "log bundle",
                Map.of("entries", List.of(Map.of(
                        "message", "Authorization: Bearer production-token"))),
                "guance:log", NOW);
        when(sopPersistence.find(WORKSPACE_ID, "CSDP", "903001")).thenReturn(sop);
        when(diagnosisService.diagnoseAndPersist(
                anyLong(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any()))
                .thenReturn(new StoredDiagnosis(diagnosis(), 1, true));

        intake.report(WORKSPACE_ID, incident, List.of(unsafe), false);

        ArgumentCaptor<List<EvidenceResult>> evidenceCaptor = ArgumentCaptor.forClass(List.class);
        verify(diagnosisService).diagnoseAndPersist(
                eq(WORKSPACE_ID), eq(incident), eq(sop), evidenceCaptor.capture(),
                eq(false), eq(true), eq(NOW), eq(NOW));
        assertThat(evidenceCaptor.getValue().getFirst().observed().toString())
                .contains(TroubleshootingSecretRedactor.REDACTED)
                .doesNotContain("production-token");
    }

    @Test
    void redactsStructuredImpactBeforeDeterministicDiagnosisPersistence() {
        SopEntry sop = sop();
        IncidentContext unsafeIncident = new IncidentContext(
                "incident-impact", "CSDP", "csdp-session-service", "903001",
                "会话消息发送失败", "P2",
                new IncidentImpact(
                        "消息发送 token=production-secret",
                        null,
                        null,
                        BlastRadius.UNKNOWN,
                        List.of(),
                        null,
                        "Authorization: Bearer another-secret"),
                null, NOW, null, "manual",
                IncidentCompleteness.STRUCTURED, null);
        when(sopPersistence.find(WORKSPACE_ID, "CSDP", "903001")).thenReturn(sop);
        when(diagnosisService.diagnoseAndPersist(
                anyLong(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any()))
                .thenReturn(new StoredDiagnosis(diagnosis(), 1, true));

        intake.report(WORKSPACE_ID, unsafeIncident, List.of(evidence()), false);

        ArgumentCaptor<IncidentContext> incidentCaptor =
                ArgumentCaptor.forClass(IncidentContext.class);
        verify(diagnosisService).diagnoseAndPersist(
                eq(WORKSPACE_ID), incidentCaptor.capture(), eq(sop), eq(List.of(evidence())),
                eq(false), eq(true), eq(NOW), eq(NOW));
        IncidentImpact persistedImpact = incidentCaptor.getValue().impact();
        assertThat(persistedImpact.functionScope())
                .contains(TroubleshootingSecretRedactor.REDACTED)
                .doesNotContain("production-secret");
        assertThat(persistedImpact.note())
                .contains(TroubleshootingSecretRedactor.REDACTED)
                .doesNotContain("another-secret");
    }

    @Test
    void remapsDangerousCollidingQueryIdsBeforeDeterministicDiagnosisPersistence() {
        SopEntry sop = sop();
        IncidentContext incident = incident("903001", IncidentCompleteness.STRUCTURED);
        EvidenceResult first = evidenceWithQueryId("token:first-secret");
        EvidenceResult second = evidenceWithQueryId("token:second-secret");
        when(sopPersistence.find(WORKSPACE_ID, "CSDP", "903001")).thenReturn(sop);
        when(diagnosisService.diagnoseAndPersist(
                anyLong(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any()))
                .thenReturn(new StoredDiagnosis(diagnosis(), 1, true));

        intake.report(WORKSPACE_ID, incident, List.of(first, second), false);

        ArgumentCaptor<List<EvidenceResult>> evidenceCaptor = ArgumentCaptor.forClass(List.class);
        verify(diagnosisService).diagnoseAndPersist(
                eq(WORKSPACE_ID), eq(incident), eq(sop), evidenceCaptor.capture(),
                eq(false), eq(true), eq(NOW), eq(NOW));
        assertThat(evidenceCaptor.getValue())
                .extracting(EvidenceResult::queryId)
                .containsExactly("supplied-redacted-1", "supplied-redacted-2");
    }

    @Test
    void remapsAQueryIdThatIsNotASecretButIsNotASafeIdentifier() {
        SopEntry sop = sop();
        IncidentContext incident = incident("903001", IncidentCompleteness.STRUCTURED);
        EvidenceResult unsafe = evidenceWithQueryId("unsafe id with spaces");
        when(sopPersistence.find(WORKSPACE_ID, "CSDP", "903001")).thenReturn(sop);
        when(diagnosisService.diagnoseAndPersist(
                anyLong(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any()))
                .thenReturn(new StoredDiagnosis(diagnosis(), 1, true));

        intake.report(WORKSPACE_ID, incident, List.of(unsafe), false);

        ArgumentCaptor<List<EvidenceResult>> evidenceCaptor = ArgumentCaptor.forClass(List.class);
        verify(diagnosisService).diagnoseAndPersist(
                eq(WORKSPACE_ID), eq(incident), eq(sop), evidenceCaptor.capture(),
                eq(false), eq(true), eq(NOW), eq(NOW));
        assertThat(evidenceCaptor.getValue())
                .extracting(EvidenceResult::queryId)
                .containsExactly("supplied-redacted-1");
    }

    @Test
    void passesRehearsalThroughSoDrillsStayOutOfDeduplication() {
        when(sopPersistence.find(anyLong(), any(), any())).thenReturn(sop());
        when(diagnosisService.diagnoseAndPersist(
                anyLong(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any()))
                .thenReturn(new StoredDiagnosis(diagnosis(), 1, true));

        intake.report(WORKSPACE_ID, incident("903001", IncidentCompleteness.STRUCTURED), List.of(), true);

        verify(diagnosisService).diagnoseAndPersist(
                anyLong(), any(), any(), any(), eq(true), anyBoolean(), any(), any());
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
                anyLong(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any());
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

    private EvidenceResult evidenceWithQueryId(String queryId) {
        EvidenceResult evidence = evidence();
        return new EvidenceResult(
                queryId,
                evidence.namespace(),
                evidence.query(),
                evidence.status(),
                evidence.summary(),
                evidence.observed(),
                evidence.source(),
                evidence.collectedAt());
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
