package vip.mate.troubleshooting.persistence;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PilotPlanMigrationTest {

    @Test
    void allDialectsKeepTheSameSecretFreeAppendOnlyShape() throws Exception {
        for (String dialect : List.of("h2", "mysql", "kingbase")) {
            String sql;
            try (var input = new ClassPathResource(
                    "db/migration/" + dialect
                            + "/V201__troubleshooting_pilot_plan.sql").getInputStream()) {
                sql = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                        .toLowerCase(Locale.ROOT);
            }
            assertThat(sql).contains(
                    "mate_troubleshooting_pilot_plan",
                    "module_scopes",
                    "second_line_user_id",
                    "third_line_user_id",
                    "source_owner_user_id",
                    "workspace_id, version");
            assertThat(sql).doesNotContain(
                    "api_key", "credential", "raw_log", "observed_data", "dql");
        }
    }

    @Test
    void h2MigrationKeepsImmutablePilotRevisionsAndRejectsDuplicateVersions()
            throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:ts-pilot-plan-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(
                    connection,
                    new EncodedResource(new ClassPathResource(
                            "db/migration/h2/V201__troubleshooting_pilot_plan.sql"),
                            "UTF-8"));
            try (Statement statement = connection.createStatement()) {
                assertThat(statement.executeUpdate(insert(1, 1))).isEqualTo(1);
                assertThat(statement.executeUpdate(insert(2, 2))).isEqualTo(1);
                assertThatThrownBy(() -> statement.executeUpdate(insert(3, 2)))
                        .isInstanceOf(SQLException.class);
            }
        }
    }

    private String insert(long id, int version) {
        return """
                INSERT INTO mate_troubleshooting_pilot_plan (
                    id, workspace_id, plan_name, module_scopes,
                    second_line_user_id, third_line_user_id, source_owner_user_id,
                    enabled, version, changed_by, change_reason
                ) VALUES (
                    %d, 7, 'CSDP pilot',
                    '[{"system":"csdp","service":"csdp-wechat"}]',
                    11, 12, 13, 1, %d, 'admin', 'test revision'
                )
                """.formatted(id, version);
    }
}
