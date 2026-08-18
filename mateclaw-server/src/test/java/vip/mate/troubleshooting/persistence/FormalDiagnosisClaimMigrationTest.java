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

class FormalDiagnosisClaimMigrationTest {

    private static final String MIGRATION =
            "V219__troubleshooting_formal_diagnosis_claim.sql";

    @Test
    void h2CreatesAnIndependentFormalDiagnosisClaimTable() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:formal-diagnosis-v219;MODE=MySQL;"
                        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                "")) {
            execute(connection, "db/migration/h2/" + MIGRATION);

            assertThat(tables(connection.getMetaData()))
                    .contains("mate_troubleshooting_formal_diagnosis_claim")
                    .doesNotContain("mate_troubleshooting_open_discovery_claim");
            assertThat(columns(
                    connection.getMetaData(),
                    "mate_troubleshooting_formal_diagnosis_claim"))
                    .contains(
                            "workspace_id", "dedup_key", "claim_token", "status",
                            "diagnosis_id", "claimed_at", "lease_expires_at",
                            "completed_at")
                    .doesNotContain("query", "raw_log", "evidence", "api_key");
            assertThat(indexes(connection.getMetaData()))
                    .contains(
                            "uk_ts_formal_diag_claim_key",
                            "idx_ts_formal_diag_claim_lease");
        }
    }

    @Test
    void mysqlAndKingbaseKeepTheSameSafeClaimShape() throws Exception {
        for (String dialect : List.of("mysql", "kingbase")) {
            String sql = resource("db/migration/" + dialect + "/" + MIGRATION);
            assertThat(sql)
                    .contains("mate_troubleshooting_formal_diagnosis_claim")
                    .contains("uk_ts_formal_diag_claim_key")
                    .contains("idx_ts_formal_diag_claim_lease")
                    .doesNotContain("mate_troubleshooting_open_discovery_claim")
                    .doesNotContain("api_key")
                    .doesNotContain("raw_log")
                    .doesNotContain("query_text")
                    .doesNotContain("model_output");
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

    private Set<String> tables(DatabaseMetaData metadata) throws Exception {
        Set<String> names = new HashSet<>();
        try (ResultSet rows = metadata.getTables(null, null, null, new String[]{"TABLE"})) {
            while (rows.next()) {
                names.add(rows.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    private Set<String> columns(DatabaseMetaData metadata, String table) throws Exception {
        Set<String> names = new HashSet<>();
        try (ResultSet rows = metadata.getColumns(null, null, table, null)) {
            while (rows.next()) {
                names.add(rows.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    private Set<String> indexes(DatabaseMetaData metadata) throws Exception {
        Set<String> names = new HashSet<>();
        try (ResultSet rows = metadata.getIndexInfo(
                null, null, "mate_troubleshooting_formal_diagnosis_claim", false, false)) {
            while (rows.next()) {
                String name = rows.getString("INDEX_NAME");
                if (name != null) {
                    names.add(name.toLowerCase(Locale.ROOT));
                }
            }
        }
        return names;
    }
}
