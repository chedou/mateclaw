package vip.mate.troubleshooting.synthesis;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReferenceSolutionComparatorTest {

    private final ReferenceSolutionComparator comparator = new ReferenceSolutionComparator();

    @Test
    void comparesStructuredIntentsOrderingAndEvidenceKindsInsteadOfTextSimilarity() {
        ReferenceSolutionComparator.Comparison report = comparator.compare(
                completeDraft(), ReferenceSolution.messageSendFailure());

        assertThat(report.passed()).isTrue();
        assertThat(report.requiredIntentCoverage()).isEqualTo(1.0);
        assertThat(report.missingStepIntents()).isEmpty();
        assertThat(report.forbiddenStepIntentsPresent()).isEmpty();
        assertThat(report.orderingViolations()).isEmpty();
        assertThat(report.missingEvidenceKinds()).isEmpty();
    }

    @Test
    void explainsEveryDeltaAndDangerousIntent() {
        PlaybookDraft incomplete = draft(
                List.of(
                        step("trace_ps_id", "log_trace_bundle"),
                        step("locate_failed_request", "log_search")),
                List.of(action("restart_production")));

        ReferenceSolutionComparator.Comparison report = comparator.compare(
                incomplete, ReferenceSolution.messageSendFailure());

        assertThat(report.passed()).isFalse();
        assertThat(report.missingStepIntents()).contains("compare_success_sample", "verify_recovery");
        assertThat(report.forbiddenStepIntentsPresent()).contains("restart_production");
        assertThat(report.orderingViolations()).contains("locate_failed_request -> trace_ps_id");
        assertThat(report.missingEvidenceKinds()).contains("contrast_sample");
    }

    private PlaybookDraft completeDraft() {
        return draft(
                List.of(
                        step("locate_failed_request", "log_search"),
                        step("trace_ps_id", "log_trace_bundle"),
                        step("compare_success_sample", "contrast_sample"),
                        step("confirm_session_state_conflict", "log_trace_bundle")),
                List.of(action("verify_recovery")));
    }

    private PlaybookDraft draft(
            List<PlaybookDraft.EvidencePlanStep> steps,
            List<PlaybookDraft.HumanAction> actions) {
        return new PlaybookDraft(
                "draft-1", "generation-1", "incident-1", "SCENARIO",
                new PlaybookDraft.ProposedSelector("CSDP", "message_send_failed", null),
                "任意不同措辞也不影响结构化比较", steps,
                List.of(new PlaybookDraft.Criterion(
                        "state_conflict", "状态冲突", List.of("log_trace_bundle"),
                        List.of("SYNTH-TRACE-BUNDLE"))),
                List.of(new PlaybookDraft.DiagnosisHypothesis(
                        "session_state_conflict", "状态冲突",
                        List.of("SYNTH-TRACE-BUNDLE"))),
                actions,
                List.of("SYNTH-LOG-SEARCH", "SYNTH-TRACE-BUNDLE", "SYNTH-CONTRAST-SAMPLE"),
                new PlaybookDraft.ModelProvenance(
                        "openai", "fixed", "v1", PlaybookDraft.CONTRACT_VERSION,
                        Instant.parse("2026-07-20T09:13:03Z"), 1),
                true, List.of());
    }

    private PlaybookDraft.EvidencePlanStep step(String intent, String kind) {
        return new PlaybookDraft.EvidencePlanStep(intent, kind, "结构化步骤", true);
    }

    private PlaybookDraft.HumanAction action(String intent) {
        return new PlaybookDraft.HumanAction(
                intent, "由研发在平台外执行", "EXTERNAL_HUMAN",
                List.of("SYNTH-TRACE-BUNDLE"));
    }
}
