package vip.mate.troubleshooting.deployment;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.model.Confidence;
import vip.mate.troubleshooting.model.ConclusionType;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.DiagnosisStatus;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.model.InvestigationMode;
import vip.mate.troubleshooting.model.PlaybookVersionRef;
import vip.mate.troubleshooting.model.RouteAuthority;
import vip.mate.troubleshooting.model.RouteMode;
import vip.mate.troubleshooting.model.SopEntry;
import vip.mate.troubleshooting.service.StoredDiagnosis;
import vip.mate.troubleshooting.service.TroubleshootingPersistenceService;
import vip.mate.troubleshooting.service.TroubleshootingPlaybookVersionService;
import vip.mate.troubleshooting.statemachine.DiagnosisStateMachine;
import vip.mate.troubleshooting.synthesis.ApprovedPlaybookVersion;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeploymentTopologyScenarioDiagnosisServiceTest {

    private static final long WORKSPACE_ID = 7L;
    private static final Instant REPORTED_AT = Instant.parse("2026-07-31T01:00:00Z");
    private static final Instant NOW = Instant.parse("2026-07-31T01:00:01Z");
    private static final PlaybookVersionRef PLAYBOOK_REF =
            new PlaybookVersionRef("playbook-topology", 3);

    private final TroubleshootingPlaybookVersionService versions =
            mock(TroubleshootingPlaybookVersionService.class);
    private final TroubleshootingPersistenceService persistence =
            mock(TroubleshootingPersistenceService.class);
    private final DeploymentTopologyScenarioPolicy policy =
            new DeploymentTopologyScenarioPolicy(versions);
    private final DeploymentTopologyScenarioDiagnosisService service =
            new DeploymentTopologyScenarioDiagnosisService(
                    versions,
                    persistence,
                    policy,
                    new DiagnosisStateMachine(
                            Clock.fixed(NOW, ZoneOffset.UTC),
                            prefix -> prefix + "-event"),
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    () -> "correlation-1");

    @Test
    void createsAnExplicitScenarioDiagnosisThatWaitsForTopologyEvidence() {
        ApprovedPlaybookVersion authority = version(topologyPlaybook(true));
        activeAuthority(authority);
        when(persistence.createOrGetForScenario(
                eq(WORKSPACE_ID), any(Diagnosis.class),
                eq(DeploymentTopologyScenarioPolicy.SCENARIO_KEY), eq(REPORTED_AT)))
                .thenAnswer(invocation -> new StoredDiagnosis(
                        invocation.getArgument(1), 0, true));

        StoredDiagnosis stored = service.create(
                WORKSPACE_ID, incident(), true, "alice", REPORTED_AT);

        Diagnosis diagnosis = stored.diagnosis();
        assertThat(diagnosis.contractVersion()).isEqualTo("1.8");
        assertThat(diagnosis.routeMode()).isEqualTo(RouteMode.DETERMINISTIC);
        assertThat(diagnosis.investigationMode())
                .isEqualTo(InvestigationMode.SCENARIO_PLAYBOOK);
        assertThat(diagnosis.routeAuthority()).isEqualTo(RouteAuthority.EXPLICIT);
        assertThat(diagnosis.conclusionType())
                .isEqualTo(ConclusionType.INSUFFICIENT_EVIDENCE);
        assertThat(diagnosis.status()).isEqualTo(DiagnosisStatus.NEEDS_INVESTIGATION);
        assertThat(diagnosis.confidence()).isEqualTo(Confidence.LOW);
        assertThat(diagnosis.abstained()).isTrue();
        assertThat(diagnosis.sopKey())
                .isEqualTo("csdp:scenario:deployment_topology_probe");
        assertThat(diagnosis.sourcePlaybookVersionRef()).isEqualTo(PLAYBOOK_REF);
        assertThat(diagnosis.evidence()).isEmpty();
        assertThat(diagnosis.recommendedActions()).isEmpty();
        assertThat(diagnosis.writeExecutionEnabled()).isFalse();
        assertThat(diagnosis.timeline())
                .extracting(event -> event.actor())
                .contains("alice");
        assertThat(diagnosis.timings().reportedAt()).isEqualTo(REPORTED_AT);
        assertThat(diagnosis.timings().conclusionAt()).isEqualTo(NOW);

        verify(versions).activeRef(
                WORKSPACE_ID, "csdp:scenario:deployment_topology_probe");
        verify(versions).lockActiveApprovedByPlaybookId(
                WORKSPACE_ID, PLAYBOOK_REF.playbookId());
        verify(persistence).createOrGetForScenario(
                WORKSPACE_ID, diagnosis,
                DeploymentTopologyScenarioPolicy.SCENARIO_KEY, REPORTED_AT);
        InOrder authorityThenInsert = inOrder(versions, persistence);
        authorityThenInsert.verify(versions).activeRef(
                WORKSPACE_ID, "csdp:scenario:deployment_topology_probe");
        authorityThenInsert.verify(versions).lockActiveApprovedByPlaybookId(
                WORKSPACE_ID, PLAYBOOK_REF.playbookId());
        authorityThenInsert.verify(persistence).createOrGetForScenario(
                WORKSPACE_ID, diagnosis,
                DeploymentTopologyScenarioPolicy.SCENARIO_KEY, REPORTED_AT);
    }

    @Test
    void keepsTheAuthorityLockAndDiagnosisInsertInOneTransaction() throws Exception {
        assertThat(DeploymentTopologyScenarioDiagnosisService.class
                .getDeclaredMethod(
                        "create",
                        long.class,
                        IncidentContext.class,
                        boolean.class,
                        String.class,
                        Instant.class)
                .getAnnotation(Transactional.class))
                .isNotNull();
    }

    @Test
    void failsClosedWhenNoActiveScenarioPlaybookExists() {
        when(versions.activeRef(
                WORKSPACE_ID, "csdp:scenario:deployment_topology_probe"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(
                WORKSPACE_ID, incident(), false, "alice", REPORTED_AT))
                .isInstanceOf(MateClawException.class)
                .satisfies(error -> assertThat(((MateClawException) error).getCode())
                        .isEqualTo(409))
                .hasMessageContaining("approved deployment topology scenario Playbook");

        verify(versions, never()).lockActiveApprovedByPlaybookId(
                eq(WORKSPACE_ID), any());
        verify(persistence, never()).createOrGetForScenario(
                eq(WORKSPACE_ID), any(), eq(
                        DeploymentTopologyScenarioPolicy.SCENARIO_KEY), eq(REPORTED_AT));
    }

    @Test
    void rejectsRawDeveloperEvidenceBeforeResolvingAnyAuthority() {
        IncidentContext unsafe = new IncidentContext(
                "incident-topology", "CSDP", "csp-prm-miniapp", null,
                "2026-07-31 09:00:00 ERROR upstream timeout", "P1", "网络访问",
                null, REPORTED_AT, null, "web:deployment-topology-scenario",
                IncidentCompleteness.STRUCTURED, null);

        assertThatThrownBy(() -> service.create(
                WORKSPACE_ID, unsafe, false, "alice", REPORTED_AT))
                .isInstanceOf(MateClawException.class)
                .satisfies(error -> assertThat(((MateClawException) error).getCode())
                        .isEqualTo(400))
                .hasMessageContaining("raw logs");

        verify(versions, never()).activeRef(eq(WORKSPACE_ID), any());
        verify(persistence, never()).createOrGetForScenario(
                eq(WORKSPACE_ID), any(), eq(
                        DeploymentTopologyScenarioPolicy.SCENARIO_KEY), eq(REPORTED_AT));
    }

    @Test
    void failsClosedWhenTheLockedAuthorityChangedOrDoesNotRequireTheTool() {
        when(versions.activeRef(
                WORKSPACE_ID, "csdp:scenario:deployment_topology_probe"))
                .thenReturn(Optional.of(PLAYBOOK_REF));
        when(versions.lockActiveApprovedByPlaybookId(
                WORKSPACE_ID, PLAYBOOK_REF.playbookId()))
                .thenReturn(Optional.of(version(topologyPlaybook(false))));

        assertThatThrownBy(() -> service.create(
                WORKSPACE_ID, incident(), false, "alice", REPORTED_AT))
                .isInstanceOf(MateClawException.class)
                .satisfies(error -> assertThat(((MateClawException) error).getCode())
                        .isEqualTo(409))
                .hasMessageContaining("topology_synthetic_probe");

        verify(persistence, never()).createOrGetForScenario(
                eq(WORKSPACE_ID), any(), eq(
                        DeploymentTopologyScenarioPolicy.SCENARIO_KEY), eq(REPORTED_AT));

        when(versions.lockActiveApprovedByPlaybookId(
                WORKSPACE_ID, PLAYBOOK_REF.playbookId()))
                .thenReturn(Optional.of(new ApprovedPlaybookVersion(
                        "playbook-replaced", 4,
                        "csdp:scenario:deployment_topology_probe",
                        "APPROVED", "MANUAL", "manual-replaced",
                        null, null, "owner", "approved", null,
                        topologyPlaybook(true), NOW, NOW)));

        assertThatThrownBy(() -> service.create(
                WORKSPACE_ID, incident(), false, "alice", REPORTED_AT))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("changed concurrently");
    }

    private void activeAuthority(ApprovedPlaybookVersion authority) {
        when(versions.activeRef(WORKSPACE_ID, authority.selectorKey()))
                .thenReturn(Optional.of(PLAYBOOK_REF));
        when(versions.lockActiveApprovedByPlaybookId(
                WORKSPACE_ID, PLAYBOOK_REF.playbookId()))
                .thenReturn(Optional.of(authority));
    }

    private IncidentContext incident() {
        return new IncidentContext(
                "incident-topology", "CSDP", "csp-prm-miniapp", null,
                "海外客户访问超时", "P1", "网络访问",
                "trace-safe-1", REPORTED_AT, "15m",
                "web:deployment-topology-scenario",
                IncidentCompleteness.STRUCTURED, null);
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
                "scenario:deployment_topology_probe", "csp-prm-miniapp",
                "部署拓扑拨测", "网络路径待核查", "network", "网络组",
                "approved", true,
                List.of(new EvidenceRequest(
                        "EV-TOPOLOGY", "synthetic_probe", "执行部署拓扑拨测",
                        Map.of(
                                "assetType", "deployment_topology",
                                "toolKey", "topology_synthetic_probe"),
                        "-15m", required)),
                List.of(), List.of(), List.of());
    }
}
