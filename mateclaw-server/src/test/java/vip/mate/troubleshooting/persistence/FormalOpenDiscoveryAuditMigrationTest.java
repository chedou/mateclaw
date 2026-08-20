package vip.mate.troubleshooting.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FormalOpenDiscoveryAuditMigrationTest {

    private static final String MIGRATION =
            "V220__troubleshooting_formal_open_discovery_audit.sql";

    @Test
    void h2AddsOnlySecretFreeFormalAuthorityToTheImmutableRun() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:formal-open-discovery-v220;MODE=MySQL;"
                        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                "");
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE mate_troubleshooting_open_discovery_run (
                        id BIGINT PRIMARY KEY,
                        workspace_id BIGINT NOT NULL,
                        run_id VARCHAR(128) NOT NULL
                    )
                    """);
            execute(connection, "db/migration/h2/" + MIGRATION);

            assertThat(columns(
                    connection.getMetaData(),
                    "mate_troubleshooting_open_discovery_run"))
                    .contains(
                            "formal_pilot_plan_version",
                            "source_acceptance_id",
                            "source_binding_fingerprint")
                    .doesNotContain(
                            "query", "dql", "raw_log", "observed_data", "api_key");
        }
    }

    @Test
    void mysqlAndKingbaseKeepTheSameNullableAuthorityShape() throws Exception {
        for (String dialect : List.of("mysql", "kingbase")) {
            String sql = resource("db/migration/" + dialect + "/" + MIGRATION)
                    .toLowerCase(Locale.ROOT);
            assertThat(sql)
                    .contains(
                            "formal_pilot_plan_version",
                            "source_acceptance_id",
                            "source_binding_fingerprint")
                    .doesNotContain(
                            "query_text", "dql", "raw_log", "observed_data", "api_key");
        }
    }

    private void execute(Connection connection, String path) {
        ScriptUtils.executeSqlScript(
                connection,
                new EncodedResource(new ClassPathResource(path), "UTF-8"));
    }

    private String resource(String path) throws Exception {
        try (var stream = new ClassPathResource(path).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Set<String> columns(DatabaseMetaData metadata, String table)
            throws Exception {
        Set<String> names = new HashSet<>();
        try (ResultSet rows = metadata.getColumns(null, null, table, null)) {
            while (rows.next()) {
                names.add(rows.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }
}
