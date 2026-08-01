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
import vip.mate.troubleshooting.evaluation.BaselineEvaluationLedger;
import vip.mate.troubleshooting.evaluation.BaselineEvaluationRun;
import vip.mate.troubleshooting.evaluation.BaselineEvaluationRunService;
import vip.mate.troubleshooting.evaluation.BaselineEvaluationRunStore;
import vip.mate.troubleshooting.evaluation.EvidenceEvaluationSample;
import vip.mate.troubleshooting.evaluation.EvidenceEvaluationSampleLedger;
import vip.mate.troubleshooting.evaluation.EvidenceEvaluationSampleService;
import vip.mate.troubleshooting.evaluation.EvidenceEvaluationSampleStore;
import vip.mate.troubleshooting.evaluation.RecordedReplayEvaluationCapability;
import vip.mate.troubleshooting.evaluation.RecordedReplayEvaluationCapabilityService;
import vip.mate.troubleshooting.evidence.GuanceEvidenceReadiness;
import vip.mate.troubleshooting.evidence.GuanceEvidenceSpinePreview;
import vip.mate.troubleshooting.model.ClosureOutcome;
import vip.mate.troubleshooting.synthesis.ReferenceSolution;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    void exposesASeparateRecordedReplayCaptureEndpoint() throws Exception {
        EvidenceEvaluationSampleService service = mock(EvidenceEvaluationSampleService.class);
        when(service.captureRecordedReplay(7L, "diag-1", "admin@example.com"))
                .thenReturn(new EvidenceEvaluationSampleStore.StoredSample(captured(), true));
        authenticate("admin@example.com");

        mvc(service).perform(post(
                        "/api/v1/troubleshooting/evaluation-samples/recorded-replay")
                        .header("X-Workspace-Id", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"diagnosisId":"diag-1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created").value(true));

        verify(service).captureRecordedReplay(7L, "diag-1", "admin@example.com");
    }

    @Test
    void rejectsBrowserSuppliedReplayCatalogTargets() throws Exception {
        EvidenceEvaluationSampleService service = mock(EvidenceEvaluationSampleService.class);
        authenticate("admin@example.com");

        mvc(service, mock(BaselineEvaluationRunService.class))
                .perform(post(
                                "/api/v1/troubleshooting/evaluation-samples/recorded-replay")
                        .header("X-Workspace-Id", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "diagnosisId":"diag-1",
                                  "scenarioKey":"forged_scenario",
                                  "window":"-2h"
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(service, never()).captureRecordedReplay(anyLong(), any(), any());
    }

    @Test
    void exposesTheServerOwnedRecordedReplayCapabilityGate() throws Exception {
        EvidenceEvaluationSampleService service = mock(EvidenceEvaluationSampleService.class);
        RecordedReplayEvaluationCapabilityService capabilityService =
                mock(RecordedReplayEvaluationCapabilityService.class);
        when(capabilityService.inspect(7L, "diag-1"))
                .thenReturn(new RecordedReplayEvaluationCapability(
                        true,
                        "READY",
                        "Recorded Replay fixture、路由与登记范围均已就绪",
                        "message_send_failed",
                        "message_send_failed",
                        "-15m"));
        authenticate("admin@example.com");

        mvc(service, mock(BaselineEvaluationRunService.class), capabilityService)
                .perform(get(
                                "/api/v1/troubleshooting/evaluation-samples/recorded-replay/capability")
                        .header("X-Workspace-Id", "7")
                        .param("diagnosisId", "diag-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(true))
                .andExpect(jsonPath("$.data.reasonCode").value("READY"))
                .andExpect(jsonPath("$.data.scenarioKey").value("message_send_failed"))
                .andExpect(jsonPath("$.data.searchTerm").value("message_send_failed"))
                .andExpect(jsonPath("$.data.window").value("-15m"));

        verify(capabilityService).inspect(7L, "diag-1");
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
                EvidenceEvaluationSample.ExpectedDisposition.DRAFT,
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
                                  "forbiddenStepIntents":["restart_production"],
                                  "expectedDisposition":"DRAFT"
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
                EvidenceEvaluationSample.ExpectedDisposition.DRAFT,
                "reviewer@example.com");
    }

    @Test
    void runsAndListsCandidateFreeSingleAgentBaselineFacts() throws Exception {
        EvidenceEvaluationSampleService sampleService =
                mock(EvidenceEvaluationSampleService.class);
        BaselineEvaluationRunService baselineService =
                mock(BaselineEvaluationRunService.class);
        BaselineEvaluationRun run = baselineRun();
        when(baselineService.run(
                7L,
                ready().sampleId(),
                1,
                "source_lookup_key",
                "-15m",
                "admin@example.com"))
                .thenReturn(new BaselineEvaluationRunStore.StoredRun(run, true));
        when(baselineService.list(7L, null, 100))
                .thenReturn(BaselineEvaluationLedger.from(List.of(run)));
        authenticate("admin@example.com");

        mvc(sampleService, baselineService).perform(post(
                        "/api/v1/troubleshooting/evaluation-samples/{sampleId}/baseline-runs",
                        ready().sampleId())
                        .header("X-Workspace-Id", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedSampleVersion":1,
                                  "searchTerm":"source_lookup_key",
                                  "window":"-15m"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created").value(true))
                .andExpect(jsonPath("$.data.run.status").value("SCORED"))
                .andExpect(jsonPath("$.data.run.quality.classification")
                        .value("HELPFUL"))
                .andExpect(jsonPath("$.data.run.model.invocationCount").value(1))
                .andExpect(jsonPath("$.data.run.draft").doesNotExist())
                .andExpect(jsonPath("$.data.run.rawEvidence").doesNotExist())
                .andExpect(jsonPath("$.data.run.gateVerdict").doesNotExist());

        mvc(sampleService, baselineService).perform(get(
                        "/api/v1/troubleshooting/evaluation-samples/baseline-runs")
                        .header("X-Workspace-Id", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.total").value(1))
                .andExpect(jsonPath("$.data.summary.guance.runCount").value(1))
                .andExpect(jsonPath(
                        "$.data.summary.guance.fixtureDiagnosis.modelP50Ms").value(100))
                .andExpect(jsonPath(
                        "$.data.summary.guance.fixtureDiagnosis.totalTokens").value(24))
                .andExpect(jsonPath("$.data.summary.passed").doesNotExist())
                .andExpect(jsonPath("$.data.gateVerdict").doesNotExist());

        verify(baselineService).run(
                7L,
                ready().sampleId(),
                1,
                "source_lookup_key",
                "-15m",
                "admin@example.com");
        verify(baselineService).list(7L, null, 100);
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
        return mvc(service, mock(BaselineEvaluationRunService.class));
    }

    private MockMvc mvc(
            EvidenceEvaluationSampleService service,
            BaselineEvaluationRunService baselineService) {
        return mvc(
                service,
                baselineService,
                mock(RecordedReplayEvaluationCapabilityService.class));
    }

    private MockMvc mvc(
            EvidenceEvaluationSampleService service,
            BaselineEvaluationRunService baselineService,
            RecordedReplayEvaluationCapabilityService replayCapabilityService) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return MockMvcBuilders.standaloneSetup(
                        new EvidenceEvaluationSampleController(
                                service,
                                baselineService,
                                replayCapabilityService))
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
                "c".repeat(64),
                NOW.minusSeconds(60),
                true,
                "admin@example.com",
                NOW);
    }

    private BaselineEvaluationRun baselineRun() {
        return new BaselineEvaluationRun(
                "baseline-0123456789012345678901",
                "b".repeat(64),
                ready().sampleId(),
                "diag-1",
                1,
                EvidenceEvaluationSample.SourcePlatform.GUANCE,
                false,
                true,
                "c".repeat(64),
                BaselineEvaluationRun.Status.SCORED,
                List.of(),
                new BaselineEvaluationRun.ValidationSnapshot(true, true, List.of()),
                new BaselineEvaluationRun.QualitySnapshot(
                        EvidenceEvaluationSample.ExpectedDisposition.DRAFT,
                        BaselineEvaluationRun.ActualDisposition.DRAFT,
                        BaselineEvaluationRun.Classification.HELPFUL,
                        true,
                        1.0,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        false),
                new BaselineEvaluationRun.ModelSnapshot(
                        "openai",
                        "gpt-test",
                        "model-config-v1",
                        NOW,
                        1,
                        16L,
                        8L,
                        24L),
                42L,
                100L,
                142L,
                "admin@example.com",
                NOW.plusSeconds(1));
    }

    private GuanceEvidenceSpinePreview.Step observed(String kind, String ref) {
        return new GuanceEvidenceSpinePreview.Step(
                kind,
                GuanceEvidenceSpinePreview.StepStatus.CANONICAL_RESULT_OBSERVED,
                ref,
                NOW);
    }
}
