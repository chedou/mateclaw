package vip.mate.troubleshooting.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.evidence.GuanceEvidenceReadiness;
import vip.mate.troubleshooting.evidence.GuanceEvidenceSpinePreview;
import vip.mate.troubleshooting.model.ClosureOutcome;
import vip.mate.troubleshooting.synthesis.ReferenceSolution;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvidenceEvaluationSampleTest {

    private static final Instant NOW = Instant.parse("2026-07-29T08:00:00Z");

    @Test
    void separatesRealGuanceEvidenceFromTheLinkedFixtureDiagnosis() {
        EvidenceEvaluationSample sample = captured(true);

        assertThat(sample.sourcePlatform())
                .isEqualTo(EvidenceEvaluationSample.SourcePlatform.GUANCE);
        assertThat(sample.evidence().fixtureMode()).isFalse();
        assertThat(sample.diagnosisFixtureMode()).isTrue();
        assertThat(sample.referenceStatus())
                .isEqualTo(EvidenceEvaluationSample.ReferenceStatus.EVIDENCE_CAPTURED);
        assertThat(sample.referenceSolution()).isNull();
        assertThat(sample.outcome()).isNull();
    }

    @Test
    void finalizesOnlyWithReferenceAndAuthoritativeOutcome() {
        EvidenceEvaluationSample captured = captured(false);
        ReferenceSolution reference = reference(captured.sampleId());
        EvidenceEvaluationSample.OutcomeSnapshot outcome =
                new EvidenceEvaluationSample.OutcomeSnapshot(
                        ClosureOutcome.RECOVERED,
                        "人工扩容连接池后恢复",
                        true,
                        NOW.plusSeconds(30));

        EvidenceEvaluationSample finalized = captured.finalizeReference(
                reference, outcome, "reviewer@example.com", NOW.plusSeconds(40));

        assertThat(finalized.referenceStatus())
                .isEqualTo(EvidenceEvaluationSample.ReferenceStatus.READY_FOR_EVALUATION);
        assertThat(finalized.version()).isEqualTo(1);
        assertThat(finalized.referenceSolution()).isEqualTo(reference);
        assertThat(finalized.outcome()).isEqualTo(outcome);
        assertThat(finalized.finalizedBy()).isEqualTo("reviewer@example.com");
    }

    @Test
    void refusesBlockedOrReplayShapedGuanceSamples() {
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
                        step("log_search", "T8-GUANCE-LOG-SEARCH"),
                        step("log_trace_bundle", "T8-GUANCE-TRACE-BUNDLE"),
                        step("contrast_sample", "T8-GUANCE-CONTRAST-SAMPLE")),
                NOW,
                List.of("blocked"));

        assertThatThrownBy(() -> EvidenceEvaluationSample.captured(
                "eval-1", "1".repeat(64), "diag-1", "CSDP", "session-svc",
                "message_send_failed", blocked, false, "admin", NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("observed Guance evidence");

        EvidenceEvaluationSample sample = captured(false);
        assertThatThrownBy(() -> new EvidenceEvaluationSample(
                sample.sampleId(), sample.sampleKey(), sample.diagnosisId(),
                sample.system(), sample.service(), sample.scenarioKey(),
                EvidenceEvaluationSample.SourcePlatform.GUANCE,
                new EvidenceEvaluationSample.EvidenceSnapshot(
                        sample.evidence().stage(),
                        true,
                        sample.evidence().matchCount(),
                        sample.evidence().psId(),
                        sample.evidence().traceEntries(),
                        sample.evidence().serviceSequence(),
                        sample.evidence().anomalyCount(),
                        sample.evidence().traceElapsedMs(),
                        sample.evidence().contrast(),
                        sample.evidence().sourceRequestCount(),
                        sample.evidence().totalDurationMs(),
                        sample.evidence().steps(),
                        sample.evidence().completedAt()),
                sample.diagnosisFixtureMode(),
                sample.referenceStatus(), sample.referenceSolution(), sample.outcome(),
                sample.version(), sample.capturedBy(), sample.finalizedBy(),
                sample.capturedAt(), sample.finalizedAt()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Guance evidence cannot be fixture");

        assertThatThrownBy(() -> new EvidenceEvaluationSample(
                sample.sampleId(), sample.sampleKey(), sample.diagnosisId(),
                sample.system(), sample.service(), sample.scenarioKey(),
                EvidenceEvaluationSample.SourcePlatform.RECORDED_REPLAY,
                sample.evidence(),
                sample.diagnosisFixtureMode(),
                sample.referenceStatus(), sample.referenceSolution(), sample.outcome(),
                sample.version(), sample.capturedBy(), sample.finalizedBy(),
                sample.capturedAt(), sample.finalizedAt()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Recorded Replay evidence must remain fixture");
    }

    @Test
    void refusesMissingAuthoritativeTimestampsAndTamperedEvidenceShape() {
        EvidenceEvaluationSample sample = captured(false);

        assertThatThrownBy(() -> sample.finalizeReference(
                reference(sample.sampleId()),
                new EvidenceEvaluationSample.OutcomeSnapshot(
                        ClosureOutcome.RECOVERED,
                        "人工扩容连接池后恢复",
                        true,
                        NOW),
                "reviewer@example.com",
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finalizedAt");

        assertThatThrownBy(() -> new EvidenceEvaluationSample.OutcomeSnapshot(
                ClosureOutcome.RECOVERED,
                "人工扩容连接池后恢复",
                true,
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("closedAt");

        assertThatThrownBy(() -> new EvidenceEvaluationSample.OutcomeSnapshot(
                ClosureOutcome.RECOVERED,
                "人工扩容连接池后恢复",
                false,
                NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recovery verification");

        List<EvidenceEvaluationSample.StepSnapshot> reordered = List.of(
                new EvidenceEvaluationSample.StepSnapshot(
                        "log_trace_bundle",
                        GuanceEvidenceSpinePreview.StepStatus.CANONICAL_RESULT_OBSERVED,
                        "T8-GUANCE-TRACE-BUNDLE",
                        NOW),
                new EvidenceEvaluationSample.StepSnapshot(
                        "log_search",
                        GuanceEvidenceSpinePreview.StepStatus.CANONICAL_RESULT_OBSERVED,
                        "T8-GUANCE-LOG-SEARCH",
                        NOW),
                new EvidenceEvaluationSample.StepSnapshot(
                        "contrast_sample",
                        GuanceEvidenceSpinePreview.StepStatus.CANONICAL_RESULT_OBSERVED,
                        "T8-GUANCE-CONTRAST-SAMPLE",
                        NOW));

        assertThatThrownBy(() -> new EvidenceEvaluationSample.EvidenceSnapshot(
                sample.evidence().stage(),
                false,
                sample.evidence().matchCount(),
                sample.evidence().psId(),
                sample.evidence().traceEntries(),
                sample.evidence().serviceSequence(),
                sample.evidence().anomalyCount(),
                sample.evidence().traceElapsedMs(),
                sample.evidence().contrast(),
                sample.evidence().sourceRequestCount(),
                sample.evidence().totalDurationMs(),
                reordered,
                NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("step order");
    }

    @Test
    void readsV181SamplesThatPredateStageTimings() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ObjectNode legacy = (ObjectNode) objectMapper.valueToTree(captured(false));
        ((ObjectNode) legacy.get("evidence")).remove("timings");

        EvidenceEvaluationSample restored = objectMapper.treeToValue(
                legacy, EvidenceEvaluationSample.class);

        assertThat(restored.evidence().timings())
                .isEqualTo(vip.mate.troubleshooting.evidence.EvidenceSpineTimings.unmeasured());
    }

    private EvidenceEvaluationSample captured(boolean diagnosisFixtureMode) {
        return EvidenceEvaluationSample.captured(
                "eval-012345678901234567890123",
                "a".repeat(64),
                "diag-1",
                "CSDP",
                "session-svc",
                "message_send_failed",
                fullPreview(),
                diagnosisFixtureMode,
                "admin@example.com",
                NOW);
    }

    private ReferenceSolution reference(String sampleId) {
        return new ReferenceSolution(
                sampleId + "/reference/v1",
                "message_send_failed",
                List.of("locate_failed_request", "trace_ps_id", "verify_recovery"),
                List.of("restart_production"),
                List.of(
                        new ReferenceSolution.OrderingConstraint(
                                "locate_failed_request", "trace_ps_id"),
                        new ReferenceSolution.OrderingConstraint(
                                "trace_ps_id", "verify_recovery")),
                List.of("log_search", "log_trace_bundle", "contrast_sample"));
    }

    private GuanceEvidenceSpinePreview fullPreview() {
        return new GuanceEvidenceSpinePreview(
                GuanceEvidenceSpinePreview.Stage.FULL_SPINE_OBSERVED,
                readiness(),
                4L,
                "ps-message-001",
                3,
                List.of("gateway", "session-svc", "openim"),
                2,
                42L,
                new GuanceEvidenceSpinePreview.Contrast(
                        true, 100, 92, 100, 3, 0.92, 0.03, 0.89),
                3,
                50L,
                List.of(
                        observed("log_search", "T8-GUANCE-LOG-SEARCH"),
                        observed("log_trace_bundle", "T8-GUANCE-TRACE-BUNDLE"),
                        observed("contrast_sample", "T8-GUANCE-CONTRAST-SAMPLE")),
                NOW,
                List.of("待 T7/T8 验收"));
    }

    private GuanceEvidenceReadiness readiness() {
        return new GuanceEvidenceReadiness(
                "CSDP", "session-svc",
                GuanceEvidenceReadiness.Status.READY_FOR_VALIDATION,
                true, true,
                GuanceEvidenceReadiness.CredentialState.CONFIGURED,
                true, List.of(), List.of());
    }

    private GuanceEvidenceSpinePreview.Step observed(String signalKind, String evidenceRef) {
        return new GuanceEvidenceSpinePreview.Step(
                signalKind,
                GuanceEvidenceSpinePreview.StepStatus.CANONICAL_RESULT_OBSERVED,
                evidenceRef,
                NOW);
    }

    private GuanceEvidenceSpinePreview.Step step(String signalKind, String evidenceRef) {
        return new GuanceEvidenceSpinePreview.Step(
                signalKind,
                GuanceEvidenceSpinePreview.StepStatus.NOT_RUN,
                evidenceRef,
                null);
    }
}
