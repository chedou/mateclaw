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
