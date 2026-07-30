package vip.mate.troubleshooting.deployment;

import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.model.Confidence;
import vip.mate.troubleshooting.model.ConclusionType;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.DiagnosisStatus;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.model.InvestigationMode;
import vip.mate.troubleshooting.model.NorthStarTimings;
import vip.mate.troubleshooting.model.PlaybookVersionRef;
import vip.mate.troubleshooting.model.RouteAuthority;
import vip.mate.troubleshooting.model.RouteMode;
import vip.mate.troubleshooting.model.SopEntry;
import vip.mate.troubleshooting.service.TroubleshootingPlaybookVersionService;
import vip.mate.troubleshooting.synthesis.ApprovedPlaybookVersion;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeploymentTopologyScenarioPolicyTest {

    private static final long WORKSPACE_ID = 7L;
    private static final Instant NOW = Instant.parse("2026-07-31T00:00:00Z");
    private static final PlaybookVersionRef PLAYBOOK_REF =
            new PlaybookVersionRef("playbook-topology", 3);

    private final TroubleshootingPlaybookVersionService versions =
            mock(TroubleshootingPlaybookVersionService.class);
    private final DeploymentTopologyScenarioPolicy policy =
            new DeploymentTopologyScenarioPolicy(versions);

    @Test
    void matchesARequiredTopologyToolFromTheExactFrozenScenarioPlaybook() {
        Diagnosis diagnosis = diagnosis(PLAYBOOK_REF, RouteAuthority.MODEL_PROPOSED);
        when(versions.findByRef(WORKSPACE_ID, PLAYBOOK_REF))
                .thenReturn(Optional.of(version(topologyPlaybook(true))));

        assertThat(policy.requiresProbe(WORKSPACE_ID, diagnosis)).isTrue();
    }

    @Test
    void refusesHistoricalDiagnosesWithoutAnExactFrozenPlaybookVersion() {
        Diagnosis diagnosis = mock(Diagnosis.class);
        when(diagnosis.investigationMode()).thenReturn(InvestigationMode.SCENARIO_PLAYBOOK);
        when(diagnosis.sourcePlaybookVersionRef()).thenReturn(null);

        assertThat(policy.requiresProbe(WORKSPACE_ID, diagnosis)).isFalse();
        verify(versions, never()).findByRef(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void refusesMissingMismatchedOrNonTopologyFrozenPlaybooks() {
        Diagnosis diagnosis = diagnosis(PLAYBOOK_REF, RouteAuthority.RULE_MATCHED);
        when(versions.findByRef(WORKSPACE_ID, PLAYBOOK_REF))
                .thenReturn(Optional.empty());
        assertThat(policy.requiresProbe(WORKSPACE_ID, diagnosis)).isFalse();

        when(versions.findByRef(WORKSPACE_ID, PLAYBOOK_REF))
                .thenReturn(Optional.of(version(topologyPlaybook(false))));
        assertThat(policy.requiresProbe(WORKSPACE_ID, diagnosis)).isFalse();

        when(versions.findByRef(WORKSPACE_ID, PLAYBOOK_REF))
                .thenReturn(Optional.of(version(otherScenarioPlaybook())));
        assertThat(policy.requiresProbe(WORKSPACE_ID, diagnosis)).isFalse();
    }

    private Diagnosis diagnosis(
            PlaybookVersionRef playbookRef,
            RouteAuthority authority) {
        IncidentContext incident = new IncidentContext(
                "incident-1", "CSDP", "csdp-network", null,
                "客户端无法访问", "P1", "网络访问",
                null, NOW, "5m", "manual",
                IncidentCompleteness.STRUCTURED, "network unavailable");
        return Diagnosis.initial(
                "diag-1", "case-1", "run-1", incident,
                RouteMode.DETERMINISTIC,
                InvestigationMode.SCENARIO_PLAYBOOK,
                authority,
                ConclusionType.HYPOTHESIS,
                NorthStarTimings.concluded(NOW.minusSeconds(30), NOW.minusSeconds(20), NOW),
                DiagnosisStatus.READY_FOR_HUMAN,
                "需要部署拓扑拨测", "待取得拓扑证据", Confidence.MEDIUM, false,
                "csdp:scenario:deployment_topology_probe", "部署拓扑拨测",
                playbookRef,
                List.of(), List.of(), List.of(), null,
                true, true, List.of(), List.of());
    }

    private ApprovedPlaybookVersion version(SopEntry playbook) {
        return new ApprovedPlaybookVersion(
                PLAYBOOK_REF.playbookId(), PLAYBOOK_REF.playbookVersion(),
                playbook.routingKey(), "APPROVED", "MANUAL", "manual-topology",
                null, null, "owner", "approved for topology diagnosis", null,
                playbook, NOW.minusSeconds(60), NOW);
    }

    private SopEntry topologyPlaybook(boolean required) {
        return new SopEntry(
                PLAYBOOK_REF.playbookId(), "sop.v1", "CSDP",
                "scenario:deployment_topology_probe", "csdp-network",
                "部署拓扑拨测", "网络路径待核查", "network", "网络组",
                "approved", true,
                List.of(new EvidenceRequest(
                        "EV-TOPOLOGY", "synthetic_probe", "执行部署拓扑拨测",
                        Map.of("assetType", "deployment_topology"), "-15m", required)),
                List.of(), List.of(), List.of());
    }

    private SopEntry otherScenarioPlaybook() {
        return new SopEntry(
                PLAYBOOK_REF.playbookId(), "sop.v1", "CSDP",
                "scenario:slow_api", "csdp-network",
                "慢接口", "下游响应慢", "application", "应用组",
                "approved", true,
                List.of(new EvidenceRequest(
                        "EV-TOPOLOGY", "synthetic_probe", "执行部署拓扑拨测",
                        Map.of("assetType", "deployment_topology"), "-15m", true)),
                List.of(), List.of(), List.of());
    }
}
