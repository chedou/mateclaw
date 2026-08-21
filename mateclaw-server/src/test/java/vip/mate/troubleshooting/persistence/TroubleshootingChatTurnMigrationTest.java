package vip.mate.troubleshooting.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class TroubleshootingChatTurnMigrationTest {
    private static final String MIGRATION = "V222__troubleshooting_chat_turn.sql";

    @Test
    void h2CreatesThePointerOnlyIdempotencyLedger() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:troubleshooting-chat-v222;MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "")) {
            ScriptUtils.executeSqlScript(connection, new EncodedResource(
                    new ClassPathResource("db/migration/h2/" + MIGRATION), "UTF-8"));
            try (var columns = connection.getMetaData().getColumns(
                    null, null, "mate_troubleshooting_chat_turn", null)) {
                StringBuilder names = new StringBuilder();
                while (columns.next()) names.append(columns.getString("COLUMN_NAME")).append(' ');
                assertThat(names.toString().toLowerCase(Locale.ROOT))
                        .contains("workspace_id", "conversation_id", "client_turn_id",
                                "user_message_id", "assistant_message_id")
                        .doesNotContain("content", "payload", "fingerprint", "raw_log");
            }
        }
    }

    @Test
    void allDialectsKeepTheLedgerPointerOnly() throws Exception {
        for (String dialect : List.of("mysql", "kingbase")) {
            String sql;
            try (var stream = new ClassPathResource(
                    "db/migration/" + dialect + "/" + MIGRATION).getInputStream()) {
                sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                        .toLowerCase(Locale.ROOT);
            }
            assertThat(sql)
                    .contains("uk_ts_chat_turn", "client_turn_id", "assistant_message_id")
                    .doesNotContain("raw_log", "authorization", "payload_fingerprint");
        }
    }
}
