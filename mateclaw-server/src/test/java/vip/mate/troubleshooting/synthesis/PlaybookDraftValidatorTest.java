package vip.mate.troubleshooting.synthesis;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlaybookDraftValidatorTest {

    private final PlaybookDraftValidator validator = new PlaybookDraftValidator();

    @Test
    void acceptsAScenarioDraftWhoseActionsStayOutsideMateClaw() {
        PlaybookDraftValidator.ValidationResult result = validator.validate(
                validDraft(), context(true));

        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void reportsForgedCitationBadSelectorDangerousActionDqlAndSecretByField() {
        PlaybookDraft unsafe = new PlaybookDraft(
                "draft-1", "generation-1", "incident-1", "ERROR_CODE",
                new PlaybookDraft.ProposedSelector("OTHER", "message_send_failed", "903001"),
                "读取 token:production-secret 后直接恢复",
                List.of(new PlaybookDraft.EvidencePlanStep(
                        "locate_failed_request", "log_search",
                        "执行 L::raw:(*) 查询并 dump raw logs", true)),
                List.of(new PlaybookDraft.Criterion(
                        "state_conflict", "发现状态冲突", List.of("log_search"),
                        List.of("FORGED-EVIDENCE"))),
                List.of(new PlaybookDraft.DiagnosisHypothesis(
                        "session_state_conflict", "状态并发写入冲突",
                        List.of("SYNTH-TRACE-BUNDLE"))),
                List.of(new PlaybookDraft.HumanAction(
                        "restart_production", "kubectl delete pod 并重启生产服务",
                        "AUTOMATED_WRITE", List.of("SYNTH-TRACE-BUNDLE"))),
                List.of("SYNTH-LOG-SEARCH", "FORGED-EVIDENCE"),
                provenance(), true, List.of());

        PlaybookDraftValidator.ValidationResult result = validator.validate(unsafe, context(true));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors())
                .extracting(PlaybookDraft.ValidationError::code)
                .contains(
                        "TYPE_NOT_ALLOWED",
                        "SELECTOR_SYSTEM_MISMATCH",
                        "ERROR_CODE_MODEL_GUESS",
                        "UNKNOWN_EVIDENCE_CITATION",
                        "DQL_OR_RAW_LOG_FORBIDDEN",
                        "SECRET_NOT_REDACTED",
                        "ACTION_MODE_FORBIDDEN",
                        "PRODUCTION_WRITE_FORBIDDEN");
        assertThat(result.errors())
                .allSatisfy(error -> assertThat(error.fieldPath()).isNotBlank());
    }

    @Test
    void requiresContrastCitationOnlyWhenContrastWasAvailable() {
        PlaybookDraft withoutContrastCitation = validDraftWithCitations(
                List.of("SYNTH-LOG-SEARCH", "SYNTH-TRACE-BUNDLE"));

        assertThat(validator.validate(withoutContrastCitation, context(false)).valid()).isTrue();
        assertThat(validator.validate(withoutContrastCitation, context(true)).errors())
                .extracting(PlaybookDraft.ValidationError::code)
                .contains("REQUIRED_EVIDENCE_CITATION_MISSING");
    }

    @Test
    void bindsEveryCriterionEvidenceKindToAnActualCitationOfThatKind() {
        PlaybookDraft base = validDraft();
        PlaybookDraft mismatched = new PlaybookDraft(
                base.draftId(), base.generationKey(), base.sourceIncident(),
                base.proposedType(), base.proposedSelector(), base.title(),
                base.evidencePlan(),
                List.of(new PlaybookDraft.Criterion(
                        "state_conflict", "对照样本显示状态冲突",
                        List.of("contrast_sample"),
                        List.of("SYNTH-LOG-SEARCH"))),
                base.diagnosisHypotheses(), base.humanActions(),
                base.evidenceCitations(), base.modelProvenance(),
                base.contrastAvailable(), base.validationErrors());

        PlaybookDraftValidator.ValidationResult result = validator.validate(
                mismatched, context(true));

        assertThat(result.errors())
                .anySatisfy(error -> {
                    assertThat(error.code()).isEqualTo("EVIDENCE_KIND_CITATION_MISMATCH");
                    assertThat(error.fieldPath()).isEqualTo("criteria[0].evidenceCitations");
                });
    }

    @Test
    void rejectsAProductionWriteIntentEvenWhenItsDescriptionLooksHarmless() {
        PlaybookDraft base = validDraft();
        PlaybookDraft disguised = new PlaybookDraft(
                base.draftId(), base.generationKey(), base.sourceIncident(),
                base.proposedType(), base.proposedSelector(), base.title(),
                base.evidencePlan(), base.criteria(), base.diagnosisHypotheses(),
                List.of(new PlaybookDraft.HumanAction(
                        "restart_production", "由值班研发在平台外处理",
                        "EXTERNAL_HUMAN", List.of("SYNTH-LOG-SEARCH"))),
                base.evidenceCitations(), base.modelProvenance(),
                base.contrastAvailable(), base.validationErrors());

        PlaybookDraftValidator.ValidationResult result = validator.validate(
                disguised, context(true));

        assertThat(result.errors())
                .anySatisfy(error -> {
                    assertThat(error.code()).isEqualTo("FORBIDDEN_INTENT");
                    assertThat(error.fieldPath()).isEqualTo("humanActions[0].intentKey");
                });
    }

    private PlaybookDraft validDraft() {
        return validDraftWithCitations(List.of(
                "SYNTH-LOG-SEARCH", "SYNTH-TRACE-BUNDLE", "SYNTH-CONTRAST-SAMPLE"));
    }

    private PlaybookDraft validDraftWithCitations(List<String> citations) {
        boolean contrastAvailable = citations.contains("SYNTH-CONTRAST-SAMPLE");
        List<PlaybookDraft.EvidencePlanStep> evidencePlan = new java.util.ArrayList<>(List.of(
                new PlaybookDraft.EvidencePlanStep(
                        "locate_failed_request", "log_search", "定位失败请求并取得 PS ID", true),
                new PlaybookDraft.EvidencePlanStep(
                        "trace_ps_id", "log_trace_bundle", "沿 PS ID 还原调用链", true)));
        if (contrastAvailable) {
            evidencePlan.add(new PlaybookDraft.EvidencePlanStep(
                    "compare_success_sample", "contrast_sample", "对照同窗口成功请求", false));
        }
        return new PlaybookDraft(
                "draft-1", "generation-1", "incident-1", "SCENARIO",
                new PlaybookDraft.ProposedSelector("CSDP", "message_send_failed", null),
                "会话消息发送失败排查草案",
                evidencePlan,
                List.of(new PlaybookDraft.Criterion(
                        "state_conflict", "失败链路出现 session state conflict，成功样本低频",
                        contrastAvailable
                                ? List.of("log_trace_bundle", "contrast_sample")
                                : List.of("log_trace_bundle"),
                        citations)),
                List.of(new PlaybookDraft.DiagnosisHypothesis(
                        "session_state_conflict", "会话状态并发写冲突导致消息发送失败",
                        List.of("SYNTH-TRACE-BUNDLE"))),
                List.of(new PlaybookDraft.HumanAction(
                        "verify_recovery", "由值班研发在平台外验证后续消息发送是否恢复",
                        "EXTERNAL_HUMAN", List.of("SYNTH-LOG-SEARCH"))),
                citations, provenance(), contrastAvailable, List.of());
    }

    private PlaybookDraftValidator.ValidationContext context(boolean contrastAvailable) {
        Map<String, String> evidence = contrastAvailable
                ? Map.of(
                        "SYNTH-LOG-SEARCH", "log_search",
                        "SYNTH-TRACE-BUNDLE", "log_trace_bundle",
                        "SYNTH-CONTRAST-SAMPLE", "contrast_sample")
                : Map.of(
                        "SYNTH-LOG-SEARCH", "log_search",
                        "SYNTH-TRACE-BUNDLE", "log_trace_bundle");
        return new PlaybookDraftValidator.ValidationContext(
                "CSDP", "message_send_failed", evidence, contrastAvailable);
    }

    private PlaybookDraft.ModelProvenance provenance() {
        return new PlaybookDraft.ModelProvenance(
                "openai", "fixed-model", "7:2026-07-20T00:00:00Z:fixed-model",
                PlaybookDraft.CONTRACT_VERSION, Instant.parse("2026-07-20T09:13:03Z"), 1);
    }
}
