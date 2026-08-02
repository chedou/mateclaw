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
import vip.mate.troubleshooting.model.SopEntry;
import vip.mate.troubleshooting.service.TroubleshootingPersistenceService;
import vip.mate.troubleshooting.service.TroubleshootingSopPersistenceService;
import vip.mate.troubleshooting.service.KnowledgeEvidenceCoverage;
import vip.mate.troubleshooting.service.SopSummary;
import vip.mate.troubleshooting.synthesis.LogTraceSkeleton;
import vip.mate.troubleshooting.model.NorthStarTimings;
import vip.mate.troubleshooting.synthesis.KnowledgeReviewInbox;
import vip.mate.troubleshooting.synthesis.KnowledgeReviewInboxService;
import vip.mate.troubleshooting.synthesis.ApprovedPlaybookVersion;
import vip.mate.troubleshooting.synthesis.KnowledgeReviewQualificationPolicy;
import vip.mate.troubleshooting.synthesis.KnowledgeQualificationPhase;
import vip.mate.troubleshooting.synthesis.PlaybookSynthesisRequest;
import vip.mate.troubleshooting.synthesis.PlaybookSynthesisResult;
import vip.mate.troubleshooting.synthesis.KnowledgeOrigin;
import vip.mate.troubleshooting.synthesis.KnowledgeReviewSnapshot;
import vip.mate.troubleshooting.synthesis.KnowledgeReviewApproval;
import vip.mate.troubleshooting.synthesis.KnowledgeReviewDeprecation;
import vip.mate.troubleshooting.synthesis.KnowledgeReviewState;
import vip.mate.troubleshooting.synthesis.KnowledgeReviewStatus;
import vip.mate.troubleshooting.synthesis.KnowledgeReviewWorkflowService;
import vip.mate.troubleshooting.synthesis.ManualPlaybookReplayAttestation;
import vip.mate.troubleshooting.synthesis.ManualPlaybookReplayService;
import vip.mate.troubleshooting.synthesis.SopSynthesisPreview;
import vip.mate.troubleshooting.synthesis.SopSynthesisService;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
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
    void exposesKnowledgeEvidenceCoverageAsCountsWithoutInventingARate() throws Exception {
        TroubleshootingSopPersistenceService sopPersistence =
                mock(TroubleshootingSopPersistenceService.class);
        SopManagementController controller = new SopManagementController(
                sopPersistence,
                mock(TroubleshootingPersistenceService.class),
                mock(SopSynthesisService.class),
                mock(KnowledgeReviewInboxService.class),
                mock(KnowledgeReviewWorkflowService.class),
                mock(ManualPlaybookReplayService.class));
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
        when(sopPersistence.knowledgeEvidenceCoverage(7L))
                .thenReturn(new KnowledgeEvidenceCoverage(146, 3, 1, 1, 1, 2));

        mvc.perform(get("/api/v1/troubleshooting/sops/evidence-coverage")
                        .header("X-Workspace-Id", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inventoryErrorCodeSelectors").value(146))
                .andExpect(jsonPath("$.data.registryErrorCodeSelectors").value(3))
                .andExpect(jsonPath("$.data.recordedAggregateSelectors").value(1))
                .andExpect(jsonPath("$.data.authoredFixtureSelectors").value(1))
                .andExpect(jsonPath("$.data.unverifiedSelectors").value(1))
                .andExpect(jsonPath("$.data.outsideInventorySelectors").value(2))
                .andExpect(jsonPath("$.data.coverageRate").doesNotExist())
                .andExpect(jsonPath("$.data.percentage").doesNotExist());

        verify(sopPersistence).knowledgeEvidenceCoverage(7L);
    }

    @Test
    void exposesTheTypedGenerationResultWithoutAnApprovalControl() throws Exception {
        SopSynthesisService synthesis = mock(SopSynthesisService.class);
        SopManagementController controller = new SopManagementController(
                mock(TroubleshootingSopPersistenceService.class),
                mock(TroubleshootingPersistenceService.class),
                synthesis,
                mock(KnowledgeReviewInboxService.class),
                mock(KnowledgeReviewWorkflowService.class),
                mock(ManualPlaybookReplayService.class));
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
        KnowledgeReviewInboxService inboxService =
                mock(KnowledgeReviewInboxService.class);
        KnowledgeReviewWorkflowService reviews =
                mock(KnowledgeReviewWorkflowService.class);
        SopManagementController controller = new SopManagementController(
                sopPersistence,
                persistence,
                mock(SopSynthesisService.class),
                inboxService,
                reviews,
                mock(ManualPlaybookReplayService.class));
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
        KnowledgeCandidate outcomeBacked = new KnowledgeCandidate(
                "candidate-outcome-001",
                KnowledgeCandidate.LEGACY_CONTRACT_VERSION,
                "diag-001", "case-001", "run-001",
                "CSDP", "903001", "csdp:903001",
                "Mongo connection pool exhausted",
                List.of("LOG-SEARCH", "TRACE-BUNDLE"),
                List.of(), List.of(),
                "Recovered after the owner recycled the stale connection",
                "retain the verification step", "owner-a",
                Instant.parse("2026-07-20T09:20:00Z"));
        SopSummary manual = new SopSummary(
                "manual-sop-001", "csdp:903002", "CSDP", "903002",
                "session-svc", "candidate", false, false,
                java.time.LocalDateTime.parse("2026-07-20T09:10:00"),
                java.time.LocalDateTime.parse("2026-07-20T09:10:00"));
        when(inboxService.read(7L, 12)).thenReturn(new KnowledgeReviewInbox(
                List.of(),
                List.of(outcomeBacked),
                List.of(manual),
                List.of(new KnowledgeReviewQualificationPolicy()
                        .outcome(outcomeBacked)),
                List.of(reviewState()),
                KnowledgeReviewInbox.CURRENT_CAPABILITY_LIMITS));

        mvc.perform(get("/api/v1/troubleshooting/sops/review-inbox")
                        .header("X-Workspace-Id", "7")
                        .param("limit", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.evidenceDerived").isArray())
                .andExpect(jsonPath("$.data.outcomeBacked[0].candidateId")
                        .value("candidate-outcome-001"))
                .andExpect(jsonPath("$.data.manual[0].sopId")
                        .value("manual-sop-001"))
                .andExpect(jsonPath("$.data.sourceStates[0].snapshot"
                                + ".eligibilityReasons[0]")
                        .value("OUTCOME_VERIFICATION_NOT_PROJECTED"))
                .andExpect(jsonPath("$.data.reviewStates[0].sourceRecordId")
                        .value("candidate-outcome-001"))
                .andExpect(jsonPath("$.data.reviewStates[0].status")
                        .value("IN_REVIEW"))
                .andExpect(jsonPath("$.data.capabilityLimits[0]")
                        .value("APPROVAL_IS_SERVER_GATED"));

        verify(inboxService).read(7L, 12);
    }

    @Test
    void reviewCommandsUseTheAuthenticatedActorAndExpectedVersion() throws Exception {
        KnowledgeReviewWorkflowService reviews =
                mock(KnowledgeReviewWorkflowService.class);
        ManualPlaybookReplayService replays =
                mock(ManualPlaybookReplayService.class);
        SopManagementController controller = new SopManagementController(
                mock(TroubleshootingSopPersistenceService.class),
                mock(TroubleshootingPersistenceService.class),
                mock(SopSynthesisService.class),
                mock(KnowledgeReviewInboxService.class),
                reviews,
                replays);
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
        KnowledgeReviewState approvedState = new KnowledgeReviewState(
                inReview.reviewId(), inReview.origin(), inReview.sourceRecordId(),
                inReview.selectorKey(), KnowledgeReviewStatus.APPROVED,
                "reviewer-a", "资格与固定回放均通过", inReview.snapshot(), 2,
                inReview.createdAt(), Instant.parse("2026-07-20T09:26:00Z"));
        SopEntry approvedSop = new SopEntry(
                "playbook-v2", SopEntry.CURRENT_CONTRACT_VERSION,
                "CSDP", "903001", "order-svc", "已审核 Playbook",
                "连接池耗尽", "database", "DBA 组", "approved", true,
                List.of(), List.of(), List.of(), List.of());
        KnowledgeReviewApproval approved = new KnowledgeReviewApproval(
                approvedState,
                new ApprovedPlaybookVersion(
                        "playbook-v2", 2, "csdp:903001", "APPROVED",
                        "OUTCOME_BACKED", "candidate-outcome-001", "review-1", 1,
                        "reviewer-a", "资格与固定回放均通过", inReview.snapshot(),
                        approvedSop,
                        Instant.parse("2026-07-20T09:26:00Z"),
                        Instant.parse("2026-07-20T09:26:00Z")));
        KnowledgeReviewState deprecatedState = new KnowledgeReviewState(
                approvedState.reviewId(), approvedState.origin(),
                approvedState.sourceRecordId(), approvedState.selectorKey(),
                KnowledgeReviewStatus.DEPRECATED,
                "reviewer-a", "该版本已被回放反例否定", approvedState.snapshot(), 3,
                approvedState.createdAt(), Instant.parse("2026-07-20T09:27:00Z"));
        ApprovedPlaybookVersion deprecatedVersion = new ApprovedPlaybookVersion(
                "playbook-v2", 2, "csdp:903001", "DEPRECATED",
                "OUTCOME_BACKED", "candidate-outcome-001", "review-1", 1,
                "reviewer-a", "资格与固定回放均通过", inReview.snapshot(),
                new SopEntry(
                        "playbook-v2", SopEntry.CURRENT_CONTRACT_VERSION,
                        "CSDP", "903001", "order-svc", "已审核 Playbook",
                        "连接池耗尽", "database", "DBA 组", "deprecated", false,
                        List.of(), List.of(), List.of(), List.of()),
                Instant.parse("2026-07-20T09:26:00Z"),
                Instant.parse("2026-07-20T09:27:00Z"));
        KnowledgeReviewDeprecation deprecated = new KnowledgeReviewDeprecation(
                deprecatedState, deprecatedVersion);
        ApprovedPlaybookVersion legacyRetired = new ApprovedPlaybookVersion(
                "legacy-approved", 1, "csdp:900001", "DEPRECATED",
                "LEGACY", "legacy-approved", null, null,
                "legacy-migration", "V186 backfill", null,
                "reviewer-a", "迁移规则已失效",
                Instant.parse("2026-07-20T09:28:00Z"),
                new SopEntry(
                        "legacy-approved", SopEntry.CURRENT_CONTRACT_VERSION,
                        "CSDP", "900001", "legacy-svc", "迁移 Playbook",
                        "旧规则", "legacy", "DBA 组", "deprecated", false,
                        List.of(), List.of(), List.of(), List.of()),
                Instant.parse("2026-07-20T09:00:00Z"),
                Instant.parse("2026-07-20T09:28:00Z"));
        when(reviews.start(
                7L, KnowledgeOrigin.OUTCOME_BACKED, "candidate-outcome-001", 0,
                "reviewer-a", "核对关闭结果"))
                .thenReturn(inReview);
        when(reviews.reject(
                7L, KnowledgeOrigin.OUTCOME_BACKED, "candidate-outcome-001", 1,
                "reviewer-a", "缺少负例回放"))
                .thenReturn(rejected);
        when(reviews.approve(
                7L, KnowledgeOrigin.OUTCOME_BACKED, "candidate-outcome-001", 1,
                "reviewer-a", "资格与固定回放均通过"))
                .thenReturn(approved);
        when(reviews.deprecate(
                7L, KnowledgeOrigin.OUTCOME_BACKED, "candidate-outcome-001", 2,
                "reviewer-a", "该版本已被回放反例否定"))
                .thenReturn(deprecated);
        when(reviews.deprecateLegacy(
                7L, "legacy-approved", 1,
                "reviewer-a", "迁移规则已失效"))
                .thenReturn(legacyRetired);
        ManualPlaybookReplayAttestation replay =
                new ManualPlaybookReplayAttestation(
                        "replay-1", "manual-topology-v1",
                        "csdp:scenario:deployment_topology_probe",
                        "a".repeat(64), "deployment-topology-probe/v1", 1,
                        "b".repeat(64),
                        ManualPlaybookReplayAttestation.Status.PASSED,
                        1, 1, 2, 2, List.of(), true, "reviewer-a",
                        Instant.parse("2026-07-20T09:29:00Z"));
        when(replays.run(7L, "manual-topology-v1", "reviewer-a"))
                .thenReturn(replay);
        when(replays.exampleCandidate("csdp:scenario:deployment_topology_probe"))
                .thenReturn(new SopEntry(
                        "manual-deployment-topology-probe-v1", "sop.v1", "CSDP",
                        "scenario:deployment_topology_probe", "network-path",
                        "部署拓扑拨测分析", "网络路径待核查", "network", "网络平台组",
                        "candidate", false,
                        List.of(), List.of(), List.of(), List.of()));
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

            mvc.perform(post("/api/v1/troubleshooting/sops/review-inbox/"
                            + "OUTCOME_BACKED/candidate-outcome-001/approve")
                            .header("X-Workspace-Id", "7")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"expectedVersion":1,"reason":"资格与固定回放均通过"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.review.status").value("APPROVED"))
                    .andExpect(jsonPath("$.data.review.version").value(2))
                    .andExpect(jsonPath("$.data.approvedVersion.playbookVersion")
                            .value(2))
                    .andExpect(jsonPath("$.data.approvedVersion.playbook.status")
                            .value("approved"));

            mvc.perform(post("/api/v1/troubleshooting/sops/review-inbox/"
                            + "OUTCOME_BACKED/candidate-outcome-001/deprecate")
                            .header("X-Workspace-Id", "7")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"expectedVersion":2,"reason":"该版本已被回放反例否定"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.review.status")
                            .value("DEPRECATED"))
                    .andExpect(jsonPath("$.data.review.version").value(3))
                    .andExpect(jsonPath("$.data.deprecatedVersion.status")
                            .value("DEPRECATED"))
                    .andExpect(jsonPath("$.data.deprecatedVersion.playbook.status")
                            .value("deprecated"));

            mvc.perform(post("/api/v1/troubleshooting/sops/versions/"
                            + "legacy-approved/deprecate")
                            .header("X-Workspace-Id", "7")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"expectedPlaybookVersion":1,"reason":"迁移规则已失效"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("DEPRECATED"))
                    .andExpect(jsonPath("$.data.deprecatedBy").value("reviewer-a"))
                    .andExpect(jsonPath("$.data.deprecationReason")
                            .value("迁移规则已失效"));

            mvc.perform(post("/api/v1/troubleshooting/sops/review-inbox/"
                            + "manual/manual-topology-v1/replay")
                            .header("X-Workspace-Id", "7"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("PASSED"))
                    .andExpect(jsonPath("$.data.positivePassed").value(1))
                    .andExpect(jsonPath("$.data.negativeOrAbstainPassed").value(2))
                    .andExpect(jsonPath("$.data.candidateFingerprint")
                            .value("a".repeat(64)))
                    .andExpect(jsonPath("$.data.executedBy").value("reviewer-a"));

            mvc.perform(get("/api/v1/troubleshooting/sops/review-inbox/manual/example")
                            .param("selectorKey",
                                    "csdp:scenario:deployment_topology_probe"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.sopId")
                            .value("manual-deployment-topology-probe-v1"))
                    .andExpect(jsonPath("$.data.status").value("candidate"))
                    .andExpect(jsonPath("$.data.verified").value(false));
        } finally {
            SecurityContextHolder.clearContext();
        }

        verify(reviews).start(
                7L, KnowledgeOrigin.OUTCOME_BACKED, "candidate-outcome-001", 0,
                "reviewer-a", "核对关闭结果");
        verify(reviews).reject(
                7L, KnowledgeOrigin.OUTCOME_BACKED, "candidate-outcome-001", 1,
                "reviewer-a", "缺少负例回放");
        verify(reviews).approve(
                7L, KnowledgeOrigin.OUTCOME_BACKED, "candidate-outcome-001", 1,
                "reviewer-a", "资格与固定回放均通过");
        verify(reviews).deprecate(
                7L, KnowledgeOrigin.OUTCOME_BACKED, "candidate-outcome-001", 2,
                "reviewer-a", "该版本已被回放反例否定");
        verify(reviews).deprecateLegacy(
                7L, "legacy-approved", 1,
                "reviewer-a", "迁移规则已失效");
        verify(replays).run(7L, "manual-topology-v1", "reviewer-a");
        verify(replays).exampleCandidate(
                "csdp:scenario:deployment_topology_probe");
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
                        "NOT_EVALUATED",
                        KnowledgeQualificationPhase.NOT_APPLICABLE,
                        List.of(), null, null,
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
