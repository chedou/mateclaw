package vip.mate.troubleshooting.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.evidence.GuanceEvidenceAcceptance;
import vip.mate.troubleshooting.evidence.GuanceEvidenceAcceptanceService;
import vip.mate.troubleshooting.evidence.SystemObservabilityScopePolicy;
import vip.mate.troubleshooting.model.IncidentContext;

import java.util.Locale;

/**
 * Freezes the platform-level authorities required by a formal generic
 * investigation. It intentionally does not depend on a scenario Playbook or
 * D20 scenario-scoped bindings.
 */
@Service
public class FormalOpenDiscoveryAdmissionService {

    private static final int GENERIC_AUTHORITY_VERSION = 1;
    private final GuanceEvidenceAcceptanceService guanceAcceptance;

    public FormalOpenDiscoveryAdmissionService(
            GuanceEvidenceAcceptanceService guanceAcceptance) {
        if (guanceAcceptance == null) {
            throw new IllegalArgumentException(
                    "Guance admission service is required");
        }
        this.guanceAcceptance = guanceAcceptance;
    }

    @Transactional
    public FormalOpenDiscoveryAdmission admit(
            long workspaceId,
            IncidentContext incident) {
        requireStructuredScope(workspaceId, incident);
        GuanceEvidenceAcceptanceService.AcceptedBinding authority =
                guanceAcceptance.requireAcceptedBindingAuthority(
                workspaceId, incident.system(), incident.service());
        GuanceEvidenceAcceptance accepted = authority.acceptance();
        requireExactAcceptance(incident, accepted);
        FormalOpenDiscoveryPlan plan = requireFormalPlan(
                authority.readOnlySignalKinds());
        return new FormalOpenDiscoveryAdmission(
                GENERIC_AUTHORITY_VERSION,
                accepted.acceptanceId(),
                accepted.bindingFingerprint(),
                plan);
    }

    public void revalidate(
            long workspaceId,
            IncidentContext incident,
            FormalOpenDiscoveryAdmission admission) {
        requireStructuredScope(workspaceId, incident);
        if (admission == null) {
            throw conflict("formal open-discovery admission is required");
        }
        if (admission.pilotPlanVersion() != GENERIC_AUTHORITY_VERSION) {
            throw conflict("generic investigation authority version changed");
        }
        GuanceEvidenceAcceptanceService.AcceptedBinding authority =
                guanceAcceptance.requireAcceptedBindingAuthority(
                workspaceId, incident.system(), incident.service());
        GuanceEvidenceAcceptance current = authority.acceptance();
        requireExactAcceptance(incident, current);
        if (!admission.guanceAcceptanceId().equals(current.acceptanceId())
                || !admission.guanceBindingFingerprint()
                        .equals(current.bindingFingerprint())) {
            throw conflict(
                    "Guance owner acceptance changed during formal open discovery");
        }
        if (!admission.plan().equals(
                requireFormalPlan(authority.readOnlySignalKinds()))) {
            throw conflict(
                    "accepted read-only capabilities changed during formal open discovery");
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
                || !(same(incident.service(), accepted.service())
                        || SystemObservabilityScopePolicy.isSystemService(
                                accepted.service()))) {
            throw conflict(
                    "Guance owner acceptance belongs to a different system");
        }
    }

    private FormalOpenDiscoveryPlan requireFormalPlan(
            java.util.Set<String> acceptedSignalKinds) {
        try {
            return FormalOpenDiscoveryPlan.fromAcceptedCapabilities(
                    acceptedSignalKinds);
        } catch (IllegalArgumentException missingCapabilities) {
            throw conflict(
                    "当前系统/服务尚未验收通用调查所需的只读能力；"
                            + missingCapabilities.getMessage());
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
