package vip.mate.troubleshooting.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import vip.mate.troubleshooting.evaluation.EvidenceEvaluationSample;
import vip.mate.troubleshooting.evaluation.EvidenceEvaluationSampleLedger;
import vip.mate.troubleshooting.evaluation.EvidenceEvaluationSampleService;
import vip.mate.troubleshooting.evaluation.EvidenceEvaluationSampleStore;
import vip.mate.troubleshooting.evidence.GuanceEvidenceReadiness;
import vip.mate.troubleshooting.evidence.GuanceEvidenceSpinePreview;
import vip.mate.troubleshooting.model.ClosureOutcome;
import vip.mate.troubleshooting.synthesis.ReferenceSolution;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EvidenceEvaluationSampleControllerTest {

    private static final Instant NOW = Instant.parse("2026-07-29T08:00:00Z");

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void capturesByRerunningTheServerOwnedGuanceSpineForTheAuthenticatedActor()
            throws Exception {
        EvidenceEvaluationSampleService service = mock(EvidenceEvaluationSampleService.class);
        EvidenceEvaluationSample captured = captured();
        when(service.capture(
                7L, "diag-1", "message_send_failed", "source_lookup_key", "-15m",
                "admin@example.com"))
                .thenReturn(new EvidenceEvaluationSampleStore.StoredSample(captured, true));
        authenticate("admin@example.com");

        mvc(service).perform(post("/api/v1/troubleshooting/evaluation-samples/guance")
                        .header("X-Workspace-Id", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "diagnosisId":"diag-1",
                                  "scenarioKey":"message_send_failed",
                                  "searchTerm":"source_lookup_key",
                                  "window":"-15m"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created").value(true))
                .andExpect(jsonPath("$.data.sample.sampleId")
                        .value("eval-012345678901234567890123"))
                .andExpect(jsonPath("$.data.sample.sourcePlatform").value("GUANCE"))
                .andExpect(jsonPath("$.data.sample.evidence.fixtureMode").value(false))
                .andExpect(jsonPath("$.data.sample.diagnosisFixtureMode").value(true))
                .andExpect(jsonPath("$.data.sample.searchTerm").doesNotExist())
                .andExpect(jsonPath("$.data.sample.rawLog").doesNotExist());

        verify(service).capture(
                7L, "diag-1", "message_send_failed", "source_lookup_key", "-15m",
                "admin@example.com");
    }

    @Test
    void finalizesOnlyStructuredReferenceInputsAndNeverAcceptsOutcomeFromTheBrowser()
            throws Exception {
        EvidenceEvaluationSampleService service = mock(EvidenceEvaluationSampleService.class);
        EvidenceEvaluationSample ready = ready();
        when(service.finalizeReference(
                7L,
                ready.sampleId(),
                0,
                List.of("locate_failed_request", "trace_ps_id"),
                List.of("restart_production"),
                "reviewer@example.com"))
                .thenReturn(ready);
        authenticate("reviewer@example.com");

        mvc(service).perform(put(
                        "/api/v1/troubleshooting/evaluation-samples/{sampleId}/reference",
                        ready.sampleId())
                        .header("X-Workspace-Id", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedVersion":0,
                                  "requiredStepIntents":["locate_failed_request","trace_ps_id"],
                                  "forbiddenStepIntents":["restart_production"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.referenceStatus")
                        .value("READY_FOR_EVALUATION"))
                .andExpect(jsonPath("$.data.outcome.outcome").value("RECOVERED"))
                .andExpect(jsonPath("$.data.outcome.summary")
                        .value("人工恢复后验证通过"));

        verify(service).finalizeReference(
                7L,
                ready.sampleId(),
                0,
                List.of("locate_failed_request", "trace_ps_id"),
                List.of("restart_production"),
                "reviewer@example.com");
    }

    @Test
    void listsAccumulationCountsWithoutPublishingAnAcceptanceVerdict() throws Exception {
        EvidenceEvaluationSampleService service = mock(EvidenceEvaluationSampleService.class);
        when(service.list(7L, null, 100))
                .thenReturn(EvidenceEvaluationSampleLedger.from(List.of(ready(), captured())));

        mvc(service).perform(get("/api/v1/troubleshooting/evaluation-samples")
                        .header("X-Workspace-Id", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.total").value(2))
                .andExpect(jsonPath("$.data.summary.readyForEvaluation").value(1))
                .andExpect(jsonPath("$.data.summary.minimumEvaluationTarget").value(20))
                .andExpect(jsonPath("$.data.summary.targetRangeMax").value(30))
                .andExpect(jsonPath("$.data.summary.passed").doesNotExist());

        verify(service).list(7L, null, 100);
    }

    private MockMvc mvc(EvidenceEvaluationSampleService service) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return MockMvcBuilders.standaloneSetup(
                        new EvidenceEvaluationSampleController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    private void authenticate(String actor) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actor, null, List.of()));
    }

    private EvidenceEvaluationSample ready() {
        EvidenceEvaluationSample captured = captured();
        return captured.finalizeReference(
                new ReferenceSolution(
                        captured.sampleId() + "/reference/v1",
                        captured.scenarioKey(),
                        List.of("locate_failed_request", "trace_ps_id"),
                        List.of("restart_production"),
                        List.of(new ReferenceSolution.OrderingConstraint(
                                "locate_failed_request", "trace_ps_id")),
                        List.of("log_search", "log_trace_bundle", "contrast_sample")),
                new EvidenceEvaluationSample.OutcomeSnapshot(
                        ClosureOutcome.RECOVERED,
                        "人工恢复后验证通过",
                        true,
                        NOW),
                "reviewer@example.com",
                NOW.plusSeconds(5));
    }

    private EvidenceEvaluationSample captured() {
        return EvidenceEvaluationSample.captured(
                "eval-012345678901234567890123",
                "a".repeat(64),
                "diag-1",
                "CSDP",
                "session-svc",
                "message_send_failed",
                new GuanceEvidenceSpinePreview(
                        GuanceEvidenceSpinePreview.Stage.FULL_SPINE_OBSERVED,
                        new GuanceEvidenceReadiness(
                                "CSDP", "session-svc",
                                GuanceEvidenceReadiness.Status.READY_FOR_VALIDATION,
                                true, true,
                                GuanceEvidenceReadiness.CredentialState.CONFIGURED,
                                true, List.of(), List.of()),
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
                                observed("log_search", "T8-GUANCE-LOG-SEARCH"),
                                observed("log_trace_bundle", "T8-GUANCE-TRACE-BUNDLE"),
                                observed("contrast_sample", "T8-GUANCE-CONTRAST-SAMPLE")),
                        NOW,
                        List.of()),
                true,
                "admin@example.com",
                NOW);
    }

    private GuanceEvidenceSpinePreview.Step observed(String kind, String ref) {
        return new GuanceEvidenceSpinePreview.Step(
                kind,
                GuanceEvidenceSpinePreview.StepStatus.CANONICAL_RESULT_OBSERVED,
                ref,
                NOW);
    }
}
