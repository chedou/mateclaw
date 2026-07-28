package vip.mate.troubleshooting.synthesis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.evidence.EvidenceSourceRouter;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlaybookSynthesisFlowTest {

    private static final Instant NOW = Instant.parse("2026-07-20T09:13:05Z");
    private static final Instant REPORTED = Instant.parse("2026-07-20T09:12:00Z");
    private static final Instant READY = Instant.parse("2026-07-20T09:13:00Z");

    private final EvidenceSourceRouter router = mock(EvidenceSourceRouter.class);
    private final PlaybookDraftInducer inducer = mock(PlaybookDraftInducer.class);
    private final PlaybookCandidateStore store = mock(PlaybookCandidateStore.class);
    private final AtomicReference<PlaybookKnowledgeRecord> stored = new AtomicReference<>();
    private SopSynthesisService service;

    @BeforeEach
    void setUp() {
        service = new SopSynthesisService(
                router,
                new DeterministicLogTraceCompressor(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                inducer,
                new PlaybookDraftValidator(),
                store);
        when(router.collect(
                anyLong(), any(), any(), eq(java.util.Set.of("recorded-replay"))))
                .thenAnswer(call -> evidence(call.<EvidenceRequest>getArgument(1).signalKind(), true));
        when(store.saveOrGet(anyLong(), any())).thenAnswer(call -> {
            PlaybookKnowledgeRecord candidate = call.getArgument(1);
            PlaybookKnowledgeRecord existing = stored.get();
            if (existing != null) {
                return new PlaybookCandidateStore.StoredCandidate(existing, false);
            }
            stored.set(candidate);
            return new PlaybookCandidateStore.StoredCandidate(candidate, true);
        });
    }

    @Test
    void runsTheFullFixtureLaneAndRetriesIdempotently() {
        when(inducer.induce(any())).thenReturn(accepted(validProposal(true)));

        PlaybookSynthesisResult first = service.generate(1L, request());
        PlaybookSynthesisResult retry = service.generate(1L, request());

        assertThat(first.stage()).isEqualTo(PlaybookSynthesisResult.Stage.CANDIDATE_CREATED);
        assertThat(retry.stage()).isEqualTo(PlaybookSynthesisResult.Stage.CANDIDATE_REUSED);
        assertThat(retry.candidate().recordId()).isEqualTo(first.candidate().recordId());
        assertThat(first.candidate().draft().generationKey()).hasSize(64);
        assertThat(first.candidate().reviewStatus()).isEqualTo("CANDIDATE");
        assertThat(first.candidate().approvalEligibility()).isEqualTo("NOT_ELIGIBLE");
        assertThat(first.candidate().fixtureMode()).isTrue();
        assertThat(first.candidate().referenceComparison().passed()).isTrue();
        assertThat(first.timings().intakeCost()).isEqualTo(Duration.ofMinutes(1));
        assertThat(first.timings().investigateCost()).isEqualTo(Duration.ofSeconds(5));
        assertThat(first.timings().handoffAt()).isNull();
        assertThat(first.timings().adoptCost()).isNull();
    }

    @Test
    void contrastFailureDegradesButLocksCalibrationAndStillCreatesCandidate() {
        when(router.collect(
                anyLong(), any(), any(), eq(java.util.Set.of("recorded-replay"))))
                .thenAnswer(call -> evidence(call.<EvidenceRequest>getArgument(1).signalKind(), false));
        when(inducer.induce(any())).thenReturn(accepted(validProposal(false)));

        PlaybookSynthesisResult result = service.generate(1L, request());

        assertThat(result.stage()).isEqualTo(PlaybookSynthesisResult.Stage.CANDIDATE_CREATED);
        assertThat(result.evidencePreview().contrastAvailable()).isFalse();
        assertThat(result.candidate().eligibilityReasons())
                .contains("P1_CALIBRATION_PERIOD", "CONTRAST_UNAVAILABLE",
                        "REFERENCE_SOLUTION_DELTA");
    }

    @Test
    void providerFailureAndExplicitAbstentionBothConcludeWithoutCandidate() {
        when(inducer.induce(any()))
                .thenReturn(
                        new PlaybookDraftInducer.InductionResult(
                                PlaybookDraftInducer.Status.REJECTED, null, "", null,
                                List.of("MODEL_CALL_FAILED")),
                        new PlaybookDraftInducer.InductionResult(
                                PlaybookDraftInducer.Status.ABSTAINED, null,
                                "insufficient evidence", invocation(), List.of()));

        PlaybookSynthesisResult failed = service.generate(1L, request());
        PlaybookSynthesisResult abstained = service.generate(1L, request());

        assertThat(failed.stage()).isEqualTo(PlaybookSynthesisResult.Stage.MODEL_REJECTED);
        assertThat(abstained.stage()).isEqualTo(PlaybookSynthesisResult.Stage.ABSTAINED);
        assertThat(failed.candidate()).isNull();
        assertThat(abstained.candidate()).isNull();
        assertThat(failed.timings().conclusionAt()).isEqualTo(NOW);
        assertThat(abstained.timings().conclusionAt()).isEqualTo(NOW);
        verify(store, never()).saveOrGet(anyLong(), any());
    }

    @Test
    void dangerousModelOutputIsRejectedBeforePersistence() {
        PlaybookDraftProposal unsafe = new PlaybookDraftProposal(
                false, "", "ERROR_CODE",
                new PlaybookDraft.ProposedSelector(
                        "CSDP", "message_send_failed", "903001"),
                "unsafe draft",
                List.of(step("locate_failed_request", "log_search")),
                List.of(new PlaybookDraft.Criterion(
                        "unsafe", "execute DQL L::raw logs",
                        List.of("log_search"), List.of("SYNTH-LOG-SEARCH"))),
                List.of(new PlaybookDraft.DiagnosisHypothesis(
                        "unsafe", "guess", List.of("SYNTH-TRACE-BUNDLE"))),
                List.of(new PlaybookDraft.HumanAction(
                        "restart_production", "kubectl delete pod",
                        "AUTOMATED_WRITE", List.of("SYNTH-TRACE-BUNDLE"))),
                List.of("SYNTH-LOG-SEARCH", "SYNTH-TRACE-BUNDLE",
                        "SYNTH-CONTRAST-SAMPLE"));
        when(inducer.induce(any())).thenReturn(accepted(unsafe));

        PlaybookSynthesisResult result = service.generate(1L, request());

        assertThat(result.stage()).isEqualTo(PlaybookSynthesisResult.Stage.VALIDATION_REJECTED);
        assertThat(result.rejectedDraft().validationErrors())
                .extracting(PlaybookDraft.ValidationError::code)
                .contains("ERROR_CODE_MODEL_GUESS", "DQL_OR_RAW_LOG_FORBIDDEN",
                        "ACTION_MODE_FORBIDDEN", "PRODUCTION_WRITE_FORBIDDEN");
        verify(store, never()).saveOrGet(anyLong(), any());
    }

    @Test
    void recordsConclusionOnlyAfterTheDeterministicValidatorHasFinished() {
        PlaybookDraftValidator gatedValidator = mock(PlaybookDraftValidator.class);
        AtomicBoolean validationFinished = new AtomicBoolean();
        when(gatedValidator.validate(any(), any())).thenAnswer(call -> {
            validationFinished.set(true);
            return new PlaybookDraftValidator.ValidationResult(true, List.of());
        });
        AtomicInteger clockCalls = new AtomicInteger();
        Clock gatedClock = mock(Clock.class);
        when(gatedClock.instant()).thenAnswer(call -> {
            int index = clockCalls.getAndIncrement();
            if (index > 0) {
                assertThat(validationFinished)
                        .as("conclusionAt must describe a validated readable conclusion")
                        .isTrue();
            }
            return index == 0 ? NOW.minusSeconds(1) : NOW;
        });
        when(inducer.induce(any())).thenReturn(accepted(validProposal(true)));
        SopSynthesisService gatedService = new SopSynthesisService(
                router, new DeterministicLogTraceCompressor(), gatedClock,
                inducer, gatedValidator, store);

        PlaybookSynthesisResult result = gatedService.generate(1L, request());

        assertThat(result.stage()).isEqualTo(PlaybookSynthesisResult.Stage.CANDIDATE_CREATED);
        assertThat(result.timings().conclusionAt()).isEqualTo(NOW);
    }

    private PlaybookSynthesisRequest request() {
        return new PlaybookSynthesisRequest(
                new SopSynthesisRequest(
                        "CSDP", "csdp-session-service", "message_send_failed", "-15m", NOW),
                "incident-message-send-001", REPORTED, READY);
    }

    private PlaybookDraftInducer.InductionResult accepted(PlaybookDraftProposal proposal) {
        return new PlaybookDraftInducer.InductionResult(
                PlaybookDraftInducer.Status.ACCEPTED, proposal, "",
                invocation(), List.of());
    }

    private PlaybookDraftInducer.ModelInvocation invocation() {
        return new PlaybookDraftInducer.ModelInvocation(
                "openai", "fixed-model", "7:model-config-v1", NOW, 1);
    }

    private PlaybookDraftProposal validProposal(boolean contrastAvailable) {
        List<PlaybookDraft.EvidencePlanStep> steps = new java.util.ArrayList<>(List.of(
                step("locate_failed_request", "log_search"),
                step("trace_ps_id", "log_trace_bundle")));
        List<String> citations = new java.util.ArrayList<>(List.of(
                "SYNTH-LOG-SEARCH", "SYNTH-TRACE-BUNDLE"));
        List<String> criterionKinds = new java.util.ArrayList<>(List.of("log_trace_bundle"));
        if (contrastAvailable) {
            steps.add(step("compare_success_sample", "contrast_sample"));
            citations.add("SYNTH-CONTRAST-SAMPLE");
            criterionKinds.add("contrast_sample");
        }
        steps.add(step("confirm_session_state_conflict", "log_trace_bundle"));
        return new PlaybookDraftProposal(
                false, "", "SCENARIO",
                new PlaybookDraft.ProposedSelector(
                        "CSDP", "message_send_failed", null),
                "会话消息发送失败排查草案",
                steps,
                List.of(new PlaybookDraft.Criterion(
                        "state_conflict", "状态并发写冲突只在失败样本中显著出现",
                        criterionKinds, citations)),
                List.of(new PlaybookDraft.DiagnosisHypothesis(
                        "session_state_conflict", "会话状态并发写冲突",
                        List.of("SYNTH-TRACE-BUNDLE"))),
                List.of(new PlaybookDraft.HumanAction(
                        "verify_recovery", "由值班研发在平台外验证新消息发送是否恢复",
                        "EXTERNAL_HUMAN", List.of("SYNTH-LOG-SEARCH"))),
                citations);
    }

    private PlaybookDraft.EvidencePlanStep step(String intent, String kind) {
        return new PlaybookDraft.EvidencePlanStep(intent, kind, "结构化排查步骤", true);
    }

    private EvidenceResult evidence(String signalKind, boolean contrastAvailable) {
        return switch (signalKind) {
            case "log_search" -> new EvidenceResult(
                    "SYNTH-LOG-SEARCH", "L", "", EvidenceStatus.ANOMALY,
                    "sample", Map.of(
                            "match_count", 4,
                            "ps_id", "synthetic-ps-message-send-001",
                            "sample_message", "message send failed"),
                    "recorded-replay:message-send-failed", NOW);
            case "log_trace_bundle" -> new EvidenceResult(
                    "SYNTH-TRACE-BUNDLE", "L", "", EvidenceStatus.ANOMALY,
                    "trace", Map.of(
                            "ps_id", "synthetic-ps-message-send-001",
                            "entries", List.of(
                                    entry(1000, "session-api", "INFO", "message accepted"),
                                    entry(1042, "session-state", "ERROR", "state conflict"),
                                    entry(1087, "session-api", "ERROR", "message send failed"))),
                    "recorded-replay:message-send-failed", NOW);
            case "contrast_sample" -> contrastAvailable
                    ? new EvidenceResult(
                            "SYNTH-CONTRAST-SAMPLE", "L", "", EvidenceStatus.NORMAL,
                            "control", Map.of(
                                    "discriminating_feature", "session_state_conflict",
                                    "failure_sample_count", 100,
                                    "failure_match_count", 92,
                                    "success_sample_count", 100,
                                    "success_match_count", 3),
                            "recorded-replay:message-send-failed", NOW)
                    : new EvidenceResult(
                            "SYNTH-CONTRAST-SAMPLE", "UNKNOWN", "", EvidenceStatus.MISSING,
                            "missing", Map.of(), "recorded-replay:missing", NOW);
            default -> throw new IllegalArgumentException(signalKind);
        };
    }

    private Map<String, Object> entry(long time, String service, String level, String message) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("timestamp", time);
        entry.put("service", service);
        entry.put("level", level);
        entry.put("message", message);
        return entry;
    }
}
