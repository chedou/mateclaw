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

    @Test
    void h2V178CreatesLeaseBasedInvestigationQueueAndDiagnosisOwnership() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:troubleshooting-v178;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                "")) {
            executeMigration(connection, "db/migration/h2/V172__troubleshooting_domain.sql");
            executeMigration(connection, "db/migration/h2/V178__troubleshooting_intake_investigation.sql");

            Set<String> tables = tables(connection.getMetaData());
            assertTrue(tables.contains("mate_troubleshooting_intake_investigation"));
            Set<String> columns = columns(
                    connection.getMetaData(),
                    "mate_troubleshooting_intake_investigation");
            assertTrue(columns.contains("intake_session_id"));
            assertTrue(columns.contains("diagnosis_id"));
            assertTrue(columns.contains("lease_expires_at"));
            assertTrue(columns.contains("next_attempt_at"));
            assertTrue(columns(connection.getMetaData(), "mate_troubleshooting_diagnosis")
                    .contains("source_intake_session_id"));
            assertEquals(1, countIndexes(connection, "uk_ts_intake_investigation"));
            assertEquals(1, countIndexes(connection, "uk_ts_diagnosis_intake"));
        }
    }

    @Test
    void h2V179SeparatesDeliveryRoutingAndPersistsTerminalRetryCount() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:troubleshooting-v179;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                "")) {
            executeMigration(connection, "db/migration/h2/V172__troubleshooting_domain.sql");
            executeMigration(connection, "db/migration/h2/V175__troubleshooting_intake_session.sql");
            executeMigration(connection, "db/migration/h2/V176__troubleshooting_intake_reported_at.sql");
            executeMigration(connection, "db/migration/h2/V177__troubleshooting_intake_reported_at_backfill.sql");
            executeMigration(connection, "db/migration/h2/V178__troubleshooting_intake_investigation.sql");
            executeMigration(connection, "db/migration/h2/V179__troubleshooting_intake_dispatch_reliability.sql");

            Set<String> sessionColumns = columns(
                    connection.getMetaData(), "mate_troubleshooting_intake_session");
            assertTrue(sessionColumns.contains("delivery_conversation_id"));
            Set<String> taskColumns = columns(
                    connection.getMetaData(), "mate_troubleshooting_intake_investigation");
            assertTrue(taskColumns.contains("terminal_attempts"));
            assertFalse(isNullable(
                    connection.getMetaData(),
                    "mate_troubleshooting_intake_investigation",
                    "terminal_attempts"));
        }
    }

    @Test
    void h2V180AddsDurableClosureNotificationDeliveryState() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:troubleshooting-v180;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                "")) {
            executeMigration(connection, "db/migration/h2/V172__troubleshooting_domain.sql");
            executeMigration(connection, "db/migration/h2/V178__troubleshooting_intake_investigation.sql");
            executeMigration(connection, "db/migration/h2/V180__troubleshooting_closure_notification.sql");

            Set<String> diagnosisColumns = columns(
                    connection.getMetaData(), "mate_troubleshooting_diagnosis");
            assertTrue(diagnosisColumns.contains("closure_notification_status"));
            assertTrue(diagnosisColumns.contains("closure_notification_attempts"));
            assertTrue(diagnosisColumns.contains("closure_notification_lease_expires_at"));
            assertTrue(diagnosisColumns.contains("closure_notification_next_attempt_at"));
            assertTrue(diagnosisColumns.contains("closure_notification_completed_at"));
            assertFalse(isNullable(
                    connection.getMetaData(),
                    "mate_troubleshooting_diagnosis",
                    "closure_notification_attempts"));
            assertEquals(1, countIndexes(connection, "idx_ts_diagnosis_closure_notify"));
        }
    }

    @Test
    void h2V181CreatesTheSecretFreeEvaluationSampleLedger() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:troubleshooting-v181;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                "")) {
            executeMigration(
                    connection,
                    "db/migration/h2/V181__troubleshooting_evaluation_sample.sql");

            Set<String> tables = tables(connection.getMetaData());
            assertTrue(tables.contains("mate_troubleshooting_evaluation_sample"));
            Set<String> sampleColumns = columns(
                    connection.getMetaData(),
                    "mate_troubleshooting_evaluation_sample");
            assertTrue(sampleColumns.contains("sample_key"));
            assertTrue(sampleColumns.contains("reference_status"));
            assertTrue(sampleColumns.contains("evidence_stage"));
            assertTrue(sampleColumns.contains("diagnosis_fixture_mode"));
            assertTrue(sampleColumns.contains("aggregate_json"));
            assertFalse(sampleColumns.contains("search_term"));
            assertFalse(sampleColumns.contains("dql"));
            assertFalse(sampleColumns.contains("raw_log"));
            assertEquals(1, countIndexes(connection, "uk_ts_eval_sample_id"));
            assertEquals(1, countIndexes(connection, "uk_ts_eval_sample_key"));
            assertEquals(1, countIndexes(connection, "idx_ts_eval_sample_status"));
            assertEquals(1, countIndexes(connection, "idx_ts_eval_sample_diagnosis"));
        }
    }

    @Test
    void h2V182CreatesTheCandidateFreeBaselineEvaluationLedger() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:troubleshooting-v182;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                "")) {
            executeMigration(
                    connection,
                    "db/migration/h2/V182__troubleshooting_baseline_evaluation_run.sql");

            Set<String> tables = tables(connection.getMetaData());
            assertTrue(tables.contains("mate_troubleshooting_baseline_eval_run"));
            Set<String> runColumns = columns(
                    connection.getMetaData(),
                    "mate_troubleshooting_baseline_eval_run");
            assertTrue(runColumns.contains("run_key"));
            assertTrue(runColumns.contains("sample_version"));
            assertTrue(runColumns.contains("evidence_fixture_mode"));
            assertTrue(runColumns.contains("diagnosis_fixture_mode"));
            assertTrue(runColumns.contains("model_config_version"));
            assertTrue(runColumns.contains("claim_token"));
            assertTrue(runColumns.contains("reservation_expires_at"));
            assertTrue(runColumns.contains("model_duration_ms"));
            assertTrue(runColumns.contains("result_json"));
            assertFalse(runColumns.contains("search_term"));
            assertFalse(runColumns.contains("draft_json"));
            assertFalse(runColumns.contains("abstain_reason"));
            assertFalse(runColumns.contains("gate_verdict"));
            assertEquals(1, countIndexes(connection, "uk_ts_baseline_eval_run_id"));
            assertEquals(1, countIndexes(connection, "uk_ts_baseline_eval_run_key"));
            assertEquals(1, countIndexes(connection, "idx_ts_baseline_eval_sample"));
            assertEquals(1, countIndexes(connection, "idx_ts_baseline_eval_diagnosis"));
            assertEquals(1, countIndexes(connection, "idx_ts_baseline_eval_claim"));
        }
    }

    @Test
    void h2V183BackfillsAndIndexesImmutableEvaluationSampleRevisions() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:troubleshooting-v183;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                "")) {
            executeMigration(
                    connection,
                    "db/migration/h2/V181__troubleshooting_evaluation_sample.sql");
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        INSERT INTO mate_troubleshooting_evaluation_sample (
                            id, workspace_id, sample_id, sample_key, diagnosis_id,
                            system, service, scenario_key, source_platform,
                            evidence_stage, reference_status, fixture_mode,
                            diagnosis_fixture_mode, aggregate_json, version, deleted)
                        VALUES (
                            1, 7, 'eval-old',
                            'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                            'diag-1', 'CSDP', 'session-svc', 'message_send_failed',
                            'GUANCE', 'FULL_SPINE_OBSERVED', 'EVIDENCE_CAPTURED',
                            FALSE, FALSE, '{}', 0, 0)
                        """);
            }

            executeMigration(
                    connection,
                    "db/migration/h2/V183__troubleshooting_evaluation_sample_revision.sql");

            Set<String> columns = columns(
                    connection.getMetaData(),
                    "mate_troubleshooting_evaluation_sample");
            assertTrue(columns.contains("capture_identity_key"));
            assertTrue(columns.contains("capture_revision"));
            assertFalse(isNullable(
                    connection.getMetaData(),
                    "mate_troubleshooting_evaluation_sample",
                    "capture_identity_key"));
            assertEquals(1, countIndexes(connection, "uk_ts_eval_capture_revision"));
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT capture_identity_key, capture_revision
                    FROM mate_troubleshooting_evaluation_sample
                    WHERE id = 1
                    """); ResultSet row = query.executeQuery()) {
                assertTrue(row.next());
                assertEquals(
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        row.getString("capture_identity_key"));
                assertEquals(1, row.getInt("capture_revision"));
            }
        }
    }

    @Test
    void h2V184CreatesASecretFreeImmutableGuanceAcceptanceLedger()
            throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:troubleshooting-v184;MODE=MySQL;"
                        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                "")) {
            executeMigration(
                    connection,
                    "db/migration/h2/"
                            + "V184__troubleshooting_guance_acceptance.sql");

            Set<String> tables = tables(connection.getMetaData());
            assertTrue(tables.contains(
                    "mate_troubleshooting_guance_acceptance"));
            Set<String> columns = columns(
                    connection.getMetaData(),
                    "mate_troubleshooting_guance_acceptance");
            assertTrue(columns.contains("acceptance_id"));
            assertTrue(columns.contains("scope_key"));
            assertTrue(columns.contains("binding_fingerprint"));
            assertTrue(columns.contains("aggregate_json"));
            assertFalse(columns.contains("search_term"));
            assertFalse(columns.contains("ps_id"));
            assertFalse(columns.contains("dql"));
            assertFalse(columns.contains("credential"));
            assertFalse(columns.contains("raw_log"));
            assertEquals(1, countIndexes(
                    connection, "uk_ts_guance_acceptance_id"));
            assertEquals(1, countIndexes(
                    connection, "uk_ts_guance_acceptance_binding"));
            assertEquals(1, countIndexes(
                    connection, "idx_ts_guance_acceptance_scope"));
        }
    }

    @Test
    void h2V185CreatesAWorkspaceScopedOptimisticKnowledgeReviewLedger()
            throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:troubleshooting-v185;MODE=MySQL;"
                        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                "")) {
            executeMigration(
                    connection,
                    "db/migration/h2/"
                            + "V185__troubleshooting_knowledge_review.sql");

            Set<String> tables = tables(connection.getMetaData());
            assertTrue(tables.contains(
                    "mate_troubleshooting_knowledge_review"));
            Set<String> columns = columns(
                    connection.getMetaData(),
                    "mate_troubleshooting_knowledge_review");
            assertTrue(columns.contains("review_id"));
            assertTrue(columns.contains("origin"));
            assertTrue(columns.contains("source_record_id"));
            assertTrue(columns.contains("selector_key"));
            assertTrue(columns.contains("status"));
            assertTrue(columns.contains("reviewer"));
            assertTrue(columns.contains("reason"));
            assertTrue(columns.contains("snapshot_json"));
            assertTrue(columns.contains("version"));
            assertFalse(columns.contains("search_term"));
            assertFalse(columns.contains("dql"));
            assertFalse(columns.contains("credential"));
            assertFalse(columns.contains("raw_log"));
            assertEquals(1, countIndexes(
                    connection, "uk_ts_knowledge_review_id"));
            assertEquals(1, countIndexes(
                    connection, "uk_ts_knowledge_review_source"));
            assertEquals(1, countIndexes(
                    connection, "idx_ts_knowledge_review_status"));
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
