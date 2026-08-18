package vip.mate.troubleshooting.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.evidence.GuanceEvidenceAcceptance;
import vip.mate.troubleshooting.evidence.GuanceEvidenceAcceptanceService;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.model.IncidentImpact;
import vip.mate.troubleshooting.model.PlaybookVersionRef;
import vip.mate.troubleshooting.model.SopEntry;
import vip.mate.troubleshooting.pilot.TroubleshootingPilotPlanService;
import vip.mate.troubleshooting.synthesis.ApprovedPlaybookVersion;

import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FormalDiagnosisAdmissionServiceTest {

    private static final long WORKSPACE_ID = 7L;
    private static final Instant NOW = Instant.parse("2026-08-17T07:44:00Z");

    @Mock private TroubleshootingPilotPlanService pilotPlans;
    @Mock private TroubleshootingPlaybookVersionService playbookVersions;
    @Mock private GuanceEvidenceAcceptanceService guanceAcceptance;

    private FormalDiagnosisAdmissionService service;

    @BeforeEach
    void setUp() {
        service = new FormalDiagnosisAdmissionService(
                pilotPlans, playbookVersions, guanceAcceptance);
    }

    @Test
    void admissionUsesAWritableTransactionBecauseItLocksTheActiveAuthority() throws Exception {
        Transactional transaction = FormalDiagnosisAdmissionService.class
                .getDeclaredMethod(
                        "admit", long.class, IncidentContext.class, SopEntry.class)
                .getAnnotation(Transactional.class);

        assertThat(transaction).isNotNull();
        assertThat(transaction.readOnly()).isFalse();
        assertThat(Modifier.isFinal(FormalDiagnosisAdmissionService.class.getModifiers()))
                .as("Spring must be able to proxy the transaction around SELECT FOR UPDATE")
                .isFalse();
    }

    @Test
    void rejectsCtiScenarioUntilD20ScenarioScopedBindingAndAcceptanceExist() {
        SopEntry playbook = playbook(
                "sop-cti", "scenario:cti_create_conversation_failed",
                "csdp-task", "cti_create_conversation_failed");
        IncidentContext incident = incident(
                "csdp-task", "scenario:cti_create_conversation_failed",
                "CTI创建会话失败");

        assertThatThrownBy(() -> service.admit(WORKSPACE_ID, incident, playbook))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("scenario-scoped binding and acceptance")
                .extracting(error -> ((MateClawException) error).getCode())
                .isEqualTo(409);

        verifyNoInteractions(pilotPlans, playbookVersions, guanceAcceptance);
    }

    @Test
    void admitsTheExactCurrentItgwErrorCodeAuthorityAndT7Scope() {
        SopEntry playbook = playbook(
                "sop-itgw", "904003", "csdp-wechat", "itgw_access_failed");
        IncidentContext incident = incident(
                "csdp-wechat", "904003", "ITGW访问失败【904003】");
        ApprovedPlaybookVersion authority = authority(playbook, 3);
        GuanceEvidenceAcceptance accepted = accepted("csdp-wechat");
        stubAdmitted(playbook, incident, authority, accepted, 11);

        FormalDiagnosisAdmission admission = service.admit(
                WORKSPACE_ID, incident, playbook);

        assertThat(admission.pilotPlanVersion()).isEqualTo(11);
        assertThat(admission.playbookVersionRef())
                .isEqualTo(new PlaybookVersionRef("sop-itgw", 3));
        verify(guanceAcceptance).requireAccepted(
                WORKSPACE_ID, "CSDP", "csdp-wechat");
    }

    @Test
    void rejectsAFormalRequestOutsideTheExactPilotBeforeAcceptance() {
        SopEntry playbook = playbook(
                "sop-itgw", "904003", "csdp-wechat", "itgw_access_failed");
        IncidentContext incident = incident(
                "csdp-wechat", "904003", "ITGW访问失败【904003】");
        when(pilotPlans.enrollmentVersion(
                WORKSPACE_ID, "CSDP", "csdp-wechat", false)).thenReturn(null);

        assertThatThrownBy(() -> service.admit(WORKSPACE_ID, incident, playbook))
                .isInstanceOf(MateClawException.class)
                .extracting(error -> ((MateClawException) error).getCode())
                .isEqualTo(409);

        verifyNoInteractions(playbookVersions, guanceAcceptance);
    }

    @Test
    void rejectsAPlaybookThatIsNoLongerTheActiveAuthorityBeforeAcceptance() {
        SopEntry playbook = playbook(
                "sop-itgw", "904003", "csdp-wechat", "itgw_access_failed");
        IncidentContext incident = incident(
                "csdp-wechat", "904003", "ITGW访问失败【904003】");
        when(pilotPlans.enrollmentVersion(
                WORKSPACE_ID, "CSDP", "csdp-wechat", false)).thenReturn(11);
        when(playbookVersions.activeRef(WORKSPACE_ID, playbook.routingKey()))
                .thenReturn(Optional.of(new PlaybookVersionRef("replacement", 5)));

        assertThatThrownBy(() -> service.admit(WORKSPACE_ID, incident, playbook))
                .isInstanceOf(MateClawException.class)
                .extracting(error -> ((MateClawException) error).getCode())
                .isEqualTo(409);

        verify(playbookVersions, never()).lockActiveApprovedByPlaybookId(
                WORKSPACE_ID, playbook.sopId());
        verifyNoInteractions(guanceAcceptance);
    }

    @Test
    void rejectsAnUnacceptedExactGuanceScope() {
        SopEntry playbook = playbook(
                "sop-itgw", "904003", "csdp-wechat", "itgw_access_failed");
        IncidentContext incident = incident(
                "csdp-wechat", "904003", "ITGW访问失败【904003】");
        ApprovedPlaybookVersion authority = authority(playbook, 3);
        when(pilotPlans.enrollmentVersion(
                WORKSPACE_ID, "CSDP", "csdp-wechat", false)).thenReturn(11);
        when(playbookVersions.activeRef(WORKSPACE_ID, playbook.routingKey()))
                .thenReturn(Optional.of(new PlaybookVersionRef("sop-itgw", 3)));
        when(playbookVersions.lockActiveApprovedByPlaybookId(
                WORKSPACE_ID, "sop-itgw")).thenReturn(Optional.of(authority));
        when(guanceAcceptance.requireAccepted(
                WORKSPACE_ID, "CSDP", "csdp-wechat"))
                .thenThrow(new MateClawException(
                        "err.troubleshooting.guance_acceptance_conflict", 409,
                        "not accepted"));

        assertThatThrownBy(() -> service.admit(WORKSPACE_ID, incident, playbook))
                .isInstanceOf(MateClawException.class)
                .extracting(error -> ((MateClawException) error).getCode())
                .isEqualTo(409);
    }

    @Test
    void revalidationRejectsAPilotRevisionChangedDuringEvidenceCollection() {
        SopEntry playbook = playbook(
                "sop-itgw", "904003", "csdp-wechat", "itgw_access_failed");
        IncidentContext incident = incident(
                "csdp-wechat", "904003", "ITGW访问失败【904003】");
        ApprovedPlaybookVersion authority = authority(playbook, 3);
        GuanceEvidenceAcceptance accepted = accepted("csdp-wechat");
        when(pilotPlans.enrollmentVersion(
                WORKSPACE_ID, "CSDP", "csdp-wechat", false))
                .thenReturn(11, 12);
        PlaybookVersionRef ref = new PlaybookVersionRef("sop-itgw", 3);
        when(playbookVersions.activeRef(WORKSPACE_ID, playbook.routingKey()))
                .thenReturn(Optional.of(ref));
        when(playbookVersions.lockActiveApprovedByPlaybookId(
                WORKSPACE_ID, "sop-itgw")).thenReturn(Optional.of(authority));
        when(guanceAcceptance.requireAccepted(
                WORKSPACE_ID, "CSDP", "csdp-wechat")).thenReturn(accepted);
        FormalDiagnosisAdmission admission = service.admit(
                WORKSPACE_ID, incident, playbook);

        assertThatThrownBy(() -> service.revalidate(
                WORKSPACE_ID, incident, admission))
                .isInstanceOf(MateClawException.class)
                .extracting(error -> ((MateClawException) error).getCode())
                .isEqualTo(409);

        verify(guanceAcceptance).requireAccepted(
                WORKSPACE_ID, "CSDP", "csdp-wechat");
    }

    @Test
    void revalidationRejectsAnAcceptanceChangedDuringEvidenceCollection() {
        SopEntry playbook = playbook(
                "sop-itgw", "904003", "csdp-wechat", "itgw_access_failed");
        IncidentContext incident = incident(
                "csdp-wechat", "904003", "ITGW访问失败【904003】");
        ApprovedPlaybookVersion authority = authority(playbook, 3);
        GuanceEvidenceAcceptance before = accepted("csdp-wechat");
        GuanceEvidenceAcceptance after = new GuanceEvidenceAcceptance(
                "t7-abcdefghijklmnopqrstuvwx", "CSDP", "csdp-wechat",
                "c".repeat(64), before.checklist(), before.validation(),
                "owner", NOW.plusSeconds(1));
        when(pilotPlans.enrollmentVersion(
                WORKSPACE_ID, "CSDP", "csdp-wechat", false)).thenReturn(11);
        PlaybookVersionRef ref = new PlaybookVersionRef("sop-itgw", 3);
        when(playbookVersions.activeRef(WORKSPACE_ID, playbook.routingKey()))
                .thenReturn(Optional.of(ref));
        when(playbookVersions.lockActiveApprovedByPlaybookId(
                WORKSPACE_ID, "sop-itgw")).thenReturn(Optional.of(authority));
        when(guanceAcceptance.requireAccepted(
                WORKSPACE_ID, "CSDP", "csdp-wechat"))
                .thenReturn(before, after);
        FormalDiagnosisAdmission admission = service.admit(
                WORKSPACE_ID, incident, playbook);

        assertThatThrownBy(() -> service.revalidate(
                WORKSPACE_ID, incident, admission))
                .isInstanceOf(MateClawException.class)
                .extracting(error -> ((MateClawException) error).getCode())
                .isEqualTo(409);
    }

    @Test
    void revalidationRejectsAnAcceptanceReturnedForAnotherScope() {
        SopEntry playbook = playbook(
                "sop-itgw", "904003", "csdp-wechat", "itgw_access_failed");
        IncidentContext incident = incident(
                "csdp-wechat", "904003", "ITGW访问失败【904003】");
        ApprovedPlaybookVersion authority = authority(playbook, 3);
        GuanceEvidenceAcceptance before = accepted("csdp-wechat");
        GuanceEvidenceAcceptance wrongScope = new GuanceEvidenceAcceptance(
                before.acceptanceId(), "CSDP", "csdp-task",
                before.bindingFingerprint(), before.checklist(), before.validation(),
                "owner", NOW.plusSeconds(1));
        when(pilotPlans.enrollmentVersion(
                WORKSPACE_ID, "CSDP", "csdp-wechat", false)).thenReturn(11);
        PlaybookVersionRef ref = new PlaybookVersionRef("sop-itgw", 3);
        when(playbookVersions.activeRef(WORKSPACE_ID, playbook.routingKey()))
                .thenReturn(Optional.of(ref));
        when(playbookVersions.lockActiveApprovedByPlaybookId(
                WORKSPACE_ID, "sop-itgw")).thenReturn(Optional.of(authority));
        when(guanceAcceptance.requireAccepted(
                WORKSPACE_ID, "CSDP", "csdp-wechat"))
                .thenReturn(before, wrongScope);
        FormalDiagnosisAdmission admission = service.admit(
                WORKSPACE_ID, incident, playbook);

        assertThatThrownBy(() -> service.revalidate(
                WORKSPACE_ID, incident, admission))
                .isInstanceOf(MateClawException.class)
                .extracting(error -> ((MateClawException) error).getCode())
                .isEqualTo(409);
    }

    private void stubAdmitted(
            SopEntry playbook,
            IncidentContext incident,
            ApprovedPlaybookVersion authority,
            GuanceEvidenceAcceptance accepted,
            int pilotVersion) {
        when(pilotPlans.enrollmentVersion(
                WORKSPACE_ID, incident.system(), incident.service(), false))
                .thenReturn(pilotVersion);
        PlaybookVersionRef ref = new PlaybookVersionRef(
                authority.playbookId(), authority.playbookVersion());
        when(playbookVersions.activeRef(WORKSPACE_ID, playbook.routingKey()))
                .thenReturn(Optional.of(ref));
        when(playbookVersions.lockActiveApprovedByPlaybookId(
                WORKSPACE_ID, playbook.sopId())).thenReturn(Optional.of(authority));
        when(guanceAcceptance.requireAccepted(
                WORKSPACE_ID, incident.system(), incident.service()))
                .thenReturn(accepted);
    }

    private ApprovedPlaybookVersion authority(SopEntry playbook, int version) {
        return new ApprovedPlaybookVersion(
                playbook.sopId(), version, playbook.routingKey(), "APPROVED",
                "MANUAL", "manual-" + playbook.sopId(),
                "review-" + playbook.sopId(), 1, "reviewer", "approved",
                null, playbook, NOW, NOW);
    }

    private GuanceEvidenceAcceptance accepted(String service) {
        return new GuanceEvidenceAcceptance(
                "t7-012345678901234567890123", "CSDP", service, "a".repeat(64),
                new GuanceEvidenceAcceptance.Checklist(
                        true, true, true, true, true, true, true),
                new GuanceEvidenceAcceptance.ValidationFacts(
                        3, 2, "b".repeat(64), 10, 20, 35, NOW),
                "owner", NOW);
    }

    private IncidentContext incident(
            String service, String errorCode, String title) {
        return new IncidentContext(
                "incident-1", "CSDP", service, errorCode, title, "P1",
                IncidentImpact.unknown("3 alerts"), null, NOW, null,
                "alert_webhook", IncidentCompleteness.STRUCTURED, title);
    }

    private SopEntry playbook(
            String id, String errorCode, String service, String searchTerm) {
        return new SopEntry(
                id, SopEntry.CURRENT_CONTRACT_VERSION, "CSDP", errorCode, service,
                "reviewed route", "reviewed cause", "integration", "owner",
                "approved", true,
                List.of(
                        new EvidenceRequest(
                                id + "-search", "log_search", "search",
                                Map.of("search_term", searchTerm), "-15m", true),
                        new EvidenceRequest(
                                id + "-trace", "log_trace_bundle", "trace",
                                Map.of("ps_id", "server-owned"), "-15m", true),
                        new EvidenceRequest(
                                id + "-contrast", "contrast_sample", "contrast",
                                Map.of("scenario_key", searchTerm), "-15m", false)),
                List.of(), List.of(), List.of(), List.of(titleTrigger(errorCode)));
    }

    private String titleTrigger(String errorCode) {
        return errorCode.startsWith("scenario:") ? "cti创建会话失败" : "itgw访问失败";
    }
}
