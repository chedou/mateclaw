package vip.mate.troubleshooting.deployment;

import org.springframework.stereotype.Service;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.InvestigationMode;
import vip.mate.troubleshooting.model.PlaybookVersionRef;
import vip.mate.troubleshooting.model.ScenarioSelector;
import vip.mate.troubleshooting.service.TroubleshootingPlaybookVersionService;
import vip.mate.troubleshooting.synthesis.ApprovedPlaybookVersion;

import java.util.Optional;

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
        return probePlaybook(workspaceId, diagnosis).isPresent();
    }

    /**
     * The exact frozen Playbook this Diagnosis is being probed against, when
     * there is one.
     *
     * <p>{@link #requiresProbe} is this same lookup with the answer thrown
     * away. Callers that need to record the probe's result as evidence need the
     * Playbook itself — its evidence request, criteria and rules — and
     * re-resolving it separately would be a second implementation of "which
     * Playbook is in force", free to disagree with the first.</p>
     */
    public Optional<ApprovedPlaybookVersion> probePlaybook(
            long workspaceId,
            Diagnosis diagnosis) {
        if (diagnosis == null
                || diagnosis.investigationMode() != InvestigationMode.SCENARIO_PLAYBOOK) {
            return Optional.empty();
        }
        PlaybookVersionRef frozenRef = diagnosis.sourcePlaybookVersionRef();
        if (frozenRef == null) {
            return Optional.empty();
        }
        String expectedSelector = selectorFor(diagnosis.incident().system());
        if (!expectedSelector.equals(diagnosis.sopKey())) {
            return Optional.empty();
        }
        return playbookVersions.findByRef(workspaceId, frozenRef)
                .filter(version -> supportsRequiredProbe(version, expectedSelector));
    }

    /** The required probe request the scenario Playbook authored, if it has one. */
    public Optional<EvidenceRequest> requiredProbeRequest(ApprovedPlaybookVersion version) {
        if (version == null) {
            return Optional.empty();
        }
        return version.playbook().evidenceRequests().stream()
                .filter(DeploymentTopologyScenarioPolicy::isProbeRequest)
                .findFirst();
    }

    public String selectorFor(String system) {
        return new ScenarioSelector(system, SCENARIO_KEY).routingKey();
    }

    public boolean supportsRequiredProbe(
            ApprovedPlaybookVersion version,
            String expectedSelector) {
        if (version == null
                || !expectedSelector.equals(version.selectorKey())
                || !expectedSelector.equals(version.playbook().routingKey())) {
            return false;
        }
        return version.playbook().evidenceRequests().stream()
                .anyMatch(DeploymentTopologyScenarioPolicy::isProbeRequest);
    }

    private static boolean isProbeRequest(EvidenceRequest request) {
        return request.required()
                && REQUIRED_SIGNAL_KIND.equals(request.signalKind())
                && TOOL_KEY.equals(request.target().get("toolKey"))
                && ASSET_TYPE.equals(request.target().get("assetType"));
    }
}
