package vip.mate.troubleshooting.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import vip.mate.troubleshooting.pilot.TroubleshootingPilotPlanService;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TroubleshootingPilotPlanControllerTest {

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void viewerReadsTheLatestWorkspacePlan() throws Exception {
        TroubleshootingPilotPlanService service = mock(TroubleshootingPilotPlanService.class);
        when(service.current(7L)).thenReturn(unconfigured());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new TroubleshootingPilotPlanController(service)).build();

        mvc.perform(get("/api/v1/troubleshooting/pilot-plan")
                        .header("X-Workspace-Id", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workspaceId").value(7))
                .andExpect(jsonPath("$.data.configured").value(false));

        RequireWorkspaceRole role = TroubleshootingPilotPlanController.class
                .getDeclaredMethod("current", Long.class)
                .getAnnotation(RequireWorkspaceRole.class);
        assertThat(role.value()).isEqualTo("viewer");
        verify(service).current(7L);
    }

    @Test
    void adminDeclarationPreservesDecimalSnowflakeMemberIds() throws Exception {
        TroubleshootingPilotPlanService service = mock(TroubleshootingPilotPlanService.class);
        when(service.declare(eq(7L), any(), eq("pilot-admin")))
                .thenReturn(unconfigured());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("pilot-admin", "n/a", List.of()));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new TroubleshootingPilotPlanController(service)).build();

        mvc.perform(put("/api/v1/troubleshooting/pilot-plan")
                        .header("X-Workspace-Id", "7")
                        .contentType("application/json")
                        .content("""
                                {
                                  "name":"CSDP 首批试点",
                                  "modules":[{"system":"csdp","service":"csdp-wechat"}],
                                  "secondLineUserId":"9007199254740993",
                                  "thirdLineUserId":"9007199254740994",
                                  "sourceOwnerUserId":"9007199254740995",
                                  "enabled":true,
                                  "expectedVersion":0,
                                  "reason":"固定首批范围与三类试点人员"
                                }
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<TroubleshootingPilotPlanService.Declaration> declaration =
                ArgumentCaptor.forClass(TroubleshootingPilotPlanService.Declaration.class);
        verify(service).declare(eq(7L), declaration.capture(), eq("pilot-admin"));
        assertThat(declaration.getValue().secondLineUserId())
                .isEqualTo(9_007_199_254_740_993L);
        assertThat(declaration.getValue().thirdLineUserId())
                .isEqualTo(9_007_199_254_740_994L);
        assertThat(declaration.getValue().sourceOwnerUserId())
                .isEqualTo(9_007_199_254_740_995L);

        RequireWorkspaceRole role = TroubleshootingPilotPlanController.class
                .getDeclaredMethod(
                        "declare",
                        TroubleshootingPilotPlanController.DeclareRequest.class,
                        Long.class)
                .getAnnotation(RequireWorkspaceRole.class);
        assertThat(role.value()).isEqualTo("admin");
    }

    private TroubleshootingPilotPlanService.PlanView unconfigured() {
        return new TroubleshootingPilotPlanService.PlanView(
                7L, false, false, 0, null, List.of(),
                null, null, null, null, null, null,
                List.of("试点范围尚未配置"));
    }
}
