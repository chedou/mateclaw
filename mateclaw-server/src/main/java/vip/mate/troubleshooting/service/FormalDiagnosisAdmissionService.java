package vip.mate.troubleshooting.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.evidence.EvidenceSpinePlan;
import vip.mate.troubleshooting.evidence.EvidenceSpinePlanResolver;
import vip.mate.troubleshooting.evidence.GuanceEvidenceAcceptance;
import vip.mate.troubleshooting.evidence.GuanceEvidenceAcceptanceService;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.model.PlaybookVersionRef;
import vip.mate.troubleshooting.model.SopEntry;
import vip.mate.troubleshooting.pilot.TroubleshootingPilotPlanService;
import vip.mate.troubleshooting.synthesis.ApprovedPlaybookVersion;

import java.util.Locale;

/** Freezes every authority needed by one formal Intake before external I/O. */
@Service
public class FormalDiagnosisAdmissionService {

    private final TroubleshootingPilotPlanService pilotPlans;
    private final TroubleshootingPlaybookVersionService playbookVersions;
    private final GuanceEvidenceAcceptanceService guanceAcceptance;

    public FormalDiagnosisAdmissionService(
            TroubleshootingPilotPlanService pilotPlans,
            TroubleshootingPlaybookVersionService playbookVersions,
            GuanceEvidenceAcceptanceService guanceAcceptance) {
        if (pilotPlans == null || playbookVersions == null || guanceAcceptance == null) {
            throw new IllegalArgumentException(
                    "pilot, Playbook and Guance admission services are required");
        }
        this.pilotPlans = pilotPlans;
        this.playbookVersions = playbookVersions;
        this.guanceAcceptance = guanceAcceptance;
    }

    /**
     * The transaction protects the active-authority read. The returned record,
     * rather than a later lookup, owns the exact identity used by collection
     * and persistence.
     */
    @Transactional
    public FormalDiagnosisAdmission admit(
            long workspaceId,
            IncidentContext incident,
            SopEntry routedPlaybook) {
        if (workspaceId <= 0 || incident == null || routedPlaybook == null) {
            throw conflict("workspace, incident and routed Playbook are required");
        }
        requireExactScope(incident, routedPlaybook);
        if (routedPlaybook.scenarioScoped()) {
            throw conflict(
                    "formal scenario diagnosis requires D20 scenario-scoped binding and acceptance; "
                            + "service-level Guance acceptance cannot authorize this scenario yet");
        }

        Integer pilotPlanVersion = pilotPlans.enrollmentVersion(
                workspaceId, incident.system(), incident.service(), false);
        if (pilotPlanVersion == null) {
            throw conflict(
                    "formal diagnosis requires an enabled pilot plan containing the exact system/service");
        }

        String selector = routedPlaybook.routingKey();
        PlaybookVersionRef active = playbookVersions.activeRef(workspaceId, selector)
                .orElseThrow(() -> conflict(
                        "formal diagnosis requires a current active-approved Playbook"));
        if (!routedPlaybook.sopId().equals(active.playbookId())) {
            throw conflict("the routed Playbook is no longer the active authority");
        }
        ApprovedPlaybookVersion authority = playbookVersions
                .lockActiveApprovedByPlaybookId(workspaceId, active.playbookId())
                .orElseThrow(() -> conflict(
                        "the routed Playbook is no longer active-approved"));
        PlaybookVersionRef locked = new PlaybookVersionRef(
                authority.playbookId(), authority.playbookVersion());
        if (!active.equals(locked)
                || !"APPROVED".equals(authority.status())
                || !selector.equals(authority.selectorKey())
                || !selector.equals(authority.playbook().routingKey())
                || !routedPlaybook.equals(authority.playbook())
                || !authority.playbook().operational()) {
            throw conflict(
                    "the active Playbook changed or does not match the Diagnosis selector");
        }

        EvidenceSpinePlan evidencePlan;
        try {
            evidencePlan = EvidenceSpinePlanResolver.resolve(authority.playbook());
        } catch (IllegalArgumentException invalid) {
            throw conflict("the active Playbook Evidence Spine is invalid");
        }
        if (evidencePlan == null) {
            throw conflict(
                    "formal diagnosis currently requires the reviewed three-stage Evidence Spine");
        }

        GuanceEvidenceAcceptance accepted = guanceAcceptance.requireAccepted(
                workspaceId, incident.system(), incident.service());
        if (!same(incident.system(), accepted.system())
                || !same(incident.service(), accepted.service())) {
            throw conflict("Guance owner acceptance belongs to a different system/service");
        }
        return new FormalDiagnosisAdmission(
                pilotPlanVersion,
                locked,
                authority.playbook(),
                authority.knowledgeEvidenceGrade(),
                evidencePlan,
                accepted.acceptanceId(),
                accepted.bindingFingerprint());
    }

    /** Rechecks mutable pilot and source-owner authority after source I/O. */
    public void revalidate(
            long workspaceId,
            IncidentContext incident,
            FormalDiagnosisAdmission admission) {
        if (workspaceId <= 0 || incident == null || admission == null) {
            throw conflict("workspace, incident and formal admission are required");
        }
        Integer currentPilot = pilotPlans.enrollmentVersion(
                workspaceId, incident.system(), incident.service(), false);
        if (currentPilot == null
                || currentPilot != admission.pilotPlanVersion()) {
            throw conflict("pilot plan changed during formal evidence collection");
        }
        GuanceEvidenceAcceptance current = guanceAcceptance.requireAccepted(
                workspaceId, incident.system(), incident.service());
        if (!same(incident.system(), current.system())
                || !same(incident.service(), current.service())
                || !admission.guanceAcceptanceId().equals(current.acceptanceId())
                || !admission.guanceBindingFingerprint()
                        .equals(current.bindingFingerprint())) {
            throw conflict("Guance owner acceptance changed during formal evidence collection");
        }
    }

    private void requireExactScope(IncidentContext incident, SopEntry playbook) {
        if (!same(incident.system(), playbook.system())
                || !same(incident.service(), playbook.service())
                || !same(incident.errorCode(), playbook.errorCode())) {
            throw conflict(
                    "the routed Playbook does not match the exact Diagnosis system/service/selector");
        }
    }

    private boolean same(String left, String right) {
        return normalize(left).equals(normalize(right));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private MateClawException conflict(String message) {
        return new MateClawException(
                "err.troubleshooting.formal_admission_conflict", 409, message);
    }
}
