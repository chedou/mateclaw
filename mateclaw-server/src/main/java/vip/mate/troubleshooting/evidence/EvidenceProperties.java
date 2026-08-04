package vip.mate.troubleshooting.evidence;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Configuration for platform-neutral evidence routing and source bindings. */
@Getter
@Setter
@ConfigurationProperties(prefix = "mateclaw.troubleshooting.evidence")
public class EvidenceProperties {

    /** Explicit {@code system -> signal kind -> ordered source names} routes. */
    private Map<String, Map<String, List<String>>> routes = new LinkedHashMap<>();

    /** Optional ordered fallback used only when an operator explicitly sets it. */
    private List<String> defaultSources = List.of();

    private Guance guance = new Guance();

    private RecordedReplay recordedReplay = new RecordedReplay();

    private Prometheus prometheus = new Prometheus();

    private Elasticsearch elasticsearch = new Elasticsearch();

    private SynthesisPreview synthesisPreview = new SynthesisPreview();

    @Getter
    @Setter
    public static class Guance {
        private boolean enabled;
        private String baseUrl;
        private String apiKey;
        private boolean allowInsecureHttp;
        /** HTTP implementation. The native curl path is opt-in for local pilot compatibility only. */
        private String transport = "jdk";
        private String nativeCurlExecutable = "/usr/bin/curl";
        private String queryPath = "/api/v1/df/query_data_v1";
        private Duration timeout = Duration.ofSeconds(5);
        private Map<String, Binding> bindings = new LinkedHashMap<>();

        /**
         * Exact tenant/resource authorizations. An API key and a binding alone
         * never authorize a query.
         */
        private List<AssetBinding> assetBindings = List.of();
    }

    /**
     * Maps one MateClaw workspace resource to explicitly named Guance bindings.
     * System, service, and signal keys are exact after case/whitespace normalization;
     * wildcards and default bindings are intentionally unsupported.
     */
    @Getter
    @Setter
    public static class AssetBinding {
        private long workspaceId;
        private String system;
        private String service;
        private Map<String, String> signalBindings = new LinkedHashMap<>();
    }

    @Getter
    @Setter
    public static class Binding {
        private String namespace = "UNKNOWN";
        private String summary = "";
        /** Developer-facing scenario name; it never participates in a source query. */
        private String scenario = "";
        /** The operational question this reviewed contract answers. */
        private String question = "";
        /** Safe summaries of fixed server-owned filters; never raw DQL. */
        private List<String> fixedConditions = List.of();
        private String queryTemplate;
        /** Ordered DQL components for one compound read-only evidence contract. */
        private List<String> queryTemplates = List.of();
        private QueryOptions queryOptions;

        /** Maximum accepted rows; Guance receives one extra overflow sentinel row. */
        private int maxRows = 200;

        /** Maps a source column name to the canonical field consumed by criteria. */
        private Map<String, String> fieldAliases = new LinkedHashMap<>();

        /** Server-owned canonical literals that describe a configured aggregate. */
        private Map<String, String> constantFields = new LinkedHashMap<>();
    }

    /** Optional Guance query envelope fields owned by one concrete binding. */
    @Getter
    @Setter
    public static class QueryOptions {
        private int maxPointCount = 720;
        private int interval = 10;
        private boolean alignTime = true;
        private int seriesLimit = 20;
        private boolean disableSampling;
        private String timeZone = "Asia/Shanghai";
    }

    /**
     * 只读 Prometheus 绑定（含 VictoriaMetrics / Thanos / Mimir）。
     *
     * <p>默认关闭。{@code fieldQueries} 必须覆盖 canonical {@code metric} 的
     * 全部字段——那个信号是一个整包，缺一个字段的绑定永远产不出合法结果，
     * 而 health 会诚实地报 DEGRADED 而不是 READY。</p>
     */
    @Getter
    @Setter
    public static class Prometheus {
        private boolean enabled;
        private String baseUrl;
        /** 可选。留空表示端点不需要 Bearer 鉴权；这里不接受用户名密码。 */
        private String bearerToken;
        /** canonical 字段名 → PromQL。只来自配置，不接受报障文本或模型拼接。 */
        private Map<String, String> fieldQueries = new LinkedHashMap<>();
    }

    /**
     * 只读 Elasticsearch / OpenSearch 绑定，服务 {@code log_search}。
     *
     * <p>{@code correlationField} **没有默认值**：一次请求在跨服务日志里的
     * 串联键各环境叫法不同（{@code trace.id} / {@code traceId} /
     * {@code x_request_id}…）。猜错的后果不是取不到，是把两次不相干的请求
     * 当成同一次，而下游会照单全收。没配就整个不可用。</p>
     */
    @Getter
    @Setter
    public static class Elasticsearch {
        private boolean enabled;
        private String baseUrl;
        private String index;
        /** 本环境用哪个字段串联一次请求。留空 = 适配器不可用。 */
        private String correlationField;
        private String messageField = "message";
        private String bearerToken;
    }

    @Getter
    @Setter
    public static class RecordedReplay {
        private boolean enabled;
        private String resource =
                "classpath:/troubleshooting/evidence/recorded-replay-catalog.json";
    }

    /** Explicit fixture-only scope for the read-only synthesis preview. */
    @Getter
    @Setter
    public static class SynthesisPreview {
        private long fixtureWorkspaceId = 1L;
        private Map<String, List<String>> fixtureServices = new LinkedHashMap<>(
                Map.of("CSDP", List.of("csdp-session-service")));
    }
}
