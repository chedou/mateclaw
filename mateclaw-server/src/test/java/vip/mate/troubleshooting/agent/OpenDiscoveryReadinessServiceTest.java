package vip.mate.troubleshooting.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.agent.AgentService;
import vip.mate.agent.binding.service.AgentBindingService;
import vip.mate.agent.model.AgentEntity;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenDiscoveryReadinessServiceTest {

    @Mock private AgentService agentService;
    @Mock private AgentBindingService bindingService;

    private TroubleshootingAgentProperties properties;
    private OpenDiscoveryReadinessService readiness;

    @BeforeEach
    void setUp() {
        properties = new TroubleshootingAgentProperties();
        properties.setEnabled(false);
        properties.setAgentId(0);
        properties.setMaxIterations(6);
        properties.setMaxEvidenceRequests(6);
        properties.setMaxPromptChars(8_192);
        properties.setTriageTimeout(Duration.ofSeconds(20));

        TroubleshootingAgentProperties.ScenarioEvidencePlan csdp =
                new TroubleshootingAgentProperties.ScenarioEvidencePlan();
        csdp.setEnabled(true);
        csdp.setSystem("CSDP");
        csdp.setSearchTerm("message_send_failed");
        csdp.setWindow("-15m");
        csdp.setWorkspaceIds(List.of(1L));
        csdp.setPermittedPlatforms(List.of("recorded-replay"));

        TroubleshootingAgentProperties.ScenarioEvidencePlan itgw =
                new TroubleshootingAgentProperties.ScenarioEvidencePlan();
        itgw.setEnabled(true);
        itgw.setSystem("ITGW");
        itgw.setSearchTerm("itgw_access_failed");
        itgw.setWindow("-15m");
        itgw.setWorkspaceIds(List.of(1L));
        itgw.setPermittedPlatforms(List.of("recorded-replay"));

        properties.setApprovedScenarioPlans(Map.of(
                "message_send_failed", csdp,
                "itgw_access_failed", itgw));

        OpenDiscoveryAgentGate gate = new OpenDiscoveryAgentGate(
                properties, agentService, bindingService);
        ApprovedEvidenceSpineCatalog catalog = new ApprovedEvidenceSpineCatalog(properties);
        readiness = new OpenDiscoveryReadinessService(properties, gate, catalog);
    }

    @Test
    void reportsDisabledWithNextActionWhenSwitchIsOff() {
        OpenDiscoveryReadiness view = readiness.inspect(1L, "CSDP");

        assertThat(view.status()).isEqualTo(OpenDiscoveryReadiness.Status.DISABLED);
        assertThat(view.agentEnabled()).isFalse();
        assertThat(view.configuredPlanCount()).isEqualTo(2);
        assertThat(view.visiblePlanCount()).isEqualTo(1);
        assertThat(view.nextAction()).contains("agent.enabled");
        assertThat(view.blockers()).anyMatch(blocker -> blocker.contains("开关未打开"));
    }

    @Test
    void marksRehearsalReadyWhenAgentIsConfiguredButOnlyReplayIsAllowed() {
        properties.setEnabled(true);
        properties.setAgentId(42L);
        AgentEntity agent = safeAgent(1L);
        when(agentService.getAgent(42L)).thenReturn(agent);
        when(bindingService.getBoundToolNames(42L))
                .thenReturn(Set.of("TroubleshootingEvidenceTool"));

        OpenDiscoveryReadiness view = readiness.inspect(1L, "CSDP");

        assertThat(view.status()).isEqualTo(OpenDiscoveryReadiness.Status.READY_FOR_REHEARSAL);
        assertThat(view.agentReady()).isTrue();
        assertThat(view.trueSourcePermitted()).isFalse();
        assertThat(view.nextAction()).contains("recorded-replay");
    }

    @Test
    void marksBoundedFallbackReadyWhenGuanceIsMergedIntoPlans() {
        properties.setEnabled(true);
        properties.setAgentId(42L);
        properties.setExtraPermittedPlatforms(List.of("guance"));
        AgentEntity agent = safeAgent(1L);
        when(agentService.getAgent(42L)).thenReturn(agent);
        when(bindingService.getBoundToolNames(42L))
                .thenReturn(Set.of("TroubleshootingEvidenceTool"));

        OpenDiscoveryReadiness view = readiness.inspect(1L, "ITGW");

        assertThat(view.status()).isEqualTo(
                OpenDiscoveryReadiness.Status.READY_FOR_BOUNDED_FALLBACK);
        assertThat(view.trueSourcePermitted()).isTrue();
        assertThat(view.visiblePlanCount()).isEqualTo(1);
        assertThat(view.plans())
                .filteredOn(OpenDiscoveryReadiness.PlanSummary::visibleForRequestedSystem)
                .extracting(OpenDiscoveryReadiness.PlanSummary::scenarioKey)
                .containsExactly("itgw_access_failed");
    }

    @Test
    void marksGenericFormalRuntimeReadyWithoutAnAgentOrScenarioPlan() {
        properties.setBoundedInvestigationEnabled(true);
        properties.setBoundedInvestigationPermittedPlatforms(List.of("guance"));
        properties.setApprovedScenarioPlans(Map.of());

        OpenDiscoveryReadiness view = readiness.inspect(1L, "CSDP");

        assertThat(view.status()).isEqualTo(
                OpenDiscoveryReadiness.Status.READY_FOR_BOUNDED_FALLBACK);
        assertThat(view.agentReady()).isFalse();
        assertThat(view.trueSourcePermitted()).isTrue();
        assertThat(view.blockers()).isEmpty();
        assertThat(view.nextAction())
                .contains("试点范围")
                .contains("owner 验收")
                .doesNotContain("Agent");
    }

    private AgentEntity safeAgent(long workspaceId) {
        AgentEntity agent = new AgentEntity();
        agent.setId(42L);
        agent.setEnabled(true);
        agent.setWorkspaceId(workspaceId);
        agent.setAgentType("react");
        agent.setModelName("test-model");
        agent.setSkillsDisabled(true);
        agent.setWikiDisabled(true);
        agent.setToolsDisabled(false);
        agent.setMaxIterations(4);
        return agent;
    }
}
