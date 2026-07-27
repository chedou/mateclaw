package vip.mate.troubleshooting.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import vip.mate.troubleshooting.synthesis.DeterministicLogTraceCompressor;
import vip.mate.troubleshooting.synthesis.SopSynthesisPreview;
import vip.mate.troubleshooting.synthesis.SopSynthesisRequest;
import vip.mate.troubleshooting.synthesis.SopSynthesisService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** P6 fixture proof for the real router -> replay -> compressor composition. */
class SopSynthesisReplayTest {

    private static final Instant NOW = Instant.parse("2026-07-20T09:13:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void preparesTheMeetingSpecifiedMessageSendFailureFromTheBundledReplay() {
        SopSynthesisService service = service();

        SopSynthesisPreview preview = service.preview(
                1L,
                new SopSynthesisRequest(
                        "CSDP",
                        "csdp-session-service",
                        "message_send_failed",
                        "-15m",
                        NOW));

        assertThat(preview.stage()).isEqualTo(SopSynthesisPreview.Stage.READY_FOR_MODEL);
        assertThat(preview.matchCount()).isEqualTo(4);
        assertThat(preview.psId()).isEqualTo("synthetic-ps-message-send-001");
        assertThat(preview.searchEvidence().source())
                .isEqualTo("recorded-replay:message-send-failed");
        assertThat(preview.skeleton().serviceSequence())
                .containsExactly("session-api", "session-state", "session-api");
        assertThat(preview.skeleton().anomalySequenceIndexes()).containsExactly(1, 2);
    }

    @Test
    void doesNotReuseTheP6ReplayForAnotherSafeKeyword() {
        SopSynthesisService service = service();

        assertThatThrownBy(() -> service.preview(
                1L,
                new SopSynthesisRequest(
                        "CSDP",
                        "csdp-session-service",
                        "unrelated_safe_keyword",
                        "-15m",
                        NOW)))
                .hasMessageContaining("log_search");
    }

    private SopSynthesisService service() {
        EvidenceProperties.RecordedReplay replayConfig = new EvidenceProperties.RecordedReplay();
        replayConfig.setEnabled(true);
        RecordedReplayAdapter replay = new RecordedReplayAdapter(
                replayConfig,
                new ObjectMapper(),
                new ClassPathResource("troubleshooting/evidence/recorded-replay-903001.json"),
                CLOCK);
        EvidenceProperties properties = new EvidenceProperties();
        properties.setRoutes(Map.of(
                "CSDP",
                Map.of(
                        "log_search", List.of("recorded-replay"),
                        "log_trace_bundle", List.of("recorded-replay"))));
        return new SopSynthesisService(
                new EvidenceSourceRouter(List.of(replay), properties, CLOCK),
                new DeterministicLogTraceCompressor());
    }
}
