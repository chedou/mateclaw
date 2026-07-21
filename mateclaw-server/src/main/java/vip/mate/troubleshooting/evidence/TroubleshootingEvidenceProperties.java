package vip.mate.troubleshooting.evidence;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "mateclaw.troubleshooting.evidence")
public class TroubleshootingEvidenceProperties {

    private final DataFlux dataflux = new DataFlux();
    private final LogSearch logSearch = new LogSearch();
    private final ReleasePlatform releasePlatform = new ReleasePlatform();
    private final Guance guance = new Guance();

    @Data
    public static class DataFlux {
        private boolean enabled = false;
        private String baseUrl = "";
        private String queryPath = "/api/v1/query";
        private String token = "";
        private String tokenHeader = "Authorization";
        private String tokenPrefix = "Bearer ";
        private Duration connectTimeout = Duration.ofSeconds(2);
        private Duration readTimeout = Duration.ofSeconds(5);
        private int maxResponseChars = 4000;
    }

    @Data
    public static class LogSearch {
        private boolean enabled = false;
        private String baseUrl = "";
        private String queryPath = "/api/v1/search";
        private String token = "";
        private String tokenHeader = "Authorization";
        private String tokenPrefix = "Bearer ";
        private String window = "alert_time +/- 15m";
        private int limit = 50;
        /**
         * Optional JSON request body template for adapting to an internal log platform.
         * Placeholders use ${name}, for example:
         * {"query":"service=${serviceName} ${keywords}","limit":"${limit}"}
         */
        private String payloadTemplate = "";
        private Duration connectTimeout = Duration.ofSeconds(2);
        private Duration readTimeout = Duration.ofSeconds(5);
        private int maxResponseChars = 4000;
    }

    @Data
    public static class ReleasePlatform {
        private boolean enabled = false;
        private String baseUrl = "";
        private String queryPath = "/api/v1/releases/search";
        private String token = "";
        private String tokenHeader = "Authorization";
        private String tokenPrefix = "Bearer ";
        private String window = "alert_time - 2h to alert_time + 15m";
        private int limit = 20;
        /**
         * Optional JSON request body template for adapting to an internal release platform.
         * Placeholders use ${name}, for example:
         * {"service":"${serviceName}","env":"${env}","limit":"${limit}"}
         */
        private String payloadTemplate = "";
        private Duration connectTimeout = Duration.ofSeconds(2);
        private Duration readTimeout = Duration.ofSeconds(5);
        private int maxResponseChars = 4000;
    }

    @Data
    public static class Guance {
        private boolean enabled = false;
        private String baseUrl = "";
        private String syntheticsPath = "/api/v1/synthetics/search";
        private String metricsPath = "/api/v1/df/query_data_v1";
        private String token = "";
        private String tokenHeader = "Authorization";
        private String tokenPrefix = "Bearer ";
        private String window = "alert_time +/- 15m";
        private int limit = 20;
        private String metricsWindow = "alert_time +/- 15m";
        private int metricsLimit = 50;
        /**
         * Optional JSON request body template for adapting to Guance synthetics/dial-test APIs.
         * Placeholders use ${name}, for example:
         * {"query":"service=${serviceName} endpoint=${endpoint}","limit":"${limit}"}
         */
        private String payloadTemplate = "";
        /**
         * Named synthetics payload templates. Alert labels can select one with
         * syntheticsPayloadTemplateName / guanceSyntheticsPayloadTemplateName.
         */
        private Map<String, String> syntheticsPayloadTemplates = new LinkedHashMap<>();
        /**
         * Optional JSON request body template for adapting to Guance metrics/query APIs.
         */
        private String metricsPayloadTemplate = "";
        private Duration connectTimeout = Duration.ofSeconds(2);
        private Duration readTimeout = Duration.ofSeconds(5);
        private int maxResponseChars = 4000;
    }
}
