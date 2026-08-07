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

class EvidenceContractTrialMigrationTest {

    @Test
    void h2MigrationStoresImmutableSecretFreeTrialsAndRejectsDuplicateTrialIds()
            throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:ts-contract-trial-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(
                    connection,
                    new EncodedResource(new ClassPathResource(
                            "db/migration/h2/"
                                    + "V195__troubleshooting_evidence_contract_trial.sql"),
                            "UTF-8"));
            try (Statement statement = connection.createStatement()) {
                assertThat(statement.executeUpdate(insert(1, "trial-safe"))).isEqualTo(1);
                assertThatThrownBy(() -> statement.executeUpdate(insert(2, "trial-safe")))
                        .isInstanceOf(SQLException.class);
            }
        }
    }

    private String insert(long id, String trialId) {
        return """
                INSERT INTO mate_troubleshooting_evidence_contract_trial (
                    id, trial_id, workspace_id, system, service, contract_ref,
                    signal_kind, asset_id, asset_version, status, source_platform,
                    stop_reason, observed_fields, duration_ms, actor, completed_at
                ) VALUES (
                    %d, '%s', 7, 'csdp', 'session-service', 'csdp-log-search',
                    'log_search', 'asset-1', 3, 'OBSERVED', 'guance',
                    'COMPLETED', '["match_count","ps_id"]', 42, 'ops-admin', CURRENT_TIMESTAMP
                )
                """.formatted(id, trialId);
    }
}
