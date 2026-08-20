package vip.mate.troubleshooting.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.evidence.GuanceEvidenceAcceptance;
import vip.mate.troubleshooting.evidence.GuanceEvidenceAcceptanceService;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.model.IncidentImpact;
import vip.mate.troubleshooting.pilot.TroubleshootingPilotPlanService;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FormalOpenDiscoveryAdmissionServiceTest {

    private static final long WORKSPACE_ID = 7L;
    private static final Instant NOW = Instant.parse("2026-08-21T01:00:00Z");

    @Mock private TroubleshootingPilotPlanService pilotPlans;
    @Mock private GuanceEvidenceAcceptanceService guanceAcceptance;

    private FormalOpenDiscoveryAdmissionService service;

    @BeforeEach
    void setUp() {
        service = new FormalOpenDiscoveryAdmissionService(
                pilotPlans, guanceAcceptance);
    }

    @Test
    void admitsAStructuredGenericIncidentWithoutAPlaybookOrD20() {
        IncidentContext incident = incident(IncidentCompleteness.SYMPTOM);
        GuanceEvidenceAcceptance accepted = accepted();
        when(pilotPlans.enrollmentVersion(
                WORKSPACE_ID, "CSDP", "csdp-session-service", false))
                .thenReturn(4);
        when(guanceAcceptance.requireAccepted(
                WORKSPACE_ID, "CSDP", "csdp-session-service"))
                .thenReturn(accepted);

        FormalOpenDiscoveryAdmission admission = service.admit(
                WORKSPACE_ID, incident);

        assertThat(admission.pilotPlanVersion()).isEqualTo(4);
        assertThat(admission.guanceAcceptanceId())
                .isEqualTo(accepted.acceptanceId());
        assertThat(admission.guanceBindingFingerprint())
                .isEqualTo(accepted.bindingFingerprint());
    }

    @Test
    void rejectsAnIncidentWithoutAnExactTitleBeforeOwnerAcceptance() {
        IncidentContext incomplete = new IncidentContext(
                "incident-generic-1", "CSDP", "csdp-session-service", null,
                "", "P2", IncidentImpact.unknown("影响待确认"),
                null, NOW, null, "alert_webhook", IncidentCompleteness.SYMPTOM,
                "未知会话异常");
        assertThatThrownBy(() -> service.admit(
                WORKSPACE_ID, incomplete))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("structured system and service")
                .extracting(error -> ((MateClawException) error).getCode())
                .isEqualTo(409);

        verify(guanceAcceptance, never()).requireAccepted(
                WORKSPACE_ID, "CSDP", "csdp-session-service");
    }

    @Test
    void revalidationRejectsAnAcceptanceChangedDuringBoundedInvestigation() {
        IncidentContext incident = incident(IncidentCompleteness.STRUCTURED);
        GuanceEvidenceAcceptance before = accepted();
        GuanceEvidenceAcceptance after = new GuanceEvidenceAcceptance(
                "t7-changed-acceptance-000001", "CSDP", "csdp-session-service",
                "b".repeat(64), before.checklist(), before.validation(),
                "owner", NOW.plusSeconds(1));
        when(pilotPlans.enrollmentVersion(
                WORKSPACE_ID, "CSDP", "csdp-session-service", false))
                .thenReturn(4);
        when(guanceAcceptance.requireAccepted(
                WORKSPACE_ID, "CSDP", "csdp-session-service"))
                .thenReturn(before, after);
        FormalOpenDiscoveryAdmission admission = service.admit(
                WORKSPACE_ID, incident);

        assertThatThrownBy(() -> service.revalidate(
                WORKSPACE_ID, incident, admission))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("changed during formal open discovery")
                .extracting(error -> ((MateClawException) error).getCode())
                .isEqualTo(409);
    }

    private IncidentContext incident(IncidentCompleteness completeness) {
        return new IncidentContext(
                "incident-generic-1", "CSDP", "csdp-session-service", "999999",
                "未知会话异常", "P2", IncidentImpact.unknown("影响待确认"),
                null, NOW, null, "alert_webhook", completeness, "未知会话异常");
    }

    private GuanceEvidenceAcceptance accepted() {
        GuanceEvidenceAcceptance.Checklist checklist =
                new GuanceEvidenceAcceptance.Checklist(
                        true, true, true, true, true, true, true);
        GuanceEvidenceAcceptance.ValidationFacts validation =
                new GuanceEvidenceAcceptance.ValidationFacts(
                        1, 1, "c".repeat(64), 5, 5, 10, NOW);
        return new GuanceEvidenceAcceptance(
                "t7-accepted-generic-000001", "CSDP", "csdp-session-service",
                "a".repeat(64), checklist, validation, "owner", NOW);
    }
}
