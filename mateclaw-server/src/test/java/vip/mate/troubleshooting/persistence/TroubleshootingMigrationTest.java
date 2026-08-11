package vip.mate.troubleshooting.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void h2V186CreatesImmutableVersionsAndEnforcesOneActiveSelector()
            throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:troubleshooting-v186;MODE=MySQL;"
                        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                "")) {
            executeMigration(
                    connection,
                    "db/migration/h2/V172__troubleshooting_domain.sql");
            executeMigration(
                    connection,
                    "db/migration/h2/V185__troubleshooting_knowledge_review.sql");
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO mate_troubleshooting_sop (
                            id, workspace_id, sop_id, route_key, system, error_code,
                            service, status, verified, contract_version, aggregate_json,
                            version, deleted, create_time, update_time
                        ) VALUES (
                            11, 7, 'legacy-approved', 'csdp:903001', 'CSDP', '903001',
                            'order-svc', 'approved', TRUE, 'sop.v1', '{}',
                            0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                        )
                        """);
                statement.executeUpdate("""
                        INSERT INTO mate_troubleshooting_knowledge_review (
                            id, workspace_id, review_id, origin, source_record_id,
                            selector_key, status, reviewer, reason, snapshot_json,
                            version, deleted, create_time, update_time
                        ) VALUES (
                            21, 7, 'review-inflight', 'MANUAL', 'manual-inflight',
                            'csdp:903001', 'IN_REVIEW', 'reviewer-a',
                            '迁移前已开始审核', '{}', 1, 0,
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                        )
                        """);
            }

            executeMigration(
                    connection,
                    "db/migration/h2/V186__troubleshooting_playbook_version.sql");

            Set<String> tables = tables(connection.getMetaData());
            assertTrue(tables.contains("mate_troubleshooting_playbook_version"));
            Set<String> versionColumns = columns(
                    connection.getMetaData(),
                    "mate_troubleshooting_playbook_version");
            assertTrue(versionColumns.contains("playbook_version"));
            assertTrue(versionColumns.contains("active_selector_key"));
            assertTrue(versionColumns.contains("source_origin"));
            assertTrue(versionColumns.contains("review_id"));
            assertTrue(versionColumns.contains("approval_snapshot_json"));
            assertTrue(versionColumns.contains("deprecated_by"));
            assertTrue(versionColumns.contains("deprecation_reason"));
            assertTrue(versionColumns.contains("deprecated_at"));
            Set<String> reviewColumns = columns(
                    connection.getMetaData(),
                    "mate_troubleshooting_knowledge_review");
            assertTrue(reviewColumns.contains("active_baseline_known"));
            assertTrue(reviewColumns.contains("base_playbook_id"));
            assertTrue(reviewColumns.contains("base_playbook_version"));
            assertEquals(1, countIndexes(
                    connection, "uk_ts_playbook_version_active"));
            assertEquals(1, countIndexes(
                    connection, "uk_ts_playbook_version_number"));
            assertEquals(1, countIndexes(
                    connection, "uk_ts_playbook_version_review"));
            assertEquals(0, countIndexes(connection, "uk_ts_sop_route"));
            assertEquals(1, countIndexes(
                    connection, "idx_ts_sop_route_sources"));

            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT playbook_id, playbook_version, active_selector_key
                    FROM mate_troubleshooting_playbook_version
                    WHERE workspace_id = 7 AND selector_key = 'csdp:903001'
                    """)) {
                try (ResultSet row = query.executeQuery()) {
                    assertTrue(row.next());
                    assertEquals("legacy-approved", row.getString("playbook_id"));
                    assertEquals(1, row.getInt("playbook_version"));
                    assertEquals("csdp:903001", row.getString("active_selector_key"));
                    assertFalse(row.next());
                }
            }

            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT active_baseline_known, base_playbook_id,
                           base_playbook_version
                    FROM mate_troubleshooting_knowledge_review
                    WHERE workspace_id = 7 AND review_id = 'review-inflight'
                    """)) {
                try (ResultSet row = query.executeQuery()) {
                    assertTrue(row.next());
                    assertTrue(row.getBoolean("active_baseline_known"));
                    assertEquals("legacy-approved", row.getString("base_playbook_id"));
                    assertEquals(1, row.getInt("base_playbook_version"));
                }
            }

            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO mate_troubleshooting_sop (
                            id, workspace_id, sop_id, route_key, system, error_code,
                            service, status, verified, contract_version, aggregate_json,
                            version, deleted, create_time, update_time
                        ) VALUES
                            (12, 7, 'manual-source-2', 'csdp:903001', 'CSDP', '903001',
                             'order-svc', 'candidate', FALSE, 'sop.v1', '{}',
                             0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                            (13, 7, 'manual-source-3', 'csdp:903001', 'CSDP', '903001',
                             'order-svc', 'candidate', FALSE, 'sop.v1', '{}',
                             0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """);
            }

            assertThrows(java.sql.SQLException.class, () -> {
                try (PreparedStatement duplicate = connection.prepareStatement("""
                        INSERT INTO mate_troubleshooting_playbook_version (
                            id, workspace_id, playbook_id, selector_key,
                            playbook_version, active_selector_key,
                            system, error_code, service, status,
                            source_origin, source_record_id, review_id, review_version,
                            approved_by, approval_reason, approval_snapshot_json,
                            contract_version, aggregate_json, version, deleted,
                            create_time, update_time
                        ) VALUES (
                            14, 7, 'second-active', 'csdp:903001',
                            2, 'csdp:903001',
                            'CSDP', '903001', 'order-svc', 'APPROVED',
                            'MANUAL', 'manual-2', 'review-2', 1,
                            'reviewer-a', '并发审批', '{}',
                            'sop.v1', '{}', 0, 0,
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                        )
                        """)) {
                    duplicate.executeUpdate();
                }
            });
        }
    }

    @Test
    void h2V190MakesKnowledgeEvidenceGradeExplicitAndLeavesHistoryFailClosed()
            throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:troubleshooting-v190;MODE=MySQL;"
                        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                "")) {
            executeMigration(
                    connection,
                    "db/migration/h2/V172__troubleshooting_domain.sql");
            executeMigration(
                    connection,
                    "db/migration/h2/V185__troubleshooting_knowledge_review.sql");
            executeMigration(
                    connection,
                    "db/migration/h2/V186__troubleshooting_playbook_version.sql");
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO mate_troubleshooting_playbook_version (
                            id, workspace_id, playbook_id, selector_key,
                            playbook_version, active_selector_key,
                            system, error_code, service, status,
                            source_origin, source_record_id, approved_by,
                            approval_reason, contract_version, aggregate_json,
                            version, deleted, create_time, update_time
                        ) VALUES
                            (31, 7, 'pb-im1010', 'csdp:IM1010', 1, 'csdp:IM1010',
                             'CSDP', 'IM1010', 'csp-rpc-msg', 'APPROVED',
                             'MANUAL', 'manual-csdp-im1010-v1', 'seed', 'recorded',
                             'sop.v1', '{}', 0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                            (32, 7, 'pb-903001', 'csdp:903001', 1, 'csdp:903001',
                             'CSDP', '903001', 'order-svc', 'APPROVED',
                             'MANUAL', 'manual-csdp-903001-v1', 'seed', 'fixture',
                             'sop.v1', '{}', 0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                            (33, 7, 'pb-unknown', 'csdp:999999', 1, 'csdp:999999',
                             'CSDP', '999999', 'unknown', 'APPROVED',
                             'LEGACY', 'legacy-unknown', 'migration', 'unknown',
                             'sop.v1', '{}', 0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                            (34, 7, 'pb-im1010-rogue', 'csdp:IM1010', 2, NULL,
                             'CSDP', 'IM1010', 'csp-rpc-msg', 'DEPRECATED',
                             'MANUAL', 'manual-im1010-rogue', 'seed', 'handwritten',
                             'sop.v1', '{}', 0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                            (35, 7, 'pb-903001-rogue', 'csdp:903001', 2, NULL,
                             'CSDP', '903001', 'order-svc', 'DEPRECATED',
                             'MANUAL', 'manual-903001-rogue', 'seed', 'unknown',
                             'sop.v1', '{}', 0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """);
            }

            executeMigration(
                    connection,
                    "db/migration/h2/V190__troubleshooting_knowledge_evidence_grade.sql");

            Set<String> columns = columns(
                    connection.getMetaData(), "mate_troubleshooting_playbook_version");
            assertTrue(columns.contains("knowledge_evidence_grade"));
            assertFalse(isNullable(
                    connection.getMetaData(),
                    "mate_troubleshooting_playbook_version",
                    "knowledge_evidence_grade"));
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT source_record_id, knowledge_evidence_grade
                    FROM mate_troubleshooting_playbook_version
                    ORDER BY source_record_id
                    """); ResultSet rows = query.executeQuery()) {
                java.util.Map<String, String> grades = new java.util.LinkedHashMap<>();
                while (rows.next()) {
                    grades.put(rows.getString(1), rows.getString(2));
                }
                assertEquals("UNVERIFIED",
                        grades.get("manual-csdp-903001-v1"));
                assertEquals("UNVERIFIED",
                        grades.get("manual-csdp-im1010-v1"));
                assertEquals("UNVERIFIED", grades.get("legacy-unknown"));
                assertEquals("UNVERIFIED", grades.get("manual-im1010-rogue"));
                assertEquals("UNVERIFIED", grades.get("manual-903001-rogue"));
            }
        }
    }

    @Test
    void h2V191IndexesOnlyPersistedRouteSemantics() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:troubleshooting-v191;MODE=MySQL;"
                        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                "")) {
            executeMigration(
                    connection,
                    "db/migration/h2/V172__troubleshooting_domain.sql");
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO mate_troubleshooting_diagnosis (
                            id, workspace_id, diagnosis_id, case_id, run_id,
                            system, error_code, service, dedup_key, rehearsal,
                            status, contract_version, aggregate_json, version, deleted,
                            create_time, update_time
                        ) VALUES
                            (41, 7, 'diag-legacy', 'case-legacy', 'run-legacy',
                             'CSDP', '903001', 'csdp-wechat',
                             'legacydeduplegacydeduplegacydeduplegacydeduplegacydedup',
                             FALSE, 'READY_FOR_HUMAN', '1.4',
                             '{"diagnosisId":"diag-legacy","contractVersion":"1.4","caseId":"case-legacy","runId":"run-legacy","incident":{"incidentId":"inc-legacy","system":"CSDP","service":"csdp-wechat","errorCode":"903001","symptom":"legacy symptom","severity":"P1","environment":"prod","traceId":null,"occurredAt":"2026-07-25T01:02:00Z","additionalContext":null,"source":"manual","completeness":"STRUCTURED","customerRef":null},"routeMode":"DETERMINISTIC","conclusionType":"ROOT_CAUSE_CONFIRMED","status":"READY_FOR_HUMAN","summary":"legacy summary","rootCause":"legacy root cause","confidence":"HIGH","abstained":false,"sopKey":"csdp:903001","sopTitle":"903001 SOP","sourcePlaybookOwner":null,"evidence":[],"evidenceCitations":[],"triggeredSignals":[],"recommendedActions":[],"pendingWrites":[],"routeToTeam":null,"transfers":[],"actionOutcomes":[],"closure":null,"knowledgeCandidates":[],"timeline":[],"timings":{"observedAt":null,"firstActionAt":null,"concludedAt":null},"rehearsal":false,"fixtureMode":true,"writeExecutionEnabled":false,"warnings":[]}',
                             0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                            (42, 7, 'diag-persisted', 'case-persisted', 'run-persisted',
                             'CSDP', '903001', 'csdp-wechat',
                             'persisteddeduppersisteddeduppersisteddeduppersisteddedup',
                             FALSE, 'NEEDS_INVESTIGATION', '1.8',
                             '{
                               "diagnosisId" : "diag-persisted",
                               "contractVersion" : "1.8",
                               "caseId" : "case-persisted",
                               "runId" : "run-persisted",
                               "incident" : {
                                 "incidentId" : "inc-persisted",
                                 "system" : "CSDP",
                                 "service" : "csdp-wechat",
                                 "errorCode" : "903001",
                                 "symptom" : "persisted symptom",
                                 "severity" : "P1",
                                 "environment" : "prod",
                                 "traceId" : null,
                                 "occurredAt" : "2026-07-25T01:02:00Z",
                                 "additionalContext" : null,
                                 "source" : "manual",
                                 "completeness" : "STRUCTURED",
                                 "customerRef" : null
                               },
                               "routeMode" : "DETERMINISTIC",
                               "investigationMode" : "SCENARIO_PLAYBOOK",
                               "routeAuthority" : "RULE_MATCHED",
                               "conclusionType" : "INSUFFICIENT_EVIDENCE",
                               "status" : "NEEDS_INVESTIGATION",
                               "summary" : "persisted summary",
                               "rootCause" : "persisted root cause",
                               "confidence" : "LOW",
                               "abstained" : true,
                               "sopKey" : "csdp:scenario:deployment_topology_probe",
                               "sopTitle" : "Deployment Topology Probe",
                               "sourcePlaybookOwner" : null,
                               "sourcePlaybookVersionRef" : {
                                 "playbookId" : "playbook-topology",
                                 "version" : 3
                               },
                               "evidence" : [],
                               "evidenceCitations" : [],
                               "triggeredSignals" : [],
                               "recommendedActions" : [],
                               "pendingWrites" : [],
                               "routeToTeam" : null,
                               "transfers" : [],
                               "actionOutcomes" : [],
                               "closure" : null,
                               "knowledgeCandidates" : [],
                               "timeline" : [],
                               "timings" : {
                                 "observedAt" : "2026-07-25T01:02:00Z",
                                 "firstActionAt" : "2026-07-25T01:02:01Z",
                                 "concludedAt" : "2026-07-25T01:02:01Z"
                               },
                               "rehearsal" : false,
                               "fixtureMode" : true,
                               "writeExecutionEnabled" : false,
                               "warnings" : []
                             }',
                             0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """);
            }

            executeMigration(
                    connection,
                    "db/migration/h2/V191__troubleshooting_route_semantics.sql");

            Set<String> columns = columns(
                    connection.getMetaData(), "mate_troubleshooting_diagnosis");
            assertTrue(columns.contains("investigation_mode"));
            assertTrue(columns.contains("route_authority"));
            assertEquals(1, countIndexes(connection, "idx_ts_diagnosis_investigation"));
            assertEquals(1, countIndexes(connection, "idx_ts_diagnosis_authority"));
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT diagnosis_id, investigation_mode, route_authority
                    FROM mate_troubleshooting_diagnosis
                    WHERE workspace_id = 7
                    ORDER BY id
                    """);
                    ResultSet rows = query.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("diag-legacy", rows.getString("diagnosis_id"));
                assertNull(rows.getString("investigation_mode"));
                assertNull(rows.getString("route_authority"));
                assertTrue(rows.next());
                assertEquals("diag-persisted", rows.getString("diagnosis_id"));
                assertEquals("SCENARIO_PLAYBOOK", rows.getString("investigation_mode"));
                assertEquals("RULE_MATCHED", rows.getString("route_authority"));
                assertFalse(rows.next());
            }
        }
    }

    @Test
    void v191DialectResourcesGuardMysqlDdlAndWhitelistExplicitSemantics() throws Exception {
        String mysql = resourceText("db/migration/mysql/V191__troubleshooting_route_semantics.sql");
        String kingbase = resourceText("db/migration/kingbase/V191__troubleshooting_route_semantics.sql");
        String mysqlLogic = stripSqlLineComments(mysql);
        String kingbaseLogic = stripSqlLineComments(kingbase);

        assertFalse(mysql.contains("ADD COLUMN IF NOT EXISTS"));
        assertEquals(2, countOccurrences(mysql, "INFORMATION_SCHEMA.COLUMNS"));
        assertTrue(mysql.contains("COLUMN_NAME = 'investigation_mode'"));
        assertTrue(mysql.contains("COLUMN_NAME = 'route_authority'"));
        assertEquals(2, countOccurrences(mysql, "INFORMATION_SCHEMA.STATISTICS"));
        assertTrue(mysql.contains("INDEX_NAME = 'idx_ts_diagnosis_investigation'"));
        assertTrue(mysql.contains("INDEX_NAME = 'idx_ts_diagnosis_authority'"));
        assertTrue(countOccurrences(mysql, "PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;") >= 4);

        assertFalse(mysqlLogic.contains("routeMode"));
        assertTrue(mysqlLogic.contains("ERROR_CODE_PLAYBOOK"));
        assertTrue(mysqlLogic.contains("SCENARIO_PLAYBOOK"));
        assertTrue(mysqlLogic.contains("OPEN_DISCOVERY"));
        assertTrue(mysqlLogic.contains("EXPLICIT"));
        assertTrue(mysqlLogic.contains("RULE_MATCHED"));
        assertTrue(mysqlLogic.contains("MODEL_PROPOSED"));
        assertTrue(countOccurrences(mysqlLogic, "ELSE NULL") >= 2);
        assertTrue(mysqlLogic.contains("contract_version NOT IN ('1.3', '1.4')"));

        assertFalse(kingbaseLogic.contains("routeMode"));
        assertTrue(kingbaseLogic.contains("ERROR_CODE_PLAYBOOK"));
        assertTrue(kingbaseLogic.contains("SCENARIO_PLAYBOOK"));
        assertTrue(kingbaseLogic.contains("OPEN_DISCOVERY"));
        assertTrue(kingbaseLogic.contains("EXPLICIT"));
        assertTrue(kingbaseLogic.contains("RULE_MATCHED"));
        assertTrue(kingbaseLogic.contains("MODEL_PROPOSED"));
        assertTrue(countOccurrences(kingbaseLogic, "ELSE NULL") >= 2);
        assertTrue(kingbaseLogic.contains("contract_version NOT IN ('1.3', '1.4')"));
    }

    @Test
    void v196CreatesImmutableScenarioEvidenceRunAuditInAllDialects() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:troubleshooting-v196;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                "")) {
            executeMigration(
                    connection,
                    "db/migration/h2/V196__troubleshooting_scenario_evidence_run.sql");

            assertTrue(tables(connection.getMetaData())
                    .contains("mate_troubleshooting_scenario_evidence_run"));
            Set<String> columns = columns(
                    connection.getMetaData(),
                    "mate_troubleshooting_scenario_evidence_run");
            assertTrue(columns.containsAll(Set.of(
                    "workspace_id", "run_id", "diagnosis_id", "playbook_id",
                    "playbook_version", "diagnosis_status", "conclusion_type",
                    "evidence_refs", "actor_ref", "started_at", "completed_at")));
            assertFalse(columns.contains("query"));
            assertFalse(columns.contains("observed"));
            assertFalse(columns.contains("raw_log"));
            assertEquals(1, countIndexes(connection, "uk_ts_scenario_evidence_run_id"));
            assertEquals(1, countIndexes(
                    connection, "idx_ts_scenario_evidence_run_diagnosis"));
        }

        for (String dialect : List.of("mysql", "kingbase")) {
            String migration = resourceText(
                    "db/migration/" + dialect
                            + "/V196__troubleshooting_scenario_evidence_run.sql");
            assertTrue(migration.contains("mate_troubleshooting_scenario_evidence_run"));
            assertTrue(migration.contains("evidence_refs"));
            assertFalse(migration.contains("api_key"));
            assertFalse(migration.contains("raw_log"));
            assertFalse(migration.contains("query_text"));
        }
    }

    @Test
    void v197CreatesSecretFreeOpenDiscoveryRunAuditInAllDialects() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:troubleshooting-v197;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                "")) {
            executeMigration(
                    connection,
                    "db/migration/h2/V197__troubleshooting_open_discovery_run.sql");

            assertTrue(tables(connection.getMetaData())
                    .contains("mate_troubleshooting_open_discovery_run"));
            Set<String> columns = columns(
                    connection.getMetaData(),
                    "mate_troubleshooting_open_discovery_run");
            assertTrue(columns.containsAll(Set.of(
                    "workspace_id", "run_id", "diagnosis_id",
                    "visible_scenario_keys", "selected_scenario_key",
                    "planned_signal_kinds", "max_iterations",
                    "max_evidence_requests", "source_request_count",
                    "time_budget_ms", "stop_reason", "evidence_refs",
                    "actor_ref", "started_at", "completed_at")));
            assertFalse(columns.contains("prompt"));
            assertFalse(columns.contains("model_output"));
            assertFalse(columns.contains("query"));
            assertFalse(columns.contains("observed"));
            assertFalse(columns.contains("raw_log"));
            assertEquals(1, countIndexes(connection, "uk_ts_open_discovery_run_id"));
            assertEquals(1, countIndexes(
                    connection, "idx_ts_open_discovery_run_diagnosis"));
        }

        for (String dialect : List.of("mysql", "kingbase")) {
            String migration = resourceText(
                    "db/migration/" + dialect
                            + "/V197__troubleshooting_open_discovery_run.sql");
            assertTrue(migration.contains("mate_troubleshooting_open_discovery_run"));
            assertTrue(migration.contains("stop_reason"));
            assertFalse(migration.contains("api_key"));
            assertFalse(migration.contains("raw_log"));
            assertFalse(migration.contains("query_text"));
            assertFalse(migration.contains("model_output"));
        }
    }

    private void executeMigration(Connection connection, String resourcePath) {
        ScriptUtils.executeSqlScript(
                connection,
                new EncodedResource(new ClassPathResource(resourcePath), "UTF-8"));
    }

    private String resourceText(String resourcePath) throws Exception {
        try (var inputStream = new ClassPathResource(resourcePath).getInputStream()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private int countOccurrences(String haystack, String needle) {
        int count = 0;
        int fromIndex = 0;
        while ((fromIndex = haystack.indexOf(needle, fromIndex)) >= 0) {
            count++;
            fromIndex += needle.length();
        }
        return count;
    }

    private String stripSqlLineComments(String sql) {
        return sql.replaceAll("(?m)^\\s*--.*$", "");
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
