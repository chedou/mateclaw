package vip.mate.troubleshooting.synthesis;

import org.springframework.stereotype.Component;
import vip.mate.troubleshooting.TroubleshootingSecretRedactor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Deterministic trust boundary between model output and candidate persistence. */
@Component
public final class PlaybookDraftValidator {

    private static final Pattern SAFE_KEY =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}");
    private static final Pattern DQL_OR_RAW = Pattern.compile(
            "(?is)(?:\\b[LMTO]::|\\bdql\\b|raw[ _-]?logs?|原始日志|全量日志包)");
    private static final Pattern TOOL_CALL = Pattern.compile(
            "(?is)(?:tool[_ -]?call|execute[_ -]?tool|kubectl|\\bshell\\b|\\bcurl\\b|调用工具)");
    private static final Pattern PRODUCTION_WRITE = Pattern.compile(
            "(?is)(?:restart[_ -]?production|delete\\s+pod|kubectl|写入生产|删除生产|重启生产|"
                    + "update\\s+(?:the\\s+)?(?:production|database)|drop\\s+table)");
    private static final Set<String> ALLOWED_SIGNAL_KINDS = Set.of(
            "log_search", "log_trace_bundle", "contrast_sample");
    private static final int MAX_ITEMS = 32;
    private static final int MAX_TEXT = 1_000;

    public ValidationResult validate(PlaybookDraft draft, ValidationContext context) {
        if (draft == null || context == null) {
            throw new IllegalArgumentException("draft and validation context are required");
        }
        List<PlaybookDraft.ValidationError> errors = new ArrayList<>();
        required(draft.draftId(), "draftId", errors);
        required(draft.generationKey(), "generationKey", errors);
        required(draft.sourceIncident(), "sourceIncident", errors);
        if (!"SCENARIO".equals(normalizeUpper(draft.proposedType()))) {
            add(errors, "TYPE_NOT_ALLOWED", "proposedType",
                    "P1 no-error-code synthesis may only propose SCENARIO playbooks");
        }
        validateSelector(draft.proposedSelector(), context, errors);
        boundedText(draft.title(), "title", true, errors);
        boundedSize(draft.evidencePlan(), "evidencePlan", true, errors);
        boundedSize(draft.criteria(), "criteria", true, errors);
        boundedSize(draft.diagnosisHypotheses(), "diagnosisHypotheses", true, errors);
        boundedSize(draft.humanActions(), "humanActions", false, errors);

        for (int index = 0; index < draft.evidencePlan().size(); index++) {
            PlaybookDraft.EvidencePlanStep step = draft.evidencePlan().get(index);
            String path = "evidencePlan[" + index + "]";
            validateIntent(step.intentKey(), path + ".intentKey", context, errors);
            if (!ALLOWED_SIGNAL_KINDS.contains(normalize(step.signalKind()))) {
                add(errors, "SIGNAL_KIND_NOT_ALLOWED", path + ".signalKind",
                        "evidence plan contains a non-read-only or unknown signal kind");
            } else if (!context.evidenceKindsById().containsValue(normalize(step.signalKind()))) {
                add(errors, "UNAVAILABLE_EVIDENCE_KIND", path + ".signalKind",
                        "evidence plan cannot claim a signal kind absent from this bundle");
            }
            boundedText(step.purpose(), path + ".purpose", true, errors);
        }
        for (int index = 0; index < draft.criteria().size(); index++) {
            PlaybookDraft.Criterion criterion = draft.criteria().get(index);
            String path = "criteria[" + index + "]";
            safeKey(criterion.criterionKey(), path + ".criterionKey", errors);
            boundedText(criterion.description(), path + ".description", true, errors);
            validateKinds(
                    criterion.evidenceKinds(), path + ".evidenceKinds", context, errors);
            citations(criterion.evidenceCitations(), path + ".evidenceCitations", context, errors);
            bindKindsToCitations(criterion, path, context, errors);
        }
        for (int index = 0; index < draft.diagnosisHypotheses().size(); index++) {
            PlaybookDraft.DiagnosisHypothesis hypothesis = draft.diagnosisHypotheses().get(index);
            String path = "diagnosisHypotheses[" + index + "]";
            safeKey(hypothesis.hypothesisKey(), path + ".hypothesisKey", errors);
            boundedText(hypothesis.summary(), path + ".summary", true, errors);
            citations(hypothesis.evidenceCitations(), path + ".evidenceCitations", context, errors);
        }
        for (int index = 0; index < draft.humanActions().size(); index++) {
            PlaybookDraft.HumanAction action = draft.humanActions().get(index);
            String path = "humanActions[" + index + "]";
            validateIntent(action.intentKey(), path + ".intentKey", context, errors);
            boundedText(action.instruction(), path + ".instruction", true, errors);
            if (!"EXTERNAL_HUMAN".equals(normalizeUpper(action.executionMode()))) {
                add(errors, "ACTION_MODE_FORBIDDEN", path + ".executionMode",
                        "P1 actions must be executed by a human outside MateClaw");
            }
            citations(action.evidenceCitations(), path + ".evidenceCitations", context, errors);
        }
        citations(draft.evidenceCitations(), "evidenceCitations", context, errors);
        requireEvidenceKindCitation("log_search", context, draft.evidenceCitations(), errors);
        requireEvidenceKindCitation("log_trace_bundle", context, draft.evidenceCitations(), errors);
        if (context.contrastAvailable()) {
            requireEvidenceKindCitation("contrast_sample", context, draft.evidenceCitations(), errors);
        }
        if (draft.contrastAvailable() != context.contrastAvailable()) {
            add(errors, "CONTRAST_FLAG_MISMATCH", "contrastAvailable",
                    "draft contrast flag must be server-derived from this evidence bundle");
        }
        scanAllText(draft, errors);
        List<PlaybookDraft.ValidationError> unique = List.copyOf(new LinkedHashSet<>(errors));
        return new ValidationResult(unique.isEmpty(), unique);
    }

    /**
     * Validates an abstention before any draft payload can be discarded.
     * A refusal is a separate protocol branch: every draft field must be empty,
     * while the reason and any attempted hidden payload still cross the same
     * secret/tool/production-write scanner as a normal draft.
     */
    public ValidationResult validateAbstention(
            PlaybookDraftProposal proposal,
            ValidationContext context) {
        if (context == null) {
            throw new IllegalArgumentException("validation context is required");
        }
        List<PlaybookDraft.ValidationError> errors = new ArrayList<>();
        if (proposal == null || !proposal.abstain()) {
            add(errors, "ABSTAIN_PROPOSAL_REQUIRED", "abstain",
                    "an abstention requires its complete model proposal");
            return new ValidationResult(false, errors);
        }
        if (hasDraftPayload(proposal)) {
            add(errors, "ABSTAIN_DRAFT_FIELDS_PRESENT", "proposal",
                    "an abstention cannot carry a hidden draft payload");
        }
        validateAbstentionResidue(proposal, context, errors);
        scan(proposal.abstainReason(), "abstainReason", errors);
        scanProposalText(proposal, errors);
        List<PlaybookDraft.ValidationError> unique = List.copyOf(new LinkedHashSet<>(errors));
        return new ValidationResult(unique.isEmpty(), unique);
    }

    /**
     * Applies the same authority checks to every non-empty residual field without
     * pretending that an abstention must satisfy the mandatory shape of a draft.
     */
    private void validateAbstentionResidue(
            PlaybookDraftProposal proposal,
            ValidationContext context,
            List<PlaybookDraft.ValidationError> errors) {
        if (present(proposal.proposedType())
                && !"SCENARIO".equals(normalizeUpper(proposal.proposedType()))) {
            add(errors, "TYPE_NOT_ALLOWED", "proposedType",
                    "P1 no-error-code synthesis may only propose SCENARIO playbooks");
        }
        if (proposal.proposedSelector() != null) {
            validateSelector(proposal.proposedSelector(), context, errors);
        }
        boundedText(proposal.title(), "title", false, errors);
        boundedSize(proposal.evidencePlan(), "evidencePlan", false, errors);
        boundedSize(proposal.criteria(), "criteria", false, errors);
        boundedSize(proposal.diagnosisHypotheses(), "diagnosisHypotheses", false, errors);
        boundedSize(proposal.humanActions(), "humanActions", false, errors);

        for (int index = 0; index < proposal.evidencePlan().size(); index++) {
            PlaybookDraft.EvidencePlanStep step = proposal.evidencePlan().get(index);
            String path = "evidencePlan[" + index + "]";
            validateIntent(step.intentKey(), path + ".intentKey", context, errors);
            if (!ALLOWED_SIGNAL_KINDS.contains(normalize(step.signalKind()))) {
                add(errors, "SIGNAL_KIND_NOT_ALLOWED", path + ".signalKind",
                        "evidence plan contains a non-read-only or unknown signal kind");
            } else if (!context.evidenceKindsById().containsValue(normalize(step.signalKind()))) {
                add(errors, "UNAVAILABLE_EVIDENCE_KIND", path + ".signalKind",
                        "evidence plan cannot claim a signal kind absent from this bundle");
            }
            boundedText(step.purpose(), path + ".purpose", true, errors);
        }
        for (int index = 0; index < proposal.criteria().size(); index++) {
            PlaybookDraft.Criterion criterion = proposal.criteria().get(index);
            String path = "criteria[" + index + "]";
            safeKey(criterion.criterionKey(), path + ".criterionKey", errors);
            boundedText(criterion.description(), path + ".description", true, errors);
            validateKinds(criterion.evidenceKinds(), path + ".evidenceKinds", context, errors);
            citations(criterion.evidenceCitations(), path + ".evidenceCitations", context, errors);
            bindKindsToCitations(criterion, path, context, errors);
        }
        for (int index = 0; index < proposal.diagnosisHypotheses().size(); index++) {
            PlaybookDraft.DiagnosisHypothesis hypothesis =
                    proposal.diagnosisHypotheses().get(index);
            String path = "diagnosisHypotheses[" + index + "]";
            safeKey(hypothesis.hypothesisKey(), path + ".hypothesisKey", errors);
            boundedText(hypothesis.summary(), path + ".summary", true, errors);
            citations(hypothesis.evidenceCitations(), path + ".evidenceCitations", context, errors);
        }
        for (int index = 0; index < proposal.humanActions().size(); index++) {
            PlaybookDraft.HumanAction action = proposal.humanActions().get(index);
            String path = "humanActions[" + index + "]";
            validateIntent(action.intentKey(), path + ".intentKey", context, errors);
            boundedText(action.instruction(), path + ".instruction", true, errors);
            if (!"EXTERNAL_HUMAN".equals(normalizeUpper(action.executionMode()))) {
                add(errors, "ACTION_MODE_FORBIDDEN", path + ".executionMode",
                        "P1 actions must be executed by a human outside MateClaw");
            }
            citations(action.evidenceCitations(), path + ".evidenceCitations", context, errors);
        }
        if (!proposal.evidenceCitations().isEmpty()) {
            citations(proposal.evidenceCitations(), "evidenceCitations", context, errors);
        }
    }

    private boolean hasDraftPayload(PlaybookDraftProposal proposal) {
        return present(proposal.proposedType())
                || proposal.proposedSelector() != null
                || present(proposal.title())
                || !proposal.evidencePlan().isEmpty()
                || !proposal.criteria().isEmpty()
                || !proposal.diagnosisHypotheses().isEmpty()
                || !proposal.humanActions().isEmpty()
                || !proposal.evidenceCitations().isEmpty();
    }

    private void scanProposalText(
            PlaybookDraftProposal proposal,
            List<PlaybookDraft.ValidationError> errors) {
        scan(proposal.proposedType(), "proposedType", errors);
        scan(proposal.title(), "title", errors);
        if (proposal.proposedSelector() != null) {
            scan(proposal.proposedSelector().system(), "proposedSelector.system", errors);
            scan(proposal.proposedSelector().scenarioKey(),
                    "proposedSelector.scenarioKey", errors);
            scan(proposal.proposedSelector().errorCode(),
                    "proposedSelector.errorCode", errors);
        }
        for (int index = 0; index < proposal.evidencePlan().size(); index++) {
            PlaybookDraft.EvidencePlanStep step = proposal.evidencePlan().get(index);
            scan(step.intentKey(), "evidencePlan[" + index + "].intentKey", errors);
            scan(step.signalKind(), "evidencePlan[" + index + "].signalKind", errors);
            scan(step.purpose(), "evidencePlan[" + index + "].purpose", errors);
        }
        for (int index = 0; index < proposal.criteria().size(); index++) {
            PlaybookDraft.Criterion criterion = proposal.criteria().get(index);
            scan(criterion.criterionKey(), "criteria[" + index + "].criterionKey", errors);
            scan(criterion.description(), "criteria[" + index + "].description", errors);
        }
        for (int index = 0; index < proposal.diagnosisHypotheses().size(); index++) {
            PlaybookDraft.DiagnosisHypothesis hypothesis =
                    proposal.diagnosisHypotheses().get(index);
            scan(hypothesis.hypothesisKey(),
                    "diagnosisHypotheses[" + index + "].hypothesisKey", errors);
            scan(hypothesis.summary(),
                    "diagnosisHypotheses[" + index + "].summary", errors);
        }
        for (int index = 0; index < proposal.humanActions().size(); index++) {
            PlaybookDraft.HumanAction action = proposal.humanActions().get(index);
            scan(action.intentKey(), "humanActions[" + index + "].intentKey", errors);
            scan(action.instruction(), "humanActions[" + index + "].instruction", errors);
            scan(action.executionMode(),
                    "humanActions[" + index + "].executionMode", errors);
        }
        for (int index = 0; index < proposal.evidenceCitations().size(); index++) {
            scan(proposal.evidenceCitations().get(index),
                    "evidenceCitations[" + index + "]", errors);
        }
    }

    private boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private void validateSelector(
            PlaybookDraft.ProposedSelector selector,
            ValidationContext context,
            List<PlaybookDraft.ValidationError> errors) {
        if (selector == null) {
            add(errors, "SELECTOR_REQUIRED", "proposedSelector", "selector is required");
            return;
        }
        safeKey(selector.system(), "proposedSelector.system", errors);
        safeKey(selector.scenarioKey(), "proposedSelector.scenarioKey", errors);
        if (!equalsIgnoreCase(selector.system(), context.system())) {
            add(errors, "SELECTOR_SYSTEM_MISMATCH", "proposedSelector.system",
                    "model cannot widen the confirmed system scope");
        }
        if (!equalsIgnoreCase(selector.scenarioKey(), context.scenarioKey())) {
            add(errors, "SELECTOR_SCENARIO_MISMATCH", "proposedSelector.scenarioKey",
                    "model may only propose the server-confirmed scenario key");
        }
        if (selector.errorCode() != null && !selector.errorCode().isBlank()) {
            add(errors, "ERROR_CODE_MODEL_GUESS", "proposedSelector.errorCode",
                    "a model-proposed error code cannot enter deterministic routing");
        }
    }

    private void validateKinds(
            List<String> kinds,
            String path,
            ValidationContext context,
            List<PlaybookDraft.ValidationError> errors) {
        boundedSize(kinds, path, true, errors);
        for (int index = 0; index < kinds.size(); index++) {
            if (!ALLOWED_SIGNAL_KINDS.contains(normalize(kinds.get(index)))) {
                add(errors, "SIGNAL_KIND_NOT_ALLOWED", path + "[" + index + "]",
                        "criterion references an unknown evidence kind");
            } else if (!context.evidenceKindsById().containsValue(normalize(kinds.get(index)))) {
                add(errors, "UNAVAILABLE_EVIDENCE_KIND", path + "[" + index + "]",
                        "criterion cannot claim a signal kind absent from this bundle");
            }
        }
    }

    private void citations(
            List<String> citations,
            String path,
            ValidationContext context,
            List<PlaybookDraft.ValidationError> errors) {
        boundedSize(citations, path, true, errors);
        for (int index = 0; index < citations.size(); index++) {
            String citation = citations.get(index);
            if (!context.evidenceKindsById().containsKey(citation)) {
                add(errors, "UNKNOWN_EVIDENCE_CITATION", path + "[" + index + "]",
                        "citation is not part of this synthesis evidence bundle");
            }
        }
    }

    private void bindKindsToCitations(
            PlaybookDraft.Criterion criterion,
            String path,
            ValidationContext context,
            List<PlaybookDraft.ValidationError> errors) {
        Set<String> citedKinds = criterion.evidenceCitations().stream()
                .map(context.evidenceKindsById()::get)
                .filter(java.util.Objects::nonNull)
                .map(this::normalize)
                .collect(java.util.stream.Collectors.toSet());
        List<String> unsupportedKinds = criterion.evidenceKinds().stream()
                .map(this::normalize)
                .filter(ALLOWED_SIGNAL_KINDS::contains)
                .filter(kind -> !citedKinds.contains(kind))
                .distinct()
                .toList();
        if (!unsupportedKinds.isEmpty()) {
            add(errors, "EVIDENCE_KIND_CITATION_MISMATCH",
                    path + ".evidenceCitations",
                    "criterion declares evidence kinds without matching citations: "
                            + String.join(",", unsupportedKinds));
        }
    }

    private void requireEvidenceKindCitation(
            String kind,
            ValidationContext context,
            List<String> citations,
            List<PlaybookDraft.ValidationError> errors) {
        boolean present = citations.stream()
                .map(context.evidenceKindsById()::get)
                .anyMatch(kind::equals);
        if (!present) {
            add(errors, "REQUIRED_EVIDENCE_CITATION_MISSING", "evidenceCitations",
                    "draft must cite " + kind + " evidence from this bundle");
        }
    }

    private void scanAllText(
            PlaybookDraft draft,
            List<PlaybookDraft.ValidationError> errors) {
        scan(draft.title(), "title", errors);
        for (int index = 0; index < draft.evidencePlan().size(); index++) {
            scan(draft.evidencePlan().get(index).purpose(),
                    "evidencePlan[" + index + "].purpose", errors);
        }
        for (int index = 0; index < draft.criteria().size(); index++) {
            scan(draft.criteria().get(index).description(),
                    "criteria[" + index + "].description", errors);
        }
        for (int index = 0; index < draft.diagnosisHypotheses().size(); index++) {
            scan(draft.diagnosisHypotheses().get(index).summary(),
                    "diagnosisHypotheses[" + index + "].summary", errors);
        }
        for (int index = 0; index < draft.humanActions().size(); index++) {
            scan(draft.humanActions().get(index).instruction(),
                    "humanActions[" + index + "].instruction", errors);
        }
    }

    private void scan(
            String text,
            String path,
            List<PlaybookDraft.ValidationError> errors) {
        if (text == null) {
            return;
        }
        if (!text.equals(TroubleshootingSecretRedactor.redact(text))) {
            add(errors, "SECRET_NOT_REDACTED", path,
                    "model output contains secret-shaped content");
        }
        if (DQL_OR_RAW.matcher(text).find()) {
            add(errors, "DQL_OR_RAW_LOG_FORBIDDEN", path,
                    "model output must not contain DQL or raw log instructions");
        }
        if (TOOL_CALL.matcher(text).find()) {
            add(errors, "TOOL_CALL_FORBIDDEN", path,
                    "a PlaybookDraft cannot invoke tools");
        }
        if (PRODUCTION_WRITE.matcher(text).find()) {
            add(errors, "PRODUCTION_WRITE_FORBIDDEN", path,
                    "production write actions are disabled");
        }
    }

    private void boundedText(
            String value,
            String path,
            boolean required,
            List<PlaybookDraft.ValidationError> errors) {
        if (value == null || value.isBlank()) {
            if (required) {
                add(errors, "REQUIRED_FIELD_MISSING", path, "field must not be blank");
            }
            return;
        }
        if (value.length() > MAX_TEXT) {
            add(errors, "TEXT_TOO_LONG", path, "field exceeds " + MAX_TEXT + " characters");
        }
    }

    private void safeKey(
            String value,
            String path,
            List<PlaybookDraft.ValidationError> errors) {
        if (value == null || !SAFE_KEY.matcher(value).matches()) {
            add(errors, "INVALID_KEY", path, "field must be a bounded server-safe key");
        }
    }

    private void validateIntent(
            String value,
            String path,
            ValidationContext context,
            List<PlaybookDraft.ValidationError> errors) {
        safeKey(value, path, errors);
        if (context.forbiddenStepIntents().contains(canonicalIntent(value))) {
            add(errors, "FORBIDDEN_INTENT", path,
                    "intent is forbidden by the sample's human-authored reference solution");
        }
        scan(value, path, errors);
    }

    private void required(
            String value,
            String path,
            List<PlaybookDraft.ValidationError> errors) {
        if (value == null || value.isBlank()) {
            add(errors, "REQUIRED_FIELD_MISSING", path, "field must not be blank");
        }
    }

    private void boundedSize(
            List<?> values,
            String path,
            boolean required,
            List<PlaybookDraft.ValidationError> errors) {
        if (required && values.isEmpty()) {
            add(errors, "REQUIRED_COLLECTION_EMPTY", path, "collection must not be empty");
        }
        if (values.size() > MAX_ITEMS) {
            add(errors, "COLLECTION_TOO_LARGE", path,
                    "collection exceeds " + MAX_ITEMS + " items");
        }
    }

    private void add(
            List<PlaybookDraft.ValidationError> errors,
            String code,
            String path,
            String message) {
        errors.add(new PlaybookDraft.ValidationError(code, path, message));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeUpper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String canonicalIntent(String value) {
        return value == null
                ? ""
                : value.trim()
                        .toLowerCase(Locale.ROOT)
                        .replace('-', '_')
                        .replace('.', '_')
                        .replace('/', '_');
    }

    private boolean equalsIgnoreCase(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    public record ValidationContext(
            String system,
            String scenarioKey,
            Map<String, String> evidenceKindsById,
            boolean contrastAvailable,
            Set<String> forbiddenStepIntents) {

        public ValidationContext(
                String system,
                String scenarioKey,
                Map<String, String> evidenceKindsById,
                boolean contrastAvailable) {
            this(
                    system,
                    scenarioKey,
                    evidenceKindsById,
                    contrastAvailable,
                    Set.copyOf(ReferenceSolution.messageSendFailure().forbiddenStepIntents()));
        }

        public ValidationContext {
            evidenceKindsById = Map.copyOf(
                    evidenceKindsById == null ? Map.of() : evidenceKindsById);
            LinkedHashSet<String> normalizedForbidden = new LinkedHashSet<>();
            if (forbiddenStepIntents != null) {
                forbiddenStepIntents.stream()
                        .map(PlaybookDraftValidator::canonicalIntent)
                        .filter(value -> !value.isBlank())
                        .forEach(normalizedForbidden::add);
            }
            forbiddenStepIntents = Set.copyOf(normalizedForbidden);
        }
    }

    public record ValidationResult(
            boolean valid,
            List<PlaybookDraft.ValidationError> errors) {

        public ValidationResult {
            errors = List.copyOf(errors == null ? List.of() : errors);
        }
    }
}
