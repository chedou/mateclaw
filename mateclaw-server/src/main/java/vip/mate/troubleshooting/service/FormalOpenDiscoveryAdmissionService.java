package vip.mate.troubleshooting.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.evidence.GuanceEvidenceAcceptance;
import vip.mate.troubleshooting.evidence.GuanceEvidenceAcceptanceService;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.pilot.TroubleshootingPilotPlanService;

import java.util.Locale;

/**
 * Freezes the platform-level authorities required by a formal generic
 * investigation. It intentionally does not depend on a scenario Playbook or
 * D20 scenario-scoped bindings.
 */
@Service
public class FormalOpenDiscoveryAdmissionService {

    private final TroubleshootingPilotPlanService pilotPlans;
    private final GuanceEvidenceAcceptanceService guanceAcceptance;

    public FormalOpenDiscoveryAdmissionService(
            TroubleshootingPilotPlanService pilotPlans,
            GuanceEvidenceAcceptanceService guanceAcceptance) {
        if (pilotPlans == null || guanceAcceptance == null) {
            throw new IllegalArgumentException(
                    "pilot and Guance admission services are required");
        }
        this.pilotPlans = pilotPlans;
        this.guanceAcceptance = guanceAcceptance;
    }

    @Transactional
    public FormalOpenDiscoveryAdmission admit(
            long workspaceId,
            IncidentContext incident) {
        requireStructuredScope(workspaceId, incident);
        Integer pilotPlanVersion = pilotPlans.enrollmentVersion(
                workspaceId, incident.system(), incident.service(), false);
        if (pilotPlanVersion == null) {
            throw conflict(
                    "formal open discovery requires an enabled pilot plan containing the exact system/service");
        }
        GuanceEvidenceAcceptance accepted = guanceAcceptance.requireAccepted(
                workspaceId, incident.system(), incident.service());
        requireExactAcceptance(incident, accepted);
        return new FormalOpenDiscoveryAdmission(
                pilotPlanVersion,
                accepted.acceptanceId(),
                accepted.bindingFingerprint());
    }

    public void revalidate(
            long workspaceId,
            IncidentContext incident,
            FormalOpenDiscoveryAdmission admission) {
        requireStructuredScope(workspaceId, incident);
        if (admission == null) {
            throw conflict("formal open-discovery admission is required");
        }
        Integer currentPilot = pilotPlans.enrollmentVersion(
                workspaceId, incident.system(), incident.service(), false);
        if (currentPilot == null || currentPilot != admission.pilotPlanVersion()) {
            throw conflict("pilot plan changed during formal open discovery");
        }
        GuanceEvidenceAcceptance current = guanceAcceptance.requireAccepted(
                workspaceId, incident.system(), incident.service());
        requireExactAcceptance(incident, current);
        if (!admission.guanceAcceptanceId().equals(current.acceptanceId())
                || !admission.guanceBindingFingerprint()
                        .equals(current.bindingFingerprint())) {
            throw conflict(
                    "Guance owner acceptance changed during formal open discovery");
        }
    }

    private void requireStructuredScope(long workspaceId, IncidentContext incident) {
        if (workspaceId <= 0
                || incident == null
                || blank(incident.system())
                || blank(incident.service())
                || blank(incident.title())) {
            throw conflict(
                    "formal open discovery requires structured system and service");
        }
    }

    private void requireExactAcceptance(
            IncidentContext incident,
            GuanceEvidenceAcceptance accepted) {
        if (accepted == null
                || !same(incident.system(), accepted.system())
                || !same(incident.service(), accepted.service())) {
            throw conflict(
                    "Guance owner acceptance belongs to a different system/service");
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private boolean same(String left, String right) {
        return normalize(left).equals(normalize(right));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private MateClawException conflict(String message) {
        return new MateClawException(
                "err.troubleshooting.formal_open_discovery_conflict", 409, message);
    }
}
