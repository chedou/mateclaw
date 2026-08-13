package vip.mate.troubleshooting.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.TroubleshootingBusinessTextPolicy;
import vip.mate.troubleshooting.TroubleshootingSecretRedactor;
import vip.mate.troubleshooting.agent.TroubleshootingAgentTriageService;
import vip.mate.troubleshooting.engine.Criterion;
import vip.mate.troubleshooting.evidence.EvidenceSourceRouter;
import vip.mate.troubleshooting.evidence.EvidenceSpineOrchestrator;
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
import vip.mate.troubleshooting.model.PlaybookVersionRef;
import vip.mate.troubleshooting.model.RouteMode;
import vip.mate.troubleshooting.model.SopEntry;
import vip.mate.troubleshooting.intake.IntakeMessageEnvelope;
import vip.mate.troubleshooting.intake.IntakeSession;
import vip.mate.troubleshooting.intake.IntakeSessionReducer;
import vip.mate.troubleshooting.synthesis.DeterministicLogTraceCompressor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    void readyChannelIntakePreservesItsIdentityAndNorthStarBoundaries() {
        Instant reportedAt = NOW.minusSeconds(30);
        IntakeSession session = new IntakeSessionReducer().start(
                "intake-7",
                new IntakeMessageEnvelope(
                        WORKSPACE_ID,
                        "wecom",
                        "msg-7",
                        "wecom:99:group-1",
                        "user-1",
                        "现象: 会话消息发送失败\n系统: CSDP\n服务: csdp-wechat\n"
                                + "客户ID: tenant-42\n发生时间: 2026-07-25 17:11:00\n"
                                + "错误码: 903001",
                        List.of(),
                        reportedAt));
        SopEntry sop = sop();
        StoredDiagnosis stored = new StoredDiagnosis(diagnosis(), 0, true);
        when(sopPersistence.find(WORKSPACE_ID, "CSDP", "903001")).thenReturn(sop);
        when(diagnosisService.diagnoseAndPersistForIntake(
                anyLong(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any()))
                .thenReturn(stored);

        StoredDiagnosis result = intake.report(session);

        assertThat(result).isSameAs(stored);
        ArgumentCaptor<IncidentContext> incident = ArgumentCaptor.forClass(IncidentContext.class);
        verify(diagnosisService).diagnoseAndPersistForIntake(
                eq(WORKSPACE_ID),
                incident.capture(),
                eq(sop),
                eq(List.of()),
                eq(false),
                eq(true),
                eq(reportedAt),
                eq(reportedAt),
                eq("intake-7"));
        assertThat(incident.getValue().incidentId()).isEqualTo("incident-intake-7");
        assertThat(incident.getValue().title()).isEqualTo("会话消息发送失败");
        assertThat(incident.getValue().intakeSource()).isEqualTo("channel:wecom");
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
                // The miss path is told that symptom routing was tried and why it
                // failed, so "no route" can be distinguished from "never looked".
                "incident carries no errorCode; deterministic routing needs one;"
                        + " no approved scenario Playbook for 'CSDP' declares"
                        + " a trigger matching this symptom",
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
        verifyNoInteractions(diagnosisService, evidenceRouter);
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
        // fixtureMode 现在由证据自己决定：这一条是 router 从真源取回来的，
        // 所以不是夹具。调用方自带的证据仍一律按夹具（它不能自证成色）。
                eq(false), eq(false), eq(NOW), eq(NOW));
    }

    @Test
    void runsAThreeStepHitThroughTheSharedEvidenceSpineWithTheObservedCorrelationId() {
        SopEntry sop = itgwSop();
        IncidentContext incident = new IncidentContext(
                "inc-itgw", "CSDP", "csdp-wechat", "904003",
                "ITGW访问失败", "P1", "6条失败", null,
                Instant.parse("2026-08-07T09:12:00Z"), null,
                "alert_webhook", IncidentCompleteness.STRUCTURED,
                "ITGW访问失败【904003】");
        when(sopPersistence.find(WORKSPACE_ID, "CSDP", "904003")).thenReturn(sop);
        when(evidenceRouter.collect(
                eq(WORKSPACE_ID), any(EvidenceRequest.class), eq(incident), eq(null)))
                .thenAnswer(invocation -> itgwEvidence(invocation.getArgument(1)));
        when(diagnosisService.diagnoseAndPersist(
                anyLong(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any()))
                .thenReturn(new StoredDiagnosis(diagnosis(), 1, true));
        EvidenceSpineOrchestrator spine = new EvidenceSpineOrchestrator(
                evidenceRouter, new DeterministicLogTraceCompressor());
        TroubleshootingIntakeService collectingIntake = new TroubleshootingIntakeService(
                sopPersistence,
                diagnosisService,
                evidenceRouter,
                spine,
                agentTriageService,
                Clock.fixed(NOW, ZoneOffset.UTC));

        collectingIntake.report(WORKSPACE_ID, incident, List.of(), false);

        ArgumentCaptor<EvidenceRequest> requests = ArgumentCaptor.forClass(EvidenceRequest.class);
        verify(evidenceRouter, times(3)).collect(
                eq(WORKSPACE_ID), requests.capture(), eq(incident), eq(null));
        assertThat(requests.getAllValues().get(0).target())
                .containsExactlyEntriesOf(Map.of("search_term", "itgw_access_failed"));
        assertThat(requests.getAllValues().get(1).target())
                .containsExactlyEntriesOf(Map.of("ps_id", "itgw-trace-observed-1"));
        assertThat(requests.getAllValues().get(2).target())
                .containsExactlyEntriesOf(Map.of(
                        "scenario_key", "itgw_access_failed",
                        "exclude_ps_id", "itgw-trace-observed-1"));

        ArgumentCaptor<List<EvidenceResult>> persisted = ArgumentCaptor.forClass(List.class);
        verify(diagnosisService).diagnoseAndPersist(
                eq(WORKSPACE_ID), eq(incident), eq(sop), persisted.capture(),
                eq(false), eq(false), eq(NOW), eq(NOW));
        assertThat(persisted.getValue())
                .extracting(EvidenceResult::queryId)
                .containsExactly("ITGW-LOG-SEARCH", "ITGW-TRACE-BUNDLE", "ITGW-CONTRAST");
        assertThat(persisted.getValue())
                .extracting(EvidenceResult::query)
                .containsOnly("withheld");
        assertThat(persisted.getValue().toString())
                .doesNotContain("raw-business-payload", "blocked-term-value")
                .contains("failure_sample_count=9", "success_sample_count=35");
    }

    @Test
    void rejectsCallerEvidenceThatDoesNotExactlyCoverAThreeStepSpine() {
        SopEntry sop = itgwSop();
        IncidentContext incident = new IncidentContext(
                "inc-itgw", "CSDP", "csdp-wechat", "904003",
                "ITGW访问失败", "P1", "6条失败", null, NOW, null,
                "alert_webhook", IncidentCompleteness.STRUCTURED,
                "ITGW访问失败【904003】");
        when(sopPersistence.find(WORKSPACE_ID, "CSDP", "904003")).thenReturn(sop);
        TroubleshootingIntakeService collectingIntake = new TroubleshootingIntakeService(
                sopPersistence,
                diagnosisService,
                evidenceRouter,
                new EvidenceSpineOrchestrator(
                        evidenceRouter, new DeterministicLogTraceCompressor()),
                agentTriageService,
                Clock.fixed(NOW, ZoneOffset.UTC));
        List<EvidenceResult> wrongIds = List.of(
                evidenceWithQueryId("SAFE-OTHER-1"),
                evidenceWithQueryId("SAFE-OTHER-2"),
                evidenceWithQueryId("SAFE-OTHER-3"));

        assertThatThrownBy(() -> collectingIntake.report(
                WORKSPACE_ID, incident, wrongIds, false))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("partial caller-supplied Evidence Spine")
                .extracting(error -> ((MateClawException) error).getCode())
                .isEqualTo(409);

        verifyNoInteractions(evidenceRouter, diagnosisService, agentTriageService);
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
    void rejectsDqlAndRawLogTextBeforeRoutingPersistenceOrAgentUse() {
        IncidentContext dqlInTitle = new IncidentContext(
                "unsafe-dql", "CSDP", "order-svc", "903001",
                "L::logs:(message) {service='order-svc'} [-15m]", "P2",
                "待确认", null, NOW, null, "web:formal-workbench",
                IncidentCompleteness.STRUCTURED, "订单创建超时");
        IncidentContext rawLogInInput = new IncidentContext(
                "unsafe-log", "CSDP", "order-svc", null,
                "会话消息发送失败", "P2", "待确认", null, NOW, null,
                "web:formal-workbench", IncidentCompleteness.SYMPTOM,
                "2026-07-25 09:12:03 ERROR request failed\n"
                        + "at vip.mate.OrderService.create(OrderService.java:42)");
        IncidentContext jsonLog = new IncidentContext(
                "unsafe-json", "CSDP", "order-svc", null,
                "{\"timestamp\":\"2026-07-25T09:12:03Z\",\"level\":\"ERROR\","
                        + "\"message\":\"request failed\"}",
                "P2", "待确认", null, NOW, null, "web:formal-workbench",
                IncidentCompleteness.SYMPTOM, null);
        IncidentContext prettyJsonLog = new IncidentContext(
                "unsafe-pretty-json", "CSDP", "order-svc", null,
                "{\n  \"timestamp\": \"2026-07-25T09:12:03Z\",\n"
                        + "  \"level\": \"ERROR\",\n"
                        + "  \"message\": \"request failed\"\n}",
                "P2", "待确认", null, NOW, null, "web:formal-workbench",
                IncidentCompleteness.SYMPTOM, null);
        IncidentContext lateJsonLog = new IncidentContext(
                "unsafe-late-json", "CSDP", "order-svc", null,
                "{\"payload\":\"" + "x".repeat(1025)
                        + "\",\"level\":\"ERROR\"}",
                "P2", "待确认", null, NOW, null, "web:formal-workbench",
                IncidentCompleteness.SYMPTOM, null);
        IncidentContext oversizedBusinessText = new IncidentContext(
                "unsafe-oversized-text", "CSDP", "order-svc", null,
                "x".repeat(2001),
                "P2", "待确认", null, NOW, null, "web:formal-workbench",
                IncidentCompleteness.SYMPTOM, null);
        IncidentContext pythonTraceback = new IncidentContext(
                "unsafe-python", "CSDP", "order-svc", null,
                "会话消息发送失败", "P2", "待确认", null, NOW, null,
                "web:formal-workbench", IncidentCompleteness.SYMPTOM,
                "Traceback (most recent call last):\n"
                        + "  File \"/app/order.py\", line 42, in create");
        IncidentContext goPanic = new IncidentContext(
                "unsafe-go", "CSDP", "order-svc", null,
                "panic: runtime error: index out of range\n"
                        + "goroutine 18 [running]:",
                "P2", "待确认", null, NOW, null, "web:formal-workbench",
                IncidentCompleteness.SYMPTOM, null);
        IncidentContext nodeStack = new IncidentContext(
                "unsafe-node", "CSDP", "order-svc", null,
                "会话消息发送失败", "P2", "待确认", null, NOW, null,
                "web:formal-workbench", IncidentCompleteness.SYMPTOM,
                "TypeError: request failed\n"
                        + "    at async submitReport (/app/index.js:42:17)");
        IncidentContext browserStack = new IncidentContext(
                "unsafe-browser", "CSDP", "order-svc", null,
                "会话消息发送失败\n    at /app/bootstrap.js:3:9",
                "P2", "待确认", null, NOW, null, "web:formal-workbench",
                IncidentCompleteness.SYMPTOM, null);
        IncidentContext safariStack = new IncidentContext(
                "unsafe-safari", "CSDP", "order-svc", null,
                "Error: request failed\n"
                        + "submit@https://app.example.com/main.js:42:17",
                "P2", "待确认", null, NOW, null, "web:formal-workbench",
                IncidentCompleteness.SYMPTOM, null);
        IncidentContext accessLog = new IncidentContext(
                "unsafe-access-log", "CSDP", "order-svc", null,
                "127.0.0.1 - - [29/Jul/2026:12:00:00 +0800] "
                        + "\"GET /orders HTTP/1.1\" 500 612",
                "P2", "待确认", null, NOW, null, "web:formal-workbench",
                IncidentCompleteness.SYMPTOM, null);
        IncidentContext unsafeSource = new IncidentContext(
                "unsafe-source", "CSDP", "order-svc", "903001",
                "订单创建超时", "P2", "待确认", null, NOW, null,
                "L::logs:(message)", IncidentCompleteness.STRUCTURED, null);
        IncidentContext unsafeImpactRef = new IncidentContext(
                "unsafe-impact-ref", "CSDP", "order-svc", "903001",
                "订单创建超时", "P2",
                new IncidentImpact(
                        "订单创建", null, null, BlastRadius.UNKNOWN,
                        List.of("L::logs:message"), null, ""),
                null, NOW, null, "web:formal-workbench",
                IncidentCompleteness.STRUCTURED, null);

        for (IncidentContext unsafe : List.of(
                dqlInTitle, rawLogInInput, jsonLog, prettyJsonLog, lateJsonLog,
                oversizedBusinessText,
                pythonTraceback, goPanic, nodeStack, browserStack, safariStack, accessLog,
                unsafeSource, unsafeImpactRef)) {
            assertThatThrownBy(() -> intake.report(
                    WORKSPACE_ID, unsafe, List.of(), false))
                    .isInstanceOf(MateClawException.class)
                    .extracting(error -> ((MateClawException) error).getCode())
                    .isEqualTo(400);
        }
        assertThatCode(() -> TroubleshootingBusinessTextPolicy.requireNoDeveloperEvidence(
                "Error: order submit failed；用户打开 `/orders/{id}` 返回 404，"
                        + "Windows 路径 C:\\data\\orders 不可用",
                "title"))
                .doesNotThrowAnyException();

        verifyNoInteractions(sopPersistence, diagnosisService, evidenceRouter, agentTriageService);
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
    void remapsCallerSuppliedServerStageIdsBeforeDeterministicPersistence() {
        SopEntry sop = sop();
        IncidentContext incident = incident("903001", IncidentCompleteness.STRUCTURED);
        EvidenceResult callerClaim = new EvidenceResult(
                "ONLINE-CONTRAST-SAMPLE", "UNKNOWN", "", EvidenceStatus.MISSING,
                "caller claims collection ran", Map.of(), "supplied", NOW);
        when(sopPersistence.find(WORKSPACE_ID, "CSDP", "903001")).thenReturn(sop);
        when(diagnosisService.diagnoseAndPersist(
                anyLong(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any()))
                .thenReturn(new StoredDiagnosis(diagnosis(), 1, true));

        intake.report(WORKSPACE_ID, incident, List.of(callerClaim), false);

        ArgumentCaptor<List<EvidenceResult>> evidenceCaptor = ArgumentCaptor.forClass(List.class);
        verify(diagnosisService).diagnoseAndPersist(
                eq(WORKSPACE_ID), eq(incident), eq(sop), evidenceCaptor.capture(),
                eq(false), eq(true), eq(NOW), eq(NOW));
        assertThat(evidenceCaptor.getValue())
                .extracting(EvidenceResult::queryId)
                .containsExactly("supplied-reserved-1");
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

        // The registry is consulted for a scenario owner first; only the absence
        // of one leaves the miss path, which is unwired here.
        verifyNoInteractions(diagnosisService);
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

    /**
     * A monitoring platform raises dial-test failures by symptom, so requiring
     * an error code meant no reviewed Playbook could ever own this whole class
     * of alert; every one of them spent a model call to reach an abstention.
     */
    @Test
    void anAlertWithNoErrorCodeReachesTheScenarioPlaybookInsteadOfTheAgent() {
        SopEntry probe = scenarioSop();
        when(sopPersistence.list(eq(WORKSPACE_ID), eq("approved"), eq("CSDP"), anyInt()))
                .thenReturn(List.of(scenarioSummary(probe)));
        when(sopPersistence.find(WORKSPACE_ID, "CSDP", probe.errorCode())).thenReturn(probe);
        when(diagnosisService.diagnoseAndPersist(
                anyLong(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any()))
                .thenReturn(new StoredDiagnosis(diagnosis(), 1, true));
        TroubleshootingIntakeService wired = new TroubleshootingIntakeService(
                sopPersistence, diagnosisService, evidenceRouter, agentTriageService,
                Clock.fixed(NOW, ZoneOffset.UTC));

        wired.report(
                WORKSPACE_ID,
                incident(null, IncidentCompleteness.STRUCTURED),
                List.of(evidence()),
                false);

        verify(diagnosisService).diagnoseAndPersist(
                eq(WORKSPACE_ID), any(), eq(probe), any(),
                anyBoolean(), anyBoolean(), any(), any());
        verifyNoInteractions(agentTriageService);
    }

    /**
     * A symptom may replace only the missing code. An unstructured report never
     * had its system and service confirmed, so matching its text would attach
     * reviewed authority to fields nobody verified.
     */
    @Test
    void anUnstructuredReportIsNotRoutedByItsSymptomEvenWhenAPlaybookWouldMatch() {
        StoredDiagnosis stored = new StoredDiagnosis(diagnosis(), 1, true);
        when(agentTriageService.triage(
                anyLong(), any(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(stored);
        TroubleshootingIntakeService wired = new TroubleshootingIntakeService(
                sopPersistence, diagnosisService, evidenceRouter, agentTriageService,
                Clock.fixed(NOW, ZoneOffset.UTC));

        wired.report(
                WORKSPACE_ID,
                incident(null, IncidentCompleteness.SYMPTOM),
                List.of(),
                false);

        verify(sopPersistence, never()).list(anyLong(), any(), any(), anyInt());
        verify(agentTriageService).triage(
                anyLong(), any(), any(), anyBoolean(), any(), any(), any());
    }

    private SopEntry scenarioSop() {
        return new SopEntry(
                "sop-topology", SopEntry.CURRENT_CONTRACT_VERSION, "CSDP",
                "scenario:deployment_topology_probe", "order-svc",
                "部署拓扑拨测", "", "availability", "SRE", "approved", true,
                List.of(new EvidenceRequest("EV-1", "log_count", "确认发生", Map.of(), "-15m", true)),
                List.of(new AnomalyCriterion("error_present", "EV-1", "错误码日志出现",
                        new Criterion.NumericGte("count", 1))),
                List.of(new DiagnosisRule("R-a", List.of("error_present"),
                        "拨测失败", "目标不可达", Confidence.HIGH, false)),
                List.of(),
                List.of("订单创建超时"));
    }

    private SopSummary scenarioSummary(SopEntry entry) {
        return new SopSummary(
                entry.sopId(), entry.routingKey(), entry.system(), entry.errorCode(),
                entry.service(), entry.status(), entry.verified(), entry.operational(),
                java.time.LocalDateTime.now(), java.time.LocalDateTime.now(),
                1, null, null, null, null, null);
    }

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
                new PlaybookVersionRef("playbook-903001", 1),
                List.of(evidence()), List.of("error_present"), List.of(),
                null, false, true, List.of());
    }

    private SopEntry itgwSop() {
        return new SopEntry(
                "sop-904003", SopEntry.CURRENT_CONTRACT_VERSION,
                "CSDP", "904003", "csdp-wechat",
                "ITGW访问失败路径核查", "ITGW内容策略拦截", "integration",
                "CSDP WECHAT 负责团队", "approved", true,
                List.of(
                        new EvidenceRequest(
                                "ITGW-LOG-SEARCH", "log_search", "检索失败并取得关联ID",
                                Map.of("search_term", "itgw_access_failed"), "-15m", true),
                        new EvidenceRequest(
                                "ITGW-TRACE-BUNDLE", "log_trace_bundle", "还原同一次调用链",
                                Map.of("ps_id", "placeholder-must-not-be-used"), "-15m", true),
                        new EvidenceRequest(
                                "ITGW-CONTRAST", "contrast_sample", "对照成功与失败样本",
                                Map.of(
                                        "scenario_key", "itgw_access_failed",
                                        "exclude_ps_id", "placeholder-must-not-be-used"),
                                "-15m", true)),
                List.of(
                        new AnomalyCriterion(
                                "failure_present", "ITGW-LOG-SEARCH", "存在失败样本",
                                new Criterion.NumericGte("match_count", 1)),
                        new AnomalyCriterion(
                                "content_policy_discriminated", "ITGW-CONTRAST",
                                "内容拦截特征只出现在失败样本",
                                new Criterion.FailureSuccessRateContrast(
                                        "failure_match_count", "failure_sample_count",
                                        "success_match_count", "success_sample_count",
                                        0.9, 0.1, 0.8))),
                List.of(new DiagnosisRule(
                        "RULE-ITGW-CONTENT-POLICY-BLOCK",
                        List.of("failure_present", "content_policy_discriminated"),
                        "ITGW内容安全策略拦截请求",
                        "失败样本命中内容拦截特征，同窗口成功请求未命中",
                        Confidence.HIGH,
                        false)),
                List.of());
    }

    private EvidenceResult itgwEvidence(EvidenceRequest request) {
        Map<String, Object> observed = switch (request.signalKind()) {
            case "log_search" -> Map.of(
                    "match_count", 9,
                    "ps_id", "itgw-trace-observed-1",
                    "sample_message", "raw-business-payload");
            case "log_trace_bundle" -> Map.of(
                    "ps_id", String.valueOf(request.target().get("ps_id")),
                    "entries", List.of(
                            Map.of(
                                    "timestamp", 1_000L,
                                    "service", "csdp-wechat",
                                    "level", "ERROR",
                                    "message", "error code 904003 blocked-term-value"),
                            Map.of(
                                    "timestamp", 1_010L,
                                    "service", "itgw",
                                    "level", "ERROR",
                                    "message", "upstream error code 500")));
            case "contrast_sample" -> Map.of(
                    "discriminating_feature", "itgw_content_policy_blocked",
                    "failure_sample_count", 9,
                    "failure_match_count", 9,
                    "success_sample_count", 35,
                    "success_match_count", 0);
            default -> throw new IllegalArgumentException(request.signalKind());
        };
        return new EvidenceResult(
                request.requestId(), "L", "source-query-withheld", EvidenceStatus.ANOMALY,
                "canonical evidence", observed, "guance:log", NOW);
    }
}
