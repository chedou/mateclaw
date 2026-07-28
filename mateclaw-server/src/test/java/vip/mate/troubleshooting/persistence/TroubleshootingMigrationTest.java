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
import java.time.LocalDateTime;
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

    @Test
    void h2MigrationCreatesAnIdempotentReviewOnlyPlaybookCandidateTable() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:troubleshooting-v174;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                "")) {
            executeMigration(
                    connection,
                    "db/migration/h2/V174__troubleshooting_playbook_candidate.sql");

            Set<String> tables = tables(connection.getMetaData());
            assertTrue(tables.contains("mate_troubleshooting_playbook_candidate"));
            Set<String> columns = columns(
                    connection.getMetaData(),
                    "mate_troubleshooting_playbook_candidate");
            assertTrue(columns.contains("generation_key"));
            assertTrue(columns.contains("review_status"));
            assertTrue(columns.contains("validation_status"));
            assertTrue(columns.contains("fixture_mode"));
            assertFalse(columns.contains("approved"));
            assertEquals(1, countIndexes(
                    connection, "uk_ts_playbook_candidate_generation"));
            assertEquals(1, countIndexes(
                    connection, "uk_ts_playbook_candidate_record"));
        }
    }

    @Test
    void h2MigrationsCreateDurableEventTimeAwareIntakeSessionsAndReceipts() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:troubleshooting-v175;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                "")) {
            executeMigration(
                    connection,
                    "db/migration/h2/V175__troubleshooting_intake_session.sql");
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO mate_troubleshooting_intake_session (
                        id, workspace_id, intake_session_id, active_key, routing_key,
                        source, conversation_ref, reporter_ref, status, last_message_at,
                        aggregate_json, version, deleted, create_time, update_time
                    ) VALUES (1, 7, 'intake-history', NULL, 'route-history',
                        'wecom', 'group-1', 'user-1', 'READY', ?, ?, 2, 0, ?, ?)
                    """)) {
                LocalDateTime reportedAt = LocalDateTime.of(2026, 7, 29, 2, 0);
                LocalDateTime lastMessageAt = reportedAt.plusMinutes(5);
                insert.setObject(1, lastMessageAt);
                insert.setString(2, """
                        {"intakeSessionId":"intake-history","contractVersion":"intake-session.v1",
                         "workspaceId":7,"source":"wecom","conversationRef":"group-1",
                         "reporterRef":"user-1","status":"READY","symptom":"failure",
                         "system":"CSDP","service":"csdp-wechat","customerRef":"tenant-42",
                         "errorCode":"","traceId":"","occurredAt":"2026-07-29T01:59:00Z",
                         "attachments":[],"missingFields":[],
                         "reportedAt":"2026-07-29T02:00:00Z",
                         "readyAt":"2026-07-29T02:05:00Z",
                         "lastMessageAt":"2026-07-29T02:05:00Z","timeline":[]}
                        """);
                insert.setObject(3, lastMessageAt);
                insert.setObject(4, lastMessageAt);
                insert.executeUpdate();
            }
            executeMigration(
                    connection,
                    "db/migration/h2/V176__troubleshooting_intake_reported_at.sql");
            executeMigration(
                    connection,
                    "db/migration/h2/V177__troubleshooting_intake_reported_at_backfill.sql");

            Set<String> tables = tables(connection.getMetaData());
            assertTrue(tables.contains("mate_troubleshooting_intake_session"));
            assertTrue(tables.contains("mate_troubleshooting_intake_message"));
            Set<String> sessionColumns = columns(
                    connection.getMetaData(), "mate_troubleshooting_intake_session");
            assertTrue(sessionColumns.contains("active_key"));
            assertTrue(sessionColumns.contains("routing_key"));
            assertTrue(sessionColumns.contains("reported_at"));
            assertTrue(sessionColumns.contains("aggregate_json"));
            assertFalse(isNullable(
                    connection.getMetaData(),
                    "mate_troubleshooting_intake_session",
                    "reported_at"));
            Set<String> receiptColumns = columns(
                    connection.getMetaData(), "mate_troubleshooting_intake_message");
            assertTrue(receiptColumns.contains("source_message_id"));
            assertFalse(receiptColumns.contains("content"));
            assertFalse(receiptColumns.contains("raw_payload"));
            assertEquals(1, countIndexes(connection, "uk_ts_intake_active"));
            assertEquals(1, countIndexes(connection, "idx_ts_intake_route_latest"));
            assertEquals(1, countIndexes(connection, "idx_ts_intake_route_reported"));
            assertEquals(1, countIndexes(connection, "uk_ts_intake_source_message"));
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT reported_at
                    FROM mate_troubleshooting_intake_session
                    WHERE intake_session_id = 'intake-history'
                    """);
                    ResultSet row = query.executeQuery()) {
                assertTrue(row.next());
                assertEquals(
                        LocalDateTime.of(2026, 7, 29, 2, 0),
                        row.getObject("reported_at", LocalDateTime.class));
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

    private boolean isNullable(
            DatabaseMetaData metadata,
            String tableName,
            String columnName) throws Exception {
        try (ResultSet rows = metadata.getColumns(null, null, tableName, columnName)) {
            assertTrue(rows.next());
            return rows.getInt("NULLABLE") == DatabaseMetaData.columnNullable;
        }
    }
}
