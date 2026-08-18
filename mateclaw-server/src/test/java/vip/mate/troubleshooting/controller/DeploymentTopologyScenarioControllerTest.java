package vip.mate.troubleshooting.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import vip.mate.troubleshooting.deployment.DeploymentTopologyScenarioDiagnosisService;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.service.StoredDiagnosis;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DeploymentTopologyScenarioControllerTest {

    @Test
    void createsTheServerOwnedScenarioFromARestrictedBusinessPayload() throws Exception {
        DeploymentTopologyScenarioDiagnosisService service =
                mock(DeploymentTopologyScenarioDiagnosisService.class);
        DeploymentTopologyScenarioController controller =
                new DeploymentTopologyScenarioController(service);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        new ObjectMapper().findAndRegisterModules()))
                .build();
        Instant reportedAt = Instant.parse("2026-07-31T01:02:03Z");
        StoredDiagnosis stored = new StoredDiagnosis(mock(Diagnosis.class), 0, true);
        when(service.create(
                eq(7L), argThat(incident ->
                        incident.system().equals("CSDP")
                                && incident.service().equals("csp-prm-miniapp")
                                && incident.errorCode() == null
                                && incident.occurredAt().equals(reportedAt)),
                eq(true), eq("alice"), eq(reportedAt)))
                .thenReturn(stored);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", "n/a", List.of()));
        try {
            mvc.perform(post("/api/v1/troubleshooting/scenarios/deployment-topology/diagnoses")
                            .requestAttr(
                                    TroubleshootingRequestTimingFilter.REPORTED_AT_ATTRIBUTE,
                                    reportedAt)
                            .header("X-Workspace-Id", "7")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "system":"CSDP",
                                      "service":"csp-prm-miniapp",
                                      "title":"海外客户访问超时",
                                      "severity":"P1",
                                      "traceId":"trace-safe-1",
                                      "rehearsal":true
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.created").value(true))
                    .andExpect(jsonPath("$.data.diagnosis").exists());
        } finally {
            SecurityContextHolder.clearContext();
        }

        verify(service).create(
                eq(7L), argThat(incident ->
                        "web:deployment-topology-scenario".equals(incident.intakeSource())
                                && "trace-safe-1".equals(incident.traceId())),
                eq(true), eq("alice"), eq(reportedAt));
    }

    @Test
    void omissionDefaultsToRehearsalInsteadOfRequestingFormalAdmission() throws Exception {
        DeploymentTopologyScenarioDiagnosisService service =
                mock(DeploymentTopologyScenarioDiagnosisService.class);
        DeploymentTopologyScenarioController controller =
                new DeploymentTopologyScenarioController(service);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        new ObjectMapper().findAndRegisterModules()))
                .build();
        Instant reportedAt = Instant.parse("2026-07-31T01:02:03Z");
        StoredDiagnosis stored = new StoredDiagnosis(mock(Diagnosis.class), 0, true);
        when(service.create(
                eq(7L), any(), eq(true), eq("alice"), eq(reportedAt)))
                .thenReturn(stored);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", "n/a", List.of()));
        try {
            mvc.perform(post("/api/v1/troubleshooting/scenarios/deployment-topology/diagnoses")
                            .requestAttr(
                                    TroubleshootingRequestTimingFilter.REPORTED_AT_ATTRIBUTE,
                                    reportedAt)
                            .header("X-Workspace-Id", "7")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "system":"CSDP",
                                      "service":"csp-prm-miniapp",
                                      "title":"海外客户访问超时",
                                      "severity":"P1"
                                    }
                                    """))
                    .andExpect(status().isOk());
        } finally {
            SecurityContextHolder.clearContext();
        }

        verify(service).create(
                eq(7L), any(), eq(true), eq("alice"), eq(reportedAt));
    }

    @Test
    void requiresAdministratorAuthorityToCreateTheScenarioDiagnosis() throws Exception {
        assertThat(DeploymentTopologyScenarioController.class
                .getDeclaredMethod(
                        "create",
                        DeploymentTopologyScenarioRequest.class,
                        Instant.class,
                        Long.class)
                .getAnnotation(RequireWorkspaceRole.class).value())
                .isEqualTo("admin");
    }
}
