package vip.mate.troubleshooting.agent;

import org.springframework.stereotype.Service;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Builds the secret-free OPEN_DISCOVERY readiness projection. */
@Service
public final class OpenDiscoveryReadinessService {

    private final TroubleshootingAgentProperties properties;
    private final OpenDiscoveryAgentGate agentGate;
    private final ApprovedEvidenceSpineCatalog catalog;

    public OpenDiscoveryReadinessService(
            TroubleshootingAgentProperties properties,
            OpenDiscoveryAgentGate agentGate,
            ApprovedEvidenceSpineCatalog catalog) {
        this.properties = properties;
        this.agentGate = agentGate;
        this.catalog = catalog;
    }

    public OpenDiscoveryReadiness inspect(long workspaceId, String system) {
        OpenDiscoveryAgentGate.Inspection agent = agentGate.inspect(workspaceId);
        List<String> blockers = new ArrayList<>(agent.blockers());
        List<OpenDiscoveryReadiness.PlanSummary> plans = listPlans(workspaceId, system);
        long visible = plans.stream().filter(OpenDiscoveryReadiness.PlanSummary::visibleForRequestedSystem).count();
        boolean trueSourcePermitted = plans.stream().anyMatch(
                OpenDiscoveryReadiness.PlanSummary::includesTrueSource);

        if (plans.isEmpty()) {
            blockers.add("尚未配置任何已审核开放调查计划（approved-scenario-plans）");
        } else if (system != null && !system.isBlank() && visible == 0) {
            blockers.add("当前系统没有可见的开放调查计划；模型只能从本系统已审核计划中选一个 key");
        }

        OpenDiscoveryReadiness.Status status;
        String nextAction;
        if (agent.status() == OpenDiscoveryAgentGate.Status.DISABLED) {
            status = OpenDiscoveryReadiness.Status.DISABLED;
            nextAction = "按 agent-miss-path-runbook 创建专用数字员工后，打开 mateclaw.troubleshooting.agent.enabled";
        } else if (!blockers.isEmpty() || agent.status() != OpenDiscoveryAgentGate.Status.AGENT_READY) {
            status = OpenDiscoveryReadiness.Status.BLOCKED;
            nextAction = blockers.isEmpty()
                    ? "检查专用数字员工、工具绑定与预算配置"
                    : blockers.get(0);
        } else if (!trueSourcePermitted) {
            status = OpenDiscoveryReadiness.Status.READY_FOR_REHEARSAL;
            nextAction = "当前计划仅允许 recorded-replay，可做脱敏演练；接真源后把 guance 加入 permitted-platforms 或 EXTRA_PLATFORMS";
        } else {
            status = OpenDiscoveryReadiness.Status.READY_FOR_BOUNDED_FALLBACK;
            nextAction = "未知告警可走受限开放调查：结论最高 MEDIUM，证据不足会弃权转人工";
        }

        String agentName = agent.agent() == null ? "" : safeName(agent.agent().getName());
        return new OpenDiscoveryReadiness(
                status,
                properties.isEnabled(),
                agentGate.resolveAgentId(workspaceId),
                agentName,
                agentGate.bindingSource(workspaceId).name(),
                agent.status() == OpenDiscoveryAgentGate.Status.AGENT_READY,
                plans.size(),
                (int) visible,
                trueSourcePermitted,
                plans,
                blockers,
                nextAction);
    }

    private List<OpenDiscoveryReadiness.PlanSummary> listPlans(long workspaceId, String system) {
        Map<String, TroubleshootingAgentProperties.ScenarioEvidencePlan> configured =
                properties.getApprovedScenarioPlans();
        if (configured == null || configured.isEmpty()) {
            return List.of();
        }
        String requested = normalize(system);
        List<OpenDiscoveryReadiness.PlanSummary> plans = new ArrayList<>();
        for (Map.Entry<String, TroubleshootingAgentProperties.ScenarioEvidencePlan> entry
                : configured.entrySet()) {
            TroubleshootingAgentProperties.ScenarioEvidencePlan plan = entry.getValue();
            if (plan == null) {
                continue;
            }
            List<String> platforms = effectivePlatforms(plan);
            boolean visible = plan.isEnabled()
                    && plan.getWorkspaceIds() != null
                    && plan.getWorkspaceIds().contains(workspaceId)
                    && (requested.isEmpty()
                    || normalize(plan.getSystem()).equals(requested));
            // Prefer catalog visibility for the *requested* system so the count
            // matches what triage would actually expose to the Agent.
            if (!requested.isEmpty()) {
                IncidentContext probe = probeIncident(system);
                visible = catalog.visibleScenarioKeys(workspaceId, probe).contains(entry.getKey());
            }
            plans.add(new OpenDiscoveryReadiness.PlanSummary(
                    entry.getKey(),
                    plan.getSystem() == null ? "" : plan.getSystem().trim(),
                    plan.isEnabled(),
                    visible,
                    platforms,
                    platforms.stream().anyMatch(platform ->
                            "guance".equals(normalize(platform)))));
        }
        return List.copyOf(plans);
    }

    private List<String> effectivePlatforms(
            TroubleshootingAgentProperties.ScenarioEvidencePlan plan) {
        Set<String> platforms = new LinkedHashSet<>();
        if (plan.getPermittedPlatforms() != null) {
            for (String platform : plan.getPermittedPlatforms()) {
                if (platform != null && !platform.isBlank()) {
                    platforms.add(platform.trim());
                }
            }
        }
        if (properties.getExtraPermittedPlatforms() != null) {
            for (String platform : properties.getExtraPermittedPlatforms()) {
                if (platform != null && !platform.isBlank()) {
                    platforms.add(platform.trim());
                }
            }
        }
        return List.copyOf(platforms);
    }

    private IncidentContext probeIncident(String system) {
        return new IncidentContext(
                "open-discovery-readiness",
                system == null || system.isBlank() ? "UNKNOWN" : system,
                "readiness-probe",
                null,
                "open discovery readiness probe",
                "P3",
                "readiness",
                null,
                null,
                null,
                "readiness",
                IncidentCompleteness.SYMPTOM,
                null);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String safeName(String value) {
        return value == null ? "" : value.trim();
    }
}
