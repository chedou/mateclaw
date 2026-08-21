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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosisFollowUpRunMigrationTest {

    private static final String MIGRATION =
            "V221__troubleshooting_diagnosis_follow_up_run.sql";

    @Test
    void h2CreatesAnImmutableSecretFreeFollowUpRunLedger() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:diagnosis-follow-up-v221;MODE=MySQL;"
                        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "")) {
            execute(connection, "db/migration/h2/" + MIGRATION);

            assertThat(columns(
                    connection.getMetaData(),
                    "mate_troubleshooting_diagnosis_follow_up_run"))
                    .contains(
                            "workspace_id", "run_id", "diagnosis_id",
                            "diagnosis_version", "conclusion_type", "turn_kind",
                            "content_length", "disposition",
                            "actor_ref", "recorded_at")
                    .doesNotContain(
                            "content", "content_fingerprint", "query", "dql", "raw_log", "observed_data",
                            "api_key", "answer");
        }
    }

    @Test
    void mysqlAndKingbaseKeepTheSameSecretFreeShape() throws Exception {
        for (String dialect : List.of("mysql", "kingbase")) {
            String sql = resource("db/migration/" + dialect + "/" + MIGRATION)
                    .toLowerCase(Locale.ROOT);
            assertThat(sql)
                    .contains(
                            "diagnosis_version", "conclusion_type", "turn_kind",
                            "content_length", "recorded_not_verified")
                    .doesNotContain(
                            "raw_log", "observed_data", "api_key", "authorization");
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
