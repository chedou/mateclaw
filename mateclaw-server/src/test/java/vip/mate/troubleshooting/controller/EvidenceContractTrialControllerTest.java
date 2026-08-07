package vip.mate.troubleshooting.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import vip.mate.troubleshooting.evidence.EvidenceContractTrialRequest;
import vip.mate.troubleshooting.evidence.EvidenceContractTrialService;
import vip.mate.troubleshooting.evidence.EvidenceContractTrialView;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EvidenceContractTrialControllerTest {

    private static final Instant COMPLETED_AT = Instant.parse("2026-08-06T10:00:00Z");

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void adminRunsOneBoundedTrialWithTheAuthenticatedActor() throws Exception {
        EvidenceContractTrialService service = mock(EvidenceContractTrialService.class);
        when(service.run(eq(7L), any(EvidenceContractTrialRequest.class), eq("ops-admin")))
                .thenReturn(view());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("ops-admin", "n/a", List.of()));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new EvidenceContractTrialController(service)).build();

        mvc.perform(post("/api/v1/troubleshooting/evidence/contract-trials")
                        .header("X-Workspace-Id", "7")
                        .contentType("application/json")
                        .content("""
                                {
                                  "system":"csdp",
                                  "service":"session-service",
                                  "contractRef":"csdp-log-search",
                                  "parameters":{"search_term":"SendMsgFailed"},
                                  "window":"-15m",
                                  "occurredAt":"2026-08-06T09:59:00Z"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OBSERVED"))
                .andExpect(jsonPath("$.data.canonicalFields[0]").value("match_count"))
                .andExpect(jsonPath("$.data.warning").isNotEmpty());

        RequireWorkspaceRole role = EvidenceContractTrialController.class
                .getDeclaredMethod("run", EvidenceContractTrialRequest.class, Long.class)
                .getAnnotation(RequireWorkspaceRole.class);
        assertThat(role.value()).isEqualTo("admin");
        verify(service).run(eq(7L), any(EvidenceContractTrialRequest.class), eq("ops-admin"));
    }

    @Test
    void viewersCanReadOnlyTheSafeAuditProjection() throws Exception {
        EvidenceContractTrialService service = mock(EvidenceContractTrialService.class);
        when(service.list(7L, "csdp", "session-service", "csdp-log-search", 20))
                .thenReturn(List.of(view()));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new EvidenceContractTrialController(service)).build();

        mvc.perform(get("/api/v1/troubleshooting/evidence/contract-trials")
                        .header("X-Workspace-Id", "7")
                        .param("system", "csdp")
                        .param("service", "session-service")
                        .param("contractRef", "csdp-log-search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].trialId").value("trial-safe"))
                .andExpect(jsonPath("$.data[0].source").value("guance"));

        RequireWorkspaceRole role = EvidenceContractTrialController.class
                .getDeclaredMethod("list", Long.class, String.class, String.class,
                        String.class, Integer.class)
                .getAnnotation(RequireWorkspaceRole.class);
        assertThat(role.value()).isEqualTo("viewer");
        verify(service).list(7L, "csdp", "session-service", "csdp-log-search", 20);
    }

    private EvidenceContractTrialView view() {
        return new EvidenceContractTrialView(
                "trial-safe", 7L, "csdp", "session-service", "csdp-log-search",
                "log_search", "asset-1", 3,
                EvidenceContractTrialView.Status.OBSERVED, "COMPLETED", "guance",
                List.of("match_count", "ps_id"), 42L, "ops-admin", COMPLETED_AT,
                "只证明只读查询返回规范证据，不代表生产验收通过");
    }
}
