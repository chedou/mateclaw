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
import vip.mate.troubleshooting.repository.TroubleshootingPlaybookVersionMapper;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Executes every annotated V186 version lookup against a real H2 database. */
class TroubleshootingPlaybookVersionMapperIntegrationTest {

    private JdbcDataSource dataSource;
    private SqlSession sqlSession;
    private SqlSessionFactory sqlSessionFactory;
    private TroubleshootingPlaybookVersionMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL(
                "jdbc:h2:mem:ts-playbook-version-mapper-" + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=250");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        try (Connection connection = dataSource.getConnection()) {
            executeMigration(connection, "db/migration/h2/V172__troubleshooting_domain.sql");
            executeMigration(connection, "db/migration/h2/V185__troubleshooting_knowledge_review.sql");
            executeMigration(connection, "db/migration/h2/V186__troubleshooting_playbook_version.sql");
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO mate_troubleshooting_playbook_version (
                            id, workspace_id, playbook_id, selector_key,
                            playbook_version, active_selector_key, system,
                            error_code, service, status, source_origin,
                            source_record_id, review_id, review_version,
                            approved_by, approval_reason, contract_version,
                            aggregate_json, version, deleted
                        ) VALUES (
                            1, 7, 'playbook-1', 'csdp:903001',
                            1, 'csdp:903001', 'CSDP', '903001', 'order-svc',
                            'APPROVED', 'MANUAL', 'manual-1', 'review-1', 2,
                            'reviewer-a', 'fixed replay passed', 'sop.v1',
                            '{}', 0, 0
                        )
                        """);
            }
        }

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment(
                "test", new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(TroubleshootingPlaybookVersionMapper.class);
        sqlSessionFactory =
                new MybatisSqlSessionFactoryBuilder().build(configuration);
        sqlSession = sqlSessionFactory.openSession(false);
        mapper = sqlSession.getMapper(TroubleshootingPlaybookVersionMapper.class);
    }

    @AfterEach
    void tearDown() {
        if (sqlSession != null) {
            sqlSession.close();
        }
    }

    @Test
    void annotatedVersionLookupsUseExecutableSql() {
        assertThat(mapper.findActive(7L, "csdp:903001").getPlaybookId())
                .isEqualTo("playbook-1");
        assertThat(mapper.findCurrent(7L, "csdp:903001").getPlaybookId())
                .isEqualTo("playbook-1");
        assertThat(mapper.findByReview(7L, "review-1").getPlaybookId())
                .isEqualTo("playbook-1");
        assertThat(mapper.findByPlaybookId(7L, "playbook-1").getSelectorKey())
                .isEqualTo("csdp:903001");
        assertThat(mapper.lockActiveApprovedByPlaybookId(7L, "playbook-1")
                .getPlaybookVersion()).isEqualTo(1);
        assertThat(mapper.listLatest(7L, null, null, 10))
                .singleElement()
                .extracting("playbookId")
                .isEqualTo("playbook-1");
    }

    @Test
    void activeAuthorityLockIsHeldUntilTheOwningTransactionEnds() throws Exception {
        assertThat(mapper.lockActiveApprovedByPlaybookId(7L, "playbook-1"))
                .isNotNull();

        try (SqlSession concurrent = sqlSessionFactory.openSession(false)) {
            assertThatThrownBy(() -> {
                try (Statement statement = concurrent.getConnection().createStatement()) {
                    statement.executeUpdate("""
                            UPDATE mate_troubleshooting_playbook_version
                            SET status = 'DEPRECATED'
                            WHERE workspace_id = 7 AND playbook_id = 'playbook-1'
                            """);
                }
            }).isInstanceOf(SQLException.class);
        }

        // A SELECT ... FOR UPDATE does not mark MyBatis dirty, so force the
        // rollback to end the JDBC transaction and release its row lock.
        sqlSession.rollback(true);
        try (SqlSession concurrent = sqlSessionFactory.openSession(false);
                Statement statement = concurrent.getConnection().createStatement()) {
            assertThat(statement.executeUpdate("""
                    UPDATE mate_troubleshooting_playbook_version
                    SET status = 'DEPRECATED'
                    WHERE workspace_id = 7 AND playbook_id = 'playbook-1'
                    """)).isEqualTo(1);
            concurrent.rollback();
        }
    }

    private void executeMigration(Connection connection, String resourcePath) {
        ScriptUtils.executeSqlScript(
                connection,
                new EncodedResource(new ClassPathResource(resourcePath), "UTF-8"));
    }
}
