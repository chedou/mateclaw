package vip.mate.troubleshooting.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import vip.mate.troubleshooting.TroubleshootingSecretRedactor;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.IncidentContext;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 只读 Elasticsearch / OpenSearch 日志检索适配器，服务 {@code log_search}。
 *
 * <p><b>关联字段必须由环境显式给出，这里不猜。</b> canonical 的
 * {@code log_search} 要求一个 {@code ps_id}——一次请求在跨服务日志里的串联键。
 * 不同环境叫法完全不同：{@code trace.id}、{@code traceId}、
 * {@code x_request_id}、{@code ps_id}……**猜错的后果不是取不到，是把两次不相干
 * 的请求当成同一次**，而下游的全链路日志包会照单全收。所以
 * {@code correlationField} 没配就直接不可用，而不是挑一个看起来像的。</p>
 *
 * <p><b>只读到什么程度。</b> 只发 {@code POST /{index}/_search}，query 由服务端
 * 用参数化结构拼装——报障文本只作为 {@code match_phrase} 的**值**进入，永远不会
 * 变成查询结构的一部分。ES 的 query DSL 是 JSON，把用户文本拼进结构里等于开放了
 * 一个查询注入面。</p>
 */
public final class ElasticsearchEvidenceAdapter implements EvidenceSourceAdapter {

    public static final String PLATFORM = "elasticsearch";

    private static final String SIGNAL_KIND = "log_search";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int SAMPLE_SIZE = 1;
    private static final int MAX_SEARCH_TERM = 256;
    private static final Pattern SAFE_INDEX = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._*-]{0,127}");
    private static final Pattern SAFE_FIELD = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}");

    private final Binding binding;
    private final EvidenceHttpTransport transport;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ElasticsearchEvidenceAdapter(
            Binding binding,
            EvidenceHttpTransport transport,
            ObjectMapper objectMapper,
            Clock clock) {
        this.binding = binding;
        this.transport = Objects.requireNonNull(transport, "transport");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * @param correlationField 这个环境用哪个字段串联一次请求。**没有默认值**：
     *     猜错会把两次不相干的请求当成同一次
     * @param messageField     日志正文字段，用于取一条脱敏样本
     */
    public record Binding(
            URI endpoint,
            String index,
            String correlationField,
            String messageField,
            String bearerToken) {

        boolean usable() {
            if (endpoint == null) {
                return false;
            }
            String scheme = endpoint.getScheme();
            return ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    && index != null && SAFE_INDEX.matcher(index).matches()
                    && correlationField != null && SAFE_FIELD.matcher(correlationField).matches()
                    && messageField != null && SAFE_FIELD.matcher(messageField).matches();
        }
    }

    @Override
    public String platform() {
        return PLATFORM;
    }

    @Override
    public boolean supports(String signalKind) {
        return SIGNAL_KIND.equals(signalKind) && binding != null && binding.usable();
    }

    @Override
    public EvidenceResult collect(
            long workspaceId, EvidenceRequest request, IncidentContext incident) {
        if (workspaceId <= 0 || request == null || incident == null) {
            throw new IllegalArgumentException("workspace, request and incident are required");
        }
        if (!supports(request.signalKind())) {
            return missing(request, "elasticsearch binding is not configured for this signal");
        }
        Object rawTerm = request.target().get("search_term");
        if (!(rawTerm instanceof String searchTerm)
                || searchTerm.isBlank()
                || searchTerm.length() > MAX_SEARCH_TERM) {
            return missing(request, "log_search requires a bounded search_term");
        }

        JsonNode root = search(searchTerm);
        if (root == null) {
            return missing(request, "elasticsearch returned no usable response");
        }
        long matchCount = totalHits(root);
        if (matchCount < 0) {
            return missing(request, "elasticsearch response has no readable hit total");
        }
        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("match_count", matchCount);

        JsonNode firstHit = root.path("hits").path("hits").path(0).path("_source");
        String correlationId = text(firstHit, binding.correlationField());
        if (correlationId == null) {
            // 没有串联键，后面那步「按 PS ID 取回全链路」就无从谈起。
            // 报 MISSING 好过给一条无法继续的半份证据。
            return missing(request, "no value at the configured correlation field "
                    + binding.correlationField());
        }
        observed.put("ps_id", correlationId);
        String message = text(firstHit, binding.messageField());
        if (message != null) {
            observed.put("sample_message",
                    TroubleshootingSecretRedactor.redact(message));
        }
        if (!CanonicalEvidenceSchema.isValid(SIGNAL_KIND, observed)) {
            return missing(request, "elasticsearch result violates the canonical contract");
        }
        return new EvidenceResult(
                request.requestId(),
                "L",
                "elasticsearch:" + binding.index() + "/_search",
                // 命中数多少算异常是 Playbook 判据的事，不是适配器的。
                EvidenceStatus.NORMAL,
                "Elasticsearch 日志检索结果",
                observed,
                PLATFORM,
                Instant.now(clock));
    }

    @Override
    public EvidenceSourceHealth health() {
        if (binding == null) {
            return new EvidenceSourceHealth(PLATFORM, EvidenceSourceHealth.Status.DISABLED,
                    false, "adapter disabled");
        }
        if (!binding.usable()) {
            return new EvidenceSourceHealth(PLATFORM, EvidenceSourceHealth.Status.DEGRADED,
                    false, "endpoint, index or correlation field is missing or unsafe");
        }
        // verified 恒 false，且**目前无法变成 true**——本适配器还没有 owner 验收
        // 接缝（Guance 有 V184）。把指纹一并露出来，是为了让运维看得见「将来那次
        // 验收要钉在哪一串上」，以及配置一改它就变。指纹不含凭据，可安全外露。
        return new EvidenceSourceHealth(PLATFORM, EvidenceSourceHealth.Status.READY,
                false, "index and correlation field bound"
                        + "; binding=" + bindingFingerprint()
                        + "; 未验收（本适配器尚无 owner 验收接缝）");
    }


    /**
     * 这份绑定的指纹——owner 验收要钉在它上面。
     *
     * <p><b>放什么、不放什么，是这里唯一要想清楚的事。</b></p>
     *
     * <ul>
     *   <li><b>放</b>：端点、索引、串联字段、正文字段。owner 验收的是「查这个地方、用这些查询」，
     *       其中任何一项变了，之前那次验收就不再指向同一件事，必须自动失效。</li>
     *   <li><b>不放 token 本身</b>：轮换凭据不改变被查的是什么，却会让每次轮换
     *       都白白作废一次验收，逼人形成「重新验收 = 走个过场」的习惯——
     *       那比不验收更糟。</li>
     *   <li><b>但放「是否带鉴权」这个布尔</b>：从匿名改成带 Bearer（或反过来）
     *       换掉的是授权路径，不是同一件事了。</li>
     * </ul>
     *
     * <p>指纹本身不含凭据，可以安全地存进验收记录与日志。</p>
     */
    public String bindingFingerprint() {
        if (binding == null || !binding.usable()) {
            return null;
        }
        return sha256(PLATFORM + "\u001f" + binding.endpoint().toString()
                + "\u001f" + (binding.bearerToken() == null ? "anon" : "bearer")
                + "\u001f" + binding.index()
                + "\u001f" + binding.correlationField()
                + "\u001f" + binding.messageField());
    }

    private static String sha256(String canonical) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest) {
                hex.append(Character.forDigit((value >> 4) & 0xF, 16))
                        .append(Character.forDigit(value & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    private JsonNode search(String searchTerm) {
        try {
            // 参数化：报障文本只作为 match_phrase 的值，永远不进入查询结构。
            Map<String, Object> query = Map.of(
                    "size", SAMPLE_SIZE,
                    "track_total_hits", true,
                    "query", Map.of("match_phrase",
                            Map.of(binding.messageField(), searchTerm)));
            URI uri = URI.create(binding.endpoint().toString().replaceAll("/+$", "")
                    + "/" + binding.index() + "/_search");
            Map<String, String> headers = binding.bearerToken() == null
                    ? Map.of("Content-Type", "application/json", "Accept", "application/json")
                    : Map.of("Content-Type", "application/json", "Accept", "application/json",
                            "Authorization", "Bearer " + binding.bearerToken());
            EvidenceHttpTransport.Response response = transport.postJson(
                    uri, headers, objectMapper.writeValueAsString(query), TIMEOUT);
            if (response.statusCode() != 200) {
                return null;
            }
            return objectMapper.readTree(response.body());
        } catch (Exception unreachable) {
            return null;
        }
    }

    /** @return the hit total, or -1 when the response never stated one */
    private long totalHits(JsonNode root) {
        JsonNode total = root.path("hits").path("total");
        // ES 7+ 是对象 {value,relation}；更早的版本与部分兼容实现是裸数字。
        if (total.isObject() && total.path("value").isNumber()) {
            return total.path("value").asLong();
        }
        return total.isNumber() ? total.asLong() : -1L;
    }

    private String text(JsonNode source, String field) {
        JsonNode node = source;
        // 支持 `trace.id` 这类点分路径；ES 的 _source 是嵌套 JSON。
        for (String segment : field.split("\\.")) {
            node = node.path(segment);
        }
        if (!node.isValueNode() || node.isNull()) {
            return null;
        }
        String value = node.asText("").trim();
        return value.isEmpty() ? null : value;
    }

    private EvidenceResult missing(EvidenceRequest request, String summary) {
        return new EvidenceResult(
                request.requestId(), "UNKNOWN", "", EvidenceStatus.MISSING,
                summary, Map.of(), PLATFORM + ":unavailable", Instant.now(clock));
    }
}
