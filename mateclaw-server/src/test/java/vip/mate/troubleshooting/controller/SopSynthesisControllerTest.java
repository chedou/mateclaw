package vip.mate.troubleshooting.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.KnowledgeCandidate;
import vip.mate.troubleshooting.service.TroubleshootingPersistenceService;
import vip.mate.troubleshooting.service.TroubleshootingSopPersistenceService;
import vip.mate.troubleshooting.service.SopSummary;
import vip.mate.troubleshooting.synthesis.LogTraceSkeleton;
import vip.mate.troubleshooting.model.NorthStarTimings;
import vip.mate.troubleshooting.synthesis.PlaybookSynthesisRequest;
import vip.mate.troubleshooting.synthesis.PlaybookSynthesisResult;
import vip.mate.troubleshooting.synthesis.PlaybookCandidateReader;
import vip.mate.troubleshooting.synthesis.KnowledgeOrigin;
import vip.mate.troubleshooting.synthesis.KnowledgeReviewSnapshot;
import vip.mate.troubleshooting.synthesis.KnowledgeReviewSourceKey;
import vip.mate.troubleshooting.synthesis.KnowledgeReviewState;
import vip.mate.troubleshooting.synthesis.KnowledgeReviewStatus;
import vip.mate.troubleshooting.synthesis.KnowledgeReviewWorkflowService;
import vip.mate.troubleshooting.synthesis.SopSynthesisPreview;
import vip.mate.troubleshooting.synthesis.SopSynthesisService;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SopSynthesisControllerTest {

    @Test
    void exposesTheTypedGenerationResultWithoutAnApprovalControl() throws Exception {
        SopSynthesisService synthesis = mock(SopSynthesisService.class);
        SopManagementController controller = new SopManagementController(
                mock(TroubleshootingSopPersistenceService.class),
                mock(TroubleshootingPersistenceService.class),
                synthesis,
                mock(PlaybookCandidateReader.class),
                mock(KnowledgeReviewWorkflowService.class));
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
        when(synthesis.generate(eq(1L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(abstained());

        mvc.perform(post("/api/v1/troubleshooting/sops/synthesis/candidates")
                        .header("X-Workspace-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "system":"CSDP",
                                  "service":"csdp-session-service",
                                  "searchTerm":"message_send_failed",
                                  "window":"-15m",
                                  "occurredAt":"2026-07-20T09:13:00Z",
                                  "sourceIncidentId":"incident-message-send-001",
                                  "reportedAt":"2026-07-20T09:12:00Z",
                                  "readyAt":"2026-07-20T09:13:00Z"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stage").value("ABSTAINED"))
                .andExpect(jsonPath("$.data.candidate").doesNotExist())
                .andExpect(jsonPath("$.data.evidencePreview.contrastAvailable").value(false))
                .andExpect(jsonPath("$.data.timings.reportedAt")
                        .value("2026-07-20T09:12:00Z"))
                .andExpect(jsonPath("$.data.timings.handoffAt").doesNotExist());

        ArgumentCaptor<PlaybookSynthesisRequest> request =
                ArgumentCaptor.forClass(PlaybookSynthesisRequest.class);
        verify(synthesis).generate(eq(1L), request.capture());
        assertThat(request.getValue().sourceIncidentId())
                .isEqualTo("incident-message-send-001");
        assertThat(request.getValue().evidenceRequest().searchTerm())
                .isEqualTo("message_send_failed");
    }

    @Test
    void exposesAllPersistedCandidateLanesAsOneReadOnlyReviewInbox() throws Exception {
        TroubleshootingPersistenceService persistence =
                mock(TroubleshootingPersistenceService.class);
        TroubleshootingSopPersistenceService sopPersistence =
                mock(TroubleshootingSopPersistenceService.class);
        PlaybookCandidateReader candidateReader = mock(PlaybookCandidateReader.class);
        KnowledgeReviewWorkflowService reviews =
                mock(KnowledgeReviewWorkflowService.class);
        SopManagementController controller = new SopManagementController(
                sopPersistence,
                persistence,
                mock(SopSynthesisService.class),
                candidateReader,
                reviews);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
        KnowledgeCandidate outcomeBacked = new KnowledgeCandidate(
                "candidate-outcome-001",
                KnowledgeCandidate.CURRENT_CONTRACT_VERSION,
                "diag-001", "case-001", "run-001",
                "CSDP", "903001", "csdp:903001",
                "Mongo connection pool exhausted",
                List.of("LOG-SEARCH", "TRACE-BUNDLE"),
                List.of(), List.of(),
                "Recovered after the owner recycled the stale connection",
                "retain the verification step", "owner-a",
                Instant.parse("2026-07-20T09:20:00Z"));
        when(candidateReader.list(7L, 12)).thenReturn(List.of());
        when(persistence.listKnowledgeCandidates(7L, 12))
                .thenReturn(List.of(outcomeBacked));
        when(sopPersistence.list(7L, "candidate", null, 12)).thenReturn(List.of(
                new SopSummary(
                        "manual-sop-001", "csdp:903002", "CSDP", "903002",
                        "session-svc", "candidate", false, false,
                        java.time.LocalDateTime.parse("2026-07-20T09:10:00"),
                        java.time.LocalDateTime.parse("2026-07-20T09:10:00"))));
        when(reviews.listForSources(eq(7L), anyList()))
                .thenReturn(List.of(reviewState()));

        mvc.perform(get("/api/v1/troubleshooting/sops/review-inbox")
                        .header("X-Workspace-Id", "7")
                        .param("limit", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.evidenceDerived").isArray())
                .andExpect(jsonPath("$.data.outcomeBacked[0].candidateId")
                        .value("candidate-outcome-001"))
                .andExpect(jsonPath("$.data.manual[0].sopId")
                        .value("manual-sop-001"))
                .andExpect(jsonPath("$.data.reviewStates[0].sourceRecordId")
                        .value("candidate-outcome-001"))
                .andExpect(jsonPath("$.data.reviewStates[0].status")
                        .value("IN_REVIEW"))
                .andExpect(jsonPath("$.data.capabilityLimits[0]")
                        .value("REVIEW_START_AND_REJECT_ONLY"));

        verify(candidateReader).list(7L, 12);
        verify(persistence).listKnowledgeCandidates(7L, 12);
        verify(sopPersistence).list(7L, "candidate", null, 12);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KnowledgeReviewSourceKey>> sourceKeys =
                ArgumentCaptor.forClass(List.class);
        verify(reviews).listForSources(eq(7L), sourceKeys.capture());
        assertThat(sourceKeys.getValue()).containsExactly(
                new KnowledgeReviewSourceKey(
                        KnowledgeOrigin.OUTCOME_BACKED, "candidate-outcome-001"),
                new KnowledgeReviewSourceKey(
                        KnowledgeOrigin.MANUAL, "manual-sop-001"));
    }

    @Test
    void reviewCommandsUseTheAuthenticatedActorAndExpectedVersion() throws Exception {
        KnowledgeReviewWorkflowService reviews =
                mock(KnowledgeReviewWorkflowService.class);
        SopManagementController controller = new SopManagementController(
                mock(TroubleshootingSopPersistenceService.class),
                mock(TroubleshootingPersistenceService.class),
                mock(SopSynthesisService.class),
                mock(PlaybookCandidateReader.class),
                reviews);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
        KnowledgeReviewState inReview = reviewState();
        KnowledgeReviewState rejected = new KnowledgeReviewState(
                inReview.reviewId(), inReview.origin(), inReview.sourceRecordId(),
                inReview.selectorKey(), KnowledgeReviewStatus.REJECTED,
                "reviewer-a", "缺少负例回放", inReview.snapshot(), 2,
                inReview.createdAt(), Instant.parse("2026-07-20T09:25:00Z"));
        when(reviews.start(
                7L, KnowledgeOrigin.OUTCOME_BACKED, "candidate-outcome-001", 0,
                "reviewer-a", "核对关闭结果"))
                .thenReturn(inReview);
        when(reviews.reject(
                7L, KnowledgeOrigin.OUTCOME_BACKED, "candidate-outcome-001", 1,
                "reviewer-a", "缺少负例回放"))
                .thenReturn(rejected);
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("reviewer-a", "n/a", "ROLE_USER"));
        try {
            mvc.perform(post("/api/v1/troubleshooting/sops/review-inbox/"
                            + "OUTCOME_BACKED/candidate-outcome-001/start")
                            .header("X-Workspace-Id", "7")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"expectedVersion":0,"reason":"核对关闭结果"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("IN_REVIEW"))
                    .andExpect(jsonPath("$.data.version").value(1))
                    .andExpect(jsonPath("$.data.reviewer").value("reviewer-a"));

            mvc.perform(post("/api/v1/troubleshooting/sops/review-inbox/"
                            + "OUTCOME_BACKED/candidate-outcome-001/reject")
                            .header("X-Workspace-Id", "7")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"expectedVersion":1,"reason":"缺少负例回放"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("REJECTED"))
                    .andExpect(jsonPath("$.data.version").value(2));
        } finally {
            SecurityContextHolder.clearContext();
        }

        verify(reviews).start(
                7L, KnowledgeOrigin.OUTCOME_BACKED, "candidate-outcome-001", 0,
                "reviewer-a", "核对关闭结果");
        verify(reviews).reject(
                7L, KnowledgeOrigin.OUTCOME_BACKED, "candidate-outcome-001", 1,
                "reviewer-a", "缺少负例回放");
    }

    private KnowledgeReviewState reviewState() {
        return new KnowledgeReviewState(
                "review-1",
                KnowledgeOrigin.OUTCOME_BACKED,
                "candidate-outcome-001",
                "csdp:903001",
                KnowledgeReviewStatus.IN_REVIEW,
                "reviewer-a",
                "核对关闭结果",
                new KnowledgeReviewSnapshot(
                        "NOT_EVALUATED", List.of(), null, null,
                        "NOT_ELIGIBLE",
                        List.of("OUTCOME_ELIGIBILITY_GATE_NOT_IMPLEMENTED"),
                        null),
                1,
                Instant.parse("2026-07-20T09:20:00Z"),
                Instant.parse("2026-07-20T09:20:00Z"));
    }

    private PlaybookSynthesisResult abstained() {
        Instant reported = Instant.parse("2026-07-20T09:12:00Z");
        Instant ready = Instant.parse("2026-07-20T09:13:00Z");
        Instant concluded = Instant.parse("2026-07-20T09:13:05Z");
        LogTraceSkeleton skeleton = new LogTraceSkeleton(
                "synthetic-ps-message-send-001", 1000, 1001, 1,
                List.of("session-api"),
                List.of(new LogTraceSkeleton.TimelineEvent(
                        0, 0, "session-api", "ERROR", "message send failed", null, true)),
                List.of(0), Map.of(), 1, 0);
        SopSynthesisPreview preview = new SopSynthesisPreview(
                SopSynthesisPreview.Stage.READY_FOR_MODEL,
                "CSDP", "csdp-session-service", "message_send_failed", 4,
                "synthetic-ps-message-send-001",
                new SopSynthesisPreview.EvidenceReference(
                        "SYNTH-LOG-SEARCH", EvidenceStatus.ANOMALY,
                        "recorded-replay", concluded),
                new SopSynthesisPreview.EvidenceReference(
                        "SYNTH-TRACE-BUNDLE", EvidenceStatus.ANOMALY,
                        "recorded-replay", concluded),
                null, skeleton, true, List.of("fixture"));
        NorthStarTimings timings = NorthStarTimings.concluded(reported, ready, concluded);
        return new PlaybookSynthesisResult(
                PlaybookSynthesisResult.Stage.ABSTAINED,
                preview, null, null, timings,
                List.of("insufficient evidence"), List.of("fixture"));
    }
}
