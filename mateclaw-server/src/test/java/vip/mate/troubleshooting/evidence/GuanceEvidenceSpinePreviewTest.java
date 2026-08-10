package vip.mate.troubleshooting.evidence;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GuanceEvidenceSpinePreviewTest {

    private static final Instant COLLECTED_AT = Instant.parse("2026-07-29T08:00:00Z");

    @Test
    void rejectsAFullSpineWithoutTheThreeCanonicalSteps() {
        assertThatThrownBy(() -> preview(
                GuanceEvidenceSpinePreview.Stage.FULL_SPINE_OBSERVED,
                validContrast(),
                List.of(step(
                        "log_search",
                        GuanceEvidenceSpinePreview.StepStatus.CANONICAL_RESULT_OBSERVED,
                        "T8-GUANCE-LOG-SEARCH",
                        COLLECTED_AT))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly three steps");
    }

    @Test
    void rejectsContrastRatesThatCannotBeReproducedFromCounts() {
        assertThatThrownBy(() -> new GuanceEvidenceSpinePreview.Contrast(
                true,
                "session_state_conflict",
                100,
                92,
                100,
                3,
                0.91,
                0.03,
                0.88))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reproducible");
    }

    @Test
    void rejectsUnsafeFeatureCodesBeforeTheyReachTheOperatorProjection() {
        assertThatThrownBy(() -> new GuanceEvidenceSpinePreview.Contrast(
                true,
                "content policy blocked",
                2,
                2,
                36,
                0,
                1.0,
                0.0,
                1.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contrast measurements");
    }

    @Test
    void rejectsCoreStageWhenContrastWasReportedAsObserved() {
        assertThatThrownBy(() -> preview(
                GuanceEvidenceSpinePreview.Stage.CORE_CHAIN_OBSERVED,
                GuanceEvidenceSpinePreview.Contrast.unavailable(),
                canonicalSteps()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("core-only spine");
    }

    @Test
    void rejectsAnEndToEndDurationShorterThanItsMeasuredWork() {
        assertThatThrownBy(() -> new GuanceEvidenceSpinePreview(
                GuanceEvidenceSpinePreview.Stage.FULL_SPINE_OBSERVED,
                readiness(),
                4L,
                "ps-message-001",
                3,
                List.of("gateway", "session-svc", "openim"),
                2,
                42L,
                validContrast(),
                3,
                9L,
                new EvidenceSpineTimings(1L, 2L, 3L, 4L),
                canonicalSteps(),
                COLLECTED_AT,
                List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shorter");
    }

    private GuanceEvidenceSpinePreview preview(
            GuanceEvidenceSpinePreview.Stage stage,
            GuanceEvidenceSpinePreview.Contrast contrast,
            List<GuanceEvidenceSpinePreview.Step> steps) {
        return new GuanceEvidenceSpinePreview(
                stage,
                readiness(),
                4L,
                "ps-message-001",
                3,
                List.of("gateway", "session-svc", "openim"),
                2,
                42L,
                contrast,
                3,
                50L,
                steps,
                COLLECTED_AT,
                List.of());
    }

    private List<GuanceEvidenceSpinePreview.Step> canonicalSteps() {
        return List.of(
                step(
                        "log_search",
                        GuanceEvidenceSpinePreview.StepStatus.CANONICAL_RESULT_OBSERVED,
                        "T8-GUANCE-LOG-SEARCH",
                        COLLECTED_AT),
                step(
                        "log_trace_bundle",
                        GuanceEvidenceSpinePreview.StepStatus.CANONICAL_RESULT_OBSERVED,
                        "T8-GUANCE-TRACE-BUNDLE",
                        COLLECTED_AT),
                step(
                        "contrast_sample",
                        GuanceEvidenceSpinePreview.StepStatus.CANONICAL_RESULT_OBSERVED,
                        "T8-GUANCE-CONTRAST-SAMPLE",
                        COLLECTED_AT));
    }

    private GuanceEvidenceSpinePreview.Step step(
            String signalKind,
            GuanceEvidenceSpinePreview.StepStatus status,
            String evidenceRef,
            Instant collectedAt) {
        return new GuanceEvidenceSpinePreview.Step(
                signalKind,
                status,
                evidenceRef,
                collectedAt);
    }

    private GuanceEvidenceSpinePreview.Contrast validContrast() {
        return new GuanceEvidenceSpinePreview.Contrast(
                true,
                "session_state_conflict",
                100,
                92,
                100,
                3,
                0.92,
                0.03,
                0.89);
    }

    private GuanceEvidenceReadiness readiness() {
        return new GuanceEvidenceReadiness(
                "CSDP",
                "session-svc",
                GuanceEvidenceReadiness.Status.READY_FOR_VALIDATION,
                true,
                true,
                GuanceEvidenceReadiness.CredentialState.CONFIGURED,
                true,
                List.of(),
                List.of());
    }
}
