package vip.mate.troubleshooting.deployment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.DiagnosisStatus;
import vip.mate.troubleshooting.model.TroubleshootingTopologyProbeRunEntity;
import vip.mate.troubleshooting.repository.TroubleshootingTopologyProbeRunMapper;
import vip.mate.troubleshooting.service.StoredDiagnosis;
import vip.mate.troubleshooting.service.TroubleshootingPersistenceService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TopologyProbeEvidenceRunServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-30T12:00:00Z");

    private final TroubleshootingPersistenceService persistence =
            mock(TroubleshootingPersistenceService.class);
    private final DeploymentTopologyLibraryService library =
            mock(DeploymentTopologyLibraryService.class);
    private final TroubleshootingTopologyProbeRunMapper mapper =
            mock(TroubleshootingTopologyProbeRunMapper.class);
    private final TopologyProbeEvidenceRunPersistenceService runPersistence =
            mock(TopologyProbeEvidenceRunPersistenceService.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final TopologyProbeEvidenceRunService service =
            new TopologyProbeEvidenceRunService(
                    persistence,
                    library,
                    mapper,
                    runPersistence,
                    objectMapper,
                    Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void persistsOneSafeEvidenceRunUnderTheExistingDiagnosis() {
        Diagnosis diagnosis = mock(Diagnosis.class);
        when(diagnosis.status()).thenReturn(DiagnosisStatus.NEEDS_INVESTIGATION);
        when(persistence.get(7L, "diag-1"))
                .thenReturn(new StoredDiagnosis(diagnosis, 2, false));
        when(library.analyze(7L, "topology-1")).thenReturn(result(false));

        TopologyProbeEvidenceRun run = service.run(
                7L, "diag-1", "topology-1", "alice");

        assertThat(run.diagnosisId()).isEqualTo("diag-1");
        assertThat(run.topologyId()).isEqualTo("topology-1");
        assertThat(run.scenarioKey()).isEqualTo("deployment_topology_probe");
        assertThat(run.toolKey()).isEqualTo("topology_synthetic_probe");
        assertThat(run.result().persisted()).isTrue();
        assertThat(run.startedAt()).isEqualTo(NOW);
        assertThat(run.completedAt()).isEqualTo(NOW);

        ArgumentCaptor<TroubleshootingTopologyProbeRunEntity> captor =
                ArgumentCaptor.forClass(TroubleshootingTopologyProbeRunEntity.class);
        verify(runPersistence).insertIfDiagnosisOpen(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq("diag-1"),
                captor.capture());
        TroubleshootingTopologyProbeRunEntity entity = captor.getValue();
        assertThat(entity.getWorkspaceId()).isEqualTo(7L);
        assertThat(entity.getDiagnosisId()).isEqualTo("diag-1");
        assertThat(entity.getScenarioKey()).isEqualTo("deployment_topology_probe");
        assertThat(entity.getToolKey()).isEqualTo("topology_synthetic_probe");
        assertThat(entity.getResultJson())
                .contains("NETWORK_PROBLEM_DETECTED")
                .doesNotContainIgnoringCase("api-key")
                .doesNotContainIgnoringCase("dql")
                .doesNotContainIgnoringCase("rawResponse");
    }

    @Test
    void refusesToAppendNewEvidenceToAClosedDiagnosis() {
        Diagnosis diagnosis = mock(Diagnosis.class);
        when(diagnosis.status()).thenReturn(DiagnosisStatus.CLOSED);
        when(persistence.get(7L, "diag-closed"))
                .thenReturn(new StoredDiagnosis(diagnosis, 3, false));

        assertThatThrownBy(() -> service.run(
                7L, "diag-closed", "topology-1", "alice"))
                .isInstanceOf(MateClawException.class)
                .satisfies(error -> assertThat(((MateClawException) error).getCode())
                        .isEqualTo(409))
                .hasMessageContaining("closed");

        verify(library, never()).analyze(7L, "topology-1");
        verify(runPersistence, never()).insertIfDiagnosisOpen(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(TroubleshootingTopologyProbeRunEntity.class));
    }

    @Test
    void doesNotPersistWhenDiagnosisClosesDuringEvidenceCollection() {
        Diagnosis diagnosis = mock(Diagnosis.class);
        when(diagnosis.status()).thenReturn(DiagnosisStatus.NEEDS_INVESTIGATION);
        when(persistence.get(7L, "diag-racing-close"))
                .thenReturn(new StoredDiagnosis(diagnosis, 3, false));
        when(library.analyze(7L, "topology-1")).thenReturn(result(false));
        org.mockito.Mockito.doThrow(new MateClawException(
                        "err.troubleshooting.topology_probe_diagnosis_closed",
                        409,
                        "diagnosis was closed while topology evidence was being collected"))
                .when(runPersistence).insertIfDiagnosisOpen(
                        org.mockito.ArgumentMatchers.eq(7L),
                        org.mockito.ArgumentMatchers.eq("diag-racing-close"),
                        org.mockito.ArgumentMatchers.any(TroubleshootingTopologyProbeRunEntity.class));

        assertThatThrownBy(() -> service.run(
                7L, "diag-racing-close", "topology-1", "alice"))
                .isInstanceOf(MateClawException.class)
                .satisfies(error -> assertThat(((MateClawException) error).getCode())
                        .isEqualTo(409))
                .hasMessageContaining("closed");

        verify(library).analyze(7L, "topology-1");
        verify(runPersistence).insertIfDiagnosisOpen(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq("diag-racing-close"),
                org.mockito.ArgumentMatchers.any(TroubleshootingTopologyProbeRunEntity.class));
    }

    @Test
    void listsOnlyTheRequestedDiagnosisHistoryNewestFirst() throws Exception {
        TopologyProbeEvidenceRun stored = new TopologyProbeEvidenceRun(
                "topology-run-1",
                "diag-1",
                "topology-1",
                "deployment_topology_probe",
                "topology_synthetic_probe",
                result(true),
                NOW.minusSeconds(5),
                NOW,
                "alice");
        TroubleshootingTopologyProbeRunEntity entity = new TroubleshootingTopologyProbeRunEntity();
        entity.setWorkspaceId(7L);
        entity.setRunId("topology-run-1");
        entity.setDiagnosisId("diag-1");
        entity.setTopologyId("topology-1");
        entity.setResultJson(objectMapper.writeValueAsString(stored));
        when(persistence.get(7L, "diag-1"))
                .thenReturn(new StoredDiagnosis(mock(Diagnosis.class), 1, false));
        when(mapper.listByDiagnosis(7L, "diag-1", 50)).thenReturn(List.of(entity));

        assertThat(service.list(7L, "diag-1", 50))
                .extracting(TopologyProbeEvidenceRun::runId)
                .containsExactly("topology-run-1");

        verify(mapper).listByDiagnosis(7L, "diag-1", 50);
        verify(mapper, never()).listByDiagnosis(8L, "diag-1", 50);
    }

    private DeploymentTopologySopResult result(boolean persisted) {
        return new DeploymentTopologySopResult(
                "1.0",
                "csp-deployment",
                "CSP 部署架构",
                NOW.minusSeconds(60),
                "synthetic_probe",
                DeploymentTopologySopResult.AnalysisStatus.NETWORK_PROBLEM_DETECTED,
                new DeploymentTopologySopResult.Summary(2, 1, 1, 1, 0, 1, 0),
                List.of(new DeploymentTopologySopResult.NodeObservation(
                        "entry", "公网入口", "client", "https://example.test",
                        "首页拨测", "-5m",
                        DeploymentTopologySopResult.ObservationStatus.FAILED,
                        503, "https://example.test", "首页拨测",
                        "guance:synthetic_probe", "拨测异常", NOW)),
                List.of(new DeploymentTopologySopResult.SuspectLink(
                        "entry", "gateway", "失败节点相邻链路，仅供核查")),
                List.of("gateway"),
                List.of("未覆盖节点不声称健康"),
                NOW,
                false,
                persisted);
    }
}
