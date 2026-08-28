package vip.mate.config;

import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Flyway migration configuration.
 * <p>
 * Repair is deliberately disabled by default. A failed migration or checksum
 * mismatch is production evidence and must stop startup until an operator has
 * reviewed it. Desktop-style self-healing remains an explicit opt-in through
 * {@code mateclaw.flyway.auto-repair=true}.
 *
 * @author MateClaw Team
 */
@Slf4j
@Configuration
public class FlywayRepairConfig {

    @Bean
    public FlywayMigrationInitializer flywayInitializer(
            Flyway flyway,
            @Value("${mateclaw.flyway.auto-repair:false}") boolean autoRepair) {
        return new FlywayMigrationInitializer(flyway, f -> {
            if (autoRepair) {
                log.warn("[Flyway] Explicit auto-repair is enabled; repairing before migrate");
                f.repair();
            } else {
                log.info("[Flyway] Auto-repair is disabled; migration failures remain fail-closed");
            }
            f.migrate();
        });
    }
}
