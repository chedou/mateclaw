package vip.mate.troubleshooting.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.IncidentContext;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 只读 Prometheus 取证适配器（含 VictoriaMetrics / Thanos / Mimir 等兼容实现）。
 *
 * <p><b>Why this one.</b> Guance 的真源验收卡在内网窗口上，而 Prometheus 是企业
 * IT 里最普遍的指标源。它给的是一条**不依赖那扇窗口**的真实证据通路：谁手里有
 * Prometheus，谁就能先拿到真数据，而不必等 T7。</p>
 *
 * <p><b>它绝不自己编 canonical 值。</b> Playbook 的判据读的是
 * {@code connections_current}、{@code slow_query_count} 这类固定字段；这里只做
 * 「PromQL 结果 → 指定字段」的搬运，映射由 binding 显式给出。任何一步不确定
 * ——查询没配、返回非 200、JSON 结构不符、结果为空、值不是有限数——一律返回
 * {@link EvidenceStatus#MISSING}，而不是补一个 0。**一个编出来的 0 会让判据
 * 求值成功并给出结论，而那个结论没有任何观测支撑**；缺一条证据只会让系统弃权，
 * 那是便宜得多的错误。</p>
 *
 * <p><b>只读到什么程度。</b> 只发 GET {@code /api/v1/query}，不写、不删、不改
 * 告警规则。PromQL 由 binding 配置提供，不接受来自报障文本或模型的拼接——
 * 否则报障人就能通过一段文字决定去查什么。</p>
 */
public final class PrometheusEvidenceAdapter implements EvidenceSourceAdapter {

    public static final String PLATFORM = "prometheus";

    private static final String SIGNAL_KIND = "metric";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    /** Bounded so a pathological binding cannot turn one request into a scan. */
    private static final int MAX_QUERIES = 8;
    private static final Pattern SAFE_FIELD = Pattern.compile("[a-z][a-z0-9_]{0,63}");

    private final Binding binding;
    private final EvidenceHttpTransport transport;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PrometheusEvidenceAdapter(
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
     * One workspace's authorized Prometheus endpoint and its field mapping.
     *
     * @param fieldQueries canonical field name → PromQL. Supplied by
     *     configuration only; nothing derived from an incident report or a model
     *     may reach it, or the reporter would be choosing what gets queried.
     */
    public record Binding(URI endpoint, Map<String, String> fieldQueries, String bearerToken) {

        public Binding {
            // LinkedHashMap 拷贝：保留配置顺序，取证失败时报出来的字段才是可复现的
            fieldQueries = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(
                    fieldQueries == null ? Map.of() : fieldQueries));
        }

        boolean usable() {
            if (endpoint == null || fieldQueries.isEmpty()
                    || fieldQueries.size() > MAX_QUERIES) {
                return false;
            }
            String scheme = endpoint.getScheme();
            if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
                return false;
            }
            boolean everyQueryIsSane = fieldQueries.entrySet().stream().allMatch(entry ->
                    entry.getKey() != null && SAFE_FIELD.matcher(entry.getKey()).matches()
                            && entry.getValue() != null && !entry.getValue().isBlank());
            // The canonical `metric` signal is a bundle, not a menu: a partial
            // mapping can never produce a valid result. Accepting one would let
            // health report READY while every single collect returns MISSING —
            // "看起来就绪、实则永远取不到" is worse than an honest DEGRADED.
            return everyQueryIsSane
                    && fieldQueries.keySet().equals(CanonicalEvidenceSchema.fields(SIGNAL_KIND));
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
            return missing(request, "prometheus binding is not configured for this signal");
        }
        Map<String, Object> observed = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : binding.fieldQueries().entrySet()) {
            Double value = scalar(entry.getValue());
            if (value == null) {
                // 部分字段取不到就整条判 MISSING：半份指标会让判据算出一个
                // 看起来成立、实则没有依据的结论。
                return missing(request, "prometheus returned no usable value for "
                        + entry.getKey());
            }
            if (CanonicalEvidenceSchema.isBooleanField(SIGNAL_KIND, entry.getKey())) {
                // Prometheus 对一切都返回数字，包括 `up`。声明为布尔的字段只接受
                // 0 或 1——把 0.5 或 2 当成 true，就是替观测数据下了一个它没给的判断。
                if (value != 0.0d && value != 1.0d) {
                    return missing(request, "prometheus returned a non-boolean value for "
                            + entry.getKey());
                }
                observed.put(entry.getKey(), value == 1.0d);
            } else {
                observed.put(entry.getKey(), value);
            }
        }
        if (!CanonicalEvidenceSchema.isValid(SIGNAL_KIND, observed)) {
            return missing(request, "prometheus result violates the canonical contract");
        }
        return new EvidenceResult(
                request.requestId(),
                "M",
                "prometheus:/api/v1/query",
                // 适配器只搬运观测值，不判断异常与否——那是 Playbook 判据的事。
                EvidenceStatus.NORMAL,
                "Prometheus 即时查询结果",
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
                    false, "endpoint or canonical field mapping is incomplete");
        }
        // verified stays false: a reachable endpoint is not a verified one, and
        // only an owner acceptance may claim otherwise.
        // verified 恒 false，且**目前无法变成 true**——本适配器还没有 owner 验收
        // 接缝（Guance 有 V184）。把指纹一并露出来，是为了让运维看得见「将来那次
        // 验收要钉在哪一串上」，以及配置一改它就变。指纹不含凭据，可安全外露。
        return new EvidenceSourceHealth(PLATFORM, EvidenceSourceHealth.Status.READY,
                false, "endpoint configured; field mapping bound"
                        + "; binding=" + bindingFingerprint()
                        + "; 未验收（本适配器尚无 owner 验收接缝）");
    }


    /**
     * 这份绑定的指纹——owner 验收要钉在它上面。
     *
     * <p><b>放什么、不放什么，是这里唯一要想清楚的事。</b></p>
     *
     * <ul>
     *   <li><b>放</b>：端点、每个 canonical 字段绑定的 PromQL。owner 验收的是「查这个地方、用这些查询」，
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
                + "\u001f" + binding.fieldQueries().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(entry -> entry.getKey() + "=" + entry.getValue())
                        .collect(java.util.stream.Collectors.joining("\u001e")));
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

    /** @return the single finite scalar the query produced, or null — never a substitute */
    private Double scalar(String promQl) {
        JsonNode root;
        try {
            URI uri = URI.create(binding.endpoint().toString().replaceAll("/+$", "")
                    + "/api/v1/query?query="
                    + URLEncoder.encode(promQl, StandardCharsets.UTF_8));
            Map<String, String> headers = binding.bearerToken() == null
                    ? Map.of("Accept", "application/json")
                    : Map.of("Accept", "application/json",
                            "Authorization", "Bearer " + binding.bearerToken());
            EvidenceHttpTransport.Response response = transport.get(uri, headers, TIMEOUT);
            if (response.statusCode() != 200) {
                return null;
            }
            root = objectMapper.readTree(response.body());
        } catch (Exception unreachable) {
            return null;
        }
        if (!"success".equals(root.path("status").asText(""))) {
            return null;
        }
        JsonNode result = root.path("data").path("result");
        // Exactly one series. Two series mean the query did not identify a
        // single thing, and silently taking the first would answer a question
        // nobody asked.
        if (!result.isArray() || result.size() != 1) {
            return null;
        }
        JsonNode sample = result.get(0).path("value");
        if (!sample.isArray() || sample.size() != 2) {
            return null;
        }
        try {
            double parsed = Double.parseDouble(sample.get(1).asText());
            return Double.isFinite(parsed) ? parsed : null;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private EvidenceResult missing(EvidenceRequest request, String summary) {
        return new EvidenceResult(
                request.requestId(), "UNKNOWN", "", EvidenceStatus.MISSING,
                summary, Map.of(), PLATFORM + ":unavailable", Instant.now(clock));
    }

    /** The canonical field names a binding may map onto, for configuration UIs. */
    public static java.util.Set<String> canonicalFields() {
        return CanonicalEvidenceSchema.fields(SIGNAL_KIND);
    }
}
