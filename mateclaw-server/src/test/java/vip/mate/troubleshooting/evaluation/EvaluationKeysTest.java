package vip.mate.troubleshooting.evaluation;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationKeysTest {

    @Test
    void currentContractDoesNotReuseLegacyOrCrossSourceSampleKeys() {
        Instant occurredAt = Instant.parse("2026-07-29T08:00:00Z");
        String legacy = EvaluationKeys.hash(7L
                + "\u001fdiag-1"
                + "\u001fmessage_send_failed"
                + "\u001fsource_lookup_key"
                + "\u001f-15m"
                + "\u001f" + occurredAt);
        String guance = EvaluationKeys.sampleKey(
                7L,
                "diag-1",
                "message_send_failed",
                EvidenceEvaluationSample.SourcePlatform.GUANCE,
                "source_lookup_key",
                "-15m",
                occurredAt);
        String replay = EvaluationKeys.sampleKey(
                7L,
                "diag-1",
                "message_send_failed",
                EvidenceEvaluationSample.SourcePlatform.RECORDED_REPLAY,
                "source_lookup_key",
                "-15m",
                occurredAt);

        assertThat(guance).isNotEqualTo(legacy);
        assertThat(replay).isNotEqualTo(legacy).isNotEqualTo(guance);
    }

    @Test
    void recaptureRevisionKeepsTheLookupIdentityButGetsANewImmutableSampleKey() {
        Instant occurredAt = Instant.parse("2026-07-29T08:00:00Z");
        String identity = EvaluationKeys.captureIdentityKey(
                7L,
                "diag-1",
                "message_send_failed",
                EvidenceEvaluationSample.SourcePlatform.GUANCE,
                "source_lookup_key",
                "-15m",
                occurredAt);

        String revisionOne = EvaluationKeys.sampleKey(
                7L, "diag-1", "message_send_failed",
                EvidenceEvaluationSample.SourcePlatform.GUANCE,
                "source_lookup_key", "-15m", occurredAt, 1);
        String revisionTwo = EvaluationKeys.sampleKey(
                7L, "diag-1", "message_send_failed",
                EvidenceEvaluationSample.SourcePlatform.GUANCE,
                "source_lookup_key", "-15m", occurredAt, 2);

        assertThat(revisionOne).isEqualTo(identity);
        assertThat(revisionTwo).matches("[a-f0-9]{64}").isNotEqualTo(identity);
        assertThat(EvaluationKeys.sampleKey(
                7L, "diag-1", "message_send_failed",
                EvidenceEvaluationSample.SourcePlatform.GUANCE,
                "source_lookup_key", "-15m", occurredAt, 2))
                .isEqualTo(revisionTwo);
    }
}
