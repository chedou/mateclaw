package vip.mate.troubleshooting.agent;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TroubleshootingEvidenceToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void collectsThroughTheSharedRouterAndRecordsTheCanonicalResult() throws Exception {
        EvidenceSourceRouter router = mock(EvidenceSourceRouter.class);
        TroubleshootingAgentProperties properties = properties();
        TroubleshootingEvidenceSessionRegistry sessions =
                new TroubleshootingEvidenceSessionRegistry(router, properties);
        TroubleshootingEvidenceTool tool =
                new TroubleshootingEvidenceTool(sessions, objectMapper);
        IncidentContext incident = incident();
        EvidenceResult collected = new EvidenceResult(
                "agent-log-1", "L", "safe query", EvidenceStatus.ANOMALY,
                "发现错误日志", Map.of("count", 12), "recorded-replay", Instant.now());
        when(router.collect(eq(7L), any(EvidenceRequest.class), any(IncidentContext.class)))
                .thenReturn(collected);

        try (TroubleshootingEvidenceSessionRegistry.SessionHandle session =
                     sessions.open("triage-1", 7L, incident, List.of())) {
            String json = tool.collectTroubleshootingEvidence(
                    "agent-log-1",
                    "log_count",
                    "确认异常日志",
                    "{\"service\":\"order-svc\"}",
                    "-15m",
                    ChatOrigin.web("triage-1", "troubleshooting", 7L, null).toToolContext());

            assertThat(objectMapper.readValue(json, EvidenceResult.class)).isEqualTo(collected);
            assertThat(session.snapshot().evidence()).containsExactly(collected);
            assertThat(session.snapshot().toolCollectedQueryIds()).containsExactly("agent-log-1");
        }

        verify(router).collect(eq(7L), any(EvidenceRequest.class), any(IncidentContext.class));
    }

    @Test
    void redactsSuppliedAndCollectedEvidenceBeforeAgentOrDiagnosisUse() throws Exception {
        EvidenceSourceRouter router = mock(EvidenceSourceRouter.class);
        TroubleshootingEvidenceSessionRegistry sessions =
                new TroubleshootingEvidenceSessionRegistry(router, properties());
        TroubleshootingEvidenceTool tool =
                new TroubleshootingEvidenceTool(sessions, objectMapper);
        EvidenceResult supplied = sensitiveEvidence(
                "token=supplied-secret", "supplied-secret");
        EvidenceResult collected = sensitiveEvidence(
                "token=collected-secret", "collected-secret");
        when(router.collect(eq(7L), any(EvidenceRequest.class), any(IncidentContext.class)))
                .thenReturn(collected);

        try (TroubleshootingEvidenceSessionRegistry.SessionHandle session =
                     sessions.open("triage-1", 7L, incident(), List.of(supplied))) {
            String json = tool.collectTroubleshootingEvidence(
                    "agent-log-1", "log_count", "确认异常", "{}", "-15m",
                    ChatOrigin.web("triage-1", "troubleshooting", 7L, null).toToolContext());

            assertThat(json)
                    .contains("<REDACTED>")
                    .doesNotContain("collected-secret");
            EvidenceResult returned = objectMapper.readValue(json, EvidenceResult.class);
            assertThat(returned.queryId()).isEqualTo("agent-log-1");
            assertThat(returned.query()).doesNotContain("collected-secret");
            assertThat(returned.summary()).doesNotContain("collected-secret");
            assertThat(returned.namespace()).doesNotContain("collected-secret");
            assertThat(returned.source()).doesNotContain("collected-secret");
            assertThat(returned.observed().toString()).doesNotContain("collected-secret");
            assertThat(session.snapshot().evidence().toString())
                    .contains("<REDACTED>")
                    .doesNotContain("supplied-secret", "collected-secret");
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
    void refusesDuplicateRequestIdsWithoutOverwritingCitedEvidence() throws Exception {
        EvidenceSourceRouter router = mock(EvidenceSourceRouter.class);
        TroubleshootingEvidenceSessionRegistry sessions =
                new TroubleshootingEvidenceSessionRegistry(router, properties());
        TroubleshootingEvidenceTool tool =
                new TroubleshootingEvidenceTool(sessions, objectMapper);
        EvidenceResult first = new EvidenceResult(
                "adapter-first", "L", "first query", EvidenceStatus.ANOMALY,
                "first result", Map.of("count", 1), "source-1", Instant.now());
        EvidenceResult second = new EvidenceResult(
                "adapter-second", "L", "second query", EvidenceStatus.NORMAL,
                "second result", Map.of("count", 0), "source-2", Instant.now());
        when(router.collect(eq(7L), any(EvidenceRequest.class), any(IncidentContext.class)))
                .thenReturn(first, second);

        try (TroubleshootingEvidenceSessionRegistry.SessionHandle session =
                     sessions.open("triage-1", 7L, incident(), List.of())) {
            ToolContext context = ChatOrigin.web(
                    "triage-1", "troubleshooting", 7L, null).toToolContext();
            EvidenceResult accepted = objectMapper.readValue(
                    tool.collectTroubleshootingEvidence(
                            "agent-log-1", "log_count", "first", "{}", "-15m", context),
                    EvidenceResult.class);
            EvidenceResult refused = objectMapper.readValue(
                    tool.collectTroubleshootingEvidence(
                            "agent-log-1", "log_count", "second", "{}", "-15m", context),
                    EvidenceResult.class);

            assertThat(accepted.summary()).isEqualTo("first result");
            assertThat(refused.status()).isEqualTo(EvidenceStatus.MISSING);
            assertThat(session.snapshot().evidence()).containsExactly(accepted);
            assertThat(session.snapshot().toolCollectedQueryIds())
                    .containsExactly("agent-log-1");
        }

        verify(router, times(1)).collect(eq(7L), any(), any());
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
}
