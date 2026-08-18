package vip.mate.troubleshooting.intake;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import vip.mate.troubleshooting.TroubleshootingSecretRedactor;
import vip.mate.troubleshooting.investigation.ReviewedIncidentPolicy;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure reducer for RECEIVED -> AWAITING_INPUT -> READY. */
public final class IntakeSessionReducer {

    private static final ObjectMapper STRICT_JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final List<DateTimeFormatter> LOCAL_TIME_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    private static final List<String> REQUIRED_FIELDS = List.of(
            "symptom", "system", "service", "customerRef", "occurredAt");
    /** Embedded wall-clock in pasted alert banners, e.g. 【重要】2026-08-12 16:36:00. */
    private static final Pattern EMBEDDED_LOCAL_TIME = Pattern.compile(
            "(20\\d{2}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}(?::\\d{2})?)");
    /**
     * Explicit business error code embedded in an alert symptom, for example
     * {@code 异常：ITGW访问失败【904003】} or {@code 异常：客户-搜索用户名超限制【1009】}.
     * This is not a fuzzy number extractor: the identifier must be bracketed
     * and attached to failure/limit wording. Four digits are in scope because
     * CSDP already publishes 4-digit codes (1004, 1008, 1009); five was an
     * accidental floor copied from 903001-shaped alerts.
     */
    private static final Pattern EXPLICIT_ERROR_CODE = Pattern.compile(
            "(?m)^\\s*(?:错误码|error(?:[ _-]*code))\\s*[:：]\\s*"
                    + "(?:【\\s*)?([A-Za-z0-9][A-Za-z0-9._-]{2,127})(?:\\s*】)?\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FAILURE_SYMPTOM_ERROR_CODE = Pattern.compile(
            "(?m)^\\s*(?:(?:异常|现象)\\s*[:：][^\\r\\n]{0,100}?"
                    + "(?:失败|错误|超限制|超时|拦截|限流|拒绝)[^\\r\\n]{0,80}?"
                    + "|错误\\s*[:：][^\\r\\n]{0,180}?)"
                    + "【\\s*(\\d{4,12})\\s*】\\s*$",
            Pattern.CASE_INSENSITIVE);
    /**
     * Infra dial-probe / VM health / cluster alerts rarely name a customer; the
     * product already accepts the explicit token “未知”, so we only fill it when
     * the pasted text itself signals that class of alert. A 集群 dimension is
     * such a signal: it addresses a deployment, not a customer.
     */
    private static final Pattern INFRA_ALERT_WITHOUT_CUSTOMER = Pattern.compile(
            "拨测|虚机|主机|存活检测|监控项|集群|告警分组|告警级别|告警URL",
            Pattern.CASE_INSENSITIVE);
    /**
     * One reviewed alert producer encodes the owning service in a stable API
     * gateway path. Only the exact host/path pair is accepted; arbitrary URLs
     * must never be allowed to nominate a deterministic service route.
     */
    private static final Pattern CSDP_WECHAT_APPLET_URL = Pattern.compile(
            "https://csdp-applet\\.sangfor\\.com/api/(csdp-wechat)"
                    + "/scl/v1/external/get_icare_product_mapping(?:[?\\s,]|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OUTBOUND_HTTP_STATUS = Pattern.compile(
            "http\\s+code\\s+is\\s+not\\s+200\\s+code\\s*[:：]\\s*(\\d{3})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CSDP_WECHAT_OPERATION = Pattern.compile(
            "https://csdp-applet\\.sangfor\\.com/api/csdp-wechat/scl/v1/external/"
                    + "(get_icare_product_mapping)(?:[?\\s,]|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ICARE_MOBILE_FINISH_ENDPOINT = Pattern.compile(
            "https://it-gw\\.sangfor\\.com/icare/api/(sf-icare-openapi)"
                    + "/openapi/case/workOrderPhase/channel/(updateFinish)\\?"
                    + "(?:app=CSDP|[^\\\"\\r\\n]*&app=CSDP)(?=&|\\\"|\\s|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ICARE_MOBILE_FINISH_PAYLOAD_MARKER = Pattern.compile(
            "https://it-gw\\.sangfor\\.com/icare/api/sf-icare-openapi"
                    + "/openapi/case/workOrderPhase/channel/updateFinish(?:[?\\\"\\s]|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ICARE_MOBILE_FINISH_POLICY_REJECTION = Pattern.compile(
            "\\\"error\\\"\\s*:\\s*\\\""
                    + "移动端不支持该操作【工单涉及变更单】；"
                    + "请到PC端操作(?:（如PC端无法完成操作，"
                    + "请联系icare技术支持）)?\\\"",
            Pattern.CASE_INSENSITIVE);
    private static final String ICARE_REQUIRED_REVISIT_INFORMATION_REJECTION =
            "当前工单需要填写回访信息，不能完结";
    private static final Pattern URL_EPOCH_SECONDS = Pattern.compile(
            "(?:[?&])time=(\\d{10})(?:&|\\\"|$)");

    public IntakeSession start(String intakeSessionId, IntakeMessageEnvelope envelope) {
        ParsedInput parsed = parse(envelope.text());
        List<IntakeSessionEvent> initialTimeline = List.of(
                new IntakeSessionEvent(
                        envelope.receivedAt(), IntakeSessionStatus.RECEIVED,
                        envelope.sourceMessageId()));
        return build(
                intakeSessionId,
                envelope,
                parsed.symptom(),
                parsed.system(),
                parsed.service(),
                parsed.customerRef(),
                parsed.errorCode(),
                parsed.traceId(),
                parsed.occurredAt(),
                parsed.normalizedFactKind(),
                envelope.attachments(),
                envelope.receivedAt(),
                null,
                initialTimeline);
    }

    public IntakeSession accept(IntakeSession current, IntakeMessageEnvelope envelope) {
        if (current == null) {
            throw new IllegalArgumentException("current intake session must not be null");
        }
        if (current.workspaceId() != envelope.workspaceId()
                || !current.source().equals(envelope.source())
                || !current.conversationRef().equals(envelope.conversationRef())
                || !current.reporterRef().equals(envelope.reporterRef())) {
            throw new IllegalArgumentException("intake message belongs to another session");
        }
        if (current.status() == IntakeSessionStatus.READY) {
            // READY is a hand-off checkpoint. Later chat frames may be stored as
            // receipts by the coordinator, but cannot silently rewrite fields
            // that were already declared ready for investigation.
            return current;
        }
        if (!envelope.receivedAt().isAfter(current.lastMessageAt())) {
            return current;
        }
        ParsedInput parsed = parse(envelope.text());
        return build(
                current.intakeSessionId(),
                envelope,
                first(parsed.symptom(), current.symptom()),
                first(parsed.system(), current.system()),
                first(parsed.service(), current.service()),
                first(parsed.customerRef(), current.customerRef()),
                first(parsed.errorCode(), current.errorCode()),
                first(parsed.traceId(), current.traceId()),
                parsed.occurredAt() == null ? current.occurredAt() : parsed.occurredAt(),
                parsed.normalizedFactKind() == null
                        ? current.normalizedFactKind()
                        : parsed.normalizedFactKind(),
                mergeAttachments(current.attachments(), envelope.attachments()),
                current.reportedAt(),
                current.readyAt(),
                current.timeline());
    }

    /**
     * Applies a server-owned exact route match to an incomplete intake.
     *
     * <p>The route resolver may fill only the system, and only when one and
     * only one operational Playbook matches the already parsed service — with
     * the explicit error code when the report carries one, by service alone
     * when it does not. It cannot invent a code from prose or choose between
     * ambiguous systems; that single-authority check belongs to the resolver
     * and stays outside this reducer.</p>
     *
     * <p>Requiring an error code here would exclude exactly the reports that
     * need the lookup most: monitoring alerts name a service and never carry
     * a code.</p>
     */
    IntakeSession acceptResolvedSystem(
            IntakeSession current,
            IntakeMessageEnvelope envelope,
            String resolvedSystem) {
        if (current == null || current.status() == IntakeSessionStatus.READY) {
            return current;
        }
        if (!isMissing(current.system()) || isMissing(resolvedSystem)
                || isMissing(current.service())) {
            return current;
        }
        return build(
                current.intakeSessionId(),
                envelope,
                current.symptom(),
                resolvedSystem,
                current.service(),
                current.customerRef(),
                current.errorCode(),
                current.traceId(),
                current.occurredAt(),
                current.normalizedFactKind(),
                current.attachments(),
                current.reportedAt(),
                current.readyAt(),
                current.timeline());
    }

    private IntakeSession build(
            String intakeSessionId,
            IntakeMessageEnvelope envelope,
            String symptom,
            String system,
            String service,
            String customerRef,
            String errorCode,
            String traceId,
            Instant occurredAt,
            NormalizedIncidentFactKind normalizedFactKind,
            List<IntakeAttachmentRef> attachments,
            Instant reportedAt,
            Instant existingReadyAt,
            List<IntakeSessionEvent> timeline) {
        List<String> missing = missing(symptom, system, service, customerRef, occurredAt);
        IntakeSessionStatus status = missing.isEmpty()
                ? IntakeSessionStatus.READY
                : IntakeSessionStatus.AWAITING_INPUT;
        Instant readyAt = status == IntakeSessionStatus.READY
                ? existingReadyAt == null ? envelope.receivedAt() : existingReadyAt
                : null;
        List<IntakeSessionEvent> nextTimeline = new ArrayList<>(timeline);
        IntakeSessionStatus lastStatus = nextTimeline.isEmpty()
                ? null
                : nextTimeline.getLast().status();
        if (lastStatus != status) {
            nextTimeline.add(new IntakeSessionEvent(
                    envelope.receivedAt(), status, envelope.sourceMessageId()));
        }
        return new IntakeSession(
                intakeSessionId,
                IntakeSession.CURRENT_CONTRACT_VERSION,
                envelope.workspaceId(),
                envelope.source(),
                envelope.conversationRef(),
                envelope.reporterRef(),
                status,
                safe(symptom),
                safe(system),
                safe(service),
                safe(customerRef),
                safe(errorCode),
                safe(traceId),
                occurredAt,
                attachments,
                missing,
                reportedAt,
                readyAt,
                envelope.receivedAt(),
                nextTimeline,
                normalizedFactKind);
    }

    private ParsedInput parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return ParsedInput.empty();
        }
        ReviewedParse reviewed = parseReviewedIcareFinishRejection(raw);
        if (reviewed.handled()) {
            return reviewed.parsed();
        }
        Map<String, String> fields = new LinkedHashMap<>();
        List<String> freeLines = new ArrayList<>();
        for (String rawLine : raw.lines().toList()) {
            String line = stripAlertDecorations(rawLine.trim());
            if (line.isBlank() || isMediaMarker(line)) {
                continue;
            }
            Instant embeddedTime = extractEmbeddedTime(line);
            if (embeddedTime != null && !fields.containsKey("occurredAt")) {
                fields.put("occurredAt", formatLocal(embeddedTime));
            }
            int separator = separator(line);
            if (separator > 0) {
                String key = canonicalKey(line.substring(0, separator));
                if ("ignore".equals(key)) {
                    continue;
                }
                if (key != null) {
                    fields.put(key, safe(line.substring(separator + 1)));
                    continue;
                }
            }
            freeLines.add(line);
        }

        String symptom = first(
                trimMonitorItem(fields.get("symptom")),
                firstFreeSymptom(freeLines));
        symptom = enrichOutboundHttpFailure(symptom, fields.get("errorDetail"));
        String system = fields.get("system");
        String service = first(
                fields.get("service"),
                first(inferKnownService(fields.get("errorDetail")), inferService(symptom)));
        String customerRef = fields.get("customerRef");
        if (isMissing(customerRef) && looksLikeAlertWithoutCustomer(raw)) {
            customerRef = "未知";
        }
        Instant occurredAt = parseOccurredAt(fields.get("occurredAt"));
        if (occurredAt == null) {
            occurredAt = extractEmbeddedTime(raw);
        }
        return new ParsedInput(
                symptom,
                system,
                service,
                customerRef,
                resolveErrorCode(fields.get("errorCode"), raw),
                fields.get("traceId"),
                occurredAt,
                null);
    }

    private ReviewedParse parseReviewedIcareFinishRejection(String raw) {
        StrictJson strictJson = readStrictJson(raw);
        if (!strictJson.valid()) {
            return looksLikeSensitiveJson(raw)
                    ? ReviewedParse.rejected()
                    : ReviewedParse.notReviewed();
        }
        JsonNode root = strictJson.root();
        if (root == null || !root.isObject()) {
            return ReviewedParse.notReviewed();
        }
        JsonNode urlNode = root.get("url");
        if (urlNode == null || !urlNode.isTextual()
                || !ICARE_MOBILE_FINISH_PAYLOAD_MARKER.matcher(
                        urlNode.textValue().replace("&amp;", "&")).find()) {
            return ReviewedParse.notReviewed();
        }
        ReviewedIcarePayload payload = readReviewedIcarePayload(root);
        if (payload == null) {
            return ReviewedParse.rejected();
        }
        String normalizedUrl = payload.url().replace("&amp;", "&");
        Matcher endpoint = ICARE_MOBILE_FINISH_ENDPOINT.matcher(normalizedUrl);
        if (!endpoint.find()) {
            return ReviewedParse.rejected();
        }
        if (ICARE_REQUIRED_REVISIT_INFORMATION_REJECTION.equals(payload.error())
                && payload.hasStructuredRevisitResult()
                && payload.revisitResult().isBlank()) {
            return ReviewedParse.matched(new ParsedInput(
                    ReviewedIncidentPolicy.ICARE_REQUIRED_REVISIT_RESULT_MISSING_TITLE,
                    "CSDP",
                    endpoint.group(1).toLowerCase(Locale.ROOT),
                    "未知",
                    null,
                    null,
                    extractUrlEpochSeconds(normalizedUrl),
                    NormalizedIncidentFactKind.ICARE_REQUIRED_REVISIT_RESULT_MISSING));
        }
        if (!ICARE_MOBILE_FINISH_POLICY_REJECTION.matcher(
                "\"error\":\"" + payload.error() + "\"").find()) {
            return ReviewedParse.rejected();
        }
        return ReviewedParse.matched(new ParsedInput(
                ReviewedIncidentPolicy.ICARE_MOBILE_CHANGE_ORDER_FINISH_REJECTED_TITLE,
                "CSDP",
                endpoint.group(1).toLowerCase(Locale.ROOT),
                "未知",
                null,
                null,
                extractUrlEpochSeconds(normalizedUrl),
                NormalizedIncidentFactKind.ICARE_MOBILE_CHANGE_ORDER_FINISH_REJECTED));
    }

    /**
     * Parses only the three fields that are allowed to nominate this reviewed
     * incident. Strict duplicate detection prevents a later duplicate key from
     * silently replacing the value a human sees first. No parsed payload field
     * is retained outside this method.
     */
    private StrictJson readStrictJson(String raw) {
        try {
            JsonNode root = STRICT_JSON.readTree(raw);
            return new StrictJson(true, root);
        } catch (java.io.IOException invalidJson) {
            return new StrictJson(false, null);
        }
    }

    private ReviewedIcarePayload readReviewedIcarePayload(JsonNode root) {
        try {
            JsonNode url = root.get("url");
            JsonNode error = root.get("error");
            if (url == null || !url.isTextual() || error == null || !error.isTextual()) {
                return null;
            }
            JsonNode content = root.get("content");
            if (content != null && !content.isObject()) {
                return null;
            }
            JsonNode revisit = content == null ? null : content.get("revisitResult");
            if (revisit != null && !revisit.isTextual()) {
                return null;
            }
            return new ReviewedIcarePayload(
                    url.textValue(),
                    error.textValue(),
                    revisit != null,
                    revisit == null ? "" : revisit.textValue());
        } catch (RuntimeException invalidShape) {
            return null;
        }
    }

    private boolean looksLikeSensitiveJson(String raw) {
        String candidate = raw == null ? "" : raw.stripLeading();
        if (!candidate.startsWith("{")) {
            return false;
        }
        return candidate.contains("updateFinish")
                || candidate.contains("workOrderId")
                || candidate.contains("workOrderCode")
                || candidate.contains("loginPrmUser")
                || candidate.contains("syncCustomerDetail")
                || candidate.contains("revisitResult");
    }

    private record StrictJson(boolean valid, JsonNode root) {
    }

    private record ReviewedIcarePayload(
            String url,
            String error,
            boolean hasStructuredRevisitResult,
            String revisitResult) {
    }

    /**
     * Keeps a sensitive but rejected iCare payload out of generic free-text
     * parsing. The safe placeholder intentionally remains incomplete so it
     * cannot start the generic Agent path without a human resubmitting a
     * sanitized incident.
     */
    private record ReviewedParse(boolean handled, ParsedInput parsed) {

        private static ReviewedParse notReviewed() {
            return new ReviewedParse(false, null);
        }

        private static ReviewedParse rejected() {
            return new ReviewedParse(true, new ParsedInput(
                    "iCare 完结请求未通过安全的结构校验",
                    null, null, null, null, null, null, null));
        }

        private static ReviewedParse matched(ParsedInput parsed) {
            return new ReviewedParse(true, parsed);
        }
    }

    private Instant extractUrlEpochSeconds(String text) {
        Matcher matcher = URL_EPOCH_SECONDS.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        try {
            long epochSeconds = Long.parseLong(matcher.group(1));
            Instant instant = Instant.ofEpochSecond(epochSeconds);
            int year = instant.atZone(BUSINESS_ZONE).getYear();
            return year >= 2020 && year <= 2100 ? instant : null;
        } catch (NumberFormatException | java.time.DateTimeException invalid) {
            return null;
        }
    }

    private String firstFreeSymptom(List<String> freeLines) {
        for (String line : freeLines) {
            if (extractEmbeddedTime(line) != null && line.length() < 40) {
                continue;
            }
            if (line.startsWith("http://") || line.startsWith("https://")) {
                continue;
            }
            return safe(line);
        }
        return null;
    }

    private String inferService(String symptom) {
        if (symptom == null || symptom.isBlank()) {
            return null;
        }
        String cleaned = trimMonitorItem(symptom);
        // sf-icare-app-虚机-拨测检测异常 → sf-icare-app
        int zh = indexOfFirstCjk(cleaned);
        if (zh > 0) {
            String asciiPrefix = cleaned.substring(0, zh).replaceAll("[-_]+$", "");
            if (!asciiPrefix.isBlank() && asciiPrefix.length() >= 3) {
                return asciiPrefix;
            }
        }
        String[] parts = cleaned.split("[-_]");
        if (parts.length >= 2 && parts[0].matches("[A-Za-z0-9]+") && parts[1].matches("[A-Za-z0-9]+")) {
            return parts[0] + "-" + parts[1];
        }
        return null;
    }

    private String inferKnownService(String errorDetail) {
        if (errorDetail == null || errorDetail.isBlank()) {
            return null;
        }
        Matcher matcher = CSDP_WECHAT_APPLET_URL.matcher(errorDetail);
        return matcher.find() ? matcher.group(1).toLowerCase(Locale.ROOT) : null;
    }

    private String enrichOutboundHttpFailure(String symptom, String errorDetail) {
        if (symptom == null || symptom.isBlank()
                || errorDetail == null || errorDetail.isBlank()) {
            return symptom;
        }
        Matcher status = OUTBOUND_HTTP_STATUS.matcher(errorDetail);
        Matcher operation = CSDP_WECHAT_OPERATION.matcher(errorDetail);
        if (!status.find() || !operation.find()) {
            return symptom;
        }
        return safe(symptom + "（HTTP " + status.group(1)
                + " · " + operation.group(1) + "）");
    }

    private String trimMonitorItem(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.trim();
        int cut = cleaned.indexOf('，');
        if (cut > 0) {
            cleaned = cleaned.substring(0, cut).trim();
        }
        cut = cleaned.indexOf(',');
        if (cut > 0) {
            cleaned = cleaned.substring(0, cut).trim();
        }
        return cleaned;
    }

    private boolean looksLikeAlertWithoutCustomer(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        if (INFRA_ALERT_WITHOUT_CUSTOMER.matcher(raw).find()) {
            return true;
        }
        // A structured service alarm often has no customer dimension. Keep the
        // truth explicit as “未知” only when service, anomaly and event time are
        // all present; an ordinary free-text report must still be followed up.
        boolean structuredServiceAlert =
                Pattern.compile("(?m)^\\s*服务\\s*[:：]").matcher(raw).find()
                        && Pattern.compile("(?m)^\\s*(?:异常|现象)\\s*[:：]")
                                .matcher(raw).find();
        boolean reviewedGatewayAlert = CSDP_WECHAT_APPLET_URL.matcher(raw).find()
                && Pattern.compile("(?m)^\\s*告警标题\\s*[:：]").matcher(raw).find();
        return (structuredServiceAlert || reviewedGatewayAlert)
                && extractEmbeddedTime(raw) != null;
    }

    private String resolveErrorCode(String explicitFieldValue, String text) {
        if (text == null || text.isBlank()) {
            return normalizeErrorCode(explicitFieldValue);
        }
        Set<String> candidates = new LinkedHashSet<>();
        String normalizedField = normalizeErrorCode(explicitFieldValue);
        if (normalizedField != null) {
            candidates.add(normalizedField);
        }
        collectErrorCodes(EXPLICIT_ERROR_CODE, text, candidates);
        collectErrorCodes(FAILURE_SYMPTOM_ERROR_CODE, text, candidates);
        // Conflicting bracketed codes must be clarified instead of selecting
        // whichever one happened to appear first.
        return candidates.size() == 1 ? candidates.iterator().next() : null;
    }

    private String normalizeErrorCode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.startsWith("【") && normalized.endsWith("】")) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }
        return normalized.matches("[A-Za-z0-9][A-Za-z0-9._-]{2,127}")
                ? normalized
                : null;
    }

    private void collectErrorCodes(
            Pattern pattern,
            String text,
            Set<String> candidates) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            candidates.add(matcher.group(1));
        }
    }

    private Instant extractEmbeddedTime(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = EMBEDDED_LOCAL_TIME.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        return parseOccurredAt(matcher.group(1).replace('T', ' '));
    }

    private String formatLocal(Instant instant) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(BUSINESS_ZONE)
                .format(instant);
    }

    private int indexOfFirstCjk(String text) {
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN) {
                return i;
            }
        }
        return -1;
    }

    private String stripAlertDecorations(String line) {
        if (line == null) {
            return "";
        }
        return line
                .replaceFirst("^[■●◆*]\\s*", "")
                .replaceFirst("^【重要】\\s*", "")
                .trim();
    }

    private Instant parseOccurredAt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim()
                .replace('T', ' ')
                .replaceAll("\\s+", " ");
        // Drop trailing ticket crumbs: "2026-08-12 16:36:00 (r/95b771)"
        int paren = normalized.indexOf('(');
        if (paren > 0) {
            normalized = normalized.substring(0, paren).trim();
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException ignored) {
            // Try an explicit offset next.
        }
        try {
            return OffsetDateTime.parse(value.trim()).toInstant();
        } catch (DateTimeParseException ignored) {
            // Try the two documented local formats next.
        }
        for (DateTimeFormatter format : LOCAL_TIME_FORMATS) {
            try {
                return LocalDateTime.parse(normalized, format).atZone(BUSINESS_ZONE).toInstant();
            } catch (DateTimeParseException ignored) {
                // Continue through the small deterministic format set.
            }
        }
        return null;
    }

    private List<String> missing(
            String symptom,
            String system,
            String service,
            String customerRef,
            Instant occurredAt) {
        Map<String, Object> values = Map.of(
                "symptom", nullable(symptom),
                "system", nullable(system),
                "service", nullable(service),
                "customerRef", nullable(customerRef),
                "occurredAt", occurredAt == null ? "" : occurredAt);
        return REQUIRED_FIELDS.stream()
                .filter(field -> isMissing(values.get(field)))
                .toList();
    }

    private boolean isMissing(Object value) {
        return value == null || (value instanceof String text && text.isBlank());
    }

    private Object nullable(String value) {
        return value == null ? "" : value;
    }

    private List<IntakeAttachmentRef> mergeAttachments(
            List<IntakeAttachmentRef> current,
            List<IntakeAttachmentRef> incoming) {
        Map<String, IntakeAttachmentRef> merged = new LinkedHashMap<>();
        for (IntakeAttachmentRef ref : current) {
            merged.put(ref.kind() + "\u0000" + ref.reference(), ref);
        }
        for (IntakeAttachmentRef ref : incoming) {
            merged.putIfAbsent(ref.kind() + "\u0000" + ref.reference(), ref);
        }
        return List.copyOf(merged.values());
    }

    private String canonicalKey(String rawKey) {
        String key = rawKey.trim().toLowerCase(Locale.ROOT)
                .replace("_", "")
                .replace("-", "")
                .replace(" ", "");
        return switch (key) {
            case "现象", "异常", "问题", "问题现象", "symptom", "title", "告警标题", "监控项" -> "symptom";
            case "系统", "system", "业务系统" -> "system";
            case "服务", "service", "运行服务", "服务名" -> "service";
            case "客户id", "客户", "租户id", "customerid", "customerref", "影响对象" -> "customerRef";
            case "发生时间", "时间", "occurredat", "告警时间" -> "occurredAt";
            case "错误码", "errorcode" -> "errorCode";
            case "traceid", "psid", "psid/traceid" -> "traceId";
            case "错误详情", "errordetail" -> "errorDetail";
            case "集群", "数量", "说明", "告警分组", "告警级别", "告警应用", "函数", "文件", "代码行",
                    "告警url", "报警url" -> "ignore";
            default -> null;
        };
    }

    private int separator(String line) {
        int ascii = line.indexOf(':');
        int chinese = line.indexOf('：');
        if (ascii < 0) return chinese;
        if (chinese < 0) return ascii;
        return Math.min(ascii, chinese);
    }

    private boolean isMediaMarker(String line) {
        return line.matches("^【?(?:图片|视频|音频|语音|文件)(?::[^]]*)?】?$")
                || line.matches("^\\[(?:图片|视频|音频|语音|文件)(?::[^]]*)?]$");
    }

    private String first(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private String safe(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String redacted = TroubleshootingSecretRedactor.redact(value.trim());
        return redacted.length() <= 2000 ? redacted : redacted.substring(0, 2000);
    }

    private record ParsedInput(
            String symptom,
            String system,
            String service,
            String customerRef,
            String errorCode,
            String traceId,
            Instant occurredAt,
            NormalizedIncidentFactKind normalizedFactKind) {

        static ParsedInput empty() {
            return new ParsedInput(null, null, null, null, null, null, null, null);
        }
    }
}
