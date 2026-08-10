package vip.mate.troubleshooting.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.evidence.GuanceEvidenceReadiness;
import vip.mate.troubleshooting.evidence.GuanceEvidenceSpineObservation;
import vip.mate.troubleshooting.evidence.GuanceEvidenceSpinePreview;
import vip.mate.troubleshooting.synthesis.LogTraceSkeleton;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluationModelInputFactoryTest {

    private static final Instant NOW = Instant.parse("2026-07-29T08:00:00Z");

    private final EvaluationModelInputFactory factory = new EvaluationModelInputFactory(
            new ObjectMapper().findAndRegisterModules());

    @Test
    void fingerprintsTheExactBoundedModelInputWithoutPersistingLookupMaterial() {
        EvaluationModelInputFactory.FingerprintedInput result = factory.create(
                "CSDP",
                "session-svc",
                "message_send_failed",
                observation("state conflict after optimistic update"));

        assertThat(result.input().evidence())
                .extracting(descriptor -> descriptor.evidenceId() + ":" + descriptor.signalKind())
                .containsExactly(
                        "T8-GUANCE-LOG-SEARCH:log_search",
                        "T8-GUANCE-TRACE-BUNDLE:log_trace_bundle",
                        "T8-GUANCE-CONTRAST-SAMPLE:contrast_sample");
        assertThat(result.input().traceSkeleton().timeline().get(1).message())
                .isEqualTo("state conflict after optimistic update");
        assertThat(result.fingerprint()).matches("[a-f0-9]{64}");
        assertThat(result.toString())
                .doesNotContain("source_lookup_key", "-15m", "runtime-secret", "L::logs");
    }

    @Test
    void changesTheFingerprintWhenTheModelVisibleSkeletonChanges() {
        EvaluationModelInputFactory.FingerprintedInput first = factory.create(
                "CSDP", "session-svc", "message_send_failed", observation("conflict A"));
        EvaluationModelInputFactory.FingerprintedInput second = factory.create(
                "CSDP", "session-svc", "message_send_failed", observation("conflict B"));

        assertThat(first.fingerprint()).isNotEqualTo(second.fingerprint());
    }

    @Test
    void refusesBlockedOrSkeletonFreeObservations() {
        GuanceEvidenceSpinePreview blocked = new GuanceEvidenceSpinePreview(
                GuanceEvidenceSpinePreview.Stage.BLOCKED,
                readiness(),
                null,
                null,
                null,
                List.of(),
                0,
                null,
                GuanceEvidenceSpinePreview.Contrast.unavailable(),
                0,
                4L,
                List.of(
                        notRun("log_search", "T8-GUANCE-LOG-SEARCH"),
                        notRun("log_trace_bundle", "T8-GUANCE-TRACE-BUNDLE"),
                        notRun("contrast_sample", "T8-GUANCE-CONTRAST-SAMPLE")),
                NOW,
                List.of("blocked"));

        assertThatThrownBy(() -> factory.create(
                "CSDP",
                "session-svc",
                "message_send_failed",
                new GuanceEvidenceSpineObservation(blocked, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("observed Evidence Spine");
    }

    private GuanceEvidenceSpineObservation observation(String anomalyMessage) {
        LogTraceSkeleton skeleton = new LogTraceSkeleton(
                "ps-message-001",
                1_000,
                1_042,
                42,
                List.of("gateway", "session-svc"),
                List.of(
                        new LogTraceSkeleton.TimelineEvent(
                                0, 0, "gateway", "INFO", "request accepted", 1.0, false),
                        new LogTraceSkeleton.TimelineEvent(
                                1, 42, "session-svc", "ERROR", anomalyMessage, 42.0, true)),
                List.of(1),
                Map.of("session-svc", new LogTraceSkeleton.DurationSummary(1, 42, 42, 42)),
                2,
                0,
                new LogTraceSkeleton.ContrastSummary(
                        true, "state_conflict", 100, 92, 100, 3, 0.92, 0.03, 0.89));
        GuanceEvidenceSpinePreview preview = new GuanceEvidenceSpinePreview(
                GuanceEvidenceSpinePreview.Stage.FULL_SPINE_OBSERVED,
                readiness(),
                4L,
                "ps-message-001",
                2,
                List.of("gateway", "session-svc"),
                1,
                42L,
                new GuanceEvidenceSpinePreview.Contrast(
                        true, "session_state_conflict",
                        100, 92, 100, 3, 0.92, 0.03, 0.89),
                3,
                50L,
                List.of(
                        observed("log_search", "T8-GUANCE-LOG-SEARCH"),
                        observed("log_trace_bundle", "T8-GUANCE-TRACE-BUNDLE"),
                        observed("contrast_sample", "T8-GUANCE-CONTRAST-SAMPLE")),
                NOW,
                List.of());
        return new GuanceEvidenceSpineObservation(preview, skeleton);
    }

    private GuanceEvidenceReadiness readiness() {
        return new GuanceEvidenceReadiness(
                "CSDP",
                "session-svc",
                GuanceEvidenceReadiness.Status.CANONICAL_SIGNALS_OBSERVED,
                true,
                true,
                GuanceEvidenceReadiness.CredentialState.CONFIGURED,
                true,
                List.of(),
                List.of());
    }

    private GuanceEvidenceSpinePreview.Step observed(String signalKind, String evidenceRef) {
        return new GuanceEvidenceSpinePreview.Step(
                signalKind,
                GuanceEvidenceSpinePreview.StepStatus.CANONICAL_RESULT_OBSERVED,
                evidenceRef,
                NOW);
    }

    private GuanceEvidenceSpinePreview.Step notRun(String signalKind, String evidenceRef) {
        return new GuanceEvidenceSpinePreview.Step(
                signalKind,
                GuanceEvidenceSpinePreview.StepStatus.NOT_RUN,
                evidenceRef,
                null);
    }
}
