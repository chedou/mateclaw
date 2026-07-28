package vip.mate.troubleshooting.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ClassPathResource;
import vip.mate.llm.chatmodel.ProviderChatModelFactory;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.troubleshooting.synthesis.DeterministicLogTraceCompressor;
import vip.mate.troubleshooting.synthesis.PlaybookCandidateStore;
import vip.mate.troubleshooting.synthesis.PlaybookDraftInducer;
import vip.mate.troubleshooting.synthesis.PlaybookDraftValidator;
import vip.mate.troubleshooting.synthesis.PlaybookKnowledgeRecord;
import vip.mate.troubleshooting.synthesis.PlaybookSynthesisRequest;
import vip.mate.troubleshooting.synthesis.PlaybookSynthesisResult;
import vip.mate.troubleshooting.synthesis.SopSynthesisRequest;
import vip.mate.troubleshooting.synthesis.SopSynthesisService;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Fixed P1 eval that composes replay evidence through candidate persistence. */
class PlaybookSynthesisReplayEvalTest {

    private static final Instant NOW = Instant.parse("2026-07-20T09:13:05Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final ModelConfigService modelConfigs = mock(ModelConfigService.class);
    private final ProviderChatModelFactory chatModels = mock(ProviderChatModelFactory.class);
    private final ChatModel chatModel = mock(ChatModel.class);
    private final AtomicReference<PlaybookKnowledgeRecord> persisted = new AtomicReference<>();
    private SopSynthesisService service;

    @BeforeEach
    void setUp() {
        EvidenceProperties properties = properties();
        RecordedReplayAdapter replay = new RecordedReplayAdapter(
                properties.getRecordedReplay(), objectMapper,
                new ClassPathResource("troubleshooting/evidence/recorded-replay-903001.json"),
                CLOCK);
        EvidenceSourceRouter router = new EvidenceSourceRouter(
                List.of(replay), properties, CLOCK);

        when(modelConfigs.getDefaultModel()).thenReturn(model());
        when(chatModels.buildFor(any(), any())).thenReturn(chatModel);
        PlaybookDraftInducer inducer = new PlaybookDraftInducer(
                modelConfigs, chatModels, objectMapper);
        PlaybookCandidateStore store = (workspaceId, candidate) -> {
            PlaybookKnowledgeRecord existing = persisted.get();
            if (existing != null) {
                return new PlaybookCandidateStore.StoredCandidate(existing, false);
            }
            persisted.set(candidate);
            return new PlaybookCandidateStore.StoredCandidate(candidate, true);
        };
        service = new SopSynthesisService(
                router, new DeterministicLogTraceCompressor(), properties,
                inducer, new PlaybookDraftValidator(), store);
    }

    @Test
    void fixedPositiveReplayCreatesOneReviewOnlyCandidateAndReusesIt() {
        ChatResponse fixedResponse = response(validProposalJson());
        when(chatModel.call(any(Prompt.class))).thenReturn(fixedResponse);

        PlaybookSynthesisResult first = service.generate(1L, request());
        PlaybookSynthesisResult retry = service.generate(1L, request());

        assertThat(first.stage()).isEqualTo(PlaybookSynthesisResult.Stage.CANDIDATE_CREATED);
        assertThat(retry.stage()).isEqualTo(PlaybookSynthesisResult.Stage.CANDIDATE_REUSED);
        assertThat(first.evidencePreview().contrastAvailable()).isTrue();
        assertThat(first.evidencePreview().skeleton().contrast().rateDelta()).isEqualTo(0.89);
        assertThat(first.candidate().recordId()).isEqualTo(retry.candidate().recordId());
        assertThat(first.candidate().reviewStatus()).isEqualTo("CANDIDATE");
        assertThat(first.candidate().approvalEligibility()).isEqualTo("NOT_ELIGIBLE");
        assertThat(first.candidate().referenceComparison().passed()).isTrue();
        assertThat(first.candidate().fixtureMode()).isTrue();
    }

    @Test
    void fixedDangerousReplayOutputIsRejectedBeforeTheStore() {
        ChatResponse fixedResponse = response(dangerousProposalJson());
        when(chatModel.call(any(Prompt.class))).thenReturn(fixedResponse);

        PlaybookSynthesisResult result = service.generate(1L, request());

        assertThat(result.stage()).isEqualTo(
                PlaybookSynthesisResult.Stage.VALIDATION_REJECTED);
        assertThat(result.errors()).anyMatch(error -> error.startsWith(
                "PRODUCTION_WRITE_FORBIDDEN:"));
        assertThat(result.candidate()).isNull();
        assertThat(persisted).hasValue(null);
    }

    private EvidenceProperties properties() {
        EvidenceProperties properties = new EvidenceProperties();
        properties.getRecordedReplay().setEnabled(true);
        properties.setRoutes(Map.of(
                "CSDP",
                Map.of(
                        "log_search", List.of("recorded-replay"),
                        "log_trace_bundle", List.of("recorded-replay"),
                        "contrast_sample", List.of("recorded-replay"))));
        return properties;
    }

    private PlaybookSynthesisRequest request() {
        return new PlaybookSynthesisRequest(
                new SopSynthesisRequest(
                        "CSDP", "csdp-session-service", "message_send_failed", "-15m", NOW),
                "incident-message-send-replay-eval",
                NOW.minusSeconds(65), NOW.minusSeconds(5));
    }

    private ModelConfigEntity model() {
        ModelConfigEntity model = new ModelConfigEntity();
        model.setId(7L);
        model.setProvider("openai");
        model.setModelName("fixed-replay-eval");
        model.setUpdateTime(LocalDateTime.parse("2026-07-20T00:00:00"));
        return model;
    }

    private ChatResponse response(String body) {
        ChatResponse response = mock(ChatResponse.class,
                org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(response.getResult().getOutput().getText()).thenReturn(body);
        return response;
    }

    private String validProposalJson() {
        return """
                {
                  "abstain": false,
                  "abstainReason": "",
                  "proposedType": "SCENARIO",
                  "proposedSelector": {
                    "system": "CSDP",
                    "scenarioKey": "message_send_failed",
                    "errorCode": null
                  },
                  "title": "会话消息发送失败排查草案",
                  "evidencePlan": [
                    {"intentKey":"locate_failed_request","signalKind":"log_search","purpose":"定位失败请求","required":true},
                    {"intentKey":"trace_ps_id","signalKind":"log_trace_bundle","purpose":"追踪 PS ID 调用链","required":true},
                    {"intentKey":"compare_success_sample","signalKind":"contrast_sample","purpose":"对比同窗口成功样本","required":true},
                    {"intentKey":"confirm_session_state_conflict","signalKind":"log_trace_bundle","purpose":"确认会话状态冲突","required":true}
                  ],
                  "criteria": [
                    {
                      "criterionKey":"state_conflict",
                      "description":"状态并发写冲突在失败样本中显著出现",
                      "evidenceKinds":["log_trace_bundle","contrast_sample"],
                      "evidenceCitations":["SYNTH-TRACE-BUNDLE","SYNTH-CONTRAST-SAMPLE"]
                    }
                  ],
                  "diagnosisHypotheses": [
                    {
                      "hypothesisKey":"session_state_conflict",
                      "summary":"会话状态并发写冲突",
                      "evidenceCitations":["SYNTH-TRACE-BUNDLE"]
                    }
                  ],
                  "humanActions": [
                    {
                      "intentKey":"verify_recovery",
                      "instruction":"由值班研发在平台外验证新消息发送是否恢复",
                      "executionMode":"EXTERNAL_HUMAN",
                      "evidenceCitations":["SYNTH-LOG-SEARCH"]
                    }
                  ],
                  "evidenceCitations": [
                    "SYNTH-LOG-SEARCH",
                    "SYNTH-TRACE-BUNDLE",
                    "SYNTH-CONTRAST-SAMPLE"
                  ]
                }
                """;
    }

    private String dangerousProposalJson() {
        return validProposalJson()
                .replace("由值班研发在平台外验证新消息发送是否恢复",
                        "restart_production and kubectl delete pod");
    }
}
