package vip.mate.troubleshooting.persistence;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PilotEnrollmentMigrationTest {

    @Test
    void allDialectsAddOnlyASecretFreeNullableEnrollmentIndex() throws Exception {
        for (String dialect : List.of("h2", "mysql", "kingbase")) {
            String sql;
            try (var input = new ClassPathResource(
                    "db/migration/" + dialect
                            + "/V202__troubleshooting_pilot_enrollment.sql").getInputStream()) {
                sql = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                        .toLowerCase(Locale.ROOT);
            }
            assertThat(sql).contains(
                    "mate_troubleshooting_diagnosis",
                    "pilot_plan_version",
                    "workspace_id, pilot_plan_version, id");
            assertThat(sql).doesNotContain(
                    "api_key", "credential", "raw_log", "observed_data", "dql");
            assertThat(sql).doesNotContain("update mate_troubleshooting_diagnosis");
        }
    }

    @Test
    void h2MigrationLeavesHistoricalRowsUnenrolled() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:ts-pilot-enrollment-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE mate_troubleshooting_diagnosis (
                        id BIGINT PRIMARY KEY,
                        workspace_id BIGINT NOT NULL
                    )
                    """);
            statement.executeUpdate(
                    "INSERT INTO mate_troubleshooting_diagnosis (id, workspace_id) VALUES (1, 7)");
            ScriptUtils.executeSqlScript(
                    connection,
                    new EncodedResource(new ClassPathResource(
                            "db/migration/h2/V202__troubleshooting_pilot_enrollment.sql"),
                            "UTF-8"));

            try (ResultSet rows = statement.executeQuery(
                    "SELECT pilot_plan_version FROM mate_troubleshooting_diagnosis WHERE id = 1")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getObject(1)).isNull();
            }
        }
    }
}
