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
import vip.mate.troubleshooting.intake.IntakeSession;
import vip.mate.troubleshooting.intake.IntakeSessionStatus;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.model.IncidentImpact;
import vip.mate.troubleshooting.model.PlaybookVersionRef;
import vip.mate.troubleshooting.model.SopEntry;

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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FormalAdmissionFallbackIntakeServiceTest {

    private static final long WORKSPACE_ID = 7L;
    private static final Instant NOW = Instant.parse("2026-08-17T07:44:00Z");
    private static final String FALLBACK_REASON =
            "标准排障方法未通过正式准入，已转入通用只读调查";

    @Mock private TroubleshootingSopPersistenceService sopPersistence;
    @Mock private DeterministicDiagnosisService diagnosisService;
    @Mock private EvidenceSourceRouter evidenceRouter;
    @Mock private EvidenceSpineOrchestrator evidenceSpine;
    @Mock private TroubleshootingAgentTriageService genericInvestigation;
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
                genericInvestigation,
                formalAdmissions,
                persistence,
                formalClaims,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void matchedPlaybookAdmissionConflictFallsBackToFormalGenericAfterReleasingDirectClaim() {
        IncidentContext incident = incident();
        SopEntry playbook = playbook();
        FormalDiagnosisClaim claim = directClaim(incident);
        StoredDiagnosis expected = org.mockito.Mockito.mock(StoredDiagnosis.class);
        when(sopPersistence.find(WORKSPACE_ID, "CSDP", "904003"))
                .thenReturn(playbook);
        when(formalClaims.claim(
                        WORKSPACE_ID,
                        claim.dedupKey(),
                        NOW,
                        Duration.ofMinutes(5)))
                .thenReturn(FormalDiagnosisClaimService.ClaimResult.acquired(claim));
        when(formalAdmissions.admit(WORKSPACE_ID, incident, playbook))
                .thenThrow(formalAdmissionConflict("pilot does not contain this service"));
        when(genericInvestigation.triageFormal(
                        WORKSPACE_ID,
                        incident,
                        List.of(),
                        FALLBACK_REASON,
                        NOW,
                        NOW))
                .thenReturn(expected);

        assertThat(intake.report(WORKSPACE_ID, incident, List.of(), false, NOW))
                .isSameAs(expected);

        InOrder order = inOrder(formalAdmissions, formalClaims, genericInvestigation);
        order.verify(formalAdmissions).admit(WORKSPACE_ID, incident, playbook);
        order.verify(formalClaims).release(WORKSPACE_ID, claim);
        order.verify(genericInvestigation).triageFormal(
                WORKSPACE_ID, incident, List.of(), FALLBACK_REASON, NOW, NOW);
        verifyNoInteractions(evidenceSpine, evidenceRouter, diagnosisService);
    }

    @Test
    void intakeSessionAdmissionConflictDelegatesItsLiveClaimToFormalGeneric() {
        IntakeSession session = intakeSession();
        IncidentContext incident = incidentFor(session);
        SopEntry playbook = playbook();
        FormalDiagnosisClaim claim = intakeClaim(session);
        StoredDiagnosis expected = org.mockito.Mockito.mock(StoredDiagnosis.class);
        when(formalClaims.claim(
                        WORKSPACE_ID,
                        claim.dedupKey(),
                        NOW,
                        Duration.ofMinutes(5)))
                .thenReturn(FormalDiagnosisClaimService.ClaimResult.acquired(claim));
        when(persistence.findByIntakeSessionId(WORKSPACE_ID, session.intakeSessionId()))
                .thenReturn(Optional.empty());
        when(sopPersistence.find(WORKSPACE_ID, "CSDP", "904003"))
                .thenReturn(playbook);
        when(formalAdmissions.admit(WORKSPACE_ID, incident, playbook))
                .thenThrow(formalAdmissionConflict("active Playbook changed"));
        when(genericInvestigation.triageFormalForIntake(
                        WORKSPACE_ID,
                        incident,
                        List.of(),
                        FALLBACK_REASON,
                        NOW,
                        NOW,
                        session.intakeSessionId(),
                        claim))
                .thenReturn(expected);

        assertThat(intake.report(session, false)).isSameAs(expected);

        verify(genericInvestigation).triageFormalForIntake(
                WORKSPACE_ID,
                incident,
                List.of(),
                FALLBACK_REASON,
                NOW,
                NOW,
                session.intakeSessionId(),
                claim);
        verify(formalClaims, never()).release(WORKSPACE_ID, claim);
        verifyNoInteractions(evidenceSpine, evidenceRouter, diagnosisService);
    }

    @Test
    void arbitraryAdmissionRuntimeFailureIsNotDowngradedToGeneric() {
        IncidentContext incident = incident();
        SopEntry playbook = playbook();
        FormalDiagnosisClaim claim = directClaim(incident);
        IllegalStateException sourceFailure = new IllegalStateException("database unavailable");
        when(sopPersistence.find(WORKSPACE_ID, "CSDP", "904003"))
                .thenReturn(playbook);
        when(formalClaims.claim(
                        WORKSPACE_ID,
                        claim.dedupKey(),
                        NOW,
                        Duration.ofMinutes(5)))
                .thenReturn(FormalDiagnosisClaimService.ClaimResult.acquired(claim));
        when(formalAdmissions.admit(WORKSPACE_ID, incident, playbook))
                .thenThrow(sourceFailure);

        assertThatThrownBy(() -> intake.report(
                        WORKSPACE_ID, incident, List.of(), false, NOW))
                .isSameAs(sourceFailure);

        verify(formalClaims).release(WORKSPACE_ID, claim);
        verifyNoInteractions(genericInvestigation, evidenceSpine, evidenceRouter, diagnosisService);
    }

    @Test
    void admissionKeyWithoutConflictStatusIsNotDowngradedToGeneric() {
        IncidentContext incident = incident();
        SopEntry playbook = playbook();
        FormalDiagnosisClaim claim = directClaim(incident);
        MateClawException serverFailure = new MateClawException(
                "err.troubleshooting.formal_admission_conflict",
                500,
                "admission storage failed");
        when(sopPersistence.find(WORKSPACE_ID, "CSDP", "904003"))
                .thenReturn(playbook);
        when(formalClaims.claim(
                        WORKSPACE_ID,
                        claim.dedupKey(),
                        NOW,
                        Duration.ofMinutes(5)))
                .thenReturn(FormalDiagnosisClaimService.ClaimResult.acquired(claim));
        when(formalAdmissions.admit(WORKSPACE_ID, incident, playbook))
                .thenThrow(serverFailure);

        assertThatThrownBy(() -> intake.report(
                        WORKSPACE_ID, incident, List.of(), false, NOW))
                .isSameAs(serverFailure);

        verify(formalClaims).release(WORKSPACE_ID, claim);
        verifyNoInteractions(genericInvestigation, evidenceSpine, evidenceRouter, diagnosisService);
    }

    @Test
    void evidenceSourceFailureAfterSuccessfulAdmissionIsNotDowngradedToGeneric() {
        IncidentContext incident = incident();
        SopEntry playbook = playbook();
        FormalDiagnosisClaim claim = directClaim(incident);
        FormalDiagnosisAdmission admission = admission(playbook);
        MateClawException sourceFailure = formalAdmissionConflict("source transport failed");
        when(sopPersistence.find(WORKSPACE_ID, "CSDP", "904003"))
                .thenReturn(playbook);
        when(formalClaims.claim(
                        WORKSPACE_ID,
                        claim.dedupKey(),
                        NOW,
                        Duration.ofMinutes(5)))
                .thenReturn(FormalDiagnosisClaimService.ClaimResult.acquired(claim));
        when(formalAdmissions.admit(WORKSPACE_ID, incident, playbook))
                .thenReturn(admission);
        when(evidenceSpine.collect(
                        WORKSPACE_ID,
                        incident,
                        admission.evidenceSpinePlan(),
                        java.util.Set.of("guance")))
                .thenThrow(sourceFailure);

        assertThatThrownBy(() -> intake.report(
                        WORKSPACE_ID, incident, List.of(), false, NOW))
                .isSameAs(sourceFailure);

        verify(formalClaims).release(WORKSPACE_ID, claim);
        verifyNoInteractions(genericInvestigation, evidenceRouter, diagnosisService);
    }

    private IncidentContext incident() {
        return new IncidentContext(
                "incident-itgw",
                "CSDP",
                "csdp-wechat",
                "904003",
                "ITGW访问失败【904003】",
                "P1",
                IncidentImpact.unknown("6 alerts"),
                null,
                NOW,
                null,
                "alert_webhook",
                IncidentCompleteness.STRUCTURED,
                "ITGW访问失败【904003】");
    }

    private IncidentContext incidentFor(IntakeSession session) {
        return new IncidentContext(
                "incident-" + session.intakeSessionId(),
                session.system(),
                session.service(),
                session.errorCode(),
                session.symptom(),
                "P2",
                IncidentImpact.unknown("客户/影响对象: " + session.customerRef()),
                session.traceId(),
                session.occurredAt(),
                null,
                "channel:" + session.source(),
                IncidentCompleteness.STRUCTURED,
                session.symptom());
    }

    private IntakeSession intakeSession() {
        return new IntakeSession(
                "intake-formal-fallback",
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
                "sop-itgw",
                SopEntry.CURRENT_CONTRACT_VERSION,
                "CSDP",
                "904003",
                "csdp-wechat",
                "ITGW route",
                "reviewed cause",
                "integration",
                "owner",
                "approved",
                true,
                List.of(
                        new EvidenceRequest(
                                "ITGW-SEARCH",
                                "log_search",
                                "search",
                                Map.of("search_term", "itgw_access_failed"),
                                "-15m",
                                true),
                        new EvidenceRequest(
                                "ITGW-TRACE",
                                "log_trace_bundle",
                                "trace",
                                Map.of("ps_id", "server-owned"),
                                "-15m",
                                true),
                        new EvidenceRequest(
                                "ITGW-CONTRAST",
                                "contrast_sample",
                                "contrast",
                                Map.of("scenario_key", "itgw_access_failed"),
                                "-15m",
                                false)),
                List.of(),
                List.of(),
                List.of(),
                List.of("itgw访问失败"));
    }

    private FormalDiagnosisAdmission admission(SopEntry playbook) {
        return new FormalDiagnosisAdmission(
                11,
                new PlaybookVersionRef(playbook.sopId(), 3),
                playbook,
                null,
                new EvidenceSpinePlan(
                        "ITGW-SEARCH",
                        "ITGW-TRACE",
                        "ITGW-CONTRAST",
                        "itgw_access_failed",
                        "-15m"),
                "t7-012345678901234567890123",
                "a".repeat(64));
    }

    private FormalDiagnosisClaim directClaim(IncidentContext incident) {
        String key = IncidentDeduplicationKey.create(incident, false, NOW).orElseThrow();
        return new FormalDiagnosisClaim(
                key,
                "claim-direct-fallback",
                NOW,
                NOW.plus(Duration.ofMinutes(5)));
    }

    private FormalDiagnosisClaim intakeClaim(IntakeSession session) {
        return new FormalDiagnosisClaim(
                FormalDiagnosisClaimKey.forIntake(WORKSPACE_ID, session.intakeSessionId()),
                "claim-intake-fallback",
                NOW,
                NOW.plus(Duration.ofMinutes(5)));
    }

    private MateClawException formalAdmissionConflict(String message) {
        return new MateClawException(
                "err.troubleshooting.formal_admission_conflict",
                409,
                message);
    }
}
