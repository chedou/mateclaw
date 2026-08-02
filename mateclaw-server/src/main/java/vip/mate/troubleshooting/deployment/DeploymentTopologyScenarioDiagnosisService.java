package vip.mate.troubleshooting.deployment;

import org.springframework.stereotype.Service;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.service.ScenarioDiagnosisService;
import vip.mate.troubleshooting.service.StoredDiagnosis;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Creates the Diagnosis owner for an explicitly selected deployment-topology scenario.
 *
 * <p>The generic half of this — resolve the scenario selector, row-lock the
 * active approved authority, build an {@code INSUFFICIENT_EVIDENCE} scenario
 * Diagnosis inside the same transaction — now lives in
 * {@link ScenarioDiagnosisService}, because it was never topology-specific and
 * keeping it here made the capability reachable for exactly one scenario. What
 * stays here is the part that genuinely is topology's own: the demand that the
 * authority actually requires the synthetic probe.</p>
 */
@Service
public class DeploymentTopologyScenarioDiagnosisService {

    private final ScenarioDiagnosisService scenarios;
    private final DeploymentTopologyScenarioPolicy policy;

    public DeploymentTopologyScenarioDiagnosisService(
            ScenarioDiagnosisService scenarios,
            DeploymentTopologyScenarioPolicy policy) {
        this.scenarios = Objects.requireNonNull(scenarios, "scenarios");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /** Locks the active authority through the Diagnosis insert, then returns the idempotent owner. */
    public StoredDiagnosis create(
            long workspaceId,
            IncidentContext incident,
            boolean rehearsal,
            String actor,
            Instant reportedAt) {
        return scenarios.create(
                workspaceId,
                incident,
                DeploymentTopologyScenarioPolicy.SCENARIO_KEY,
                rehearsal,
                actor,
                reportedAt,
                List.of("部署拓扑拨测尚未执行；当前 Diagnosis 不输出根因或处置建议。"),
                (authority, selector) -> policy.supportsRequiredProbe(authority, selector)
                        ? null
                        : "the deployment topology scenario Playbook must require "
                                + DeploymentTopologyScenarioPolicy.TOOL_KEY
                                + " evidence for a "
                                + DeploymentTopologyScenarioPolicy.ASSET_TYPE
                                + " asset");
    }
}
