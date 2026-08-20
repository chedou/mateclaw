package vip.mate.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Startup security validator.
 *
 * <p>Development keeps the historical warning-only behavior. An operator that
 * explicitly enables production mode gets a fail-closed startup gate: the
 * process cannot serve traffic with default secrets, a wildcard/non-TLS CORS
 * origin, H2, or an unverifiable release identity.</p>
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@Order(1)
public class SecurityStartupValidator implements ApplicationRunner, InitializingBean {

    private static final String DEFAULT_JWT_SECRET = "MateClaw-JWT-Secret-Key-2024-Please-Change-In-Production";
    private static final int MINIMUM_SECRET_LENGTH = 32;

    private final boolean productionEnabled;
    private final String jwtSecret;
    private final boolean h2ConsoleEnabled;
    private final String corsOrigins;
    private final String settingKey;
    private final String datasourceUrl;
    private final String releaseCommit;

    public SecurityStartupValidator(
            @Value("${mateclaw.production.enabled:false}") boolean productionEnabled,
            @Value("${mateclaw.jwt.secret}") String jwtSecret,
            @Value("${spring.h2.console.enabled:false}") boolean h2ConsoleEnabled,
            @Value("${mateclaw.cors.allowed-origins:*}") String corsOrigins,
            @Value("${mateclaw.setting.key:}") String settingKey,
            @Value("${spring.datasource.url:}") String datasourceUrl,
            @Value("${mateclaw.release.commit:unknown}") String releaseCommit) {
        this.productionEnabled = productionEnabled;
        this.jwtSecret = jwtSecret;
        this.h2ConsoleEnabled = h2ConsoleEnabled;
        this.corsOrigins = corsOrigins;
        this.settingKey = settingKey;
        this.datasourceUrl = datasourceUrl;
        this.releaseCommit = releaseCommit;
    }

    @Override
    public void afterPropertiesSet() {
        if (productionEnabled) {
            requireHardenedProductionRuntime();
        }
    }

    @Override
    public void run(ApplicationArguments args) {
        if (productionEnabled) {
            log.info("[Security] Production startup gate passed.");
            return;
        }

        boolean hasWarnings = false;

        if (DEFAULT_JWT_SECRET.equals(jwtSecret)) {
            log.warn("╔══════════════════════════════════════════════════════════════╗");
            log.warn("║  SECURITY WARNING: Using default JWT secret!                ║");
            log.warn("║  Set JWT_SECRET environment variable for production.        ║");
            log.warn("╚══════════════════════════════════════════════════════════════╝");
            hasWarnings = true;
        }

        if (h2ConsoleEnabled) {
            log.warn("[Security] H2 Console is enabled at /h2-console. Set H2_CONSOLE_ENABLED=false in production.");
            hasWarnings = true;
        }

        if ("*".equals(corsOrigins.trim())) {
            log.warn("[Security] CORS allows all origins. Set MATECLAW_CORS_ALLOWED_ORIGINS in production.");
            hasWarnings = true;
        }

        if (!hasWarnings) {
            log.info("[Security] Startup security check passed.");
        }
    }

    private void requireHardenedProductionRuntime() {
        List<String> blockers = new ArrayList<>();
        if (isBlank(jwtSecret)
                || DEFAULT_JWT_SECRET.equals(jwtSecret)
                || jwtSecret.length() < MINIMUM_SECRET_LENGTH) {
            blockers.add("JWT_SECRET must be a dedicated secret of at least 32 characters");
        }
        if (h2ConsoleEnabled) {
            blockers.add("H2_CONSOLE_ENABLED must be false");
        }
        if (!hasOnlyHttpsOrigins(corsOrigins)) {
            blockers.add("MATECLAW_CORS_ALLOWED_ORIGINS must contain only explicit HTTPS origins");
        }
        if (isBlank(settingKey) || settingKey.length() < MINIMUM_SECRET_LENGTH) {
            blockers.add("MATECLAW_SETTING_KEY must be stable and at least 32 characters");
        }
        if (!isSupportedProductionDatabase(datasourceUrl)) {
            blockers.add("spring.datasource.url must use a supported external production database");
        }
        if (isBlank(releaseCommit) || !releaseCommit.trim().matches("[0-9a-fA-F]{40}")) {
            blockers.add("MATECLAW_RELEASE_COMMIT must be the full 40-character Git SHA");
        }
        if (!blockers.isEmpty()) {
            throw new IllegalStateException(
                    "Production startup security gate failed: " + String.join("; ", blockers));
        }
    }

    private static boolean hasOnlyHttpsOrigins(String origins) {
        if (isBlank(origins)) {
            return false;
        }
        return Arrays.stream(origins.split(","))
                .map(String::trim)
                .allMatch(SecurityStartupValidator::isExplicitHttpsOrigin);
    }

    private static boolean isExplicitHttpsOrigin(String origin) {
        if (origin.isEmpty() || "*".equals(origin)) {
            return false;
        }
        try {
            URI uri = new URI(origin);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && !isBlank(uri.getHost())
                    && uri.getUserInfo() == null
                    && uri.getQuery() == null
                    && uri.getFragment() == null
                    && (isBlank(uri.getPath()) || "/".equals(uri.getPath()));
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    private static boolean isSupportedProductionDatabase(String url) {
        if (isBlank(url)) {
            return false;
        }
        String normalized = url.trim().toLowerCase();
        return normalized.startsWith("jdbc:mysql:")
                || normalized.startsWith("jdbc:postgresql:")
                || normalized.startsWith("jdbc:kingbase8:");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
