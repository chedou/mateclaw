package vip.mate.system;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Fails the deployment health gate when the merged migration streams were
 * applied over an incompatible V172 lineage.
 */
@Component("migrationLineage")
public final class MigrationLineageHealthIndicator implements HealthIndicator {

    static final MigrationVersion MINIMUM_COMBINED_VERSION =
            MigrationVersion.fromVersion("217");

    private final Flyway flyway;
    private final JdbcTemplate jdbcTemplate;

    public MigrationLineageHealthIndicator(Flyway flyway, JdbcTemplate jdbcTemplate) {
        this.flyway = flyway;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Health health() {
        try {
            MigrationInfo current = flyway.info().current();
            MigrationVersion version = current == null ? null : current.getVersion();
            if (version == null || version.compareTo(MINIMUM_COMBINED_VERSION) < 0) {
                return Health.down()
                        .withDetail("reason", "combined migration stream is incomplete")
                        .withDetail("minimumVersion", MINIMUM_COMBINED_VERSION.getVersion())
                        .withDetail("currentVersion", version == null ? "none" : version.getVersion())
                        .build();
            }

            // Both roots must exist. A database that previously followed only
            // the dev Agent-Team V172 stream or only the troubleshooting V172
            // stream must not become healthy after checksum auto-repair.
            assertTableExists("mate_troubleshooting_diagnosis");
            assertTableExists("mate_agent_team");
            return Health.up()
                    .withDetail("version", version.getVersion())
                    .withDetail("lineage", "troubleshooting+agent-team")
                    .build();
        } catch (RuntimeException failure) {
            return Health.down()
                    .withDetail("reason", "combined migration lineage is not usable")
                    .withException(failure)
                    .build();
        }
    }

    private void assertTableExists(String table) {
        jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE 1 = 0",
                Long.class);
    }
}
