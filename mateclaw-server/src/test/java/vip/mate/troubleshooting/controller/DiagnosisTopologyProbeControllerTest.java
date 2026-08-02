package vip.mate.troubleshooting.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import vip.mate.troubleshooting.deployment.DeploymentTopologySopResult;
import vip.mate.troubleshooting.deployment.TopologyProbeEvidenceRun;
import vip.mate.troubleshooting.deployment.TopologyProbeEvidenceRunService;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DiagnosisTopologyProbeControllerTest {

    @Test
    void runsAndListsTopologyEvidenceUnderTheDiagnosisRoute() throws Exception {
        TopologyProbeEvidenceRunService service = mock(TopologyProbeEvidenceRunService.class);
        DiagnosisTopologyProbeController controller =
                new DiagnosisTopologyProbeController(service);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
        TopologyProbeEvidenceRun run = run();
        when(service.run(7L, "diag-1", "topology-1", "alice")).thenReturn(run);
        when(service.list(7L, "diag-1", 50)).thenReturn(List.of(run));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", "n/a", List.of()));
        try {
            mvc.perform(post("/api/v1/troubleshooting/diagnoses/diag-1/topology-probe-runs")
                            .header("X-Workspace-Id", "7")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"topologyId\":\"topology-1\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.diagnosisId").value("diag-1"))
                    .andExpect(jsonPath("$.data.scenarioKey")
                            .value("deployment_topology_probe"))
                    .andExpect(jsonPath("$.data.toolKey")
                            .value("topology_synthetic_probe"))
                    .andExpect(jsonPath("$.data.result.persisted").value(true))
                    .andExpect(jsonPath("$.data.result.apiKey").doesNotExist())
                    .andExpect(jsonPath("$.data.result.query").doesNotExist());

            mvc.perform(get("/api/v1/troubleshooting/diagnoses/diag-1/topology-probe-runs")
                            .header("X-Workspace-Id", "7"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].runId").value("topology-run-1"));
        } finally {
            SecurityContextHolder.clearContext();
        }

        verify(service).run(7L, "diag-1", "topology-1", "alice");
        verify(service).list(7L, "diag-1", 50);
    }

    @Test
    void usesAdminForRunAndViewerForHistory() throws Exception {
        assertThat(DiagnosisTopologyProbeController.class
                .getDeclaredMethod(
                        "run", String.class, TopologyProbeRunRequest.class, Long.class)
                .getAnnotation(RequireWorkspaceRole.class).value())
                .isEqualTo("admin");
        assertThat(DiagnosisTopologyProbeController.class
                .getDeclaredMethod("list", String.class, Integer.class, Long.class)
                .getAnnotation(RequireWorkspaceRole.class).value())
                .isEqualTo("viewer");
    }

    private TopologyProbeEvidenceRun run() {
        Instant now = Instant.parse("2026-07-30T12:00:00Z");
        DeploymentTopologySopResult result = new DeploymentTopologySopResult(
                "1.0", "csp-deployment", "CSP", now, "synthetic_probe",
                DeploymentTopologySopResult.AnalysisStatus.NO_PROBLEM_OBSERVED,
                new DeploymentTopologySopResult.Summary(2, 1, 1, 1, 1, 0, 0),
                List.of(), List.of(), List.of("gateway"),
                List.of("未覆盖节点不声称健康"), now, false, true);
        return new TopologyProbeEvidenceRun(
                "topology-run-1", "diag-1", "topology-1",
                "deployment_topology_probe", "topology_synthetic_probe",
                result, now, now, "alice", true);
    }
}
