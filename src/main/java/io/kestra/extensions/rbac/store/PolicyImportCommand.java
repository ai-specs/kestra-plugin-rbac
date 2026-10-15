package io.kestra.extensions.rbac.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import javax.sql.DataSource;

public final class PolicyImportCommand {
    private PolicyImportCommand() {}
    private static String environment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Required environment: " + name);
        return value;
    }
    public static void main(String[] arguments) {
        try { execute(arguments); }
        catch (Exception failure) {
            System.err.println("Policy command failed: " + failure.getClass().getSimpleName());
            System.exit(1);
        }
    }
    private static void execute(String[] arguments) throws Exception {
        if (arguments.length != 2 || !(arguments[0].equals("validate") || arguments[0].equals("initialize"))) {
            throw new IllegalArgumentException("Usage: PolicyImportCommand validate|initialize <policy.json>");
        }
        Path file = Path.of(arguments[1]);
        if (Files.size(file) > PolicyDocumentValidator.MAX_BYTES) throw new IllegalArgumentException("Policy exceeds 1MiB");
        byte[] document;
        try (var input = Files.newInputStream(file)) { document = input.readNBytes(PolicyDocumentValidator.MAX_BYTES + 1); }
        var validator = new PolicyDocumentValidator();
        var validated = validator.parse(document);
        if (arguments[0].equals("validate")) {
            System.out.println("Valid policy schemaVersion=1 roles=" + validated.document().roles().size());
            return;
        }
        String url = environment("RBAC_POLICY_JDBC_URL");
        String username = environment("RBAC_POLICY_JDBC_USER");
        String password = environment("RBAC_POLICY_JDBC_PASSWORD");
        String actor = environment("RBAC_POLICY_OPERATOR_SUBJECT");
        DataSource source = new DataSource() {
            public Connection getConnection() throws SQLException { return DriverManager.getConnection(url, username, password); }
            public Connection getConnection(String user, String secret) throws SQLException { return getConnection(); }
            public java.io.PrintWriter getLogWriter() { return null; }
            public void setLogWriter(java.io.PrintWriter writer) {}
            public int getLoginTimeout() { return DriverManager.getLoginTimeout(); }
            public void setLoginTimeout(int seconds) { DriverManager.setLoginTimeout(seconds); }
            public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }
            public <Target> Target unwrap(Class<Target> type) throws SQLException { throw new SQLException("unsupported"); }
            public boolean isWrapperFor(Class<?> type) { return false; }
        };
        var store = new JdbcPolicyStore(source, validator);
        store.initializeSchema();
        var snapshot = store.importInitial(document, actor);
        System.out.println("Policy initialized revision=" + snapshot.revision() + " schemaVersion=1");
    }
}
