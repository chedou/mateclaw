package vip.mate.tool.itdb;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@ConfigurationProperties(prefix = "mateclaw.itdb")
public class ItDbWorkflowProperties {

    private boolean enabled;
    private String baseUrl = "http://itdb.sangfor.com";
    private String username = "";
    private String password = "";
    private boolean allowInsecureHttp;
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(20);
    private List<String> allowedHosts = new ArrayList<>(List.of("itdb.sangfor.com"));

    public boolean configured() {
        return enabled && username != null && !username.isBlank()
                && password != null && !password.isBlank();
    }

    public URI validatedBaseUri() {
        URI uri;
        try {
            uri = URI.create(baseUrl == null ? "" : baseUrl.strip());
        } catch (IllegalArgumentException e) {
            throw new ItDbWorkflowException("INVALID_BASE_URL", "ITDB base URL is invalid");
        }
        if (uri.getHost() == null) {
            throw new ItDbWorkflowException("INVALID_BASE_URL", "ITDB base URL must include a host");
        }
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        boolean explicitlyAllowedHttp = allowInsecureHttp && "http".equalsIgnoreCase(uri.getScheme());
        if (!https && !explicitlyAllowedHttp) {
            throw new ItDbWorkflowException("INVALID_BASE_URL",
                    "ITDB base URL must use HTTPS unless trusted-network HTTP is explicitly enabled");
        }
        boolean allowed = allowedHosts != null && allowedHosts.stream()
                .filter(host -> host != null && !host.isBlank())
                .anyMatch(host -> uri.getHost().equalsIgnoreCase(host.strip()));
        if (!allowed) {
            throw new ItDbWorkflowException("HOST_NOT_ALLOWED", "ITDB host is not in the deployment allowlist");
        }
        String normalized = uri.toString();
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return URI.create(normalized);
    }

}
