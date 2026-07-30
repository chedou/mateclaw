package vip.mate.troubleshooting.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import vip.mate.troubleshooting.deployment.DeploymentTopologySopResult;
import vip.mate.troubleshooting.deployment.DeploymentTopologySopService;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DeploymentTopologySopControllerTest {

    @Test
    void keepsTheLiveDeploymentTopologySopAdminGated() throws Exception {
        RequireWorkspaceRole role = DeploymentTopologySopController.class
                .getDeclaredMethod(
                        "analyze",
                        DeploymentTopologySopRequest.class,
                        Long.class)
                .getAnnotation(RequireWorkspaceRole.class);

        assertThat(role).isNotNull();
        assertThat(role.value()).isEqualTo("admin");
    }

    @Test
    void acceptsASecretFreeSnapshotAndReturnsOnlyTheBoundedAnalysisProjection() throws Exception {
        DeploymentTopologySopService service = mock(DeploymentTopologySopService.class);
        DeploymentTopologySopController controller = new DeploymentTopologySopController(service);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
        when(service.analyze(anyLong(), any())).thenReturn(result());

        mvc.perform(post("/api/v1/troubleshooting/sops/deployment-topology/analyze")
                        .header("X-Workspace-Id", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "snapshot": {
                                    "schemaVersion": "1.0",
                                    "kind": "chain-board.runtime-topology-snapshot",
                                    "system": {"code": "csp-deployment"},
                                    "topology": {"nodes": [], "links": []}
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NO_PROBLEM_OBSERVED"))
                .andExpect(jsonPath("$.data.summary.configuredProbeNodes").value(1))
                .andExpect(jsonPath("$.data.observations[0].probeName")
                        .value("客服数字化平台-首页-可用性监控"))
                .andExpect(jsonPath("$.data.modelCalled").value(false))
                .andExpect(jsonPath("$.data.persisted").value(false))
                .andExpect(jsonPath("$.data.apiKey").doesNotExist())
                .andExpect(jsonPath("$.data.query").doesNotExist());

        verify(service).analyze(anyLong(), any());
    }

    private DeploymentTopologySopResult result() {
        Instant now = Instant.parse("2026-07-30T09:00:00Z");
        return new DeploymentTopologySopResult(
                "1.0",
                "csp-deployment",
                "CSP 部署架构",
                now,
                "synthetic_probe",
                DeploymentTopologySopResult.AnalysisStatus.NO_PROBLEM_OBSERVED,
                new DeploymentTopologySopResult.Summary(21, 27, 1, 1, 1, 0, 0),
                List.of(new DeploymentTopologySopResult.NodeObservation(
                        "csp-prm-miniapp",
                        "PRM 小程序",
                        "client",
                        "https://csdp-applet.example.test",
                        "客服数字化平台-首页-可用性监控",
                        "-5m",
                        DeploymentTopologySopResult.ObservationStatus.HEALTHY,
                        200,
                        "https://csdp-applet.example.test",
                        "客服数字化平台-首页-可用性监控",
                        "guance:synthetic_probe",
                        "拨测返回可达状态",
                        now)),
                List.of(),
                List.of("unconfigured"),
                List.of("20 个节点没有拨测元数据，未宣称健康。"),
                now,
                false,
                false);
    }
}
