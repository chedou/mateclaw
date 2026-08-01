package vip.mate.troubleshooting.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.troubleshooting.evidence.EvidenceSourceRouter;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TroubleshootingEvidenceToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void completesTheOnlineEvidenceSpineAndReturnsOnlyTheCompressedTraceToTheAgent()
            throws Exception {
        EvidenceSourceRouter router = mock(EvidenceSourceRouter.class);
        TroubleshootingEvidenceSessionRegistry sessions =
                new TroubleshootingEvidenceSessionRegistry(router, properties());
        TroubleshootingEvidenceTool tool =
                new TroubleshootingEvidenceTool(sessions, objectMapper);
        when(router.collect(
                eq(7L), any(EvidenceRequest.class), any(IncidentContext.class),
                eq(Set.of("recorded-replay"))))
                .thenAnswer(invocation -> {
                    EvidenceRequest request = invocation.getArgument(1);
                    assertThat(request.window()).isEqualTo("-15m");
                    if ("log_search".equals(request.signalKind())) {
                        assertThat(request.target())
                                .containsExactly(Map.entry(
                                        "search_term", "message_send_failed"));
                    }
                    return spineEvidence(request);
                });

        try (TroubleshootingEvidenceSessionRegistry.SessionHandle session =
                     sessions.open("triage-1", 7L, incident(), List.of())) {
            String json = tool.collectTroubleshootingEvidence(
                    "model-request-id",
                    "log_search",
                    "查找消息发送失败样本",
                    "{\"scenario_key\":\"message_send_failed\"}",
                    null,
                    ChatOrigin.web("triage-1", "troubleshooting", 7L, null).toToolContext());

            JsonNode response = objectMapper.readTree(json);
            assertThat(response.path("mode").asText()).isEqualTo("EVIDENCE_SPINE");
            assertThat(response.path("evidence").findValuesAsText("queryId"))
                    .containsExactly(
                            TroubleshootingEvidenceSessionRegistry.ONLINE_SEARCH_REQUEST_ID,
                            TroubleshootingEvidenceSessionRegistry.ONLINE_TRACE_REQUEST_ID,
                            TroubleshootingEvidenceSessionRegistry.ONLINE_CONTRAST_REQUEST_ID);
            assertThat(response.path("traceSkeleton").path("psId").asText())
                    .isEqualTo("synthetic-ps-1");
            assertThat(response.path("searchMatchCount").asLong()).isEqualTo(4);
            assertThat(response.path("traceSkeleton").path("contrast")
                    .path("available").asBoolean()).isTrue();
            assertThat(json)
                    .doesNotContain("raw-dql-must-not-reach-model")
                    .doesNotContain("\"entries\"");
            assertThat(session.snapshot().evidence())
                    .extracting(EvidenceResult::queryId)
                    .containsExactly(
                            TroubleshootingEvidenceSessionRegistry.ONLINE_SEARCH_REQUEST_ID,
                            TroubleshootingEvidenceSessionRegistry.ONLINE_TRACE_REQUEST_ID,
                            TroubleshootingEvidenceSessionRegistry.ONLINE_CONTRAST_REQUEST_ID);
            assertThat(session.snapshot().toolCollectedQueryIds())
                    .containsExactlyInAnyOrder(
                            TroubleshootingEvidenceSessionRegistry.ONLINE_SEARCH_REQUEST_ID,
                            TroubleshootingEvidenceSessionRegistry.ONLINE_TRACE_REQUEST_ID,
                            TroubleshootingEvidenceSessionRegistry.ONLINE_CONTRAST_REQUEST_ID);
        }

        verify(router, times(3)).collect(
                eq(7L), any(EvidenceRequest.class), any(IncidentContext.class),
                eq(Set.of("recorded-replay")));
    }

    @Test
    void refusesDirectDependentTraceCollectionBeforeRawEntriesCanReachTheAgent()
            throws Exception {
        EvidenceSourceRouter router = mock(EvidenceSourceRouter.class);
        TroubleshootingEvidenceSessionRegistry sessions =
                new TroubleshootingEvidenceSessionRegistry(router, properties());
        TroubleshootingEvidenceTool tool =
                new TroubleshootingEvidenceTool(sessions, objectMapper);

        try (TroubleshootingEvidenceSessionRegistry.SessionHandle session =
                     sessions.open("triage-1", 7L, incident(), List.of())) {
            String json = tool.collectTroubleshootingEvidence(
                    "direct-trace",
                    "log_trace_bundle",
                    "bypass compression",
                    "{\"ps_id\":\"synthetic-ps-1\"}",
                    null,
                    ChatOrigin.web("triage-1", "troubleshooting", 7L, null).toToolContext());

            EvidenceResult refused = objectMapper.readValue(json, EvidenceResult.class);
            assertThat(refused.status()).isEqualTo(EvidenceStatus.MISSING);
            assertThat(refused.source()).isEqualTo("agent-tool:rejected");
            assertThat(session.snapshot().coreEvidenceFailure())
                    .isEqualTo("online evidence plan request was rejected");
        }

        verifyNoInteractions(router);
    }

    @Test
    void refusesToStartASpineWhenTheSessionCannotReserveAllThreeSourceCalls()
            throws Exception {
        EvidenceSourceRouter router = mock(EvidenceSourceRouter.class);
        TroubleshootingAgentProperties bounded = properties();
        bounded.setMaxEvidenceRequests(2);
        TroubleshootingEvidenceSessionRegistry sessions =
                new TroubleshootingEvidenceSessionRegistry(router, bounded);
        TroubleshootingEvidenceTool tool =
                new TroubleshootingEvidenceTool(sessions, objectMapper);

        try (TroubleshootingEvidenceSessionRegistry.SessionHandle session =
                     sessions.open("triage-1", 7L, incident(), List.of())) {
            String json = tool.collectTroubleshootingEvidence(
                    "model-search-1",
                    "log_search",
                    "search",
                    "{\"scenario_key\":\"message_send_failed\"}",
                    null,
                    ChatOrigin.web("triage-1", "troubleshooting", 7L, null).toToolContext());

            EvidenceResult refused = objectMapper.readValue(json, EvidenceResult.class);
            assertThat(refused.status()).isEqualTo(EvidenceStatus.MISSING);
            assertThat(session.snapshot().evidence()).isEmpty();
            assertThat(session.snapshot().coreEvidenceFailure())
                    .isEqualTo("online evidence plan request was rejected");
        }

        verifyNoInteractions(router);
    }

    @Test
    void refusesModelOwnedSearchTermsAndRecordsAStickyCoreFailure() throws Exception {
        EvidenceSourceRouter router = mock(EvidenceSourceRouter.class);
        TroubleshootingEvidenceSessionRegistry sessions =
                new TroubleshootingEvidenceSessionRegistry(router, properties());
        TroubleshootingEvidenceTool tool =
                new TroubleshootingEvidenceTool(sessions, objectMapper);

        try (TroubleshootingEvidenceSessionRegistry.SessionHandle session =
                     sessions.open("triage-1", 7L, incident(), List.of())) {
            String json = tool.collectTroubleshootingEvidence(
                    "model-search-1",
                    "log_search",
                    "attempt to own executable plan",
                    "{\"search_term\":\"message_send_failed\"}",
                    null,
                    ChatOrigin.web("triage-1", "troubleshooting", 7L, null).toToolContext());

            EvidenceResult refused = objectMapper.readValue(json, EvidenceResult.class);
            assertThat(refused.status()).isEqualTo(EvidenceStatus.MISSING);
            assertThat(session.snapshot().evidence()).isEmpty();
            assertThat(session.snapshot().coreEvidenceFailure())
                    .isEqualTo("online evidence plan request was rejected");
        }

        verifyNoInteractions(router);
    }

    @Test
    void redactsSuppliedEvidenceBeforeSessionUse() {
        EvidenceSourceRouter router = mock(EvidenceSourceRouter.class);
        TroubleshootingEvidenceSessionRegistry sessions =
                new TroubleshootingEvidenceSessionRegistry(router, properties());
        EvidenceResult supplied = sensitiveEvidence(
                "token=supplied-secret", "supplied-secret");

        try (TroubleshootingEvidenceSessionRegistry.SessionHandle session =
                     sessions.open("triage-1", 7L, incident(), List.of(supplied))) {
            assertThat(session.snapshot().evidence().toString())
                    .contains("<REDACTED>")
                    .doesNotContain("supplied-secret");
        }
        verifyNoInteractions(router);
    }

    @Test
    void remapsCallerSuppliedServerStageIdsBeforeCollectingTheRealSpine()
            throws Exception {
        EvidenceSourceRouter router = mock(EvidenceSourceRouter.class);
        TroubleshootingEvidenceSessionRegistry sessions =
                new TroubleshootingEvidenceSessionRegistry(router, properties());
        TroubleshootingEvidenceTool tool =
                new TroubleshootingEvidenceTool(sessions, objectMapper);
        EvidenceResult supplied = new EvidenceResult(
                TroubleshootingEvidenceSessionRegistry.ONLINE_CONTRAST_REQUEST_ID,
                "UNKNOWN", "", EvidenceStatus.MISSING,
                "caller supplied missing contrast", Map.of(), "supplied", Instant.now());
        when(router.collect(
                eq(7L), any(EvidenceRequest.class), any(IncidentContext.class),
                eq(Set.of("recorded-replay"))))
                .thenAnswer(invocation -> spineEvidence(invocation.getArgument(1)));

        try (TroubleshootingEvidenceSessionRegistry.SessionHandle session =
                     sessions.open("triage-1", 7L, incident(), List.of(supplied))) {
            assertThat(session.snapshot().evidence())
                    .extracting(EvidenceResult::queryId)
                    .containsExactly("supplied-reserved-1");

            JsonNode response = objectMapper.readTree(tool.collectTroubleshootingEvidence(
                    "model-search-1", "log_search", "collect approved spine",
                    "{\"scenario_key\":\"message_send_failed\"}", null,
                    ChatOrigin.web(
                            "triage-1", "troubleshooting", 7L, null).toToolContext()));

            assertThat(response.path("mode").asText()).isEqualTo("EVIDENCE_SPINE");
            assertThat(session.snapshot().evidence())
                    .extracting(EvidenceResult::queryId)
                    .containsExactly(
                            "supplied-reserved-1",
                            TroubleshootingEvidenceSessionRegistry.ONLINE_SEARCH_REQUEST_ID,
                            TroubleshootingEvidenceSessionRegistry.ONLINE_TRACE_REQUEST_ID,
                            TroubleshootingEvidenceSessionRegistry.ONLINE_CONTRAST_REQUEST_ID);
            assertThat(session.snapshot().evidence().getLast().status())
                    .isEqualTo(EvidenceStatus.ANOMALY);
        }
    }

    @Test
    void reportsAMissingTraceWithoutMisclassifyingTheSearchAsMalformedTrace()
            throws Exception {
        EvidenceSourceRouter router = mock(EvidenceSourceRouter.class);
        TroubleshootingEvidenceSessionRegistry sessions =
                new TroubleshootingEvidenceSessionRegistry(router, properties());
        TroubleshootingEvidenceTool tool =
                new TroubleshootingEvidenceTool(sessions, objectMapper);
        when(router.collect(
                eq(7L), any(EvidenceRequest.class), any(IncidentContext.class),
                eq(Set.of("recorded-replay"))))
                .thenAnswer(invocation -> {
                    EvidenceRequest request = invocation.getArgument(1);
                    if ("log_trace_bundle".equals(request.signalKind())) {
                        return new EvidenceResult(
                                request.requestId(), "UNKNOWN", "", EvidenceStatus.MISSING,
                                "trace unavailable", Map.of(), "router:unavailable",
                                Instant.now());
                    }
                    return spineEvidence(request);
                });

        try (TroubleshootingEvidenceSessionRegistry.SessionHandle ignored =
                     sessions.open("triage-1", 7L, incident(), List.of())) {
            JsonNode response = objectMapper.readTree(tool.collectTroubleshootingEvidence(
                    "model-search-1", "log_search", "collect approved spine",
                    "{\"scenario_key\":\"message_send_failed\"}", null,
                    ChatOrigin.web(
                            "triage-1", "troubleshooting", 7L, null).toToolContext()));

            assertThat(response.path("mode").asText()).isEqualTo("EVIDENCE_SPINE");
            assertThat(response.path("traceSkeleton").isNull()).isTrue();
            assertThat(response.path("warnings").toString())
                    .contains("log_trace_bundle evidence is missing")
                    .contains("core evidence is incomplete")
                    .doesNotContain("malformed trace evidence");
        }
    }

    @Test
    void refusesCollectionWhenTheOriginWorkspaceDoesNotOwnTheSession() throws Exception {
        EvidenceSourceRouter router = mock(EvidenceSourceRouter.class);
        TroubleshootingEvidenceSessionRegistry sessions =
                new TroubleshootingEvidenceSessionRegistry(router, properties());
        TroubleshootingEvidenceTool tool =
                new TroubleshootingEvidenceTool(sessions, objectMapper);

        try (TroubleshootingEvidenceSessionRegistry.SessionHandle ignored =
                     sessions.open("triage-1", 7L, incident(), List.of())) {
            String json = tool.collectTroubleshootingEvidence(
                    "agent-log-1", "log_count", "确认异常", "{}", "-15m",
                    ChatOrigin.web("triage-1", "troubleshooting", 8L, null).toToolContext());

            EvidenceResult refused = objectMapper.readValue(json, EvidenceResult.class);
            assertThat(refused.status()).isEqualTo(EvidenceStatus.MISSING);
            assertThat(refused.source()).isEqualTo("agent-tool:rejected");
        }

        verify(router, never()).collect(anyLong(), any(), any());
    }

    @Test
    void refusesAnUnsafeRequestIdWithoutEchoingIt() throws Exception {
        EvidenceSourceRouter router = mock(EvidenceSourceRouter.class);
        TroubleshootingEvidenceSessionRegistry sessions =
                new TroubleshootingEvidenceSessionRegistry(router, properties());
        TroubleshootingEvidenceTool tool =
                new TroubleshootingEvidenceTool(sessions, objectMapper);

        try (TroubleshootingEvidenceSessionRegistry.SessionHandle ignored =
                     sessions.open("triage-1", 7L, incident(), List.of())) {
            String json = tool.collectTroubleshootingEvidence(
                    "token:tool-secret", "log_count", "确认异常", "{}", "-15m",
                    ChatOrigin.web("triage-1", "troubleshooting", 7L, null).toToolContext());

            assertThat(json).doesNotContain("tool-secret");
            EvidenceResult refused = objectMapper.readValue(json, EvidenceResult.class);
            assertThat(refused.queryId()).isEqualTo("rejected");
            assertThat(refused.status()).isEqualTo(EvidenceStatus.MISSING);
        }

        verify(router, never()).collect(anyLong(), any(), any());
    }

    @Test
    void refusesASecondSpineWithoutOverwritingTheFirstCanonicalBundle() throws Exception {
        EvidenceSourceRouter router = mock(EvidenceSourceRouter.class);
        TroubleshootingEvidenceSessionRegistry sessions =
                new TroubleshootingEvidenceSessionRegistry(router, properties());
        TroubleshootingEvidenceTool tool =
                new TroubleshootingEvidenceTool(sessions, objectMapper);
        when(router.collect(
                eq(7L), any(EvidenceRequest.class), any(IncidentContext.class),
                eq(Set.of("recorded-replay"))))
                .thenAnswer(invocation -> spineEvidence(invocation.getArgument(1)));

        try (TroubleshootingEvidenceSessionRegistry.SessionHandle session =
                     sessions.open("triage-1", 7L, incident(), List.of())) {
            ToolContext context = ChatOrigin.web(
                    "triage-1", "troubleshooting", 7L, null).toToolContext();
            JsonNode accepted = objectMapper.readTree(tool.collectTroubleshootingEvidence(
                    "model-search-1", "log_search", "first",
                    "{\"scenario_key\":\"message_send_failed\"}", null, context));
            EvidenceResult refused = objectMapper.readValue(
                    tool.collectTroubleshootingEvidence(
                            "model-search-2", "log_search", "second",
                            "{\"scenario_key\":\"message_send_failed\"}", null, context),
                    EvidenceResult.class);

            assertThat(accepted.path("mode").asText()).isEqualTo("EVIDENCE_SPINE");
            assertThat(refused.status()).isEqualTo(EvidenceStatus.MISSING);
            assertThat(session.snapshot().evidence()).hasSize(3);
            assertThat(session.snapshot().toolCollectedQueryIds())
                    .containsExactlyInAnyOrder(
                            TroubleshootingEvidenceSessionRegistry.ONLINE_SEARCH_REQUEST_ID,
                            TroubleshootingEvidenceSessionRegistry.ONLINE_TRACE_REQUEST_ID,
                            TroubleshootingEvidenceSessionRegistry.ONLINE_CONTRAST_REQUEST_ID);
        }

        verify(router, times(3)).collect(
                eq(7L), any(), any(), eq(Set.of("recorded-replay")));
    }

    @Test
    void refusesCollectionOutsideAnActiveTriageSession() throws Exception {
        EvidenceSourceRouter router = mock(EvidenceSourceRouter.class);
        TroubleshootingEvidenceTool tool = new TroubleshootingEvidenceTool(
                new TroubleshootingEvidenceSessionRegistry(router, properties()), objectMapper);

        String json = tool.collectTroubleshootingEvidence(
                "agent-log-1", "log_count", "确认异常", "{}", "-15m",
                ChatOrigin.web("missing", "troubleshooting", 7L, null).toToolContext());

        EvidenceResult refused = objectMapper.readValue(json, EvidenceResult.class);
        assertThat(refused.status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(refused.source()).isEqualTo("agent-tool:rejected");
        verify(router, never()).collect(anyLong(), any(), any());
    }

    private TroubleshootingAgentProperties properties() {
        TroubleshootingAgentProperties properties = new TroubleshootingAgentProperties();
        properties.setMaxEvidenceRequests(4);
        TroubleshootingAgentProperties.ScenarioEvidencePlan plan =
                new TroubleshootingAgentProperties.ScenarioEvidencePlan();
        plan.setEnabled(true);
        plan.setSystem("CSDP");
        plan.setSearchTerm("message_send_failed");
        plan.setWindow("-15m");
        plan.setWorkspaceIds(List.of(7L));
        plan.setPermittedPlatforms(List.of("recorded-replay"));
        properties.setApprovedScenarioPlans(Map.of("message_send_failed", plan));
        return properties;
    }

    private IncidentContext incident() {
        return new IncidentContext(
                "inc-agent-1", "CSDP", "order-svc", null,
                "订单延迟", "P1", "待确认", "trace-1", Instant.now(),
                null, "alert", IncidentCompleteness.SYMPTOM,
                "Bearer secret-token-must-not-leak");
    }

    private EvidenceResult sensitiveEvidence(String queryId, String secret) {
        return new EvidenceResult(
                queryId,
                "token=" + secret,
                "password=" + secret,
                EvidenceStatus.ANOMALY,
                "Authorization: Bearer " + secret,
                Map.of(
                        "password", secret,
                        "nested", Map.of(
                                "api_key", secret,
                                "Bearer " + secret, "safe-value")),
                "cookie=" + secret,
                Instant.now());
    }

    private EvidenceResult spineEvidence(EvidenceRequest request) {
        Map<String, Object> observed = switch (request.signalKind()) {
            case "log_search" -> Map.of(
                    "match_count", 4,
                    "ps_id", "synthetic-ps-1",
                    "sample_message", "message send failed");
            case "log_trace_bundle" -> Map.of(
                    "ps_id", "synthetic-ps-1",
                    "entries", List.of(
                            traceEntry(1_000, "session-api", "INFO", "accepted", 3),
                            traceEntry(1_020, "session-domain", "ERROR", "state conflict", 18),
                            traceEntry(1_040, "openim", "ERROR", "send rejected", 20)));
            case "contrast_sample" -> Map.of(
                    "discriminating_feature", "session_state_conflict",
                    "failure_sample_count", 100,
                    "failure_match_count", 92,
                    "success_sample_count", 100,
                    "success_match_count", 3);
            default -> throw new IllegalArgumentException(request.signalKind());
        };
        return new EvidenceResult(
                request.requestId(), "L", "raw-dql-must-not-reach-model",
                EvidenceStatus.ANOMALY, "canonical evidence", observed,
                "recorded-replay", Instant.now());
    }

    private Map<String, Object> traceEntry(
            long timestamp,
            String service,
            String level,
            String message,
            double durationMs) {
        return Map.of(
                "timestamp", timestamp,
                "service", service,
                "level", level,
                "message", message,
                "duration_ms", durationMs);
    }
}
