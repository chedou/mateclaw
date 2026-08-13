package vip.mate.troubleshooting.projection;

import org.springframework.stereotype.Service;
import vip.mate.troubleshooting.agent.ApprovedEvidenceSpineCatalog;
import vip.mate.troubleshooting.evidence.EvidenceProperties;
import vip.mate.troubleshooting.evidence.EvidenceRouteService;
import vip.mate.troubleshooting.evidence.WorkspaceObservabilityAssets;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.service.TroubleshootingSopPersistenceService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reports which onboarding layers an incident's system is still missing.
 *
 * <p>Exists because "insufficient evidence" is the wrong thing to tell a reporter
 * whose system was never onboarded. Nothing was collected, and nothing could have
 * been: no Playbook selects it, no route names a source, no asset authorizes one.
 * Asking that reporter for more logs sends them after evidence that no configured
 * path would have read anyway.
 *
 * <p>Read at projection time rather than frozen onto the Diagnosis, so the answer
 * tracks the configuration as it stands now — which is what someone deciding what
 * to configure next needs.
 */
@Service
public class SystemOnboardingGapService {

    /**
     * Mirrors the strictest scope charset enforced by the asset and route
     * registries. Kept as an independent copy on purpose: this is a read-only
     * report, and it should not be able to widen what those registries accept.
     */
    private static final Pattern USABLE_SYSTEM_SCOPE =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private static final String OWNER_ADMIN = "工作区管理员";

    private final TroubleshootingSopPersistenceService playbooks;
    private final ApprovedEvidenceSpineCatalog approvedPlans;
    private final EvidenceRouteService evidenceRoutes;
    private final EvidenceProperties evidenceProperties;
    private final WorkspaceObservabilityAssets observabilityAssets;

    public SystemOnboardingGapService(
            TroubleshootingSopPersistenceService playbooks,
            ApprovedEvidenceSpineCatalog approvedPlans,
            EvidenceRouteService evidenceRoutes,
            EvidenceProperties evidenceProperties,
            WorkspaceObservabilityAssets observabilityAssets) {
        this.playbooks = playbooks;
        this.approvedPlans = approvedPlans;
        this.evidenceRoutes = evidenceRoutes;
        this.evidenceProperties = evidenceProperties;
        this.observabilityAssets = observabilityAssets;
    }

    public List<SystemOnboardingGap> inspect(long workspaceId, IncidentContext incident) {
        if (workspaceId <= 0 || incident == null) {
            return List.of();
        }
        String system = trimmed(incident.system());
        if (system.isEmpty()) {
            return List.of();
        }
        List<SystemOnboardingGap> gaps = new ArrayList<>();

        boolean usableScope = USABLE_SYSTEM_SCOPE.matcher(system).matches();
        if (!usableScope) {
            gaps.add(new SystemOnboardingGap(
                    SystemOnboardingGapKind.SYSTEM_IDENTITY,
                    "报障里的系统名不能直接作为接入标识",
                    "「" + system + "」含中文或责任人后缀，取证路由与观测资产只接受"
                            + "字母数字加 . _ - 的稳定系统编码。需要先为这个业务系统"
                            + "登记一个固定编码（例如 ICARE），再用该编码完成下面各层配置。",
                    OWNER_ADMIN));
        }

        if (!hasApprovedPlaybook(workspaceId, system)) {
            gaps.add(new SystemOnboardingGap(
                    SystemOnboardingGapKind.PLAYBOOK,
                    "这个系统还没有审核通过的排障方案",
                    "确定性判断只走已审核 Playbook。当前该系统一条都没有，"
                            + "所以没有任何判据可以复算。",
                    OWNER_ADMIN));
        }

        if (approvedPlans.visibleScenarioKeys(workspaceId, incident).isEmpty()) {
            gaps.add(new SystemOnboardingGap(
                    SystemOnboardingGapKind.OPEN_DISCOVERY_PLAN,
                    "开放调查没有为这个系统开放任何场景",
                    "未命中已审核方案时会转开放调查，但可选场景由服务端固定。"
                            + "该系统没有可见场景，取证请求会被直接拒绝，模型只能弃权。",
                    OWNER_ADMIN));
        }

        if (!hasEvidenceRoute(workspaceId, system)) {
            gaps.add(new SystemOnboardingGap(
                    SystemOnboardingGapKind.EVIDENCE_ROUTE,
                    "这个系统没有声明取证路由",
                    "取证是 fail-closed 的：没有显式声明「哪个信号读哪个源」，"
                            + "任何取证都会以 router:unconfigured 落空，不存在默认源。",
                    OWNER_ADMIN));
        }

        String service = trimmed(incident.service());
        if (usableScope && !service.isEmpty()
                && observabilityAssets.find(workspaceId, system, service).isEmpty()) {
            gaps.add(new SystemOnboardingGap(
                    SystemOnboardingGapKind.OBSERVABILITY_ASSET,
                    "这个服务没有授权到观测资产",
                    "服务「" + service + "」缺少工作区级观测资产授权，"
                            + "具体源即使已启用也不会替它发起查询。",
                    OWNER_ADMIN));
        }

        return List.copyOf(gaps);
    }

    private boolean hasApprovedPlaybook(long workspaceId, String system) {
        try {
            return !playbooks.list(workspaceId, "approved", system, 1).isEmpty();
        } catch (RuntimeException unavailable) {
            // A read-only report must not turn a registry hiccup into a claim
            // that the system is unonboarded.
            return true;
        }
    }

    private boolean hasEvidenceRoute(long workspaceId, String system) {
        try {
            if (!evidenceRoutes.list(workspaceId, system).isEmpty()) {
                return true;
            }
        } catch (RuntimeException unavailable) {
            return true;
        }
        Map<String, Map<String, List<String>>> routes = evidenceProperties.getRoutes();
        if (routes == null || routes.isEmpty()) {
            return false;
        }
        String normalized = system.toLowerCase(Locale.ROOT);
        return routes.entrySet().stream()
                .filter(entry -> entry.getKey() != null
                        && entry.getKey().trim().toLowerCase(Locale.ROOT).equals(normalized))
                .anyMatch(entry -> entry.getValue() != null && !entry.getValue().isEmpty());
    }

    private String trimmed(String value) {
        return value == null ? "" : value.trim();
    }
}
