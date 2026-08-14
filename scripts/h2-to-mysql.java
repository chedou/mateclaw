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
///   java -cp h2.jar:mysql-connector-j.jar scripts/h2-to-mysql.java \
///        <h2-url> <mysql-url> <mysql-user> <mysql-password> <verify|copy> \
///        <expected-schema>
///
/// `verify` opens both databases, compares the table and column inventory, and
/// prints what a copy would move — without writing anything.
///
/// expected-schema is compared against the connection's actual default schema
/// before anything is written. Copying empties each target table first, so on a
/// server hosting unrelated databases a mistyped URL would otherwise be
/// destructive; this turns that into a refusal.

import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;

class Main {

    /** Rows per executeBatch, kept well under a default max_allowed_packet. */
    private static final int BATCH = 500;

    private static final String HISTORY = "flyway_schema_history";

    public static void main(String[] args) throws Exception {
        if (args.length < 6) {
            System.err.println(
                    "usage: <h2-url> <mysql-url> <user> <password> <verify|copy> <expected-schema>");
            System.exit(2);
        }
        String h2Url = args[0];
        String myUrl = args[1];
        String myUser = args[2];
        String myPass = args[3];
        boolean copy = "copy".equals(args[4]);
        if (!copy && !"verify".equals(args[4])) {
            System.err.println("mode must be 'verify' or 'copy'");
            System.exit(2);
        }
        String expectedSchema = args[5];

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
            Set<String> targetTables = targetTables(my);

            List<String> missing = new ArrayList<>();
            List<String> planned = new ArrayList<>();
            long totalRows = 0;

            for (String table : tables) {
                if (!targetTables.contains(table)) {
                    missing.add(table);
                    continue;
                }
                long rows = count(h2, table);
                if (rows == 0) {
                    continue;
                }
                planned.add(table);
                totalRows += rows;
            }

            if (!missing.isEmpty()) {
                // Not fatal on its own — an empty table absent from the target is
                // noise — but a populated one means the two dialects' migration
                // sets have drifted, and silently skipping it would lose data.
                System.out.println("tables present in H2 but not in MySQL:");
                for (String table : missing) {
                    long rows = count(h2, table);
                    System.out.printf("  %-52s %d rows%s%n",
                            table, rows, rows > 0 ? "   <-- WOULD LOSE DATA" : "");
                }
                System.out.println();
            }

            System.out.printf("%d tables to copy, %d rows total%n", planned.size(), totalRows);
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
                long n = copyTable(h2, my, table);
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

    private static long copyTable(Connection h2, Connection my, String table) throws SQLException {
        List<String> columns = intersectColumns(h2, my, table);
        if (columns.isEmpty()) {
            throw new SQLException("no shared columns for table " + table);
        }

        exec(my, "DELETE FROM `" + table + "`");

        String cols = String.join(", ", columns.stream().map(c -> "`" + c + "`").toList());
        String marks = String.join(", ", Collections.nCopies(columns.size(), "?"));
        String insert = "INSERT INTO `" + table + "` (" + cols + ") VALUES (" + marks + ")";

        String select = "SELECT " + cols + " FROM `" + table + "`";

        long rows = 0;
        try (Statement read = h2.createStatement();
             ResultSet rs = read.executeQuery(select);
             PreparedStatement write = my.prepareStatement(insert)) {
            int pending = 0;
            while (rs.next()) {
                for (int i = 1; i <= columns.size(); i++) {
                    bind(write, i, read(rs, i), table, columns.get(i - 1));
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

    private static void bind(PreparedStatement ps, int index, Object value, String table, String column)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.NULL);
            return;
        }
        try {
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

    /**
     * Columns the two schemas share, in source order. A column on only one side
     * is reported: the target keeps its default, and a source-only column would
     * otherwise be dropped without anyone noticing.
     */
    private static List<String> intersectColumns(Connection h2, Connection my, String table)
            throws SQLException {
        List<String> source = columns(h2, table, """
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'PUBLIC' AND LOWER(table_name) = ?
                ORDER BY ordinal_position""");
        Set<String> target = new HashSet<>(columns(my, table, """
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = DATABASE() AND LOWER(table_name) = ?"""));

        List<String> shared = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        for (String c : source) {
            if (target.contains(c)) {
                shared.add(c);
            } else {
                dropped.add(c);
            }
        }
        if (!dropped.isEmpty()) {
            System.out.printf("    %s: columns absent in MySQL, not copied: %s%n", table, dropped);
        }
        return shared;
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
