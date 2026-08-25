package vip.mate.tool.itdb;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItDbToolMigrationTest {

    private static final String MIGRATION = "V224__register_itdb_workflow_tool.sql";
    private static final String EMPLOYEE_MIGRATION = "V225__bind_itdb_approval_employee.sql";

    @Test
    void h2MigrationRegistersOneIdempotentBuiltinToolRow() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:itdb-tool-migration;MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "")) {
            connection.createStatement().execute("""
                    CREATE TABLE mate_tool (
                      id BIGINT PRIMARY KEY,
                      name VARCHAR(100) NOT NULL UNIQUE,
                      display_name VARCHAR(100),
                      description CLOB,
                      tool_type VARCHAR(30),
                      bean_name VARCHAR(100),
                      icon VARCHAR(30),
                      enabled BOOLEAN,
                      builtin BOOLEAN,
                      disclosure_tier VARCHAR(20),
                      create_time TIMESTAMP,
                      update_time TIMESTAMP,
                      deleted INT
                    )
                    """);

            execute(connection, "db/migration/h2/" + MIGRATION);
            execute(connection, "db/migration/h2/" + MIGRATION);

            try (ResultSet rs = connection.createStatement().executeQuery(
                    "SELECT name, bean_name, enabled, builtin FROM mate_tool WHERE id=1000000906")) {
                assertTrue(rs.next());
                assertEquals("ItDbWorkflowTool", rs.getString("name"));
                assertEquals("itDbWorkflowTool", rs.getString("bean_name"));
                assertTrue(rs.getBoolean("enabled"));
                assertTrue(rs.getBoolean("builtin"));
                assertFalse(rs.next());
            }
        }
    }

    @Test
    void everyDialectRegistersTheSameToolAndDoesNotMentionExecuteEndpoint() throws Exception {
        for (String dialect : new String[]{"h2", "mysql", "kingbase"}) {
            String sql = new ClassPathResource("db/migration/" + dialect + "/" + MIGRATION)
                    .getContentAsString(StandardCharsets.UTF_8);
            assertTrue(sql.contains("1000000906"), dialect);
            assertTrue(sql.contains("ItDbWorkflowTool"), dialect);
            assertFalse(sql.contains("/api/v1/workflow/execute/"), dialect);
        }
    }

    @Test
    void h2EmployeeMigrationKeepsOnlyItDbCapabilitiesAndIsIdempotent() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:itdb-employee-migration;MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "")) {
            connection.createStatement().execute("""
                    CREATE TABLE mate_agent (
                      id BIGINT PRIMARY KEY, name VARCHAR(128), description CLOB, agent_type VARCHAR(32),
                      system_prompt CLOB, model_name VARCHAR(128), max_iterations INT, enabled BOOLEAN,
                      icon VARCHAR(32), tags VARCHAR(256), workspace_id BIGINT, create_time TIMESTAMP,
                      update_time TIMESTAMP, deleted INT, skills_disabled BOOLEAN,
                      tools_disabled BOOLEAN, wiki_disabled BOOLEAN,
                      UNIQUE (workspace_id, name)
                    );
                    CREATE TABLE mate_skill (
                      id BIGINT PRIMARY KEY, name VARCHAR(128), deleted INT
                    );
                    CREATE TABLE mate_agent_tool (
                      id BIGINT PRIMARY KEY, agent_id BIGINT, tool_name VARCHAR(128), enabled BOOLEAN,
                      create_time TIMESTAMP, update_time TIMESTAMP, deleted INT,
                      UNIQUE (agent_id, tool_name)
                    );
                    CREATE TABLE mate_agent_skill (
                      id BIGINT PRIMARY KEY, agent_id BIGINT, skill_id BIGINT, enabled BOOLEAN,
                      config_json CLOB, create_time TIMESTAMP, update_time TIMESTAMP, deleted INT,
                      UNIQUE (agent_id, skill_id)
                    )
                    """);
            connection.createStatement().execute("""
                    INSERT INTO mate_skill VALUES
                      (2091870934831804418, 'SXF-itdb-sql-approval-reader', 0),
                      (2091870991194861569, 'SXF-itdb-sql-risk-assessor', 0),
                      (2091871049277583362, 'SXF-itdb-sql-execution-decision', 0),
                      (2091871106345283585, 'SXF-itdb-sql-approval-submit', 0)
                    """);

            execute(connection, "db/migration/h2/" + EMPLOYEE_MIGRATION);
            execute(connection, "db/migration/h2/" + EMPLOYEE_MIGRATION);

            try (ResultSet rs = connection.createStatement().executeQuery("""
                    SELECT enabled, skills_disabled, tools_disabled, wiki_disabled, system_prompt
                    FROM mate_agent WHERE name='SXF-ITDB SQL审批安全官'
                    """)) {
                assertTrue(rs.next());
                assertTrue(rs.getBoolean("enabled"));
                assertFalse(rs.getBoolean("skills_disabled"));
                assertFalse(rs.getBoolean("tools_disabled"));
                assertTrue(rs.getBoolean("wiki_disabled"));
                assertTrue(rs.getString("system_prompt").contains("canExecuteSql 永远为 false"));
                assertFalse(rs.next());
            }
            try (ResultSet rs = connection.createStatement().executeQuery("""
                    SELECT COUNT(*) FROM mate_agent_tool t JOIN mate_agent a ON a.id=t.agent_id
                    WHERE a.name='SXF-ITDB SQL审批安全官' AND t.tool_name='ItDbWorkflowTool'
                      AND t.enabled=TRUE AND t.deleted=0
                    """)) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
            try (ResultSet rs = connection.createStatement().executeQuery("""
                    SELECT COUNT(*) FROM mate_agent_skill s JOIN mate_agent a ON a.id=s.agent_id
                    WHERE a.name='SXF-ITDB SQL审批安全官' AND s.enabled=TRUE AND s.deleted=0
                    """)) {
                assertTrue(rs.next());
                assertEquals(4, rs.getInt(1));
            }
        }
    }

    @Test
    void everyDialectBindsTheDedicatedEmployeeWithoutExecuteCapability() throws Exception {
        for (String dialect : new String[]{"h2", "mysql", "kingbase"}) {
            String sql = new ClassPathResource("db/migration/" + dialect + "/" + EMPLOYEE_MIGRATION)
                    .getContentAsString(StandardCharsets.UTF_8);
            assertTrue(sql.contains("SXF-ITDB SQL审批安全官"), dialect);
            assertTrue(sql.contains("ItDbWorkflowTool"), dialect);
            assertTrue(sql.contains("2091871106345283585"), dialect);
            assertFalse(sql.contains("workflow/execute"), dialect);
        }
    }

    private static void execute(Connection connection, String resource) throws Exception {
        ScriptUtils.executeSqlScript(connection, new ClassPathResource(resource));
    }
}
