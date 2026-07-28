package vip.mate.troubleshooting.synthesis;

import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.TroubleshootingSecretRedactor;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeterministicLogTraceCompressorTest {

    private static final Instant NOW = Instant.parse("2026-07-20T09:13:02Z");
    private final DeterministicLogTraceCompressor compressor =
            new DeterministicLogTraceCompressor();

    @Test
    void compressesTheP6TraceIntoAChronologicalCallChainSkeleton() {
        LogTraceSkeleton skeleton = compressor.compress(bundle(List.of(
                entry(1753002781000L, "session-api", "INFO", "message accepted", null),
                entry(1753002781042L, "session-state", "ERROR",
                        "concurrent state write rejected", 42),
                entry(1753002781087L, "session-api", "ERROR",
                        "message send failed", 87))), contrast());

        assertThat(skeleton.psId()).isEqualTo("synthetic-ps-message-send-001");
        assertThat(skeleton.startedAtEpochMs()).isEqualTo(1753002781000L);
        assertThat(skeleton.endedAtEpochMs()).isEqualTo(1753002781087L);
        assertThat(skeleton.elapsedMs()).isEqualTo(87L);
        assertThat(skeleton.serviceSequence())
                .containsExactly("session-api", "session-state", "session-api");
        assertThat(skeleton.timeline())
                .extracting(LogTraceSkeleton.TimelineEvent::offsetMs)
                .containsExactly(0L, 42L, 87L);
        assertThat(skeleton.timeline())
                .extracting(LogTraceSkeleton.TimelineEvent::anomalous)
                .containsExactly(false, true, true);
        assertThat(skeleton.anomalySequenceIndexes()).containsExactly(1, 2);
        assertThat(skeleton.durationByService().get("session-state"))
                .isEqualTo(new LogTraceSkeleton.DurationSummary(1, 42, 42, 42));
        assertThat(skeleton.durationByService().get("session-api"))
                .isEqualTo(new LogTraceSkeleton.DurationSummary(1, 87, 87, 87));
        assertThat(skeleton.sourceEntryCount()).isEqualTo(3);
        assertThat(skeleton.omittedEntryCount()).isZero();
        assertThat(skeleton.contrast().available()).isTrue();
        assertThat(skeleton.contrast().discriminatingFeature())
                .isEqualTo("session_state_conflict");
        assertThat(skeleton.contrast().failureRate()).isEqualTo(0.92);
        assertThat(skeleton.contrast().successRate()).isEqualTo(0.03);
        assertThat(skeleton.contrast().rateDelta()).isEqualTo(0.89);
    }

    @Test
    void degradesToAnUnavailableContrastWithoutFailingTraceCompression() {
        LogTraceSkeleton skeleton = compressor.compress(
                bundle(List.of(entry(1L, "session-api", "ERROR", "failed", 1))),
                new EvidenceResult(
                        "SYNTH-CONTRAST-SAMPLE", "UNKNOWN", "", EvidenceStatus.MISSING,
                        "not found", Map.of(), "recorded-replay:missing", NOW));

        assertThat(skeleton.contrast().available()).isFalse();
        assertThat(skeleton.contrast().rateDelta()).isZero();
    }

    @Test
    void rejectsMathematicallyImpossibleContrastCounts() {
        EvidenceResult invalid = new EvidenceResult(
                "SYNTH-CONTRAST-SAMPLE", "L", "", EvidenceStatus.NORMAL,
                "invalid control", Map.of(
                        "discriminating_feature", "session_state_conflict",
                        "failure_sample_count", 100,
                        "failure_match_count", 101,
                        "success_sample_count", 100,
                        "success_match_count", 3),
                "recorded-replay:message-send-failed", NOW);

        assertThatThrownBy(() -> compressor.compress(
                bundle(List.of(entry(1L, "session-api", "ERROR", "failed", 1))), invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contrast");
    }

    @Test
    void redactsNestedLogMessagesBeforeTheyEnterTheSkeleton() {
        LogTraceSkeleton skeleton = compressor.compress(bundle(List.of(
                entry(1L, "session-api", "ERROR",
                        "Authorization: Bearer production-secret", 1))));

        assertThat(skeleton.timeline().getFirst().message())
                .contains(TroubleshootingSecretRedactor.REDACTED)
                .doesNotContain("production-secret");
    }

    @Test
    void deterministicallyBoundsTheTimelineWhileKeepingTheFirstAndLastEntry() {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int index = 0; index < 70; index++) {
            entries.add(entry(1_000L + index, "session-api", "INFO",
                    "normal event " + index, index));
        }

        LogTraceSkeleton skeleton = compressor.compress(bundle(entries));

        assertThat(skeleton.timeline()).hasSize(64);
        assertThat(skeleton.timeline().getFirst().sequenceIndex()).isZero();
        assertThat(skeleton.timeline().getLast().sequenceIndex()).isEqualTo(69);
        assertThat(skeleton.omittedEntryCount()).isEqualTo(6);
    }

    @Test
    void failsClosedWhenAnomalyPointsCannotFitTheBoundedSkeleton() {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int index = 0; index < 65; index++) {
            entries.add(entry(1_000L + index, "session-api", "ERROR",
                    "failed event " + index, index));
        }

        assertThatThrownBy(() -> compressor.compress(bundle(entries)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("anomaly events");
    }

    @Test
    void detectsAnomalyTermsBeforeTheModelVisibleMessageIsTruncated() {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int index = 0; index < 70; index++) {
            String message = index == 35
                    ? "x".repeat(300) + " timeout after downstream call"
                    : "normal event " + index;
            entries.add(entry(1_000L + index, "session-api", "INFO", message, index));
        }

        LogTraceSkeleton skeleton = compressor.compress(bundle(entries));

        assertThat(skeleton.anomalySequenceIndexes()).containsExactly(35);
        assertThat(skeleton.timeline())
                .filteredOn(event -> event.sequenceIndex() == 35)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.anomalous()).isTrue();
                    assertThat(event.message()).endsWith("...[TRUNCATED]");
                });
    }

    @Test
    void rejectsAnOversizedRawMessageBeforeRedaction() {
        List<Map<String, Object>> entries = List.of(
                entry(1L, "session-api", "INFO", " ".repeat(8_193) + "x", 1));

        assertThatThrownBy(() -> compressor.compress(bundle(entries)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("raw message");
    }

    @Test
    void rejectsAnOversizedRawTraceBeforeRedaction() {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int index = 0; index < 33; index++) {
            entries.add(entry(1_000L + index, "session-api", "INFO",
                    " ".repeat(4_095) + "x", index));
        }

        assertThatThrownBy(() -> compressor.compress(bundle(entries)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("total raw character");
    }

    private EvidenceResult bundle(List<Map<String, Object>> entries) {
        return new EvidenceResult(
                "SYNTH-TRACE-BUNDLE",
                "L",
                "",
                EvidenceStatus.ANOMALY,
                "PS ID full trace",
                Map.of(
                        "ps_id", "synthetic-ps-message-send-001",
                        "entries", entries),
                "recorded-replay:message-send-failed",
                NOW);
    }

    private EvidenceResult contrast() {
        return new EvidenceResult(
                "SYNTH-CONTRAST-SAMPLE", "L", "", EvidenceStatus.NORMAL,
                "same-window successful request comparison",
                Map.of(
                        "discriminating_feature", "session_state_conflict",
                        "failure_sample_count", 100,
                        "failure_match_count", 92,
                        "success_sample_count", 100,
                        "success_match_count", 3),
                "recorded-replay:message-send-failed", NOW);
    }

    private Map<String, Object> entry(
            long timestamp,
            String service,
            String level,
            String message,
            Number durationMs) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("timestamp", timestamp);
        entry.put("service", service);
        entry.put("level", level);
        entry.put("message", message);
        if (durationMs != null) {
            entry.put("duration_ms", durationMs);
        }
        return entry;
    }
}
