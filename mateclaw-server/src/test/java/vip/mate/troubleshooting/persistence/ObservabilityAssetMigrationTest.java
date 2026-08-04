package vip.mate.troubleshooting.persistence;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservabilityAssetMigrationTest {

    @Test
    void h2MigrationAllowsImmutableVersionsButRejectsTwoCopiesOfOneVersion()
            throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(
                "jdbc:h2:mem:ts-observability-asset-" + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(
                    connection,
                    new EncodedResource(new ClassPathResource(
                            "db/migration/h2/"
                                    + "V194__troubleshooting_observability_asset.sql"),
                            "UTF-8"));
            try (Statement statement = connection.createStatement()) {
                assertThat(statement.executeUpdate(insert(1, 1))).isEqualTo(1);
                assertThat(statement.executeUpdate(insert(2, 2))).isEqualTo(1);
                assertThatThrownBy(() -> statement.executeUpdate(insert(3, 2)))
                        .isInstanceOf(SQLException.class);
            }
        }
    }

    private String insert(long id, int version) {
        return """
                INSERT INTO mate_troubleshooting_observability_asset (
                    id, workspace_id, system, service, display_name, platform,
                    environment, enabled, signal_bindings, asset_parameters,
                    version, changed_by, change_reason
                ) VALUES (
                    %d, 7, 'csdp', 'session-service', 'CSDP session', 'guance',
                    'prod', 1, '{"log_search":"csdp-log-search"}', '{}',
                    %d, 'owner', 'test revision'
                )
                """.formatted(id, version);
    }
}
