package vip.mate.troubleshooting.synthesis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Locks the recorded model response the demo learning loop depends on.
 *
 * <p>The recording replaces exactly one step — what the model said. Everything
 * downstream still runs, which means a drifted recording does not fail loudly:
 * it turns into {@code VALIDATION_REJECTED}, and an operator sees "草稿被校验
 * 拒绝" with no way to tell a genuinely dangerous model output apart from a
 * stale fixture. That ambiguity is why this needs a test of its own.</p>
 */
class RecordedPlaybookDraftInducerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RESOURCE =
            "troubleshooting/synthesis/recorded-draft-proposals.json";

    private static Map<String, RecordedPlaybookDraftInducer.Recorded> catalog() {
        return RecordedPlaybookDraftInducer.load(MAPPER, new ClassPathResource(RESOURCE));
    }

    private static PlaybookDraftProposal meetingCase() {
        return catalog().values().stream()
                .filter(recorded -> "message_send_failed".equals(recorded.scenarioKey()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "the §11.1 acceptance case must ship with a recorded response"))
                .proposal();
    }

    @Test
    @DisplayName("录制目录可加载，且覆盖蓝图 §11.1 的验收案例")
    void theCatalogCoversTheAcceptanceCase() {
        assertThat(catalog()).isNotEmpty();
        assertThat(meetingCase().abstain())
                .as("弃权的录制响应无法证明学习环可走")
                .isFalse();
    }

    /**
     * The gate that matters: a drifted recording must fail here, not silently
     * downgrade the demo to VALIDATION_REJECTED at runtime.
     */
    @Test
    @DisplayName("录制响应能通过确定性校验，且只产出 SCENARIO 候选")
    void theRecordingPassesDeterministicValidation() {
        PlaybookDraftValidator.ValidationResult result = validate(meetingCase());
        assertThat(result.errors())
                .as("录制响应必须通过与真实模型输出同一套校验")
                .isEmpty();
        assertThat(result.valid()).isTrue();
        assertThat(meetingCase().proposedType())
                .as("无码路只能提议注册 selector，不得猜错误码")
                .isEqualTo("SCENARIO");
    }

    @Test
    @DisplayName("录制响应的每条引用都属于本次证据，不含 DQL、原始日志或生产写")
    void theRecordingOnlyCitesThisRunsEvidence() {
        PlaybookDraftProposal proposal = meetingCase();
        assertThat(proposal.evidenceCitations())
                .containsExactlyInAnyOrder(
                        "SYNTH-LOG-SEARCH", "SYNTH-TRACE-BUNDLE", "SYNTH-CONTRAST-SAMPLE");
        assertThat(proposal.humanActions())
                .allSatisfy(action -> assertThat(action.executionMode())
                        .as("动作只能由平台之外的人执行")
                        .isEqualTo("EXTERNAL_HUMAN"));
    }

    /**
     * The negative control. Without it, "the recording passes validation" would
     * be indistinguishable from "validation is not actually running".
     */
    @Test
    @DisplayName("被污染的录制响应仍会被拦下——校验没有因为是录制就放行")
    void aTamperedRecordingIsStillRejected() {
        PlaybookDraftValidator.ValidationResult result = validate(withInstruction(
                meetingCase(), "restart_production and kubectl delete pod"));
        assertThat(result.valid())
                .as("生产写动作必须被拒，无论它来自模型还是来自录制文件")
                .isFalse();
        assertThat(result.errors())
                .anySatisfy(error -> assertThat(error.code())
                        .isIn("PRODUCTION_WRITE_FORBIDDEN", "TOOL_CALL_FORBIDDEN"));
    }

    @Test
    @DisplayName("provenance 自证：provider 是 recorded，不冒用真实 provider 名")
    void provenanceDeclaresItselfAsRecorded() {
        assertThat(RecordedPlaybookDraftInducer.RECORDED_PROVIDER).isEqualTo("recorded");
        assertThat(RecordedPlaybookDraftInducer.RECORDED_MODEL_NAME)
                .doesNotContain("openai")
                .doesNotContain("dashscope")
                .doesNotContain("anthropic");
    }

    @Test
    @DisplayName("坏目录直接抛错，不静默降级成空目录")
    void anInvalidCatalogFailsLoudly() {
        assertThatThrownBy(() -> RecordedPlaybookDraftInducer.load(
                MAPPER, new ClassPathResource("troubleshooting/synthesis/does-not-exist.json")))
                .isInstanceOf(IllegalStateException.class);
    }

    /** Mirrors {@code SopSynthesisService.draft(...)} so drift shows up here. */
    private static PlaybookDraftValidator.ValidationResult validate(
            PlaybookDraftProposal proposal) {
        PlaybookDraft draft = new PlaybookDraft(
                "draft-recorded-test",
                "generation-key-test",
                "incident-test",
                proposal.proposedType(),
                proposal.proposedSelector(),
                proposal.title(),
                proposal.evidencePlan(),
                proposal.criteria(),
                proposal.diagnosisHypotheses(),
                proposal.humanActions(),
                proposal.evidenceCitations(),
                new PlaybookDraft.ModelProvenance(
                        RecordedPlaybookDraftInducer.RECORDED_PROVIDER,
                        RecordedPlaybookDraftInducer.RECORDED_MODEL_NAME,
                        "recorded-test",
                        PlaybookDraft.CONTRACT_VERSION,
                        java.time.Instant.parse("2026-08-01T00:00:00Z"), 1),
                true,
                java.util.List.of());
        return new PlaybookDraftValidator().validate(
                draft,
                new PlaybookDraftValidator.ValidationContext(
                        "CSDP", "message_send_failed",
                        Map.of(
                                "SYNTH-LOG-SEARCH", "log_search",
                                "SYNTH-TRACE-BUNDLE", "log_trace_bundle",
                                "SYNTH-CONTRAST-SAMPLE", "contrast_sample"),
                        true));
    }

    private static PlaybookDraftProposal withInstruction(
            PlaybookDraftProposal proposal, String instruction) {
        PlaybookDraft.HumanAction original = proposal.humanActions().getFirst();
        return new PlaybookDraftProposal(
                proposal.abstain(),
                proposal.abstainReason(),
                proposal.proposedType(),
                proposal.proposedSelector(),
                proposal.title(),
                proposal.evidencePlan(),
                proposal.criteria(),
                proposal.diagnosisHypotheses(),
                java.util.List.of(new PlaybookDraft.HumanAction(
                        original.intentKey(), instruction,
                        original.executionMode(), original.evidenceCitations())),
                proposal.evidenceCitations());
    }
}
