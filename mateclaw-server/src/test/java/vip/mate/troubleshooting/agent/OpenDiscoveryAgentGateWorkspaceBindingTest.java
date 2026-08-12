package vip.mate.troubleshooting.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.agent.AgentService;
import vip.mate.agent.binding.service.AgentBindingService;
import vip.mate.agent.model.AgentEntity;
import vip.mate.troubleshooting.model.TroubleshootingOpenDiscoveryAgentBindingEntity;
import vip.mate.troubleshooting.repository.TroubleshootingOpenDiscoveryAgentBindingMapper;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenDiscoveryAgentGateWorkspaceBindingTest {

    @Mock private AgentService agentService;
    @Mock private AgentBindingService bindingService;
    @Mock private TroubleshootingOpenDiscoveryAgentBindingMapper workspaceBindings;

    @Test
    void workspaceBindingWinsOverProcessConfigAgentId() {
        TroubleshootingAgentProperties properties = new TroubleshootingAgentProperties();
        properties.setEnabled(true);
        properties.setAgentId(99L);
        properties.setMaxIterations(6);
        properties.setMaxEvidenceRequests(6);
        properties.setMaxPromptChars(8_192);
        properties.setTriageTimeout(Duration.ofSeconds(20));

        TroubleshootingOpenDiscoveryAgentBindingEntity row =
                new TroubleshootingOpenDiscoveryAgentBindingEntity();
        row.setWorkspaceId(1L);
        row.setAgentId(42L);
        when(workspaceBindings.findByWorkspace(1L)).thenReturn(row);

        AgentEntity agent = new AgentEntity();
        agent.setId(42L);
        agent.setName("排障员工");
        agent.setEnabled(true);
        agent.setWorkspaceId(1L);
        agent.setAgentType("react");
        agent.setModelName("test-model");
        agent.setSkillsDisabled(true);
        agent.setWikiDisabled(true);
        agent.setToolsDisabled(false);
        agent.setMaxIterations(4);
        when(agentService.getAgent(42L)).thenReturn(agent);
        when(bindingService.getBoundToolNames(42L))
                .thenReturn(Set.of("TroubleshootingEvidenceTool"));

        OpenDiscoveryAgentGate gate = new OpenDiscoveryAgentGate(
                properties, agentService, bindingService, workspaceBindings);

        assertThat(gate.resolveAgentId(1L)).isEqualTo(42L);
        assertThat(gate.bindingSource(1L)).isEqualTo(OpenDiscoveryAgentBindingSource.WORKSPACE);
        assertThat(gate.inspect(1L).status()).isEqualTo(OpenDiscoveryAgentGate.Status.AGENT_READY);
        assertThat(gate.inspect(1L).agent().getName()).isEqualTo("排障员工");
    }
}
