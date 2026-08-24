package vip.mate.troubleshooting.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.agent.TroubleshootingAgentTriageService;
import vip.mate.troubleshooting.evidence.EvidenceSourceRouter;
import vip.mate.troubleshooting.evidence.EvidenceSpineOrchestrator;
import vip.mate.troubleshooting.evidence.EvidenceSpinePlan;
import vip.mate.troubleshooting.evidence.EvidenceSpineResult;
import vip.mate.troubleshooting.evidence.EvidenceSpineTimings;
import vip.mate.troubleshooting.intake.IntakeSession;
import vip.mate.troubleshooting.intake.IntakeSessionStatus;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.model.IncidentImpact;
import vip.mate.troubleshooting.model.InvestigationMode;
import vip.mate.troubleshooting.model.PlaybookVersionRef;
import vip.mate.troubleshooting.model.SopEntry;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FormalTroubleshootingIntakeServiceTest {

    private static final long WORKSPACE_ID = 7L;
    private static final Instant NOW = Instant.parse("2026-08-17T07:44:00Z");

    @Mock private TroubleshootingSopPersistenceService sopPersistence;
    @Mock private DeterministicDiagnosisService diagnosisService;
    @Mock private EvidenceSourceRouter evidenceRouter;
    @Mock private EvidenceSpineOrchestrator evidenceSpine;
    @Mock private TroubleshootingAgentTriageService agent;
    @Mock private FormalDiagnosisAdmissionService formalAdmissions;
    @Mock private TroubleshootingPersistenceService persistence;
    @Mock private FormalDiagnosisClaimService formalClaims;

    private TroubleshootingIntakeService intake;

    @BeforeEach
    void setUp() {
        intake = new TroubleshootingIntakeService(
                sopPersistence,
                diagnosisService,
                evidenceRouter,
                evidenceSpine,
                agent,
                formalAdmissions,
                persistence,
                formalClaims,
                Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(formalClaims.claim(
                        eq(WORKSPACE_ID), anyString(), eq(NOW), eq(Duration.ofMinutes(5))))
                .thenAnswer(invocation -> FormalDiagnosisClaimService.ClaimResult.acquired(
                        new FormalDiagnosisClaim(
                                invocation.getArgument(1),
                                "claim-intake-1",
                                NOW,
                                NOW.plusSeconds(300))));
    }

    @Test
    void nonRehearsalCannotBypassAdmissionThroughALegacyConstructor() {
        TroubleshootingIntakeService legacy = new TroubleshootingIntakeService(
                sopPersistence, diagnosisService, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> legacy.report(
                WORKSPACE_ID, incident(), List.of(), false))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("formal admission runtime")
                .extracting(error -> ((MateClawException) error).getCode())
                .isEqualTo(409);

        verifyNoInteractions(diagnosisService);
    }

    @Test
    void formalRequestRejectsCallerEvidenceBeforeRoutingOrSourceIo() {
        EvidenceResult forged = evidence("guance:dql", EvidenceStatus.ANOMALY);

        assertThatThrownBy(() -> intake.report(
                WORKSPACE_ID, incident(), List.of(forged), false))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("server-collected Guance evidence")
                .extracting(error -> ((MateClawException) error).getCode())
                .isEqualTo(409);

        verifyNoInteractions(
                sopPersistence, formalAdmissions, evidenceSpine,
                evidenceRouter, diagnosisService, agent, formalClaims);
    }

    @Test
    void nonAdmissionFailureStopsBeforeAnyEvidenceSourceCall() {
        SopEntry playbook = playbook();
        when(sopPersistence.find(WORKSPACE_ID, "CSDP", "904003"))
                .thenReturn(playbook);
        when(formalAdmissions.admit(WORKSPACE_ID, incident(), playbook))
                .thenThrow(nonAdmissionConflict("admission runtime unavailable"));

        assertThatThrownBy(() -> intake.report(
                WORKSPACE_ID, incident(), List.of(), false))
                .isInstanceOf(MateClawException.class)
                .extracting(error -> ((MateClawException) error).getCode())
                .isEqualTo(409);

        verifyNoInteractions(evidenceSpine, evidenceRouter, diagnosisService, agent);
        verify(formalClaims).release(
                WORKSPACE_ID, claimFor(incident()));
    }

    @Test
    void sequentialFormalRetryReturnsOnlyTheDiagnosisOwnedByACompletedClaim() {
        StoredDiagnosis existing = formalStored(false);
        SopEntry playbook = playbook();
        when(sopPersistence.find(WORKSPACE_ID, "CSDP", "904003"))
                .thenReturn(playbook);
        when(formalClaims.claim(
                WORKSPACE_ID, claimFor(incident()).dedupKey(), NOW,
                Duration.ofMinutes(5)))
                .thenReturn(FormalDiagnosisClaimService.ClaimResult.completed(
                        "diag-existing"));
        when(persistence.get(WORKSPACE_ID, "diag-existing")).thenReturn(existing);

        org.assertj.core.api.Assertions.assertThat(intake.report(
                WORKSPACE_ID, incident(), List.of(), false))
                .isSameAs(existing);

        verifyNoInteractions(
                formalAdmissions, evidenceSpine, evidenceRouter,
                diagnosisService, agent);
    }

    @Test
    void concurrentFormalRequestStopsAtTheClaimBeforeAdmissionOrGuance() {
        SopEntry playbook = playbook();
        when(sopPersistence.find(WORKSPACE_ID, "CSDP", "904003"))
                .thenReturn(playbook);
        when(formalClaims.claim(
                WORKSPACE_ID,
                claimFor(incident()).dedupKey(),
                NOW,
                Duration.ofMinutes(5)))
                .thenReturn(FormalDiagnosisClaimService.ClaimResult.inProgress());

        assertThatThrownBy(() -> intake.report(
                WORKSPACE_ID, incident(), List.of(), false))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("already in progress")
                .extracting(error -> ((MateClawException) error).getCode())
                .isEqualTo(409);

        verifyNoInteractions(formalAdmissions, evidenceSpine, diagnosisService);
    }

    @Test
    void completedClaimReturnsTheStoredDiagnosisWithoutAdmissionOrGuance() {
        SopEntry playbook = playbook();
        StoredDiagnosis existing = formalStored(false);
        when(sopPersistence.find(WORKSPACE_ID, "CSDP", "904003"))
                .thenReturn(playbook);
        when(formalClaims.claim(
                WORKSPACE_ID,
                claimFor(incident()).dedupKey(),
                NOW,
                Duration.ofMinutes(5)))
                .thenReturn(FormalDiagnosisClaimService.ClaimResult.completed(
                        "diag-completed"));
        when(persistence.get(WORKSPACE_ID, "diag-completed")).thenReturn(existing);

        org.assertj.core.api.Assertions.assertThat(intake.report(
                WORKSPACE_ID, incident(), List.of(), false))
                .isSameAs(existing);

        verifyNoInteractions(formalAdmissions, evidenceSpine, diagnosisService);
    }

    @Test
    void formalWebIntakeSessionClaimsBeforeAdmissionAndCompletesWithDiagnosis() {
        SopEntry playbook = playbook();
        FormalDiagnosisAdmission admission = admission(playbook);
        EvidenceSpineResult result = result("guance:dql", EvidenceStatus.ANOMALY);
        IntakeSession session = intakeSession();
        when(sopPersistence.find(WORKSPACE_ID, "CSDP", "904003"))
                .thenReturn(playbook);
        when(persistence.findByIntakeSessionId(WORKSPACE_ID, "intake-formal-1"))
                .thenReturn(Optional.empty());
        when(formalAdmissions.admit(eq(WORKSPACE_ID), any(), eq(playbook)))
                .thenReturn(admission);
        when(evidenceSpine.collect(
                eq(WORKSPACE_ID), any(), eq(admission.evidenceSpinePlan()),
                eq(Set.of("guance"))))
                .thenReturn(result);

        intake.report(session, false);

        FormalDiagnosisClaim claim = claimFor(session);
        verify(formalClaims).claim(
                WORKSPACE_ID,
                claim.dedupKey(),
                NOW,
                Duration.ofMinutes(5));
        verify(diagnosisService).diagnoseAndPersistForIntake(
                eq(WORKSPACE_ID),
                any(IncidentContext.class),
                eq(admission),
                eq(result.evidence()),
                eq(false),
                eq(NOW),
                eq(NOW),
                eq("intake-formal-1"),
                eq(claim),
                eq(NOW));
    }

    @Test
    void formalWebIntakeWithoutAPlaybookUsesTheGenericBoundedInvestigation() {
        IntakeSession session = intakeSession();
        FormalDiagnosisClaim claim = claimFor(session);
        StoredDiagnosis expected = org.mockito.Mockito.mock(StoredDiagnosis.class);
        when(persistence.findByIntakeSessionId(WORKSPACE_ID, "intake-formal-1"))
                .thenReturn(Optional.empty());
        when(sopPersistence.find(WORKSPACE_ID, "CSDP", "904003"))
                .thenReturn(null);
        when(agent.triageFormalForIntake(
                        eq(WORKSPACE_ID),
                        any(IncidentContext.class),
                        eq(List.of()),
                        eq("no SOP registered for CSDP:904003"),
                        eq(NOW),
                        eq(NOW),
                        eq("intake-formal-1"),
                        eq(claim)))
                .thenReturn(expected);

        org.assertj.core.api.Assertions.assertThat(intake.report(session, false))
                .isSameAs(expected);

        verify(agent).triageFormalForIntake(
                eq(WORKSPACE_ID),
                any(IncidentContext.class),
                eq(List.of()),
                eq("no SOP registered for CSDP:904003"),
                eq(NOW),
                eq(NOW),
                eq("intake-formal-1"),
                eq(claim));
        verifyNoInteractions(formalAdmissions, evidenceSpine, diagnosisService);
    }

    @Test
    void formalWebIntakeClaimOutlivesTheConfiguredBoundedInvestigationBudget() {
        IntakeSession session = intakeSession();
        Duration boundedClaimLease = Duration.ofMinutes(8);
        when(agent.formalOpenDiscoveryClaimLease()).thenReturn(boundedClaimLease);
        when(formalClaims.claim(
                WORKSPACE_ID,
                claimFor(session).dedupKey(),
                NOW,
                boundedClaimLease))
                .thenReturn(FormalDiagnosisClaimService.ClaimResult.inProgress());

        assertThatThrownBy(() -> intake.report(session, false))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("intake session is already in progress")
                .extracting(error -> ((MateClawException) error).getCode())
                .isEqualTo(409);

        verify(formalClaims).claim(
                WORKSPACE_ID,
                claimFor(session).dedupKey(),
                NOW,
                boundedClaimLease);
        verifyNoInteractions(
                sopPersistence, formalAdmissions, evidenceSpine, diagnosisService);
    }

    @Test
    void concurrentFormalWebIntakeStopsAtClaimBeforeAdmissionOrGuance() {
        IntakeSession session = intakeSession();
        when(formalClaims.claim(
                WORKSPACE_ID,
                claimFor(session).dedupKey(),
                NOW,
                Duration.ofMinutes(5)))
                .thenReturn(FormalDiagnosisClaimService.ClaimResult.inProgress());

        assertThatThrownBy(() -> intake.report(session, false))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("intake session is already in progress")
                .extracting(error -> ((MateClawException) error).getCode())
                .isEqualTo(409);

        verifyNoInteractions(formalAdmissions, evidenceSpine, diagnosisService);
    }

    @Test
    void formalClaimInProgressStopsARehearsalForTheSameIntakeBeforeSourceIo() {
        IntakeSession session = intakeSession();
        when(formalClaims.claim(
                WORKSPACE_ID,
                claimFor(session).dedupKey(),
                NOW,
                Duration.ofMinutes(5)))
                .thenReturn(FormalDiagnosisClaimService.ClaimResult.inProgress());

        assertThatThrownBy(() -> intake.report(session, true))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("intake session is already in progress")
                .extracting(error -> ((MateClawException) error).getCode())
                .isEqualTo(409);

        verifyNoInteractions(formalAdmissions, evidenceSpine, diagnosisService);
    }

    @Test
    void rehearsalAndFormalConcurrencyForOneIntakeInvokesTheExternalSourceOnlyOnce()
            throws Exception {
        IntakeSession session = intakeSession();
        SopEntry playbook = playbook();
        FormalDiagnosisClaim claim = claimFor(session);
        AtomicInteger claims = new AtomicInteger();
        CountDownLatch sourceEntered = new CountDownLatch(1);
        CountDownLatch allowSourceReturn = new CountDownLatch(1);
        when(formalClaims.claim(
                WORKSPACE_ID, claim.dedupKey(), NOW, Duration.ofMinutes(5)))
                .thenAnswer(invocation -> claims.getAndIncrement() == 0
                        ? FormalDiagnosisClaimService.ClaimResult.acquired(claim)
                        : FormalDiagnosisClaimService.ClaimResult.inProgress());
        when(persistence.findByIntakeSessionId(WORKSPACE_ID, "intake-formal-1"))
                .thenReturn(Optional.empty());
        when(sopPersistence.find(WORKSPACE_ID, "CSDP", "904003"))
                .thenReturn(playbook);
        when(evidenceSpine.collect(
                eq(WORKSPACE_ID), any(IncidentContext.class),
                any(EvidenceSpinePlan.class), isNull()))
                .thenAnswer(invocation -> {
                    sourceEntered.countDown();
                    if (!allowSourceReturn.await(2, TimeUnit.SECONDS)) {
                        throw new AssertionError("source test latch timed out");
                    }
                    return result("guance:dql", EvidenceStatus.ANOMALY);
                });
        StoredDiagnosis stored = org.mockito.Mockito.mock(StoredDiagnosis.class);
        when(diagnosisService.diagnoseAndPersistForIntake(
                anyLong(), any(IncidentContext.class), any(SopEntry.class), any(),
                eq(true), anyBoolean(), any(), any(), anyString(),
                any(FormalDiagnosisClaim.class), any()))
                .thenReturn(stored);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<StoredDiagnosis> rehearsal = executor.submit(
                    () -> intake.report(session, true));
            org.assertj.core.api.Assertions.assertThat(
                    sourceEntered.await(2, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> intake.report(session, false))
                    .isInstanceOf(MateClawException.class)
                    .hasMessageContaining("already in progress");

            allowSourceReturn.countDown();
            org.assertj.core.api.Assertions.assertThat(
                    rehearsal.get(2, TimeUnit.SECONDS)).isSameAs(stored);
        } finally {
            allowSourceReturn.countDown();
            executor.shutdownNow();
        }
        verify(evidenceSpine, times(1)).collect(
                eq(WORKSPACE_ID), any(IncidentContext.class),
                any(EvidenceSpinePlan.class), isNull());
    }

    @Test
    void nonAdmissionFailureReleasesTheWebIntakeClaimSoTheSessionCanRetry() {
        IntakeSession session = intakeSession();
        SopEntry playbook = playbook();
        when(persistence.findByIntakeSessionId(WORKSPACE_ID, "intake-formal-1"))
                .thenReturn(Optional.empty());
        when(sopPersistence.find(WORKSPACE_ID, "CSDP", "904003"))
                .thenReturn(playbook);
        when(formalAdmissions.admit(eq(WORKSPACE_ID), any(), eq(playbook)))
                .thenThrow(nonAdmissionConflict("admission runtime unavailable"));

        assertThatThrownBy(() -> intake.report(session, false))
                .isInstanceOf(MateClawException.class)
                .extracting(error -> ((MateClawException) error).getCode())
                .isEqualTo(409);

        verify(formalClaims).release(WORKSPACE_ID, claimFor(session));
        verifyNoInteractions(evidenceSpine, diagnosisService);
        verify(agent, never()).triageFormalForIntake(
                anyLong(), any(), any(), anyString(), any(), any(), anyString(), any());
    }

    @Test
    void anExistingRehearsalDiagnosisCannotSatisfyAFormalIntake() {
        IntakeSession session = intakeSession();
        Diagnosis rehearsal = org.mockito.Mockito.mock(Diagnosis.class);
        when(persistence.findByIntakeSessionId(WORKSPACE_ID, "intake-formal-1"))
                .thenReturn(Optional.of(new StoredDiagnosis(
                        rehearsal, 1, false, null)));

        assertThatThrownBy(() -> intake.report(session, false))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("without a completed claim cannot satisfy a formal intake")
                .extracting(error -> ((MateClawException) error).getCode())
                .isEqualTo(409);

        verify(formalClaims).release(WORKSPACE_ID, claimFor(session));
        verifyNoInteractions(sopPersistence, formalAdmissions, evidenceSpine, diagnosisService);
    }

    @Test
    void anExistingFormalDiagnosisCannotBePresentedAsARehearsalIntake() {
        IntakeSession session = intakeSession();
        Diagnosis formal = org.mockito.Mockito.mock(Diagnosis.class);
        when(formal.rehearsal()).thenReturn(false);
        when(persistence.findByIntakeSessionId(WORKSPACE_ID, "intake-formal-1"))
                .thenReturn(Optional.of(new StoredDiagnosis(
                        formal, 1, false, 11)));

        assertThatThrownBy(() -> intake.report(session, true))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("cannot satisfy a rehearsal intake")
                .extracting(error -> ((MateClawException) error).getCode())
                .isEqualTo(409);

        verify(formalClaims).release(WORKSPACE_ID, claimFor(session));
        verifyNoInteractions(sopPersistence, formalAdmissions, evidenceSpine, diagnosisService);
    }

    @Test
    void aFormalIntakeCannotReuseAnExistingDiagnosisWithoutACompletedClaim() {
        IntakeSession session = intakeSession();
        Diagnosis diagnosis = org.mockito.Mockito.mock(Diagnosis.class);
        StoredDiagnosis existing = new StoredDiagnosis(diagnosis, 1, false, 11);
        when(persistence.findByIntakeSessionId(WORKSPACE_ID, "intake-formal-1"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> intake.report(session, false))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("without a completed claim");

        verify(formalClaims).release(WORKSPACE_ID, claimFor(session));
        verifyNoInteractions(sopPersistence, formalAdmissions, evidenceSpine, diagnosisService);
    }

    @Test
    void aCompletedIntakeClaimReturnsItsAdmittedDiagnosisWithoutSourceIo() {
        IntakeSession session = intakeSession();
        Diagnosis diagnosis = org.mockito.Mockito.mock(Diagnosis.class);
        when(diagnosis.rehearsal()).thenReturn(false);
        when(diagnosis.sourcePlaybookVersionRef())
                .thenReturn(new PlaybookVersionRef("sop-itgw", 3));
        StoredDiagnosis existing = new StoredDiagnosis(diagnosis, 1, false, 11);
        when(formalClaims.claim(
                WORKSPACE_ID,
                claimFor(session).dedupKey(),
                NOW,
                Duration.ofMinutes(5)))
                .thenReturn(FormalDiagnosisClaimService.ClaimResult.completed(
                        "diag-completed-intake"));
        when(persistence.get(WORKSPACE_ID, "diag-completed-intake"))
                .thenReturn(existing);

        org.assertj.core.api.Assertions.assertThat(intake.report(session, false))
                .isSameAs(existing);

        verifyNoInteractions(formalAdmissions, evidenceSpine, diagnosisService);
    }

    @Test
    void aCompletedGenericIntakeClaimReturnsItsFormalOpenDiscoveryDiagnosis() {
        IntakeSession session = intakeSession();
        Diagnosis diagnosis = org.mockito.Mockito.mock(Diagnosis.class);
        when(diagnosis.rehearsal()).thenReturn(false);
        when(diagnosis.investigationMode()).thenReturn(InvestigationMode.OPEN_DISCOVERY);
        StoredDiagnosis existing = new StoredDiagnosis(diagnosis, 1, false, 11);
        when(formalClaims.claim(
                WORKSPACE_ID,
                claimFor(session).dedupKey(),
                NOW,
                Duration.ofMinutes(5)))
                .thenReturn(FormalDiagnosisClaimService.ClaimResult.completed(
                        "diag-completed-generic-intake"));
        when(persistence.get(WORKSPACE_ID, "diag-completed-generic-intake"))
                .thenReturn(existing);

        org.assertj.core.api.Assertions.assertThat(intake.report(session, false))
                .isSameAs(existing);

        verify(agent, never()).requireCompletedFormalOpenDiscovery(
                anyLong(), any(IncidentContext.class), any(StoredDiagnosis.class));
        verify(agent, never()).triageFormalForIntake(
                anyLong(), any(IncidentContext.class), any(), anyString(),
                any(), any(), anyString(), any(FormalDiagnosisClaim.class));
        verifyNoInteractions(
                sopPersistence, formalAdmissions, evidenceSpine, diagnosisService);
    }

    @Test
    void admittedItgwRunUsesOnlyGuanceThenRevalidatesBeforePersistence() {
        SopEntry playbook = playbook();
        FormalDiagnosisAdmission admission = admission(playbook);
        EvidenceSpineResult result = result("guance:dql", EvidenceStatus.ANOMALY);
        when(sopPersistence.find(WORKSPACE_ID, "CSDP", "904003"))
                .thenReturn(playbook);
        when(formalAdmissions.admit(WORKSPACE_ID, incident(), playbook))
                .thenReturn(admission);
        when(evidenceSpine.collect(
                WORKSPACE_ID, incident(), admission.evidenceSpinePlan(), Set.of("guance")))
                .thenReturn(result);

        intake.report(WORKSPACE_ID, incident(), List.of(), false);

        InOrder order = inOrder(formalAdmissions, evidenceSpine, diagnosisService);
        order.verify(formalAdmissions).admit(WORKSPACE_ID, incident(), playbook);
        order.verify(evidenceSpine).collect(
                WORKSPACE_ID, incident(), admission.evidenceSpinePlan(), Set.of("guance"));
        order.verify(formalAdmissions).revalidate(WORKSPACE_ID, incident(), admission);
        order.verify(diagnosisService).diagnoseAndPersist(
                eq(WORKSPACE_ID), eq(incident()), eq(admission), eq(result.evidence()),
                eq(false), eq(NOW), eq(NOW), eq(claimFor(incident())), eq(NOW));
        verifyNoInteractions(evidenceRouter, agent);
    }

    @Test
    void scenarioWithoutFormalD20AuthorityFallsBackToGenericBoundedInvestigation() {
        SopEntry playbook = ctiPlaybook();
        IncidentContext alert = new IncidentContext(
                "incident-cti", "CSDP", "csdp-task", null,
                "CTI创建会话失败", "P1",
                IncidentImpact.unknown("3 alerts"), null, NOW, null,
                "alert_webhook", IncidentCompleteness.STRUCTURED,
                "CTI创建会话失败");
        StoredDiagnosis expected = org.mockito.Mockito.mock(StoredDiagnosis.class);
        String reason = "incident carries no errorCode; deterministic routing needs one";
        when(sopPersistence.list(WORKSPACE_ID, "approved", "CSDP", 200))
                .thenReturn(List.of(summary(playbook)));
        when(sopPersistence.find(
                WORKSPACE_ID, "CSDP", "scenario:cti_create_conversation_failed"))
                .thenReturn(playbook);
        when(agent.triageFormal(
                WORKSPACE_ID, alert, List.of(), reason, NOW, NOW))
                .thenReturn(expected);

        org.assertj.core.api.Assertions.assertThat(intake.report(
                WORKSPACE_ID, alert, List.of(), false))
                .isSameAs(expected);

        verify(agent).triageFormal(
                WORKSPACE_ID, alert, List.of(), reason, NOW, NOW);
        verifyNoInteractions(formalAdmissions, evidenceSpine, diagnosisService, evidenceRouter);
    }

    @Test
    void explicitScenarioSelectorWithoutD20AuthorityFallsBackWithoutSopClaims() {
        SopEntry playbook = ctiPlaybook();
        IncidentContext routedAlert = new IncidentContext(
                "incident-cti-explicit", "CSDP", "csdp-task",
                "scenario:cti_create_conversation_failed",
                "CTI创建会话失败", "P1",
                IncidentImpact.unknown("3 alerts"), null, NOW, null,
                "alert_webhook", IncidentCompleteness.STRUCTURED,
                "CTI创建会话失败");
        IncidentContext genericAlert = new IncidentContext(
                "incident-cti-explicit", "CSDP", "csdp-task", null,
                "CTI创建会话失败", "P1",
                IncidentImpact.unknown("3 alerts"), null, NOW, null,
                "alert_webhook", IncidentCompleteness.STRUCTURED,
                "CTI创建会话失败");
        String reason = "场景专用排障能力尚未完成验收，已转入通用只读调查";
        StoredDiagnosis expected = org.mockito.Mockito.mock(StoredDiagnosis.class);
        when(sopPersistence.find(
                WORKSPACE_ID,
                "CSDP",
                "scenario:cti_create_conversation_failed"))
                .thenReturn(playbook);
        when(agent.triageFormal(
                WORKSPACE_ID, genericAlert, List.of(), reason, NOW, NOW))
                .thenReturn(expected);

        org.assertj.core.api.Assertions.assertThat(intake.report(
                WORKSPACE_ID, routedAlert, List.of(), false, NOW))
                .isSameAs(expected);

        verify(agent).triageFormal(
                WORKSPACE_ID, genericAlert, List.of(), reason, NOW, NOW);
        verifyNoInteractions(
                formalAdmissions, evidenceSpine, diagnosisService, evidenceRouter);
    }

    @Test
    void formalUnknownErrorCodeUsesGenericOpenDiscoveryWithoutD20OrAPlaybook() {
        IncidentContext unknown = new IncidentContext(
                "incident-generic", "CSDP", "csdp-session-service", "999999",
                "未知会话异常", "P2",
                IncidentImpact.unknown("影响待确认"), null, NOW, null,
                "alert_webhook", IncidentCompleteness.STRUCTURED, "未知会话异常");
        StoredDiagnosis expected = org.mockito.Mockito.mock(StoredDiagnosis.class);
        when(sopPersistence.find(WORKSPACE_ID, "CSDP", "999999"))
                .thenReturn(null);
        when(agent.triageFormal(
                WORKSPACE_ID,
                unknown,
                List.of(),
                "no SOP registered for CSDP:999999",
                NOW,
                NOW))
                .thenReturn(expected);

        org.assertj.core.api.Assertions.assertThat(intake.report(
                WORKSPACE_ID, unknown, List.of(), false, NOW))
                .isSameAs(expected);

        verifyNoInteractions(formalAdmissions, evidenceSpine, diagnosisService);
        verify(agent).triageFormal(
                WORKSPACE_ID,
                unknown,
                List.of(),
                "no SOP registered for CSDP:999999",
                NOW,
                NOW);
    }

    @Test
    void formalAlertWithoutErrorCodeUsesGenericOpenDiscoveryWithoutD20() {
        IncidentContext unknown = new IncidentContext(
                "incident-generic-no-code", "CSDP", "csdp-session-service", null,
                "会话服务持续失败", "P2",
                IncidentImpact.unknown("影响待确认"), null, NOW, null,
                "alert_webhook", IncidentCompleteness.SYMPTOM, "会话服务持续失败");
        StoredDiagnosis expected = org.mockito.Mockito.mock(StoredDiagnosis.class);
        String reason = "incident carries no errorCode; deterministic routing needs one";
        when(agent.triageFormal(
                WORKSPACE_ID, unknown, List.of(), reason, NOW, NOW))
                .thenReturn(expected);

        org.assertj.core.api.Assertions.assertThat(intake.report(
                WORKSPACE_ID, unknown, List.of(), false, NOW))
                .isSameAs(expected);

        verifyNoInteractions(formalAdmissions, evidenceSpine, diagnosisService);
        verify(agent).triageFormal(
                WORKSPACE_ID, unknown, List.of(), reason, NOW, NOW);
    }

    @Test
    void admittedRunRejectsFixtureProvenanceBeforeRevalidationOrPersistence() {
        SopEntry playbook = playbook();
        FormalDiagnosisAdmission admission = admission(playbook);
        when(sopPersistence.find(WORKSPACE_ID, "CSDP", "904003"))
                .thenReturn(playbook);
        when(formalAdmissions.admit(WORKSPACE_ID, incident(), playbook))
                .thenReturn(admission);
        when(evidenceSpine.collect(
                WORKSPACE_ID, incident(), admission.evidenceSpinePlan(), Set.of("guance")))
                .thenReturn(result("recorded-replay", EvidenceStatus.ANOMALY));

        assertThatThrownBy(() -> intake.report(
                WORKSPACE_ID, incident(), List.of(), false))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("fixture")
                .extracting(error -> ((MateClawException) error).getCode())
                .isEqualTo(409);

        verify(formalAdmissions, never()).revalidate(anyLong(), any(), any());
        verifyNoInteractions(diagnosisService, evidenceRouter, agent);
        verify(formalClaims).release(WORKSPACE_ID, claimFor(incident()));
    }

    @Test
    void genuineGuanceMissingResultPersistsAnHonestNonFixtureAbstention() {
        SopEntry playbook = playbook();
        FormalDiagnosisAdmission admission = admission(playbook);
        EvidenceSpineResult result = result("guance:dql", EvidenceStatus.MISSING);
        when(sopPersistence.find(WORKSPACE_ID, "CSDP", "904003"))
                .thenReturn(playbook);
        when(formalAdmissions.admit(WORKSPACE_ID, incident(), playbook))
                .thenReturn(admission);
        when(evidenceSpine.collect(
                WORKSPACE_ID, incident(), admission.evidenceSpinePlan(), Set.of("guance")))
                .thenReturn(result);

        intake.report(WORKSPACE_ID, incident(), List.of(), false);

        verify(diagnosisService).diagnoseAndPersist(
                WORKSPACE_ID,
                incident(),
                admission,
                result.evidence(),
                false,
                NOW,
                NOW,
                claimFor(incident()),
                NOW);
    }

    @Test
    void postSourceRevalidationFailureNeverWritesADiagnosis() {
        SopEntry playbook = playbook();
        FormalDiagnosisAdmission admission = admission(playbook);
        when(sopPersistence.find(WORKSPACE_ID, "CSDP", "904003"))
                .thenReturn(playbook);
        when(formalAdmissions.admit(WORKSPACE_ID, incident(), playbook))
                .thenReturn(admission);
        when(evidenceSpine.collect(
                WORKSPACE_ID, incident(), admission.evidenceSpinePlan(), Set.of("guance")))
                .thenReturn(result("guance:dql", EvidenceStatus.ANOMALY));
        org.mockito.Mockito.doThrow(conflict("pilot changed"))
                .when(formalAdmissions)
                .revalidate(WORKSPACE_ID, incident(), admission);

        assertThatThrownBy(() -> intake.report(
                WORKSPACE_ID, incident(), List.of(), false))
                .isInstanceOf(MateClawException.class)
                .extracting(error -> ((MateClawException) error).getCode())
                .isEqualTo(409);

        verify(diagnosisService, never()).diagnoseAndPersist(
                anyLong(), any(), any(FormalDiagnosisAdmission.class), any(),
                anyBoolean(), any(), any(), any(), any());
        verify(formalClaims).release(WORKSPACE_ID, claimFor(incident()));
    }

    private FormalDiagnosisAdmission admission(SopEntry playbook) {
        return new FormalDiagnosisAdmission(
                11,
                new PlaybookVersionRef(playbook.sopId(), 3),
                playbook,
                null,
                new EvidenceSpinePlan(
                        "ITGW-SEARCH", "ITGW-TRACE", "ITGW-CONTRAST",
                        "itgw_access_failed", "-15m"),
                "t7-012345678901234567890123",
                "a".repeat(64));
    }

    private StoredDiagnosis formalStored(boolean created) {
        Diagnosis diagnosis = org.mockito.Mockito.mock(Diagnosis.class);
        when(diagnosis.rehearsal()).thenReturn(false);
        when(diagnosis.sourcePlaybookVersionRef())
                .thenReturn(new PlaybookVersionRef("sop-itgw", 3));
        return new StoredDiagnosis(diagnosis, 1, created, 11);
    }

    private EvidenceSpineResult result(String source, EvidenceStatus status) {
        return result("ITGW-SEARCH", source, status);
    }

    private EvidenceSpineResult result(
            String requestId, String source, EvidenceStatus status) {
        return new EvidenceSpineResult(
                evidence(requestId, source, status), null, null, null,
                1, EvidenceSpineTimings.unmeasured(),
                status == EvidenceStatus.MISSING ? "log_search evidence is missing" : null);
    }

    private EvidenceResult evidence(String source, EvidenceStatus status) {
        return evidence("ITGW-SEARCH", source, status);
    }

    private EvidenceResult evidence(
            String requestId, String source, EvidenceStatus status) {
        return new EvidenceResult(
                requestId,
                "L",
                "withheld",
                status,
                status == EvidenceStatus.MISSING ? "no matching rows" : "matches found",
                status == EvidenceStatus.MISSING
                        ? Map.of()
                        : Map.of("match_count", 3, "ps_id", "ps-1"),
                source,
                NOW);
    }

    private IncidentContext incident() {
        return new IncidentContext(
                "incident-itgw", "CSDP", "csdp-wechat", "904003",
                "ITGW访问失败【904003】", "P1",
                IncidentImpact.unknown("6 alerts"), null, NOW, null,
                "alert_webhook", IncidentCompleteness.STRUCTURED,
                "ITGW访问失败【904003】");
    }

    private FormalDiagnosisClaim claimFor(IncidentContext claimIncident) {
        return new FormalDiagnosisClaim(
                IncidentDeduplicationKey.create(
                        claimIncident, false, NOW).orElseThrow(),
                "claim-intake-1",
                NOW,
                NOW.plusSeconds(300));
    }

    private FormalDiagnosisClaim claimFor(IntakeSession session) {
        return new FormalDiagnosisClaim(
                FormalDiagnosisClaimKey.forIntake(
                        session.workspaceId(), session.intakeSessionId()),
                "claim-intake-1",
                NOW,
                NOW.plusSeconds(300));
    }

    private IntakeSession intakeSession() {
        return new IntakeSession(
                "intake-formal-1",
                IntakeSession.CURRENT_CONTRACT_VERSION,
                WORKSPACE_ID,
                "web_conversation",
                "conversation-1",
                "reporter-1",
                IntakeSessionStatus.READY,
                incident().title(),
                incident().system(),
                incident().service(),
                "customer-1",
                incident().errorCode(),
                incident().traceId(),
                incident().occurredAt(),
                List.of(),
                List.of(),
                NOW,
                NOW,
                NOW,
                List.of());
    }

    private SopEntry playbook() {
        return new SopEntry(
                "sop-itgw", SopEntry.CURRENT_CONTRACT_VERSION,
                "CSDP", "904003", "csdp-wechat",
                "ITGW route", "reviewed cause", "integration", "owner",
                "approved", true,
                List.of(
                        new EvidenceRequest(
                                "ITGW-SEARCH", "log_search", "search",
                                Map.of("search_term", "itgw_access_failed"), "-15m", true),
                        new EvidenceRequest(
                                "ITGW-TRACE", "log_trace_bundle", "trace",
                                Map.of("ps_id", "server-owned"), "-15m", true),
                        new EvidenceRequest(
                                "ITGW-CONTRAST", "contrast_sample", "contrast",
                                Map.of("scenario_key", "itgw_access_failed"), "-15m", false)),
                List.of(), List.of(), List.of(), List.of("itgw访问失败"));
    }

    private SopEntry ctiPlaybook() {
        return new SopEntry(
                "sop-cti", SopEntry.CURRENT_CONTRACT_VERSION,
                "CSDP", "scenario:cti_create_conversation_failed", "csdp-task",
                "CTI route", "reviewed cause", "integration", "owner",
                "approved", true,
                List.of(
                        new EvidenceRequest(
                                "CTI-SEARCH", "log_search", "search",
                                Map.of("search_term", "cti_create_conversation_failed"),
                                "-15m", true),
                        new EvidenceRequest(
                                "CTI-TRACE", "log_trace_bundle", "trace",
                                Map.of("ps_id", "server-owned"), "-15m", true),
                        new EvidenceRequest(
                                "CTI-CONTRAST", "contrast_sample", "contrast",
                                Map.of("scenario_key", "cti_create_conversation_failed"),
                                "-15m", false)),
                List.of(), List.of(), List.of(), List.of("cti创建会话失败"));
    }

    private SopSummary summary(SopEntry playbook) {
        return new SopSummary(
                playbook.sopId(), playbook.routingKey(), playbook.system(),
                playbook.errorCode(), playbook.service(), playbook.status(),
                playbook.verified(), playbook.operational(),
                LocalDateTime.ofInstant(NOW, ZoneOffset.UTC),
                LocalDateTime.ofInstant(NOW, ZoneOffset.UTC),
                1, null, null, null, null, null);
    }

    private MateClawException conflict(String message) {
        return new MateClawException(
                "err.troubleshooting.formal_admission_conflict", 409, message);
    }

    private MateClawException nonAdmissionConflict(String message) {
        return new MateClawException(
                "err.troubleshooting.formal_runtime_failure", 409, message);
    }
}
