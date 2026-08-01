package vip.mate.troubleshooting.persistence;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManualPlaybookReplayMigrationTest {

    @Test
    void h2MigrationEnforcesOneProofPerExactCandidateAndSuiteFingerprint()
            throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(
                "jdbc:h2:mem:ts-manual-replay-" + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(
                    connection,
                    new EncodedResource(new ClassPathResource(
                            "db/migration/h2/"
                                    + "V189__troubleshooting_manual_playbook_replay.sql"),
                            "UTF-8"));
            try (Statement statement = connection.createStatement()) {
                assertThat(statement.executeUpdate(insert(1, "attestation-1")))
                        .isEqualTo(1);
                assertThatThrownBy(() -> statement.executeUpdate(
                        insert(2, "attestation-2")))
                        .isInstanceOf(SQLException.class);
            }
        }
    }

    private String insert(long id, String attestationId) {
        return """
                INSERT INTO mate_troubleshooting_manual_playbook_replay (
                    id, workspace_id, attestation_id, source_record_id,
                    selector_key, candidate_fingerprint, suite_id, suite_version,
                    suite_fingerprint, status, result_json, executed_by, executed_at,
                    deleted
                ) VALUES (
                    %d, 7, '%s', 'manual-topology-v1',
                    'csdp:scenario:deployment_topology_probe', '%s',
                    'deployment-topology-probe/v1', 1, '%s', 'PASSED', '{}',
                    'reviewer-a', CURRENT_TIMESTAMP, 0
                )
                """.formatted(id, attestationId, "a".repeat(64), "b".repeat(64));
    }
}
