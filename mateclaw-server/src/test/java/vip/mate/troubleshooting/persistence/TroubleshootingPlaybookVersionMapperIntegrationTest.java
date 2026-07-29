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
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Executes every annotated V186 version lookup against a real H2 database. */
class TroubleshootingPlaybookVersionMapperIntegrationTest {

    private JdbcDataSource dataSource;
    private SqlSession sqlSession;
    private TroubleshootingPlaybookVersionMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL(
                "jdbc:h2:mem:ts-playbook-version-mapper-" + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
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
        SqlSessionFactory factory =
                new MybatisSqlSessionFactoryBuilder().build(configuration);
        sqlSession = factory.openSession(true);
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
        assertThat(mapper.listLatest(7L, null, null, 10))
                .singleElement()
                .extracting("playbookId")
                .isEqualTo("playbook-1");
    }

    private void executeMigration(Connection connection, String resourcePath) {
        ScriptUtils.executeSqlScript(
                connection,
                new EncodedResource(new ClassPathResource(resourcePath), "UTF-8"));
    }
}
