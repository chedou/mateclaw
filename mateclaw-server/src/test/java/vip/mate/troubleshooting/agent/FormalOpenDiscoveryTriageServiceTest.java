package vip.mate.troubleshooting.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.agent.AgentService;
import vip.mate.agent.binding.service.AgentBindingService;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.evidence.EvidenceSourceRouter;
import vip.mate.troubleshooting.investigation.BoundedInvestigationPlanner;
import vip.mate.troubleshooting.investigation.BoundedOpenDiscoveryInvestigationService;
import vip.mate.troubleshooting.investigation.DefaultOpenDiscoveryHypothesisGraphFactory;
import vip.mate.troubleshooting.investigation.HypothesisGraph;
import vip.mate.troubleshooting.investigation.RootCauseFinding;
import vip.mate.troubleshooting.model.CriterionOutcome;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.model.IncidentImpact;
import vip.mate.troubleshooting.service.FormalOpenDiscoveryAdmission;
import vip.mate.troubleshooting.service.FormalOpenDiscoveryAdmissionService;
import vip.mate.troubleshooting.service.FormalDiagnosisClaim;
import vip.mate.troubleshooting.service.FormalDiagnosisClaimKey;
import vip.mate.troubleshooting.service.StoredDiagnosis;
import vip.mate.troubleshooting.statemachine.DiagnosisStateMachine;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FormalOpenDiscoveryTriageServiceTest {

    private static final long WORKSPACE_ID = 7L;
    private static final Instant NOW = Instant.parse("2026-08-21T01:00:00Z");

    @Mock private AgentService agentService;
    @Mock private AgentBindingService bindingService;
    @Mock private EvidenceSourceRouter evidenceRouter;
    @Mock private BoundedOpenDiscoveryInvestigationService boundedInvestigation;
    @Mock private OpenDiscoveryDiagnosisPersistenceService persistence;
    @Mock private FormalOpenDiscoveryAdmissionService admissions;
    @Mock private ChatStreamTracker streamTracker;

    private TroubleshootingAgentTriageService service;
    private FormalOpenDiscoveryAdmission admission;
    private TroubleshootingAgentProperties properties;

    @BeforeEach
    void setUp() {
        properties = new TroubleshootingAgentProperties();
        properties.setEnabled(true);
        properties.setBoundedInvestigationEnabled(true);
        properties.setBoundedInvestigationPermittedPlatforms(List.of("guance"));
        TroubleshootingEvidenceSessionRegistry sessions =
                new TroubleshootingEvidenceSessionRegistry(evidenceRouter, properties);
        admission = new FormalOpenDiscoveryAdmission(
                4, "t7-accepted-generic-000001", "a".repeat(64));
        service = new TroubleshootingAgentTriageService(
                properties,
                new OpenDiscoveryAgentGate(properties, agentService, bindingService),
                agentService,
                bindingService,
                sessions,
                boundedInvestigation,
                new DiagnosisStateMachine(
                        Clock.fixed(NOW, ZoneOffset.UTC), prefix -> prefix + "-fixed"),
                persistence,
                new ObjectMapper().findAndRegisterModules(),
                new TroubleshootingEvidenceModelProjector(
                        new vip.mate.troubleshooting.synthesis.DeterministicLogTraceCompressor()),
                Clock.fixed(NOW, ZoneOffset.UTC),
                streamTracker,
                admissions);
        org.mockito.Mockito.lenient().when(persistence.reserve(
                eq(WORKSPACE_ID), any(IncidentContext.class), eq(false),
                eq(NOW), eq(null), any()))
                .thenReturn(OpenDiscoveryRunReservation.unclaimed());
    }

    @Test
    void runsOnlyTheBoundedPlannerAndPersistsFrozenFormalAuthority() {
        IncidentContext incident = incident();
        BoundedOpenDiscoveryInvestigationService.Execution execution = execution("guance");
        when(admissions.admit(WORKSPACE_ID, incident)).thenReturn(admission);
        when(boundedInvestigation.investigateFormal(
                WORKSPACE_ID,
                incident,
                admission.plan(),
                admission.guanceBindingFingerprint()))
                .thenReturn(Optional.of(execution));
        when(persistence.persistFormal(
                eq(WORKSPACE_ID), any(Diagnosis.class), eq(NOW), eq(null),
                any(), eq(null), any(OpenDiscoveryRunAudit.class), eq(admission), eq(NOW)))
                .thenAnswer(call -> new StoredDiagnosis(call.getArgument(1), 0, true, 4));

        StoredDiagnosis stored = service.triageFormal(
                WORKSPACE_ID, incident, List.of(), "no SOP", NOW, NOW);

        assertThat(stored.diagnosis().rehearsal()).isFalse();
        assertThat(stored.diagnosis().fixtureMode()).isFalse();
        assertThat(stored.pilotPlanVersion()).isEqualTo(4);
        verify(admissions).revalidate(WORKSPACE_ID, incident, admission);
        ArgumentCaptor<OpenDiscoveryRunAudit> audit =
                ArgumentCaptor.forClass(OpenDiscoveryRunAudit.class);
        verify(persistence).persistFormal(
                eq(WORKSPACE_ID), eq(stored.diagnosis()), eq(NOW), eq(null),
                eq(null), eq(null), audit.capture(), eq(admission), eq(NOW));
        assertThat(audit.getValue().formalPilotPlanVersion()).isEqualTo(4);
        assertThat(audit.getValue().sourceAcceptanceId())
                .isEqualTo("t7-accepted-generic-000001");
        assertThat(audit.getValue().sourceBindingFingerprint())
                .isEqualTo("a".repeat(64));
        verifyNoInteractions(agentService);
    }

    @Test
    void formalIntakeUsesTheSameBoundedPlannerAndItsSessionOwner() {
        IncidentContext incident = incident();
        BoundedOpenDiscoveryInvestigationService.Execution execution = execution("guance");
        FormalDiagnosisClaim intakeClaim = new FormalDiagnosisClaim(
                FormalDiagnosisClaimKey.forIntake(WORKSPACE_ID, "intake-formal-1"),
                "claim-intake-1",
                NOW,
                NOW.plusSeconds(80));
        when(admissions.admit(WORKSPACE_ID, incident)).thenReturn(admission);
        when(persistence.reserve(
                eq(WORKSPACE_ID),
                eq(incident),
                eq(false),
                eq(NOW),
                eq("intake-formal-1"),
                any()))
                .thenReturn(OpenDiscoveryRunReservation.unclaimed());
        when(boundedInvestigation.investigateFormal(
                WORKSPACE_ID,
                incident,
                admission.plan(),
                admission.guanceBindingFingerprint()))
                .thenReturn(Optional.of(execution));
        when(persistence.persistFormal(
                eq(WORKSPACE_ID),
                any(Diagnosis.class),
                eq(NOW),
                eq("intake-formal-1"),
                eq(null),
                eq(intakeClaim),
                any(OpenDiscoveryRunAudit.class),
                eq(admission),
                eq(NOW)))
                .thenAnswer(call -> new StoredDiagnosis(call.getArgument(1), 0, true, 4));

        StoredDiagnosis stored = service.triageFormalForIntake(
                WORKSPACE_ID,
                incident,
                List.of(),
                "no SOP",
                NOW,
                NOW,
                "intake-formal-1",
                intakeClaim);

        assertThat(stored.diagnosis().rehearsal()).isFalse();
        verify(persistence).reserve(
                eq(WORKSPACE_ID),
                eq(incident),
                eq(false),
                eq(NOW),
                eq("intake-formal-1"),
                any());
        verify(persistence).persistFormal(
                eq(WORKSPACE_ID),
                eq(stored.diagnosis()),
                eq(NOW),
                eq("intake-formal-1"),
                eq(null),
                eq(intakeClaim),
                any(OpenDiscoveryRunAudit.class),
                eq(admission),
                eq(NOW));
        verifyNoInteractions(agentService);
    }

    @Test
    void stopsInsteadOfFallingBackToTheAgentWhenBoundedPlanningIsUnavailable() {
        IncidentContext incident = incident();
        when(admissions.admit(WORKSPACE_ID, incident)).thenReturn(admission);
        when(boundedInvestigation.investigateFormal(
                WORKSPACE_ID,
                incident,
                admission.plan(),
                admission.guanceBindingFingerprint()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.triageFormal(
                WORKSPACE_ID, incident, List.of(), "no SOP", NOW, NOW))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("bounded read-only planner")
                .extracting(error -> ((MateClawException) error).getCode())
                .isEqualTo(409);

        verifyNoInteractions(agentService);
        verify(persistence, never()).persistFormal(
                anyLong(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsNonGuanceEvidenceBeforeAuthorityRevalidationOrPersistence() {
        IncidentContext incident = incident();
        when(admissions.admit(WORKSPACE_ID, incident)).thenReturn(admission);
        when(boundedInvestigation.investigateFormal(
                WORKSPACE_ID,
                incident,
                admission.plan(),
                admission.guanceBindingFingerprint()))
                .thenReturn(Optional.of(execution("recorded-replay")));

        assertThatThrownBy(() -> service.triageFormal(
                WORKSPACE_ID, incident, List.of(), "no SOP", NOW, NOW))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("accepted Guance evidence");

        verify(admissions, never()).revalidate(anyLong(), any(), any());
        verify(persistence, never()).persistFormal(
                anyLong(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void keepsTheClaimAliveBeyondTheConfiguredFormalToolBudget() {
        IncidentContext incident = incident();
        properties.setBoundedInvestigationTimeout(Duration.ofMinutes(3));
        when(admissions.admit(WORKSPACE_ID, incident)).thenReturn(admission);
        when(boundedInvestigation.investigateFormal(
                WORKSPACE_ID,
                incident,
                admission.plan(),
                admission.guanceBindingFingerprint()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.triageFormal(
                WORKSPACE_ID, incident, List.of(), "no SOP", NOW, NOW))
                .isInstanceOf(MateClawException.class);

        ArgumentCaptor<Duration> lease = ArgumentCaptor.forClass(Duration.class);
        verify(persistence).reserve(
                eq(WORKSPACE_ID), eq(incident), eq(false), eq(NOW), eq(null),
                lease.capture());
        assertThat(lease.getValue()).isEqualTo(Duration.ofMinutes(4));
    }

    @Test
    void validatesTheFrozenAuditBeforeReusingACompletedFormalRun() {
        IncidentContext incident = incident();
        StoredDiagnosis completed = new StoredDiagnosis(
                org.mockito.Mockito.mock(Diagnosis.class),
                2, false, 4);
        when(admissions.admit(WORKSPACE_ID, incident)).thenReturn(admission);
        when(persistence.reserve(
                eq(WORKSPACE_ID), eq(incident), eq(false), eq(NOW), eq(null), any()))
                .thenReturn(OpenDiscoveryRunReservation.completed(completed));
        when(persistence.requireCompletedFormal(WORKSPACE_ID, completed, admission))
                .thenReturn(completed);

        assertThat(service.triageFormal(
                WORKSPACE_ID, incident, List.of(), "no SOP", NOW, NOW))
                .isSameAs(completed);

        verify(persistence).requireCompletedFormal(WORKSPACE_ID, completed, admission);
        verifyNoInteractions(boundedInvestigation);
    }

    @Test
    void completedFormalIntakeRetryRevalidatesItsFrozenAuthorityWithoutRerunningTools() {
        IncidentContext incident = incident();
        StoredDiagnosis completed = new StoredDiagnosis(
                org.mockito.Mockito.mock(Diagnosis.class),
                2,
                false,
                4);
        when(admissions.admit(WORKSPACE_ID, incident)).thenReturn(admission);
        when(persistence.requireCompletedFormal(WORKSPACE_ID, completed, admission))
                .thenReturn(completed);

        assertThat(service.requireCompletedFormalOpenDiscovery(
                WORKSPACE_ID, incident, completed))
                .isSameAs(completed);

        verify(admissions).admit(WORKSPACE_ID, incident);
        verify(persistence).requireCompletedFormal(WORKSPACE_ID, completed, admission);
        verifyNoInteractions(boundedInvestigation, agentService);
    }

    @Test
    void completedFormalIntakeRetryPropagatesFrozenAuthorityRejection() {
        IncidentContext incident = incident();
        StoredDiagnosis completed = new StoredDiagnosis(
                org.mockito.Mockito.mock(Diagnosis.class),
                2,
                false,
                4);
        MateClawException rejected = new MateClawException(
                "err.troubleshooting.formal_open_discovery_conflict",
                409,
                "the completed diagnosis does not match the current frozen formal authority");
        when(admissions.admit(WORKSPACE_ID, incident)).thenReturn(admission);
        when(persistence.requireCompletedFormal(WORKSPACE_ID, completed, admission))
                .thenThrow(rejected);

        assertThatThrownBy(() -> service.requireCompletedFormalOpenDiscovery(
                WORKSPACE_ID, incident, completed))
                .isSameAs(rejected);

        verifyNoInteractions(boundedInvestigation, agentService);
    }

    private IncidentContext incident() {
        return new IncidentContext(
                "incident-generic-1", "CSDP", "csdp-session-service", "999999",
                "未知会话异常", "P2", IncidentImpact.unknown("影响待确认"),
                null, NOW, null, "alert_webhook", IncidentCompleteness.STRUCTURED,
                "未知会话异常");
    }

    private BoundedOpenDiscoveryInvestigationService.Execution execution(String source) {
        EvidenceResult application = new EvidenceResult(
                "open-discovery-error-log-scan", "logs", "",
                EvidenceStatus.ANOMALY, "three application errors",
                Map.of("error_count", 3), source, NOW);
        HypothesisGraph graph = new DefaultOpenDiscoveryHypothesisGraphFactory()
                .create(incident())
                .recordOutcome(
                        "open-discovery-error-log-scan",
                        CriterionOutcome.SATISFIED,
                        application.queryId());
        BoundedInvestigationPlanner.Outcome outcome =
                new BoundedInvestigationPlanner.Outcome(
                        graph,
                        RootCauseFinding.from(
                                graph,
                                BoundedInvestigationPlanner.StopReason.EVIDENCE_EXHAUSTED),
                        List.of(application),
                        1,
                        1,
                        NOW,
                        NOW.plusSeconds(1),
                        BoundedInvestigationPlanner.StopReason.EVIDENCE_EXHAUSTED);
        return new BoundedOpenDiscoveryInvestigationService.Execution(
                outcome,
                BoundedOpenDiscoveryInvestigationService.PLAN_KEY,
                "f".repeat(64),
                List.of("error_log_scan", "k8s_workload_health"),
                2,
                2,
                Duration.ofSeconds(10));
    }
}
