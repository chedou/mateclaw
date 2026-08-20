package vip.mate.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SecurityStartupValidatorTest {

    private static final String DEFAULT_JWT =
            "MateClaw-JWT-Secret-Key-2024-Please-Change-In-Production";
    private static final String RELEASE_COMMIT =
            "0297d8801cc3707fcde040c8d9504e7fc4ea1c59";

    @Test
    void developmentModeKeepsBackwardCompatibleWarnings() {
        SecurityStartupValidator validator = new SecurityStartupValidator(
                false,
                DEFAULT_JWT,
                true,
                "*",
                "",
                "jdbc:h2:file:./data/mateclaw",
                "unknown");

        assertThatCode(validator::afterPropertiesSet).doesNotThrowAnyException();
        assertThatCode(() -> validator.run(null)).doesNotThrowAnyException();
    }

    @Test
    void productionModeAcceptsOnlyAProvableHardenedRuntime() {
        SecurityStartupValidator validator = new SecurityStartupValidator(
                true,
                "a-dedicated-production-jwt-secret-with-more-than-32-characters",
                false,
                "https://smartfix.sangfor.com",
                "a-stable-setting-encryption-key-with-more-than-32-characters",
                "jdbc:mysql://db.internal:3306/mateclaw",
                RELEASE_COMMIT);

        assertThatCode(validator::afterPropertiesSet).doesNotThrowAnyException();
        assertThatCode(() -> validator.run(null)).doesNotThrowAnyException();
    }

    @Test
    void productionModeFailsClosedAndNamesEveryUnsafeSetting() {
        SecurityStartupValidator validator = new SecurityStartupValidator(
                true,
                DEFAULT_JWT,
                true,
                "*",
                "",
                "jdbc:h2:file:./data/mateclaw",
                "unknown");

        assertThatThrownBy(validator::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET")
                .hasMessageContaining("H2_CONSOLE_ENABLED")
                .hasMessageContaining("MATECLAW_CORS_ALLOWED_ORIGINS")
                .hasMessageContaining("MATECLAW_SETTING_KEY")
                .hasMessageContaining("spring.datasource.url")
                .hasMessageContaining("MATECLAW_RELEASE_COMMIT");
    }

    @Test
    void productionModeRejectsShortSecretsAndNonHttpsOrigins() {
        SecurityStartupValidator validator = new SecurityStartupValidator(
                true,
                "short-jwt",
                false,
                "http://smartfix.sangfor.com",
                "short-setting-key",
                "jdbc:mysql://db.internal:3306/mateclaw",
                RELEASE_COMMIT);

        assertThatThrownBy(validator::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET")
                .hasMessageContaining("MATECLAW_CORS_ALLOWED_ORIGINS")
                .hasMessageContaining("MATECLAW_SETTING_KEY");
    }

    @Test
    void productionModeRejectsMalformedOriginsAndUnsupportedJdbcDrivers() {
        SecurityStartupValidator validator = new SecurityStartupValidator(
                true,
                "a-dedicated-production-jwt-secret-with-more-than-32-characters",
                false,
                "https://smartfix.sangfor.com/admin",
                "a-stable-setting-encryption-key-with-more-than-32-characters",
                "jdbc:h2:file:./data/mateclaw",
                RELEASE_COMMIT);

        assertThatThrownBy(validator::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MATECLAW_CORS_ALLOWED_ORIGINS")
                .hasMessageContaining("spring.datasource.url");
    }
}
