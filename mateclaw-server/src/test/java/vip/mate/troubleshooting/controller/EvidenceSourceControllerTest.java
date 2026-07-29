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
import vip.mate.troubleshooting.evidence.GuanceEvidenceSpinePreview;
import vip.mate.troubleshooting.evidence.GuanceEvidenceSpinePreviewService;
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
                mock(GuanceEvidenceValidationService.class),
                mock(GuanceEvidenceSpinePreviewService.class));
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
                validation,
                mock(GuanceEvidenceSpinePreviewService.class));
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
                .andExpect(jsonPath("$.data.traceEntries").value(2))
                .andExpect(jsonPath("$.data.totalDurationMs").value(50))
                .andExpect(jsonPath("$.data.steps[0].durationMs").value(12));

        verify(validation).validate(
                7L, "CSDP", "session-svc", "message_send_failed", "-15m", occurredAt);
    }

    @Test
    void exposesAnAdminTriggeredGuanceOnlyFullSpinePreview() throws Exception {
        GuanceEvidenceSpinePreviewService previewService =
                mock(GuanceEvidenceSpinePreviewService.class);
        EvidenceSourceController controller = new EvidenceSourceController(
                mock(EvidenceSourceRouter.class),
                mock(GuanceEvidenceReadinessService.class),
                mock(GuanceEvidenceValidationService.class),
                previewService);
        MockMvc mvc = mvc(controller);
        Instant occurredAt = Instant.parse("2026-07-29T08:00:00Z");
        when(previewService.preview(
                7L, "CSDP", "session-svc", "message_send_failed", "-15m", occurredAt))
                .thenReturn(spinePreview());

        mvc.perform(post("/api/v1/troubleshooting/evidence/guance/spine/preview")
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
                .andExpect(jsonPath("$.data.stage").value("FULL_SPINE_OBSERVED"))
                .andExpect(jsonPath("$.data.serviceSequence[0]").value("gateway"))
                .andExpect(jsonPath("$.data.anomalyCount").value(2))
                .andExpect(jsonPath("$.data.contrast.available").value(true))
                .andExpect(jsonPath("$.data.contrast.rateDelta").value(0.89))
                .andExpect(jsonPath("$.data.rawEvidence").doesNotExist());

        verify(previewService).preview(
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
                50L,
                List.of(new GuanceEvidenceValidationReport.Step(
                        "log_search",
                        GuanceEvidenceValidationReport.StepStatus.CANONICAL_RESULT_OBSERVED,
                        "T7-GUANCE-LOG-SEARCH",
                        "canonical match count and PS ID observed",
                        12L,
                        Instant.parse("2026-07-29T08:00:00Z"))),
                Instant.parse("2026-07-29T08:00:00Z"),
                List.of("待 T7 字段验收与 T8 历史样本"));
    }

    private GuanceEvidenceSpinePreview spinePreview() {
        Instant collectedAt = Instant.parse("2026-07-29T08:00:00Z");
        return new GuanceEvidenceSpinePreview(
                GuanceEvidenceSpinePreview.Stage.FULL_SPINE_OBSERVED,
                readiness(),
                4L,
                "ps-message-001",
                3,
                List.of("gateway", "session-svc", "openim"),
                2,
                42L,
                new GuanceEvidenceSpinePreview.Contrast(
                        true, 100, 92, 100, 3, 0.92, 0.03, 0.89),
                3,
                50L,
                List.of(
                        new GuanceEvidenceSpinePreview.Step(
                                "log_search",
                                GuanceEvidenceSpinePreview.StepStatus.CANONICAL_RESULT_OBSERVED,
                                "T8-GUANCE-LOG-SEARCH",
                                collectedAt),
                        new GuanceEvidenceSpinePreview.Step(
                                "log_trace_bundle",
                                GuanceEvidenceSpinePreview.StepStatus.CANONICAL_RESULT_OBSERVED,
                                "T8-GUANCE-TRACE-BUNDLE",
                                collectedAt),
                        new GuanceEvidenceSpinePreview.Step(
                                "contrast_sample",
                                GuanceEvidenceSpinePreview.StepStatus.CANONICAL_RESULT_OBSERVED,
                                "T8-GUANCE-CONTRAST-SAMPLE",
                                collectedAt)),
                collectedAt,
                List.of("待 T7/T8 验收"));
    }
}
