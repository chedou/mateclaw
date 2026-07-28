package vip.mate.troubleshooting.persistence;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import vip.mate.troubleshooting.repository.TroubleshootingDiagnosisMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Executes the annotated V180 mapper SQL against a real H2 database. */
class TroubleshootingDiagnosisMapperIntegrationTest {

    private JdbcDataSource dataSource;
    private SqlSession sqlSession;
    private TroubleshootingDiagnosisMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL(
                "jdbc:h2:mem:ts-diagnosis-mapper-" + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        try (Connection connection = dataSource.getConnection()) {
            executeMigration(connection, "db/migration/h2/V172__troubleshooting_domain.sql");
            executeMigration(connection, "db/migration/h2/V178__troubleshooting_intake_investigation.sql");
            executeMigration(connection, "db/migration/h2/V180__troubleshooting_closure_notification.sql");
        }

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment(
                "test",
                new JdbcTransactionFactory(),
                dataSource));
        configuration.addMapper(TroubleshootingDiagnosisMapper.class);
        SqlSessionFactory factory =
                new MybatisSqlSessionFactoryBuilder().build(configuration);
        sqlSession = factory.openSession(true);
        mapper = sqlSession.getMapper(TroubleshootingDiagnosisMapper.class);
    }

    @AfterEach
    void tearDown() {
        if (sqlSession != null) {
            sqlSession.close();
        }
    }

    @Test
    void schedulesOnlyClosedChannelOriginDiagnoses() throws Exception {
        insertDiagnosis(1L, "diag-channel", "intake-1", "CLOSED");
        insertDiagnosis(2L, "diag-web", null, "CLOSED");
        insertDiagnosis(3L, "diag-open", "intake-3", "CONFIRMED");
        LocalDateTime now = LocalDateTime.of(2026, 7, 29, 4, 0);

        assertEquals(1, mapper.scheduleClosureNotification(7L, "diag-channel", now));
        assertEquals(0, mapper.scheduleClosureNotification(7L, "diag-web", now));
        assertEquals(0, mapper.scheduleClosureNotification(7L, "diag-open", now));

        assertEquals("PENDING", status(1L));
        assertEquals("NOT_APPLICABLE", status(2L));
        assertEquals("NOT_APPLICABLE", status(3L));
    }

    @Test
    void leaseClaimRetryAndCompletionAreCompareAndSetUpdates() throws Exception {
        insertDiagnosis(1L, "diag-channel", "intake-1", "CLOSED");
        LocalDateTime now = LocalDateTime.of(2026, 7, 29, 4, 0);
        assertEquals(1, mapper.scheduleClosureNotification(7L, "diag-channel", now));

        LocalDateTime firstLease = now.plusSeconds(120);
        assertEquals(1, mapper.claimClosureNotification(1L, "worker-a", firstLease, now));
        assertEquals(0, mapper.claimClosureNotification(
                1L, "worker-b", now.plusSeconds(121), now.plusSeconds(1)));
        assertEquals(0, mapper.markClosureNotificationCompleted(
                1L, "worker-b", now.plusSeconds(2)));

        LocalDateTime retryAt = now.plusSeconds(10);
        assertEquals(1, mapper.markClosureNotificationFailed(
                1L, "worker-a", "platform rejected", retryAt, now.plusSeconds(3)));
        assertEquals("platform rejected", lastError(1L));
        assertEquals(0, mapper.claimClosureNotification(
                1L, "worker-b", now.plusSeconds(130), retryAt.minusNanos(1)));
        LocalDateTime secondLease = retryAt.plusSeconds(120);
        assertEquals(1, mapper.claimClosureNotification(
                1L, "worker-b", secondLease, retryAt));

        LocalDateTime reclaimedAt = secondLease.plusSeconds(1);
        assertEquals(1, mapper.claimClosureNotification(
                1L, "worker-c", reclaimedAt.plusSeconds(120), reclaimedAt));
        assertEquals(0, mapper.markClosureNotificationCompleted(
                1L, "worker-b", reclaimedAt.plusSeconds(1)));
        assertEquals(1, mapper.markClosureNotificationCompleted(
                1L, "worker-c", reclaimedAt.plusSeconds(2)));

        assertEquals("COMPLETED", status(1L));
        assertEquals(3, attempts(1L));
        assertNull(lastError(1L));
    }

    private void insertDiagnosis(
            long id,
            String diagnosisId,
            String intakeSessionId,
            String diagnosisStatus) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO mate_troubleshooting_diagnosis (
                            id, workspace_id, diagnosis_id, case_id, run_id,
                            system, service, source_intake_session_id, rehearsal,
                            status, contract_version, aggregate_json, version,
                            deleted, create_time, update_time
                        ) VALUES (?, 7, ?, ?, ?, 'CSDP', 'csdp-wechat', ?, FALSE,
                            ?, 'diagnosis.v1', '{}', 1, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """)) {
            insert.setLong(1, id);
            insert.setString(2, diagnosisId);
            insert.setString(3, "case-" + id);
            insert.setString(4, "run-" + id);
            insert.setString(5, intakeSessionId);
            insert.setString(6, diagnosisStatus);
            insert.executeUpdate();
        }
    }

    private String status(long id) throws Exception {
        return scalar(id, "closure_notification_status", String.class);
    }

    private int attempts(long id) throws Exception {
        Integer value = scalar(id, "closure_notification_attempts", Integer.class);
        return value == null ? -1 : value;
    }

    private String lastError(long id) throws Exception {
        return scalar(id, "closure_notification_last_error", String.class);
    }

    private <T> T scalar(long id, String column, Class<T> type) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement query = connection.prepareStatement(
                        "SELECT " + column
                                + " FROM mate_troubleshooting_diagnosis WHERE id = ?")) {
            query.setLong(1, id);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) {
                    throw new IllegalStateException("missing diagnosis row " + id);
                }
                return row.getObject(1, type);
            }
        }
    }

    private void executeMigration(Connection connection, String resourcePath) {
        ScriptUtils.executeSqlScript(
                connection,
                new EncodedResource(new ClassPathResource(resourcePath), "UTF-8"));
    }
}
