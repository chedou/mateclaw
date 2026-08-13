package vip.mate.troubleshooting.projection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import vip.mate.troubleshooting.agent.ApprovedEvidenceSpineCatalog;
import vip.mate.troubleshooting.evidence.EvidenceProperties;
import vip.mate.troubleshooting.evidence.EvidenceRouteService;
import vip.mate.troubleshooting.evidence.WorkspaceObservabilityAsset;
import vip.mate.troubleshooting.evidence.WorkspaceObservabilityAssets;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.model.IncidentImpact;
import vip.mate.troubleshooting.service.SopSummary;
import vip.mate.troubleshooting.service.TroubleshootingSopPersistenceService;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SystemOnboardingGapServiceTest {

    private static final long WORKSPACE_ID = 1L;

    @Mock
    private TroubleshootingSopPersistenceService playbooks;

    @Mock
    private ApprovedEvidenceSpineCatalog approvedPlans;

    @Mock
    private EvidenceRouteService evidenceRoutes;

    @Mock
    private WorkspaceObservabilityAssets observabilityAssets;

    private SystemOnboardingGapService service(EvidenceProperties properties) {
        return new SystemOnboardingGapService(
                playbooks, approvedPlans, evidenceRoutes, properties, observabilityAssets);
    }

    private static EvidenceProperties csdpOnlyRoutes() {
        EvidenceProperties properties = new EvidenceProperties();
        properties.setRoutes(Map.of("CSDP", Map.of("log_search", List.of("guance"))));
        return properties;
    }

    private static IncidentContext incident(String system, String service) {
        return new IncidentContext(
                "incident-1",
                system,
                service,
                null,
                "sf-icare-app-虚机-拨测检测异常",
                "P2",
                IncidentImpact.unknown("待确认"),
                null,
                Instant.parse("2026-08-12T08:36:00Z"),
                null,
                "channel:wecom",
                IncidentCompleteness.STRUCTURED,
                "sf-icare-app-虚机-拨测检测异常");
    }

    @Test
    @DisplayName("未接入系统逐层报出缺口，并把中文系统名判为不可用作接入标识")
    void unonboardedSystemReportsEveryMissingLayerIncludingItsUnusableIdentity() {
        when(playbooks.list(anyLong(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of());
        when(approvedPlans.visibleScenarioKeys(anyLong(), any())).thenReturn(List.of());
        when(evidenceRoutes.list(anyLong(), anyString())).thenReturn(List.of());
        when(observabilityAssets.find(anyLong(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        List<SystemOnboardingGap> gaps = service(csdpOnlyRoutes())
                .inspect(WORKSPACE_ID, incident("深信服新ICare系统-邹汶达", "sf-icare-app"));

        assertThat(gaps).extracting(SystemOnboardingGap::kind).containsExactly(
                SystemOnboardingGapKind.SYSTEM_IDENTITY,
                SystemOnboardingGapKind.PLAYBOOK,
                SystemOnboardingGapKind.OPEN_DISCOVERY_PLAN,
                SystemOnboardingGapKind.EVIDENCE_ROUTE);
        assertThat(gaps.get(0).detail()).contains("深信服新ICare系统-邹汶达");
        // The asset layer is keyed by the system code, so it cannot be reported
        // as a separate gap while the code itself is unusable.
        assertThat(gaps).extracting(SystemOnboardingGap::kind)
                .doesNotContain(SystemOnboardingGapKind.OBSERVABILITY_ASSET);
    }

    @Test
    @DisplayName("部署 YAML 已声明该系统路由时不再报路由缺口")
    void deploymentDeclaredRouteClosesTheRouteGap() {
        when(playbooks.list(anyLong(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of(mock(SopSummary.class)));
        when(approvedPlans.visibleScenarioKeys(anyLong(), any()))
                .thenReturn(List.of("message_send_failed"));
        when(evidenceRoutes.list(anyLong(), anyString())).thenReturn(List.of());
        when(observabilityAssets.find(anyLong(), anyString(), anyString()))
                .thenReturn(Optional.of(mock(WorkspaceObservabilityAsset.class)));

        List<SystemOnboardingGap> gaps = service(csdpOnlyRoutes())
                .inspect(WORKSPACE_ID, incident("CSDP", "csdp-session-service"));

        assertThat(gaps).isEmpty();
    }

    @Test
    @DisplayName("系统编码可用但服务没有授权资产时，只报资产这一层")
    void authorizedSystemStillReportsTheMissingServiceAsset() {
        when(playbooks.list(anyLong(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of(mock(SopSummary.class)));
        when(approvedPlans.visibleScenarioKeys(anyLong(), any()))
                .thenReturn(List.of("message_send_failed"));
        when(evidenceRoutes.list(anyLong(), anyString())).thenReturn(List.of());
        when(observabilityAssets.find(anyLong(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        List<SystemOnboardingGap> gaps = service(csdpOnlyRoutes())
                .inspect(WORKSPACE_ID, incident("CSDP", "csdp-session-service"));

        assertThat(gaps).extracting(SystemOnboardingGap::kind)
                .containsExactly(SystemOnboardingGapKind.OBSERVABILITY_ASSET);
    }

    @Test
    @DisplayName("注册表读取失败不得被当成「未接入」")
    void registryFailureNeverMasqueradesAsAnUnonboardedSystem() {
        when(playbooks.list(anyLong(), anyString(), anyString(), anyInt()))
                .thenThrow(new IllegalStateException("registry unavailable"));
        when(approvedPlans.visibleScenarioKeys(anyLong(), any()))
                .thenReturn(List.of("message_send_failed"));
        when(evidenceRoutes.list(anyLong(), anyString()))
                .thenThrow(new IllegalStateException("registry unavailable"));
        when(observabilityAssets.find(anyLong(), anyString(), anyString()))
                .thenReturn(Optional.of(mock(WorkspaceObservabilityAsset.class)));

        List<SystemOnboardingGap> gaps = service(new EvidenceProperties())
                .inspect(WORKSPACE_ID, incident("CSDP", "csdp-session-service"));

        assertThat(gaps).isEmpty();
    }
}
