package vip.mate.troubleshooting.synthesis;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.evidence.EvidenceSourceRouter;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.IncidentContext;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SopSynthesisServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-20T09:13:00Z");
    private final EvidenceSourceRouter router = mock(EvidenceSourceRouter.class);
    private final SopSynthesisService service = new SopSynthesisService(
            router,
            new DeterministicLogTraceCompressor(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void preparesTheNoErrorCodeP6CaseForModelSynthesisWithoutCreatingACandidate() {
        when(router.collect(any(), any(), eq(java.util.Set.of("recorded-replay"))))
                .thenReturn(searchEvidence(), traceEvidence(), contrastEvidence());

        SopSynthesisPreview preview = service.preview(
                1L,
                new SopSynthesisRequest(
                        "CSDP",
                        "csdp-session-service",
                        "message_send_failed",
                        "-15m",
                        NOW));

        assertThat(preview.stage()).isEqualTo(SopSynthesisPreview.Stage.READY_FOR_MODEL);
        assertThat(preview.system()).isEqualTo("CSDP");
        assertThat(preview.matchCount()).isEqualTo(4);
        assertThat(preview.psId()).isEqualTo("synthetic-ps-message-send-001");
        assertThat(preview.searchEvidence().queryId()).isEqualTo("SYNTH-LOG-SEARCH");
        assertThat(preview.traceEvidence().queryId()).isEqualTo("SYNTH-TRACE-BUNDLE");
        assertThat(preview.contrastEvidence().queryId()).isEqualTo("SYNTH-CONTRAST-SAMPLE");
        assertThat(preview.contrastAvailable()).isTrue();
        assertThat(preview.skeleton().contrast().rateDelta()).isEqualTo(0.89);
        assertThat(preview.skeleton().serviceSequence())
                .containsExactly("session-api", "session-state", "session-api");
        assertThat(preview.fixtureMode()).isTrue();
        assertThat(preview.warnings())
                .anyMatch(item -> item.contains("尚未调用模型") && item.contains("candidate"));

        ArgumentCaptor<EvidenceRequest> requests = ArgumentCaptor.forClass(EvidenceRequest.class);
        ArgumentCaptor<IncidentContext> incidents = ArgumentCaptor.forClass(IncidentContext.class);
        verify(router, times(3)).collect(
                requests.capture(), incidents.capture(), eq(java.util.Set.of("recorded-replay")));
        assertThat(requests.getAllValues())
                .extracting(EvidenceRequest::signalKind)
                .containsExactly("log_search", "log_trace_bundle", "contrast_sample");
        assertThat(requests.getAllValues().getFirst().target())
                .containsEntry("search_term", "message_send_failed");
        assertThat(requests.getAllValues().get(1).target())
                .containsEntry("ps_id", "synthetic-ps-message-send-001");
        assertThat(requests.getAllValues().getLast().target())
                .containsEntry("scenario_key", "message_send_failed")
                .containsEntry("exclude_ps_id", "synthetic-ps-message-send-001");
        assertThat(incidents.getAllValues())
                .allSatisfy(incident -> {
                    assertThat(incident.errorCode()).isNull();
                    assertThat(incident.service()).isEqualTo("csdp-session-service");
                });
    }

    @Test
    void stopsBeforeTraceCollectionWhenLogSearchIsMissing() {
        when(router.collect(any(), any(), eq(java.util.Set.of("recorded-replay"))))
                .thenReturn(new EvidenceResult(
                "SYNTH-LOG-SEARCH", "UNKNOWN", "", EvidenceStatus.MISSING,
                "not found", Map.of(), "recorded-replay:missing", NOW));

        assertThatThrownBy(() -> service.preview(1L, request()))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("log_search");

        verify(router, times(1)).collect(
                any(), any(), eq(java.util.Set.of("recorded-replay")));
    }

    @Test
    void rejectsAnUnsafeRawSearchTermBeforeItCanReachTheEvidenceRouter() {
        SopSynthesisRequest unsafe = new SopSynthesisRequest(
                "CSDP", "csdp-session-service", "message failed' OR true", "-15m", NOW);

        assertThatThrownBy(() -> service.preview(1L, unsafe))
                .isInstanceOf(MateClawException.class)
                .extracting(error -> ((MateClawException) error).getCode())
                .isEqualTo(400);

        verify(router, never()).collect(any(), any(), any());
    }

    @Test
    void rejectsASecretShapedIdentifierBeforeItCanReachTheEvidenceRouter() {
        SopSynthesisRequest unsafe = new SopSynthesisRequest(
                "CSDP", "csdp-session-service", "token:production-secret", "-15m", NOW);

        assertThatThrownBy(() -> service.preview(1L, unsafe))
                .isInstanceOf(MateClawException.class)
                .extracting(error -> ((MateClawException) error).getCode())
                .isEqualTo(400);

        verify(router, never()).collect(any(), any(), any());
    }

    @Test
    void rejectsAWorkspaceOutsideTheRegisteredFixtureScope() {
        assertThatThrownBy(() -> service.preview(2L, request()))
                .isInstanceOf(MateClawException.class)
                .extracting(error -> ((MateClawException) error).getCode())
                .isEqualTo(403);

        verify(router, never()).collect(any(), any(), any());
    }

    @Test
    void rejectsAServiceOutsideTheRegisteredFixtureScope() {
        SopSynthesisRequest otherService = new SopSynthesisRequest(
                "CSDP", "another-service", "message_send_failed", "-15m", NOW);

        assertThatThrownBy(() -> service.preview(1L, otherService))
                .isInstanceOf(MateClawException.class)
                .extracting(error -> ((MateClawException) error).getCode())
                .isEqualTo(403);

        verify(router, never()).collect(any(), any(), any());
    }

    @Test
    void rejectsAnUnboundedLookbackBeforeItCanReachTheEvidenceRouter() {
        SopSynthesisRequest unbounded = new SopSynthesisRequest(
                "CSDP", "csdp-session-service", "message_send_failed", "-25h", NOW);

        assertThatThrownBy(() -> service.preview(7L, unbounded))
                .isInstanceOf(MateClawException.class)
                .extracting(error -> ((MateClawException) error).getCode())
                .isEqualTo(400);

        verify(router, never()).collect(any(), any(), any());
    }

    @Test
    void rejectsAnOverflowingLookbackBeforeItCanReachTheEvidenceRouter() {
        SopSynthesisRequest overflowing = new SopSynthesisRequest(
                "CSDP", "csdp-session-service", "message_send_failed",
                "-999999999999999999999d", NOW);

        assertThatThrownBy(() -> service.preview(7L, overflowing))
                .isInstanceOf(MateClawException.class)
                .extracting(error -> ((MateClawException) error).getCode())
                .isEqualTo(400);

        verify(router, never()).collect(any(), any(), any());
    }

    @Test
    void rejectsAnEpochMillisecondOverflowBeforeItCanReachTheEvidenceRouter() {
        SopSynthesisRequest extreme = new SopSynthesisRequest(
                "CSDP", "csdp-session-service", "message_send_failed", "-15m",
                Instant.MAX);

        assertThatThrownBy(() -> service.preview(7L, extreme))
                .isInstanceOf(MateClawException.class)
                .extracting(error -> ((MateClawException) error).getCode())
                .isEqualTo(400);

        verify(router, never()).collect(any(), any(), any());
    }

    @Test
    void failsClosedWhenTheTraceBundlePsIdDoesNotMatchTheSearchSample() {
        EvidenceResult mismatched = traceEvidence("other-ps-id");
        when(router.collect(any(), any(), eq(java.util.Set.of("recorded-replay"))))
                .thenReturn(searchEvidence(), mismatched);

        assertThatThrownBy(() -> service.preview(1L, request()))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("PS ID");
    }

    @Test
    void missingContrastDegradesWithoutFailingThePreview() {
        when(router.collect(any(), any(), eq(java.util.Set.of("recorded-replay"))))
                .thenReturn(searchEvidence(), traceEvidence(), new EvidenceResult(
                        "SYNTH-CONTRAST-SAMPLE", "UNKNOWN", "", EvidenceStatus.MISSING,
                        "not found", Map.of(), "recorded-replay:missing", NOW));

        SopSynthesisPreview preview = service.preview(1L, request());

        assertThat(preview.contrastAvailable()).isFalse();
        assertThat(preview.contrastEvidence()).isNull();
        assertThat(preview.warnings()).anyMatch(item -> item.contains("校准期"));
    }

    private SopSynthesisRequest request() {
        return new SopSynthesisRequest(
                "CSDP", "csdp-session-service", "message_send_failed", "-15m", NOW);
    }

    private EvidenceResult searchEvidence() {
        return new EvidenceResult(
                "SYNTH-LOG-SEARCH", "L", "", EvidenceStatus.ANOMALY,
                "message send failed sample",
                Map.of(
                        "match_count", 4,
                        "ps_id", "synthetic-ps-message-send-001",
                        "sample_message", "message send failed after session state conflict"),
                "recorded-replay:message-send-failed", NOW);
    }

    private EvidenceResult traceEvidence() {
        return traceEvidence("synthetic-ps-message-send-001");
    }

    private EvidenceResult traceEvidence(String psId) {
        return new EvidenceResult(
                "SYNTH-TRACE-BUNDLE", "L", "", EvidenceStatus.ANOMALY,
                "PS ID full trace",
                Map.of(
                        "ps_id", psId,
                        "entries", List.of(
                                entry(1753002781000L, "session-api", "INFO", "message accepted"),
                                entry(1753002781042L, "session-state", "ERROR",
                                        "concurrent state write rejected"),
                                entry(1753002781087L, "session-api", "ERROR",
                                        "message send failed"))),
                "recorded-replay:message-send-failed", NOW);
    }

    private EvidenceResult contrastEvidence() {
        return new EvidenceResult(
                "SYNTH-CONTRAST-SAMPLE", "L", "", EvidenceStatus.NORMAL,
                "same-window success comparison",
                Map.of(
                        "discriminating_feature", "session_state_conflict",
                        "failure_sample_count", 100,
                        "failure_match_count", 92,
                        "success_sample_count", 100,
                        "success_match_count", 3),
                "recorded-replay:message-send-failed", NOW);
    }

    private Map<String, Object> entry(
            long timestamp, String source, String level, String message) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("timestamp", timestamp);
        entry.put("service", source);
        entry.put("level", level);
        entry.put("message", message);
        return entry;
    }
}
