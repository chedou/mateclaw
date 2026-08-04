package vip.mate.troubleshooting.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import vip.mate.troubleshooting.evidence.EvidenceQueryCatalogService;
import vip.mate.troubleshooting.evidence.EvidenceQueryCatalogView;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EvidenceQueryCatalogControllerTest {

    @Test
    void exposesTheWorkspaceCatalogToTroubleshootingViewers() throws Exception {
        EvidenceQueryCatalogService service = mock(EvidenceQueryCatalogService.class);
        when(service.inspect(7L)).thenReturn(new EvidenceQueryCatalogView(
                "evidence-query-catalog.v1", 7L, List.of(), List.of()));
        EvidenceQueryCatalogController controller =
                new EvidenceQueryCatalogController(service);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/api/v1/troubleshooting/evidence/catalog")
                        .header("X-Workspace-Id", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contractVersion")
                        .value("evidence-query-catalog.v1"))
                .andExpect(jsonPath("$.data.workspaceId").value(7));

        RequireWorkspaceRole role = EvidenceQueryCatalogController.class
                .getDeclaredMethod("catalog", Long.class)
                .getAnnotation(RequireWorkspaceRole.class);
        assertThat(role).isNotNull();
        assertThat(role.value()).isEqualTo("viewer");
        verify(service).inspect(7L);
    }
}
