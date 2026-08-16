package vip.mate.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationVersion;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.jdbc.core.JdbcTemplate;

class MigrationLineageHealthIndicatorTest {

    private Flyway flyway;
    private MigrationInfoService info;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        flyway = mock(Flyway.class);
        info = mock(MigrationInfoService.class);
        when(flyway.info()).thenReturn(info);

        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:migration-lineage;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP ALL OBJECTS");
    }

    @Test
    void isUpOnlyWhenBothMergedMigrationRootsExist() {
        currentVersion("217");
        jdbc.execute("CREATE TABLE mate_troubleshooting_diagnosis (id BIGINT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE mate_agent_team (id BIGINT PRIMARY KEY)");

        assertThat(new MigrationLineageHealthIndicator(flyway, jdbc).health().getStatus())
                .isEqualTo(Status.UP);
    }

    @Test
    void isDownForAnOldOrSingleStreamDatabase() {
        currentVersion("217");
        jdbc.execute("CREATE TABLE mate_agent_team (id BIGINT PRIMARY KEY)");

        assertThat(new MigrationLineageHealthIndicator(flyway, jdbc).health().getStatus())
                .isEqualTo(Status.DOWN);
    }

    private void currentVersion(String version) {
        MigrationInfo current = mock(MigrationInfo.class);
        when(current.getVersion()).thenReturn(MigrationVersion.fromVersion(version));
        when(info.current()).thenReturn(current);
    }
}
