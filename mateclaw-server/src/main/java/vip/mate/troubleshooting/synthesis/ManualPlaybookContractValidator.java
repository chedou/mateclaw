package vip.mate.troubleshooting.synthesis;

import vip.mate.troubleshooting.TroubleshootingSecretRedactor;
import vip.mate.troubleshooting.engine.Criterion;
import vip.mate.troubleshooting.model.ActionType;
import vip.mate.troubleshooting.model.AnomalyCriterion;
import vip.mate.troubleshooting.model.ApprovalStatus;
import vip.mate.troubleshooting.model.DiagnosisRule;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.ExecutionStatus;
import vip.mate.troubleshooting.model.RecommendedAction;
import vip.mate.troubleshooting.model.SopEntry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Complete fail-closed contract boundary for server-owned or imported manual Playbooks. */
public final class ManualPlaybookContractValidator {

    private static final Pattern SAFE_KEY =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]*");
    private static final Pattern SAFE_WINDOW =
            Pattern.compile("-?([1-9][0-9]*)([smhd])");
    private static final Pattern DQL_OR_RAW = Pattern.compile(
            "(?is)(?:\\b[A-Z]::|\\bdql\\b|raw[ _-]?logs?|原始日志|全量日志包)");
    private static final Pattern TOOL_CALL = Pattern.compile(
            "(?is)(?:tool[_ -]?call|execute[_ -]?tool|kubectl|\\bshell\\b|\\bcurl\\b|调用工具)");
    private static final Pattern PRODUCTION_WRITE = Pattern.compile(
            "(?is)(?:restart[_ -]?production|delete\\s+pod|kubectl|写入生产|删除生产|重启生产|"
                    + "update\\s+(?:the\\s+)?(?:production|database)|drop\\s+table)");
    private static final int MAX_ITEMS = 32;
    private static final int MAX_TEXT = 1_000;
    private static final int MAX_TARGET_DEPTH = 4;
    private static final int MAX_WINDOW_CHARS = 12;
    private static final long MAX_LOOKBACK_SECONDS = 86_400L;

    private ManualPlaybookContractValidator() {
    }

    public static List<PlaybookDraft.ValidationError> validate(SopEntry sop) {
        List<PlaybookDraft.ValidationError> errors = new ArrayList<>();
        if (!SopEntry.CURRENT_CONTRACT_VERSION.equals(sop.contractVersion())) {
            add(errors, "UNSUPPORTED_CONTRACT_VERSION", "contractVersion",
                    "manual playbook must use " + SopEntry.CURRENT_CONTRACT_VERSION);
        }
        key(sop.sopId(), 128, "sopId", errors);
        key(sop.system(), 96, "system", errors);
        key(sop.errorCode(), 128, "errorCode", errors);
        key(sop.service(), 192, "service", errors);
        if (sop.routingKey().length() > 256) {
            add(errors, "TEXT_TOO_LONG", "routingKey",
                    "derived selector exceeds 256 characters");
        }
        text(sop.title(), "title", true, errors);
        text(sop.cause(), "cause", false, errors);
        text(sop.category(), "category", false, errors);
        text(sop.ownerTeam(), "ownerTeam", false, errors);

        size(sop.evidenceRequests(), "evidenceRequests", true, errors);
        Set<String> requestIds = new HashSet<>();
        for (int index = 0; index < sop.evidenceRequests().size(); index++) {
            EvidenceRequest request = sop.evidenceRequests().get(index);
            String path = "evidenceRequests[" + index + "]";
            key(request.requestId(), 128, path + ".requestId", errors);
            if (!requestIds.add(request.requestId())) {
                add(errors, "DUPLICATE_EVIDENCE_REQUEST", path + ".requestId",
                        "evidence request ids must be unique");
            }
            key(request.signalKind(), 128, path + ".signalKind", errors);
            text(request.purpose(), path + ".purpose", false, errors);
            if (request.window() != null
                    && !boundedWindow(request.window())) {
                add(errors, "INVALID_WINDOW", path + ".window",
                        "evidence window must be between 1 second and 24 hours");
            }
            target(request.target(), path + ".target", 0, errors);
        }

        size(sop.anomalyCriteria(), "anomalyCriteria", true, errors);
        Set<String> signals = new HashSet<>();
        for (int index = 0; index < sop.anomalyCriteria().size(); index++) {
            AnomalyCriterion criterion = sop.anomalyCriteria().get(index);
            String path = "anomalyCriteria[" + index + "]";
            key(criterion.signal(), 128, path + ".signal", errors);
            if (!signals.add(criterion.signal())) {
                add(errors, "DUPLICATE_SIGNAL", path + ".signal",
                        "anomaly signal names must be unique");
            }
            key(criterion.sourceRequestId(), 128,
                    path + ".sourceRequestId", errors);
            if (!requestIds.contains(criterion.sourceRequestId())) {
                add(errors, "UNKNOWN_EVIDENCE_REQUEST", path + ".sourceRequestId",
                        "criterion must reference a declared evidence request");
            }
            text(criterion.description(), path + ".description", false, errors);
            criterion(criterion.rule(), path + ".rule", errors);
        }

        size(sop.diagnosisRules(), "diagnosisRules", true, errors);
        Set<String> ruleIds = new HashSet<>();
        for (int ruleIndex = 0; ruleIndex < sop.diagnosisRules().size(); ruleIndex++) {
            DiagnosisRule rule = sop.diagnosisRules().get(ruleIndex);
            String path = "diagnosisRules[" + ruleIndex + "]";
            key(rule.ruleId(), 128, path + ".ruleId", errors);
            if (!ruleIds.add(rule.ruleId())) {
                add(errors, "DUPLICATE_RULE", path + ".ruleId",
                        "diagnosis rule ids must be unique");
            }
            size(rule.requiredSignals(), path + ".requiredSignals", true, errors);
            Set<String> ruleSignals = new HashSet<>();
            for (int signalIndex = 0;
                    signalIndex < rule.requiredSignals().size();
                    signalIndex++) {
                String signal = rule.requiredSignals().get(signalIndex);
                key(signal, 128,
                        path + ".requiredSignals[" + signalIndex + "]", errors);
                if (!ruleSignals.add(signal)) {
                    add(errors, "DUPLICATE_REQUIRED_SIGNAL",
                            path + ".requiredSignals[" + signalIndex + "]",
                            "a diagnosis rule cannot require the same signal twice");
                }
                if (!signals.contains(signal)) {
                    add(errors, "UNKNOWN_REQUIRED_SIGNAL",
                            path + ".requiredSignals[" + signalIndex + "]",
                            "diagnosis rule must reference a declared anomaly signal");
                }
            }
            text(rule.rootCause(), path + ".rootCause", true, errors);
            text(rule.summary(), path + ".summary", false, errors);
        }

        size(sop.actions(), "actions", false, errors);
        Set<String> actionIds = new HashSet<>();
        for (int index = 0; index < sop.actions().size(); index++) {
            RecommendedAction action = sop.actions().get(index);
            String path = "actions[" + index + "]";
            key(action.actionId(), 128, path + ".actionId", errors);
            if (!actionIds.add(action.actionId())) {
                add(errors, "DUPLICATE_ACTION", path + ".actionId",
                        "recommended action ids must be unique");
            }
            text(action.title(), path + ".title", true, errors);
            text(action.description(), path + ".description", false, errors);
            actionPolicy(action, path, errors);
            if (action.actionType() != ActionType.MANUAL_WRITE) {
                unsafeAutomaticActionText(action.title(), path + ".title", errors);
                unsafeAutomaticActionText(
                        action.description(), path + ".description", errors);
            }
        }

        if (!"candidate".equals(sop.status()) || sop.verified()) {
            add(errors, "SOURCE_STATE_INVALID", "status",
                    "manual review source must remain candidate and unverified");
        }
        return List.copyOf(new LinkedHashSet<>(errors));
    }

    private static void criterion(
            Criterion rule,
            String path,
            List<PlaybookDraft.ValidationError> errors) {
        switch (rule) {
            case Criterion.NumericGte item -> {
                key(item.field(), 128, path + ".field", errors);
                finite(item.threshold(), path + ".threshold", errors);
            }
            case Criterion.MissingOrLte item -> {
                key(item.presenceField(), 128, path + ".presenceField", errors);
                key(item.field(), 128, path + ".field", errors);
                finite(item.threshold(), path + ".threshold", errors);
            }
            case Criterion.RatioOfSumGt item -> {
                key(item.numeratorField(), 128, path + ".numeratorField", errors);
                key(item.addendField(), 128, path + ".addendField", errors);
                finite(item.threshold(), path + ".threshold", errors);
            }
            case Criterion.MultipleGt item -> {
                key(item.field(), 128, path + ".field", errors);
                key(item.baselineField(), 128, path + ".baselineField", errors);
                finite(item.multiplier(), path + ".multiplier", errors);
            }
            case Criterion.ContainsAndIn item -> {
                key(item.containsField(), 128, path + ".containsField", errors);
                text(item.substring(), path + ".substring", true, errors);
                key(item.membershipField(), 128, path + ".membershipField", errors);
                size(item.acceptedValues(), path + ".acceptedValues", true, errors);
                for (int index = 0; index < item.acceptedValues().size(); index++) {
                    text(item.acceptedValues().get(index),
                            path + ".acceptedValues[" + index + "]", true, errors);
                }
            }
            case Criterion.BooleanEquals item ->
                    key(item.field(), 128, path + ".field", errors);
        }
    }

    private static void actionPolicy(
            RecommendedAction action,
            String path,
            List<PlaybookDraft.ValidationError> errors) {
        if (action.actionType() == ActionType.MANUAL_WRITE) {
            if (!action.requiresApproval()
                    || action.approvalStatus() != ApprovalStatus.PENDING
                    || action.executionStatus() != ExecutionStatus.BLOCKED) {
                add(errors, "MANUAL_WRITE_STATE_INVALID", path,
                        "manual writes must enter review pending approval and blocked");
            }
            return;
        }
        if (action.requiresApproval()
                || action.approvalStatus() != ApprovalStatus.NOT_REQUIRED
                || action.executionStatus() != ExecutionStatus.PENDING) {
            add(errors, "NON_WRITE_ACTION_STATE_INVALID", path,
                    "non-write recommendations must start pending without approval");
        }
    }

    private static void target(
            Object value,
            String path,
            int depth,
            List<PlaybookDraft.ValidationError> errors) {
        if (depth > MAX_TARGET_DEPTH) {
            add(errors, "TARGET_TOO_DEEP", path,
                    "evidence target exceeds the supported nesting depth");
            return;
        }
        if (value instanceof Map<?, ?> map) {
            if (map.size() > MAX_ITEMS) {
                add(errors, "COLLECTION_TOO_LARGE", path,
                        "target object exceeds " + MAX_ITEMS + " entries");
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String keyValue)) {
                    add(errors, "INVALID_TARGET_KEY", path,
                            "evidence target keys must be strings");
                    continue;
                }
                key(keyValue, 128, path + "." + keyValue, errors);
                target(entry.getValue(), path + "." + keyValue, depth + 1, errors);
            }
            return;
        }
        if (value instanceof Collection<?> collection) {
            if (collection.size() > MAX_ITEMS) {
                add(errors, "COLLECTION_TOO_LARGE", path,
                        "target collection exceeds " + MAX_ITEMS + " items");
            }
            int index = 0;
            for (Object item : collection) {
                target(item, path + "[" + index++ + "]", depth + 1, errors);
            }
            return;
        }
        if (value instanceof String textValue) {
            text(textValue, path, true, errors);
            return;
        }
        if ((value instanceof Double doubleValue && !Double.isFinite(doubleValue))
                || (value instanceof Float floatValue && !Float.isFinite(floatValue))) {
            add(errors, "NON_FINITE_NUMBER", path,
                    "evidence target numbers must be finite");
            return;
        }
        if (value == null
                || !(value instanceof Number || value instanceof Boolean)) {
            add(errors, "INVALID_TARGET_VALUE", path,
                    "evidence target values must be bounded JSON values");
        }
    }

    private static void unsafeAutomaticActionText(
            String value,
            String path,
            List<PlaybookDraft.ValidationError> errors) {
        if (value == null) {
            return;
        }
        if (TOOL_CALL.matcher(value).find()) {
            add(errors, "TOOL_CALL_FORBIDDEN", path,
                    "a non-write recommendation cannot contain a tool command");
        }
        if (PRODUCTION_WRITE.matcher(value).find()) {
            add(errors, "PRODUCTION_WRITE_FORBIDDEN", path,
                    "production writes must be typed as blocked manual actions");
        }
    }

    private static void key(
            String value,
            int maxLength,
            String path,
            List<PlaybookDraft.ValidationError> errors) {
        if (value == null
                || value.length() > maxLength
                || !SAFE_KEY.matcher(value).matches()) {
            add(errors, "INVALID_KEY", path,
                    "field must be a bounded server-safe key");
            return;
        }
        safeContent(value, path, errors);
    }

    private static void text(
            String value,
            String path,
            boolean required,
            List<PlaybookDraft.ValidationError> errors) {
        if (value == null || value.isBlank()) {
            if (required) {
                add(errors, "REQUIRED_FIELD_MISSING", path,
                        "field must not be blank");
            }
            return;
        }
        if (value.length() > MAX_TEXT
                || value.chars().anyMatch(Character::isISOControl)) {
            add(errors, "TEXT_NOT_BOUNDED", path,
                    "field exceeds its text or control-character boundary");
        }
        safeContent(value, path, errors);
    }

    private static void safeContent(
            String value,
            String path,
            List<PlaybookDraft.ValidationError> errors) {
        if (!value.equals(TroubleshootingSecretRedactor.redact(value))) {
            add(errors, "SECRET_NOT_REDACTED", path,
                    "manual Playbook content contains secret-shaped data");
        }
        if (DQL_OR_RAW.matcher(value).find()) {
            add(errors, "DQL_OR_RAW_LOG_FORBIDDEN", path,
                    "manual Playbook content cannot embed DQL or raw logs");
        }
    }

    private static void finite(
            double value,
            String path,
            List<PlaybookDraft.ValidationError> errors) {
        if (!Double.isFinite(value)) {
            add(errors, "NON_FINITE_NUMBER", path,
                    "criterion numbers must be finite");
        }
    }

    private static boolean boundedWindow(String value) {
        if (value.length() > MAX_WINDOW_CHARS) {
            return false;
        }
        var matcher = SAFE_WINDOW.matcher(value);
        if (!matcher.matches()) {
            return false;
        }
        try {
            long amount = Long.parseLong(matcher.group(1));
            long multiplier = switch (matcher.group(2)) {
                case "s" -> 1L;
                case "m" -> 60L;
                case "h" -> 3_600L;
                case "d" -> 86_400L;
                default -> throw new IllegalArgumentException("unsupported window unit");
            };
            return amount <= MAX_LOOKBACK_SECONDS / multiplier;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private static void size(
            List<?> values,
            String path,
            boolean required,
            List<PlaybookDraft.ValidationError> errors) {
        if (required && values.isEmpty()) {
            add(errors, "REQUIRED_COLLECTION_EMPTY", path,
                    "collection must not be empty");
        }
        if (values.size() > MAX_ITEMS) {
            add(errors, "COLLECTION_TOO_LARGE", path,
                    "collection exceeds " + MAX_ITEMS + " items");
        }
    }

    private static void add(
            List<PlaybookDraft.ValidationError> errors,
            String code,
            String path,
            String message) {
        errors.add(new PlaybookDraft.ValidationError(code, path, message));
    }
}
