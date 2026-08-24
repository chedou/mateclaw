package vip.mate.troubleshooting.agent;

import org.springframework.stereotype.Service;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.service.FormalOpenDiscoveryAdmissionService;

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
    private final FormalOpenDiscoveryAdmissionService formalAdmission;

    public OpenDiscoveryReadinessService(
            TroubleshootingAgentProperties properties,
            OpenDiscoveryAgentGate agentGate,
            ApprovedEvidenceSpineCatalog catalog,
            FormalOpenDiscoveryAdmissionService formalAdmission) {
        this.properties = properties;
        this.agentGate = agentGate;
        this.catalog = catalog;
        this.formalAdmission = formalAdmission;
    }

    public OpenDiscoveryReadiness inspect(long workspaceId, String system) {
        return inspect(workspaceId, system, null);
    }

    public OpenDiscoveryReadiness inspect(
            long workspaceId,
            String system,
            String service) {
        OpenDiscoveryAgentGate.Inspection agent = agentGate.inspect(workspaceId);
        boolean genericBoundedRuntimeConfigured = genericBoundedRuntimeReady();
        List<String> blockers = new ArrayList<>();
        if (!genericBoundedRuntimeConfigured && !agent.blockers().isEmpty()) {
            blockers.add("通用只读调查的运行开关、执行器或安全预算尚未配置完成");
        }
        List<OpenDiscoveryReadiness.PlanSummary> plans = listPlans(workspaceId, system);
        long visible = plans.stream().filter(OpenDiscoveryReadiness.PlanSummary::visibleForRequestedSystem).count();
        boolean genericBoundedRuntimeReady = genericBoundedRuntimeConfigured
                && exactAssetAccepted(workspaceId, system, service, blockers);
        boolean trueSourcePermitted = genericBoundedRuntimeReady || plans.stream().anyMatch(
                OpenDiscoveryReadiness.PlanSummary::includesTrueSource);

        if (!genericBoundedRuntimeConfigured && plans.isEmpty()) {
            blockers.add("尚未配置可用于当前系统的只读调查方法");
        } else if (!genericBoundedRuntimeConfigured
                && system != null && !system.isBlank() && visible == 0) {
            blockers.add("当前系统没有可用的只读调查方法");
        }

        OpenDiscoveryReadiness.Status status;
        String nextAction;
        if (genericBoundedRuntimeReady) {
            status = OpenDiscoveryReadiness.Status.READY_FOR_BOUNDED_FALLBACK;
            blockers = List.of();
            nextAction = "当前系统和服务已通过精确资产与只读能力验收，"
                    + "可开始正式只读调查";
        } else if (genericBoundedRuntimeConfigured) {
            status = OpenDiscoveryReadiness.Status.BLOCKED;
            nextAction = blockers.getFirst();
        } else if (agent.status() == OpenDiscoveryAgentGate.Status.DISABLED) {
            status = OpenDiscoveryReadiness.Status.DISABLED;
            nextAction = "请管理员启用通用只读调查，并完成数据源接入与权限配置";
        } else if (!blockers.isEmpty() || agent.status() != OpenDiscoveryAgentGate.Status.AGENT_READY) {
            status = OpenDiscoveryReadiness.Status.BLOCKED;
            nextAction = blockers.isEmpty()
                    ? "请管理员检查只读调查开关、数据接入、权限和预算配置"
                    : blockers.get(0);
        } else if (!trueSourcePermitted) {
            status = OpenDiscoveryReadiness.Status.READY_FOR_REHEARSAL;
            nextAction = "当前只可使用脱敏演练数据；"
                    + "接通真实只读数据并完成验收后可正式调查";
        } else {
            status = OpenDiscoveryReadiness.Status.READY_FOR_BOUNDED_FALLBACK;
            nextAction = "未知告警可开始受限只读调查；"
                    + "结论最高只会标记为候选方向，证据不足会停止并转人工";
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

    private boolean exactAssetAccepted(
            long workspaceId,
            String system,
            String service,
            List<String> blockers) {
        if (system == null || system.isBlank()
                || service == null || service.isBlank()) {
            blockers.add("请先填写精确的系统和服务，再检查是否可开始正式调查");
            return false;
        }
        try {
            formalAdmission.admit(
                    workspaceId,
                    probeIncident(system, service));
            return true;
        } catch (RuntimeException unavailable) {
            blockers.add("当前系统和服务的精确资产或已验收只读能力不完整，"
                    + "请管理员完成接入与连通验证");
            return false;
        }
    }

    private boolean genericBoundedRuntimeReady() {
        if (!properties.isBoundedInvestigationEnabled()
                || properties.getBoundedInvestigationMaxIterations() <= 0
                || properties.getBoundedInvestigationMaxToolCalls() <= 0
                || properties.getMaxEvidenceRequests() <= 0
                || properties.getBoundedInvestigationTimeout() == null
                || properties.getBoundedInvestigationTimeout().isZero()
                || properties.getBoundedInvestigationTimeout().isNegative()) {
            return false;
        }
        return properties.getBoundedInvestigationPermittedPlatforms() != null
                && properties.getBoundedInvestigationPermittedPlatforms().stream()
                .map(OpenDiscoveryReadinessService::normalize)
                .anyMatch("guance"::equals);
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
        return probeIncident(system, "readiness-probe");
    }

    private IncidentContext probeIncident(String system, String service) {
        return new IncidentContext(
                "open-discovery-readiness",
                system == null || system.isBlank() ? "UNKNOWN" : system,
                service == null || service.isBlank() ? "readiness-probe" : service,
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
