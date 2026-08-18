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

class EvaluationSampleProvenanceMigrationTest {

    @Test
    void allDialectsPersistOnlySecretFreeFormalSampleIdentity() throws Exception {
        for (String dialect : List.of("h2", "mysql", "kingbase")) {
            String sql;
            try (var input = new ClassPathResource(
                    "db/migration/" + dialect
                            + "/V218__troubleshooting_evaluation_sample_provenance.sql")
                    .getInputStream()) {
                sql = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                        .toLowerCase(Locale.ROOT);
            }
            assertThat(sql).contains(
                    "diagnosis_rehearsal",
                    "pilot_plan_version",
                    "source_playbook_id",
                    "source_playbook_version",
                    "idx_ts_eval_formal_pilot");
            assertThat(sql).doesNotContain(
                    "api_key", "credential", "raw_log", "observed_data", "dql");
            assertThat(sql).doesNotContain("update mate_troubleshooting_evaluation_sample");
        }
    }

    @Test
    void h2MigrationKeepsHistoricalRowsFailClosed() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:ts-eval-provenance-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE mate_troubleshooting_evaluation_sample (
                        id BIGINT PRIMARY KEY,
                        workspace_id BIGINT NOT NULL,
                        source_platform VARCHAR(32) NOT NULL,
                        create_time TIMESTAMP NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO mate_troubleshooting_evaluation_sample
                        (id, workspace_id, source_platform, create_time)
                    VALUES (1, 7, 'GUANCE', CURRENT_TIMESTAMP)
                    """);
            ScriptUtils.executeSqlScript(
                    connection,
                    new EncodedResource(new ClassPathResource(
                            "db/migration/h2/"
                                    + "V218__troubleshooting_evaluation_sample_provenance.sql"),
                            "UTF-8"));

            try (ResultSet rows = statement.executeQuery("""
                    SELECT diagnosis_rehearsal, pilot_plan_version,
                           source_playbook_id, source_playbook_version
                    FROM mate_troubleshooting_evaluation_sample WHERE id = 1
                    """)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getBoolean(1)).isTrue();
                assertThat(rows.getObject(2)).isNull();
                assertThat(rows.getObject(3)).isNull();
                assertThat(rows.getObject(4)).isNull();
            }
        }
    }
}
