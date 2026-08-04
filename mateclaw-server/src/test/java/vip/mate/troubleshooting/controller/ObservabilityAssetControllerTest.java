package vip.mate.troubleshooting.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import vip.mate.troubleshooting.evidence.ObservabilityAssetCatalogView;
import vip.mate.troubleshooting.evidence.ObservabilityAssetDeclaration;
import vip.mate.troubleshooting.evidence.ObservabilityAssetService;
import vip.mate.troubleshooting.evidence.ObservabilityAssetView;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.util.List;
import java.util.Map;

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

class ObservabilityAssetControllerTest {

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void viewersCanReadTheSecretFreeWorkspaceCatalog() throws Exception {
        ObservabilityAssetService service = mock(ObservabilityAssetService.class);
        when(service.catalog(7L)).thenReturn(
                new ObservabilityAssetCatalogView(7L, List.of(), List.of()));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new ObservabilityAssetController(service)).build();

        mvc.perform(get("/api/v1/troubleshooting/evidence/assets")
                        .header("X-Workspace-Id", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workspaceId").value(7));

        RequireWorkspaceRole role = ObservabilityAssetController.class
                .getDeclaredMethod("catalog", Long.class)
                .getAnnotation(RequireWorkspaceRole.class);
        assertThat(role.value()).isEqualTo("viewer");
        verify(service).catalog(7L);
    }

    @Test
    void adminDeclarationTakesActorFromAuthentication() throws Exception {
        ObservabilityAssetService service = mock(ObservabilityAssetService.class);
        when(service.declare(eq(7L), any(ObservabilityAssetDeclaration.class), eq("ops-owner")))
                .thenReturn(new ObservabilityAssetView(
                        "asset-1", "WORKSPACE", 7L, "csdp", "session-service",
                        "CSDP 会话服务", "guance", "prod", null, null, null,
                        true, Map.of("log_search", "csdp-log-search"), Map.of(),
                        1, "ops-owner", "接入真实取证", null));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("ops-owner", "n/a", List.of()));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new ObservabilityAssetController(service)).build();

        mvc.perform(put("/api/v1/troubleshooting/evidence/assets")
                        .header("X-Workspace-Id", "7")
                        .contentType("application/json")
                        .content("""
                                {
                                  "system":"CSDP",
                                  "service":"session-service",
                                  "displayName":"CSDP 会话服务",
                                  "platform":"guance",
                                  "environment":"prod",
                                  "enabled":true,
                                  "signalBindings":{"log_search":"csdp-log-search"},
                                  "parameters":{},
                                  "reason":"接入真实取证"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assetId").value("asset-1"))
                .andExpect(jsonPath("$.data.version").value(1));

        RequireWorkspaceRole role = ObservabilityAssetController.class
                .getDeclaredMethod(
                        "declare",
                        ObservabilityAssetController.ObservabilityAssetRequest.class,
                        Long.class)
                .getAnnotation(RequireWorkspaceRole.class);
        assertThat(role.value()).isEqualTo("admin");
        verify(service).declare(eq(7L), any(ObservabilityAssetDeclaration.class), eq("ops-owner"));
    }
}
