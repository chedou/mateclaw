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

    @Getter
    @Setter
    public static class Guance {
        private boolean enabled;
        private String baseUrl;
        private String apiKey;
        private boolean allowInsecureHttp;
        private String queryPath = "/api/v1/df/query_data_v1";
        private Duration timeout = Duration.ofSeconds(5);
        private Map<String, Binding> bindings = new LinkedHashMap<>();
    }

    @Getter
    @Setter
    public static class Binding {
        private String namespace = "UNKNOWN";
        private String summary = "";
        private String queryTemplate;

        /** Maps a source column name to the canonical field consumed by criteria. */
        private Map<String, String> fieldAliases = new LinkedHashMap<>();
    }

    @Getter
    @Setter
    public static class RecordedReplay {
        private boolean enabled;
        private String resource =
                "classpath:/troubleshooting/evidence/recorded-replay-903001.json";
    }
}
