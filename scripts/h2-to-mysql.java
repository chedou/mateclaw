/// Copies every table's rows from the local H2 development database into a
/// MySQL database whose schema Flyway has already created.
///
/// Why JDBC row-by-row instead of a SQL dump: H2 and MySQL disagree on the
/// literal syntax for booleans, CLOBs, and timestamps, so a text dump has to be
/// dialect-translated before it loads. Reading typed values and re-binding them
/// through PreparedStatement lets both drivers do that translation themselves.
///
/// flyway_schema_history is never copied. The target recorded its own migration
/// checksums under the MySQL dialect; overwriting them with the H2 dialect's
/// checksums would make every later `flyway migrate` fail validation.
///
/// Usage (Java 21 runs this file directly, no compile step):
///   MATECLAW_MIGRATION_DB_PASSWORD=... \
///   java -cp h2.jar:mysql-connector-j.jar scripts/h2-to-mysql.java \
///        <h2-url> <mysql-url> <mysql-user> <verify|copy> <expected-schema>
///
/// `verify` opens both databases, compares the table and column inventory, and
/// prints what a copy would move — without writing anything.
///
/// expected-schema is compared against the connection's actual default schema
/// before anything is written. Copying empties each target table first, so on a
/// server hosting unrelated databases a mistyped URL would otherwise be
/// destructive; this turns that into a refusal.
///
/// `copy` additionally requires MATECLAW_MIGRATION_ALLOW_REPLACE=true. The
/// target's table and column inventory must exactly match the H2 source (apart
/// from Flyway's own history table), otherwise both verify and copy fail closed.

import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;

class Main {

    /** Rows per executeBatch, kept well under a default max_allowed_packet. */
    private static final int BATCH = 500;

    private static final String HISTORY = "flyway_schema_history";
    private static final String PASSWORD_ENV = "MATECLAW_MIGRATION_DB_PASSWORD";
    private static final String REPLACE_ENV = "MATECLAW_MIGRATION_ALLOW_REPLACE";

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            System.err.println(
                    "usage: <h2-url> <mysql-url> <user> <verify|copy> <expected-schema>\n"
                            + "password must be supplied via " + PASSWORD_ENV);
            System.exit(2);
        }
        String h2Url = args[0];
        String myUrl = args[1];
        String myUser = args[2];
        String myPass = System.getenv(PASSWORD_ENV);
        if (myPass == null || myPass.isBlank()) {
            System.err.println("refusing to run: " + PASSWORD_ENV + " is empty");
            System.exit(2);
        }
        boolean copy = "copy".equals(args[3]);
        if (!copy && !"verify".equals(args[3])) {
            System.err.println("mode must be 'verify' or 'copy'");
            System.exit(2);
        }
        if (copy && !"true".equals(System.getenv(REPLACE_ENV))) {
            System.err.println("refusing to replace target data: set " + REPLACE_ENV + "=true");
            System.exit(2);
        }
        String expectedSchema = args[4];

        try (Connection h2 = DriverManager.getConnection(h2Url, "sa", "");
             Connection my = DriverManager.getConnection(myUrl, myUser, myPass)) {

            String schema = currentSchema(my);
            if (!expectedSchema.equals(schema)) {
                System.err.printf("refusing to run: connected to schema '%s', expected '%s'%n",
                        schema, expectedSchema);
                System.exit(3);
            }
            System.out.printf("target schema: %s%n%n", schema);

            my.setAutoCommit(false);

            List<String> tables = sourceTables(h2);
            Set<String> sourceTableSet = new LinkedHashSet<>(tables);
            Set<String> targetTableSet = targetTables(my);
            targetTableSet.remove(HISTORY);

            Set<String> absentFromMySql = difference(sourceTableSet, targetTableSet);
            Set<String> absentFromH2 = difference(targetTableSet, sourceTableSet);
            if (!absentFromMySql.isEmpty() || !absentFromH2.isEmpty()) {
                throw new SQLException("schema table inventory differs; absent from MySQL="
                        + absentFromMySql + ", absent from H2=" + absentFromH2);
            }

            Map<String, List<String>> columnsByTable = new LinkedHashMap<>();
            Map<String, List<String>> generatedColumnsByTable = new LinkedHashMap<>();
            List<String> planned = new ArrayList<>();
            long totalRows = 0;

            for (String table : tables) {
                List<String> sourceColumns = sourceColumns(h2, table);
                List<String> targetColumns = targetColumns(my, table);
                if (!new LinkedHashSet<>(sourceColumns).equals(new LinkedHashSet<>(targetColumns))) {
                    throw new SQLException("schema column inventory differs for " + table
                            + "; H2=" + sourceColumns + ", MySQL=" + targetColumns);
                }
                List<String> writableColumns = targetWritableColumns(my, table);
                if (!new LinkedHashSet<>(sourceColumns).containsAll(writableColumns)) {
                    throw new SQLException("MySQL writable columns are absent from H2 for " + table
                            + "; writable=" + writableColumns + ", H2=" + sourceColumns);
                }
                columnsByTable.put(table, writableColumns);
                List<String> generatedColumns = sourceColumns.stream()
                        .filter(column -> !writableColumns.contains(column))
                        .toList();
                if (!generatedColumns.isEmpty()) {
                    generatedColumnsByTable.put(table, generatedColumns);
                }
                long rows = count(h2, table);
                planned.add(table);
                totalRows += rows;
            }

            System.out.printf("%d tables to copy, %d rows total%n", planned.size(), totalRows);
            generatedColumnsByTable.forEach((table, columns) ->
                    System.out.printf("  generated by MySQL, not inserted: %s.%s%n", table, columns));
            if (!copy) {
                for (String table : planned) {
                    System.out.printf("  %-52s %d%n", table, count(h2, table));
                }
                System.out.println("\nverify only; nothing written");
                return;
            }

            // The source is a consistent snapshot of a schema built by the same
            // migration set, so referential order is already satisfiable — but
            // only once every table is loaded. Deferring the checks avoids
            // having to topologically sort 111 tables.
            exec(my, "SET FOREIGN_KEY_CHECKS=0");

            long copied = 0;
            for (String table : planned) {
                long n = copyTable(h2, my, table, columnsByTable.get(table));
                copied += n;
                System.out.printf("  %-52s %d%n", table, n);
            }

            exec(my, "SET FOREIGN_KEY_CHECKS=1");
            my.commit();

            System.out.printf("%ncopied %d rows into %d tables%n", copied, planned.size());

            System.out.println("\nverifying row counts:");
            int mismatched = 0;
            for (String table : planned) {
                long src = count(h2, table);
                long dst = count(my, table);
                if (src != dst) {
                    mismatched++;
                    System.out.printf("  MISMATCH %-44s h2=%d mysql=%d%n", table, src, dst);
                }
            }
            System.out.println(mismatched == 0
                    ? "  all tables match"
                    : "  " + mismatched + " tables did not match");
            if (mismatched > 0) {
                System.exit(1);
            }
        }
    }

    private static long copyTable(Connection h2, Connection my, String table, List<String> columns)
            throws SQLException {
        if (columns.isEmpty()) {
            throw new SQLException("no columns for table " + table);
        }

        exec(my, "DELETE FROM `" + table + "`");

        String cols = String.join(", ", columns.stream().map(c -> "`" + c + "`").toList());
        String marks = String.join(", ", Collections.nCopies(columns.size(), "?"));
        String insert = "INSERT INTO `" + table + "` (" + cols + ") VALUES (" + marks + ")";

        String select = "SELECT " + cols + " FROM `" + table + "`";
        Map<String, String> targetTypes = targetColumnTypes(my, table);

        long rows = 0;
        try (Statement read = h2.createStatement();
             ResultSet rs = read.executeQuery(select);
             PreparedStatement write = my.prepareStatement(insert)) {
            int pending = 0;
            while (rs.next()) {
                for (int i = 1; i <= columns.size(); i++) {
                    String column = columns.get(i - 1);
                    bind(write, i, read(rs, i), table, column, targetTypes.get(column));
                }
                write.addBatch();
                rows++;
                if (++pending == BATCH) {
                    write.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) {
                write.executeBatch();
            }
        }
        return rows;
    }

    /** Reads a value in a form both drivers agree on. */
    private static Object read(ResultSet rs, int index) throws SQLException {
        int type = rs.getMetaData().getColumnType(index);
        Object value = switch (type) {
            // Streaming LOB handles are tied to the source cursor and cannot be
            // handed to the target driver; materialize them instead.
            case Types.CLOB, Types.NCLOB -> rs.getString(index);
            case Types.BLOB, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> rs.getBytes(index);
            default -> rs.getObject(index);
        };
        return rs.wasNull() ? null : value;
    }

    private static void bind(PreparedStatement ps, int index, Object value, String table, String column,
                             String targetType)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.NULL);
            return;
        }
        try {
            // H2 exposes its JSON values as UTF-8 byte arrays. Binding those
            // bytes directly makes MySQL treat them as CHARACTER SET binary,
            // which JSON columns reject. Only convert when the target metadata
            // explicitly says JSON; ordinary BLOB/VARBINARY values stay bytes.
            if ("json".equalsIgnoreCase(targetType)) {
                if (value instanceof byte[] bytes) {
                    ps.setString(index, new String(bytes, StandardCharsets.UTF_8));
                } else {
                    ps.setString(index, value.toString());
                }
                return;
            }
            // H2 hands back an offset for TIMESTAMP WITH TIME ZONE; MySQL DATETIME
            // has no offset to store it in, so normalize rather than let the driver
            // guess. The schema is Asia/Shanghai throughout, so the instant is
            // preserved by converting in the JVM's zone.
            if (value instanceof OffsetDateTime odt) {
                ps.setObject(index, odt.toLocalDateTime());
                return;
            }
            ps.setObject(index, value);
        } catch (SQLException e) {
            throw new SQLException("failed to bind " + table + "." + column
                    + " (" + value.getClass().getName() + ")", e);
        }
    }

    private static List<String> sourceTables(Connection h2) throws SQLException {
        List<String> out = new ArrayList<>();
        String sql = """
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'PUBLIC' AND table_type = 'BASE TABLE'
                ORDER BY table_name""";
        try (Statement s = h2.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                String name = rs.getString(1).toLowerCase(Locale.ROOT);
                if (!HISTORY.equals(name)) {
                    out.add(name);
                }
            }
        }
        return out;
    }

    private static Set<String> targetTables(Connection my) throws SQLException {
        Set<String> out = new HashSet<>();
        String sql = """
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'""";
        try (Statement s = my.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1).toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    private static List<String> sourceColumns(Connection h2, String table) throws SQLException {
        return columns(h2, table, """
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'PUBLIC' AND LOWER(table_name) = ?
                ORDER BY ordinal_position""");
    }

    private static List<String> targetColumns(Connection my, String table) throws SQLException {
        return columns(my, table, """
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = DATABASE() AND LOWER(table_name) = ?
                ORDER BY ordinal_position""");
    }

    /**
     * MySQL rejects explicit values for virtual/stored generated columns. Their
     * definitions are still covered by the full column-inventory check above;
     * copying the ordinary source columns lets MySQL recompute them itself.
     * AUTO_INCREMENT columns remain writable so original identifiers survive.
     */
    private static List<String> targetWritableColumns(Connection my, String table) throws SQLException {
        return columns(my, table, """
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = DATABASE() AND LOWER(table_name) = ?
                  AND UPPER(COALESCE(extra, '')) NOT LIKE '%GENERATED%'
                ORDER BY ordinal_position""");
    }

    private static Map<String, String> targetColumnTypes(Connection my, String table) throws SQLException {
        Map<String, String> out = new HashMap<>();
        String sql = """
                SELECT column_name, data_type FROM information_schema.columns
                WHERE table_schema = DATABASE() AND LOWER(table_name) = ?""";
        try (PreparedStatement ps = my.prepareStatement(sql)) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1).toLowerCase(Locale.ROOT), rs.getString(2));
                }
            }
        }
        return out;
    }

    private static List<String> columns(Connection c, String table, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1).toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }

    private static String currentSchema(Connection my) throws SQLException {
        try (Statement s = my.createStatement();
             ResultSet rs = s.executeQuery("SELECT DATABASE()")) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> result = new LinkedHashSet<>(left);
        result.removeAll(right);
        return result;
    }

    private static long count(Connection c, String table) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM `" + table + "`")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }
}
