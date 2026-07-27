package vip.mate.troubleshooting.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TroubleshootingMigrationTest {

    @Test
    void h2MigrationCreatesDiagnosisSopAndOutboxTables() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:troubleshooting-v172;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                "")) {
            ScriptUtils.executeSqlScript(
                    connection,
                    new EncodedResource(
                            new ClassPathResource("db/migration/h2/V172__troubleshooting_domain.sql"),
                            "UTF-8"));

            Set<String> tables = tables(connection.getMetaData());
            assertTrue(tables.contains("mate_troubleshooting_diagnosis"));
            assertTrue(tables.contains("mate_troubleshooting_sop"));
            assertTrue(tables.contains("mate_troubleshooting_knowledge_outbox"));
            assertTrue(columns(
                    connection.getMetaData(),
                    "mate_troubleshooting_knowledge_outbox").contains("workspace_id"));
            assertEquals(1, countIndexes(connection, "uk_ts_diagnosis_dedup"));
            assertEquals(1, countIndexes(connection, "uk_ts_sop_route"));
            assertEquals(1, countIndexes(connection, "uk_ts_outbox_publication"));
        }
    }

    @Test
    void h2MigrationRegistersTroubleshootingEvidenceToolIdempotently() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:troubleshooting-v173;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                "")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE mate_tool (
                            id BIGINT PRIMARY KEY,
                            name VARCHAR(100),
                            display_name VARCHAR(100),
                            description VARCHAR(1000),
                            tool_type VARCHAR(50),
                            bean_name VARCHAR(100),
                            icon VARCHAR(20),
                            enabled BOOLEAN,
                            builtin BOOLEAN,
                            create_time TIMESTAMP,
                            update_time TIMESTAMP,
                            deleted INT
                        )
                        """);
            }

            executeMigration(connection, "db/migration/h2/V173__register_troubleshooting_evidence_tool.sql");
            executeMigration(connection, "db/migration/h2/V173__register_troubleshooting_evidence_tool.sql");

            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT name, bean_name, enabled, builtin
                    FROM mate_tool
                    WHERE id = 1000000028
                    """);
                    ResultSet row = query.executeQuery()) {
                assertTrue(row.next());
                assertEquals("TroubleshootingEvidenceTool", row.getString("name"));
                assertEquals("troubleshootingEvidenceTool", row.getString("bean_name"));
                assertTrue(row.getBoolean("enabled"));
                assertTrue(row.getBoolean("builtin"));
                assertFalse(row.next());
            }
        }
    }

    private void executeMigration(Connection connection, String resourcePath) {
        ScriptUtils.executeSqlScript(
                connection,
                new EncodedResource(new ClassPathResource(resourcePath), "UTF-8"));
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

    private int countIndexes(Connection connection, String indexName) throws Exception {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES "
                        + "WHERE LOWER(INDEX_NAME) = LOWER(?)")) {
            query.setString(1, indexName);
            try (ResultSet row = query.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        }
    }

    private Set<String> columns(DatabaseMetaData metadata, String tableName) throws Exception {
        Set<String> names = new HashSet<>();
        try (ResultSet rows = metadata.getColumns(null, null, tableName, null)) {
            while (rows.next()) {
                names.add(rows.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }
}
