package vip.mate.troubleshooting.deployment;

import org.springframework.stereotype.Service;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.InvestigationMode;
import vip.mate.troubleshooting.model.PlaybookVersionRef;
import vip.mate.troubleshooting.model.ScenarioSelector;
import vip.mate.troubleshooting.service.TroubleshootingPlaybookVersionService;
import vip.mate.troubleshooting.synthesis.ApprovedPlaybookVersion;

/** Resolves whether one Diagnosis's frozen Scenario Playbook requires topology evidence. */
@Service
public class DeploymentTopologyScenarioPolicy {

    public static final String SCENARIO_KEY = "deployment_topology_probe";
    public static final String TOOL_KEY = "topology_synthetic_probe";
    public static final String ASSET_TYPE = "deployment_topology";
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
        String expectedSelector = selectorFor(diagnosis.incident().system());
        if (!expectedSelector.equals(diagnosis.sopKey())) {
            return false;
        }
        ApprovedPlaybookVersion version = playbookVersions.findByRef(workspaceId, frozenRef)
                .orElse(null);
        return supportsRequiredProbe(version, expectedSelector);
    }

    public String selectorFor(String system) {
        return new ScenarioSelector(system, SCENARIO_KEY).routingKey();
    }

    boolean supportsRequiredProbe(
            ApprovedPlaybookVersion version,
            String expectedSelector) {
        if (version == null
                || !expectedSelector.equals(version.selectorKey())
                || !expectedSelector.equals(version.playbook().routingKey())) {
            return false;
        }
        return version.playbook().evidenceRequests().stream()
                .anyMatch(request -> request.required()
                        && REQUIRED_SIGNAL_KIND.equals(request.signalKind())
                        && TOOL_KEY.equals(request.target().get("toolKey"))
                        && ASSET_TYPE.equals(request.target().get("assetType")));
    }
}
