package vip.mate.troubleshooting.deployment;

import org.springframework.stereotype.Service;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.InvestigationMode;
import vip.mate.troubleshooting.model.PlaybookVersionRef;
import vip.mate.troubleshooting.service.TroubleshootingPlaybookVersionService;
import vip.mate.troubleshooting.synthesis.ApprovedPlaybookVersion;

import java.util.Locale;

/** Resolves whether one Diagnosis's frozen Scenario Playbook requires topology evidence. */
@Service
public class DeploymentTopologyScenarioPolicy {

    public static final String SCENARIO_KEY = "deployment_topology_probe";
    private static final String REQUIRED_SIGNAL_KIND = "synthetic_probe";

    private final TroubleshootingPlaybookVersionService playbookVersions;

    public DeploymentTopologyScenarioPolicy(
            TroubleshootingPlaybookVersionService playbookVersions) {
        this.playbookVersions = playbookVersions;
    }

    public boolean requiresProbe(long workspaceId, Diagnosis diagnosis) {
        if (diagnosis == null
                || diagnosis.investigationMode() != InvestigationMode.SCENARIO_PLAYBOOK) {
            return false;
        }
        PlaybookVersionRef frozenRef = diagnosis.sourcePlaybookVersionRef();
        if (frozenRef == null) {
            return false;
        }
        String expectedSelector = diagnosis.incident().system().trim().toLowerCase(Locale.ROOT)
                + ":scenario:" + SCENARIO_KEY;
        if (!expectedSelector.equals(diagnosis.sopKey())) {
            return false;
        }
        ApprovedPlaybookVersion version = playbookVersions.findByRef(workspaceId, frozenRef)
                .orElse(null);
        if (version == null
                || !expectedSelector.equals(version.selectorKey())
                || !expectedSelector.equals(version.playbook().routingKey())) {
            return false;
        }
        return version.playbook().evidenceRequests().stream()
                .anyMatch(request -> request.required()
                        && REQUIRED_SIGNAL_KIND.equals(request.signalKind()));
    }
}
