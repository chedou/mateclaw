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
import vip.mate.troubleshooting.evidence.EvidenceSourceRouter;
import vip.mate.troubleshooting.evidence.GuanceEvidenceAcceptance;
import vip.mate.troubleshooting.evidence.GuanceEvidenceAcceptanceService;
import vip.mate.troubleshooting.evidence.GuanceEvidenceAcceptanceView;
import vip.mate.troubleshooting.evidence.GuanceEvidenceReadiness;
import vip.mate.troubleshooting.evidence.GuanceEvidenceReadinessService;
import vip.mate.troubleshooting.evidence.GuanceEvidenceSpinePreview;
import vip.mate.troubleshooting.evidence.GuanceEvidenceSpinePreviewService;
import vip.mate.troubleshooting.evidence.GuanceEvidenceValidationReport;
import vip.mate.troubleshooting.evidence.GuanceEvidenceValidationService;
import vip.mate.troubleshooting.evidence.GuanceRecordingTargetCatalog;
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

class EvidenceSourceControllerTest {

    @Test
    void reservesPersistentGuanceAcceptanceForTheWorkspaceOwner() throws Exception {
        RequireWorkspaceRole role = EvidenceSourceController.class
                .getDeclaredMethod(
                        "acceptGuance",
                        GuanceEvidenceAcceptanceRequest.class,
                        Long.class)
                .getAnnotation(RequireWorkspaceRole.class);

        assertThat(role).isNotNull();
        assertThat(role.value()).isEqualTo("owner");
    }

    @Test
    void exposesWorkspaceSpecificSecretFreeReadiness() throws Exception {
        GuanceEvidenceReadinessService readiness = mock(GuanceEvidenceReadinessService.class);
        EvidenceSourceController controller = new EvidenceSourceController(
                mock(EvidenceSourceRouter.class),
                readiness,
                mock(GuanceEvidenceValidationService.class),
                mock(GuanceEvidenceSpinePreviewService.class),
                mock(GuanceEvidenceAcceptanceService.class),
                mock(vip.mate.troubleshooting.evidence.EvidenceSourceAcceptanceService.class),
                mock(GuanceRecordingTargetCatalog.class));
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
    void exposesOnlyTheRunningServersFrozenRecordingTargets() throws Exception {
        GuanceEvidenceReadinessService readiness = mock(GuanceEvidenceReadinessService.class);
        GuanceRecordingTargetCatalog catalog = mock(GuanceRecordingTargetCatalog.class);
        EvidenceSourceController controller = new EvidenceSourceController(
                mock(EvidenceSourceRouter.class),
                readiness,
                mock(GuanceEvidenceValidationService.class),
                mock(GuanceEvidenceSpinePreviewService.class),
                mock(GuanceEvidenceAcceptanceService.class),
                mock(vip.mate.troubleshooting.evidence.EvidenceSourceAcceptanceService.class),
                catalog);
        MockMvc mvc = mvc(controller);
        GuanceEvidenceReadiness current = readiness();
        when(readiness.inspect(7L, "CSDP", "session-svc")).thenReturn(current);
        when(catalog.inspect(current)).thenReturn(recordingTargets());

        mvc.perform(get("/api/v1/troubleshooting/evidence/guance/recording-targets")
                        .header("X-Workspace-Id", "7")
                        .queryParam("system", "CSDP")
                        .queryParam("service", "session-svc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contractVersion")
                        .value("t7-guance-recording-target-catalog.v1"))
                .andExpect(jsonPath("$.data.catalogFingerprint")
                        .value("a".repeat(64)))
                .andExpect(jsonPath("$.data.executableTargetCount").value(0))
                .andExpect(jsonPath("$.data.targets").isEmpty())
                .andExpect(jsonPath("$.data.dql").doesNotExist())
                .andExpect(jsonPath("$.data.apiKey").doesNotExist());

        verify(readiness).inspect(7L, "CSDP", "session-svc");
        verify(catalog).inspect(current);
    }

    @Test
    void acceptsAnAdminTriggeredReadOnlyValidationRequest() throws Exception {
        GuanceEvidenceValidationService validation =
                mock(GuanceEvidenceValidationService.class);
        EvidenceSourceController controller = new EvidenceSourceController(
                mock(EvidenceSourceRouter.class),
                mock(GuanceEvidenceReadinessService.class),
                validation,
                mock(GuanceEvidenceSpinePreviewService.class),
                mock(GuanceEvidenceAcceptanceService.class),
                mock(vip.mate.troubleshooting.evidence.EvidenceSourceAcceptanceService.class),
                mock(GuanceRecordingTargetCatalog.class));
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
                previewService,
                mock(GuanceEvidenceAcceptanceService.class),
                mock(vip.mate.troubleshooting.evidence.EvidenceSourceAcceptanceService.class),
                mock(GuanceRecordingTargetCatalog.class));
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

    @Test
    void exposesAndRecordsTheExactCurrentOwnerAcceptance() throws Exception {
        GuanceEvidenceAcceptanceService acceptanceService =
                mock(GuanceEvidenceAcceptanceService.class);
        EvidenceSourceController controller = new EvidenceSourceController(
                mock(EvidenceSourceRouter.class),
                mock(GuanceEvidenceReadinessService.class),
                mock(GuanceEvidenceValidationService.class),
                mock(GuanceEvidenceSpinePreviewService.class),
                acceptanceService,
                mock(vip.mate.troubleshooting.evidence.EvidenceSourceAcceptanceService.class),
                mock(GuanceRecordingTargetCatalog.class));
        MockMvc mvc = mvc(controller);
        Instant occurredAt = Instant.parse("2026-07-29T08:00:00Z");
        when(acceptanceService.inspect(7L, "CSDP", "session-svc"))
                .thenReturn(acceptanceView());
        when(acceptanceService.accept(
                7L,
                "CSDP",
                "session-svc",
                "message_send_failed",
                "-15m",
                occurredAt,
                completeChecklist(),
                "owner"))
                .thenReturn(acceptanceView());

        mvc.perform(get("/api/v1/troubleshooting/evidence/guance/acceptance")
                        .header("X-Workspace-Id", "7")
                        .queryParam("system", "CSDP")
                        .queryParam("service", "session-svc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.acceptance.acceptedBy").value("owner"))
                .andExpect(jsonPath("$.data.acceptance.validation.psIdFingerprint")
                        .value("c".repeat(64)))
                .andExpect(jsonPath("$.data.acceptance.validation.psId").doesNotExist())
                .andExpect(jsonPath("$.data.searchTerm").doesNotExist())
                .andExpect(jsonPath("$.data.dql").doesNotExist());

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "owner", "N/A", List.of()));
        try {
            mvc.perform(post("/api/v1/troubleshooting/evidence/guance/acceptance")
                            .header("X-Workspace-Id", "7")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "system":"CSDP",
                                      "service":"session-svc",
                                      "searchTerm":"message_send_failed",
                                      "window":"-15m",
                                      "occurredAt":"2026-07-29T08:00:00Z",
                                      "checklist":{
                                        "measurementAndFieldsVerified":true,
                                        "indexVerified":true,
                                        "psIdJoinVerified":true,
                                        "timestampUnitVerified":true,
                                        "timeWindowVerified":true,
                                        "dqlLatencyReviewed":true,
                                        "legacyRouteConflictReviewed":true
                                      }
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("ACCEPTED"));
        } finally {
            SecurityContextHolder.clearContext();
        }

        verify(acceptanceService).accept(
                7L,
                "CSDP",
                "session-svc",
                "message_send_failed",
                "-15m",
                occurredAt,
                completeChecklist(),
                "owner");
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

    private GuanceEvidenceAcceptance.Checklist completeChecklist() {
        return new GuanceEvidenceAcceptance.Checklist(
                true, true, true, true, true, true, true);
    }

    private GuanceEvidenceAcceptanceView acceptanceView() {
        Instant acceptedAt = Instant.parse("2026-07-29T08:00:00Z");
        GuanceEvidenceAcceptance acceptance = new GuanceEvidenceAcceptance(
                "t7-012345678901234567890123",
                "CSDP",
                "session-svc",
                "b".repeat(64),
                completeChecklist(),
                new GuanceEvidenceAcceptance.ValidationFacts(
                        4,
                        3,
                        "c".repeat(64),
                        12,
                        20,
                        40,
                        acceptedAt),
                "owner",
                acceptedAt);
        return new GuanceEvidenceAcceptanceView(
                GuanceEvidenceAcceptanceView.Status.ACCEPTED,
                "CSDP",
                "session-svc",
                "b".repeat(64),
                acceptance,
                List.of());
    }

    private GuanceRecordingTargetCatalog.View recordingTargets() {
        return new GuanceRecordingTargetCatalog.View(
                "t7-guance-recording-target-catalog.v1",
                "CSDP",
                "session-svc",
                "a".repeat(64),
                0,
                0,
                List.of(),
                Instant.parse("2026-08-02T00:00:00Z").getEpochSecond(),
                List.of("only 0 server-frozen unrecorded targets exist for this scope; 20 required"));
    }
}
