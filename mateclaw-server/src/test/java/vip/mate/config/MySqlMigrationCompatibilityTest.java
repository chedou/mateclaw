package vip.mate.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlMigrationCompatibilityTest {

    @Test
    @DisplayName("plugin migration does not require MySQL 8.0.13 TEXT defaults")
    void pluginConfigUsesApplicationSuppliedValueInsteadOfTextDefault() throws Exception {
        String sql = new ClassPathResource("db/migration/mysql/V6__plugin_table.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertTrue(sql.contains("config_json   TEXT          NOT NULL,"));
        assertFalse(sql.matches("(?is).*config_json\\s+TEXT\\s+NOT NULL\\s+DEFAULT.*"));
    }

    @Test
    @DisplayName("troubleshooting system scope is renamed away from MySQL reserved word")
    void troubleshootingSystemColumnUsesPortableName() throws Exception {
        List<String> tables = List.of(
                "mate_troubleshooting_diagnosis",
                "mate_troubleshooting_sop",
                "mate_troubleshooting_playbook_candidate",
                "mate_troubleshooting_evaluation_sample",
                "mate_troubleshooting_guance_acceptance",
                "mate_troubleshooting_playbook_version",
                "mate_troubleshooting_deployment_topology",
                "mate_troubleshooting_evidence_route",
                "mate_troubleshooting_observability_asset",
                "mate_troubleshooting_evidence_contract_trial");

        for (String dialect : List.of("h2", "mysql", "kingbase")) {
            String sql = new ClassPathResource(
                    "db/migration/" + dialect + "/V204__troubleshooting_system_column.sql")
                    .getContentAsString(StandardCharsets.UTF_8);
            for (String table : tables) {
                assertTrue(sql.contains("ALTER TABLE " + table),
                        () -> dialect + " migration must rename " + table);
            }
            assertTrue(sql.contains("system_name"));
        }
    }

    @Test
    @DisplayName("H2 copy script lets MySQL recompute generated columns and binds JSON as text")
    void h2CopyHandlesMySqlGeneratedAndJsonColumns() throws Exception {
        Path workingDirectory = Path.of("").toAbsolutePath();
        Path script = workingDirectory.resolve("scripts/h2-to-mysql.java");
        if (!Files.isRegularFile(script)) {
            script = workingDirectory.resolve("../scripts/h2-to-mysql.java").normalize();
        }
        String source = Files.readString(script, StandardCharsets.UTF_8);

        assertTrue(source.contains("NOT LIKE '%GENERATED%'"));
        assertTrue(source.contains("\"json\".equalsIgnoreCase(targetType)"));
        assertTrue(source.contains("new String(bytes, StandardCharsets.UTF_8)"));
    }
}
