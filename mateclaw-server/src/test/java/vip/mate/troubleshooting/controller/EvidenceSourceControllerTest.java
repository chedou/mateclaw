package vip.mate.troubleshooting.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import vip.mate.troubleshooting.evidence.EvidenceSourceRouter;
import vip.mate.troubleshooting.evidence.GuanceEvidenceReadiness;
import vip.mate.troubleshooting.evidence.GuanceEvidenceReadinessService;
import vip.mate.troubleshooting.evidence.GuanceEvidenceValidationReport;
import vip.mate.troubleshooting.evidence.GuanceEvidenceValidationService;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EvidenceSourceControllerTest {

    @Test
    void exposesWorkspaceSpecificSecretFreeReadiness() throws Exception {
        GuanceEvidenceReadinessService readiness = mock(GuanceEvidenceReadinessService.class);
        EvidenceSourceController controller = new EvidenceSourceController(
                mock(EvidenceSourceRouter.class),
                readiness,
                mock(GuanceEvidenceValidationService.class));
        MockMvc mvc = mvc(controller);
        when(readiness.inspect(7L, "CSDP", "session-svc"))
                .thenReturn(readiness());

        mvc.perform(get("/api/v1/troubleshooting/evidence/readiness")
                        .header("X-Workspace-Id", "7")
                        .queryParam("system", "CSDP")
                        .queryParam("service", "session-svc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("READY_FOR_VALIDATION"))
                .andExpect(jsonPath("$.data.signals[0].bindingRef")
                        .value("search-binding"))
                .andExpect(jsonPath("$.data.apiKey").doesNotExist());

        verify(readiness).inspect(7L, "CSDP", "session-svc");
    }

    @Test
    void acceptsAnAdminTriggeredReadOnlyValidationRequest() throws Exception {
        GuanceEvidenceValidationService validation =
                mock(GuanceEvidenceValidationService.class);
        EvidenceSourceController controller = new EvidenceSourceController(
                mock(EvidenceSourceRouter.class),
                mock(GuanceEvidenceReadinessService.class),
                validation);
        MockMvc mvc = mvc(controller);
        Instant occurredAt = Instant.parse("2026-07-29T08:00:00Z");
        when(validation.validate(
                7L, "CSDP", "session-svc", "message_send_failed", "-15m", occurredAt))
                .thenReturn(report());

        mvc.perform(post("/api/v1/troubleshooting/evidence/guance/validate")
                        .header("X-Workspace-Id", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "system":"CSDP",
                                  "service":"session-svc",
                                  "searchTerm":"message_send_failed",
                                  "window":"-15m",
                                  "occurredAt":"2026-07-29T08:00:00Z"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stage")
                        .value("CANONICAL_CHAIN_OBSERVED"))
                .andExpect(jsonPath("$.data.matchCount").value(4))
                .andExpect(jsonPath("$.data.psId").value("ps-message-001"))
                .andExpect(jsonPath("$.data.traceEntries").value(2));

        verify(validation).validate(
                7L, "CSDP", "session-svc", "message_send_failed", "-15m", occurredAt);
    }

    private MockMvc mvc(EvidenceSourceController controller) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    private GuanceEvidenceReadiness readiness() {
        return new GuanceEvidenceReadiness(
                "CSDP",
                "session-svc",
                GuanceEvidenceReadiness.Status.READY_FOR_VALIDATION,
                true,
                true,
                GuanceEvidenceReadiness.CredentialState.CONFIGURED,
                true,
                List.of(new GuanceEvidenceReadiness.SignalReadiness(
                        "log_search",
                        true,
                        GuanceEvidenceReadiness.SignalStatus.READY_FOR_VALIDATION,
                        "search-binding",
                        null,
                        "ready")),
                List.of());
    }

    private GuanceEvidenceValidationReport report() {
        return new GuanceEvidenceValidationReport(
                GuanceEvidenceValidationReport.Stage.CANONICAL_CHAIN_OBSERVED,
                readiness(),
                4L,
                "ps-message-001",
                2,
                List.of(),
                Instant.parse("2026-07-29T08:00:00Z"),
                List.of("不代表 T7 已验收"));
    }
}
