package io.kestra.extensions.rbac.declaration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.webserver.oidc.ApplicationScopeDeclarationsV1;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

public final class ApplicationScopeDeclarationStore {
    private static final TypeReference<List<ApplicationScopeDeclarationsV1.Permission>> PERMISSIONS = new TypeReference<>() {};
    private final DataSource source;
    private final ObjectMapper mapper;

    public record StoredScope(String applicationGroupId, String clientId, String scopeKey,
                              List<ApplicationScopeDeclarationsV1.Permission> permissions, long revision) {
        public StoredScope {
            permissions = List.copyOf(permissions);
        }
    }

    public static final class Conflict extends RuntimeException {
        public Conflict() { super("Application scope declaration revision conflict"); }
    }

    public ApplicationScopeDeclarationStore(DataSource source, ObjectMapper mapper) {
        this.source = io.micronaut.data.connection.jdbc.advice.DelegatingDataSource.unwrapDataSource(Objects.requireNonNull(source));
        this.mapper = Objects.requireNonNull(mapper);
    }

    public void initializeSchema() {
        transaction(connection -> {
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(5);
                statement.execute("CREATE TABLE IF NOT EXISTS rbac_application_scope_declaration (application_group_id VARCHAR(128) NOT NULL, client_id VARCHAR(512) NOT NULL, scope_key VARCHAR(64) NOT NULL, revision BIGINT NOT NULL, permissions_json TEXT NOT NULL, PRIMARY KEY(application_group_id,client_id,scope_key))");
            }
            return null;
        });
    }

    public List<StoredScope> list(String applicationGroupId, String clientId) {
        return transaction(connection -> list(connection, applicationGroupId, clientId));
    }

    public void initialize(String applicationGroupId, String clientId, List<ApplicationScopeDeclarationsV1.Scope> scopes) {
        target(applicationGroupId, clientId);
        if (scopes == null || scopes.isEmpty() || scopes.stream().map(ApplicationScopeDeclarationsV1.Scope::key).distinct().count() != scopes.size())
            throw new IllegalArgumentException("Initial application declarations required");
        transaction(connection -> {
            lock(connection);
            if (!list(connection, applicationGroupId, clientId).isEmpty()) throw new Conflict();
            var permissionKeys = new java.util.HashSet<String>();
            for (var scope : scopes) {
                valid(applicationGroupId, clientId, scope.key(), scope.permissions());
                if (scope.permissions().stream().map(ApplicationScopeDeclarationsV1.Permission::key)
                    .anyMatch(key -> !permissionKeys.add(key))) throw new Conflict();
                try (var statement = prepare(connection, "INSERT INTO rbac_application_scope_declaration(application_group_id,client_id,scope_key,revision,permissions_json) VALUES(?,?,?,1,?)")) {
                    statement.setString(1, applicationGroupId);
                    statement.setString(2, clientId);
                    statement.setString(3, scope.key());
                    statement.setString(4, json(scope.permissions()));
                    statement.executeUpdate();
                }
            }
            return null;
        });
    }

    public List<StoredScope> list(Connection connection, String applicationGroupId, String clientId) throws SQLException {
        target(applicationGroupId, clientId);
        var result = new ArrayList<StoredScope>();
        try (var statement = prepare(connection, "SELECT scope_key,revision,permissions_json FROM rbac_application_scope_declaration WHERE application_group_id=? AND client_id=? ORDER BY scope_key")) {
            statement.setString(1, applicationGroupId);
            statement.setString(2, clientId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    var permissions = parse(rows.getString(3));
                    result.add(new StoredScope(applicationGroupId, clientId, rows.getString(1), permissions, rows.getLong(2)));
                }
            }
        }
        return List.copyOf(result);
    }

    public StoredScope create(String applicationGroupId, String clientId, String scopeKey,
                              List<ApplicationScopeDeclarationsV1.Permission> permissions) {
        valid(applicationGroupId, clientId, scopeKey, permissions);
        return transaction(connection -> {
            lock(connection);
            requireUniquePermissionKeys(connection, applicationGroupId, clientId, scopeKey, permissions);
            try (var statement = prepare(connection, "INSERT INTO rbac_application_scope_declaration(application_group_id,client_id,scope_key,revision,permissions_json) VALUES(?,?,?,1,?)")) {
                statement.setString(1, applicationGroupId);
                statement.setString(2, clientId);
                statement.setString(3, scopeKey);
                statement.setString(4, json(permissions));
                statement.executeUpdate();
            } catch (SQLException conflict) {
                if ("23505".equals(conflict.getSQLState())) throw new Conflict();
                throw conflict;
            }
            return new StoredScope(applicationGroupId, clientId, scopeKey, permissions, 1);
        });
    }

    public StoredScope replace(String applicationGroupId, String clientId, String scopeKey, long expectedRevision,
                               List<ApplicationScopeDeclarationsV1.Permission> permissions) {
        valid(applicationGroupId, clientId, scopeKey, permissions);
        if (expectedRevision < 1) throw new IllegalArgumentException("Positive revision required");
        return transaction(connection -> {
            lock(connection);
            requireUniquePermissionKeys(connection, applicationGroupId, clientId, scopeKey, permissions);
            try (var statement = prepare(connection, "UPDATE rbac_application_scope_declaration SET revision=revision+1,permissions_json=? WHERE application_group_id=? AND client_id=? AND scope_key=? AND revision=?")) {
                statement.setString(1, json(permissions));
                statement.setString(2, applicationGroupId);
                statement.setString(3, clientId);
                statement.setString(4, scopeKey);
                statement.setLong(5, expectedRevision);
                if (statement.executeUpdate() != 1) throw new Conflict();
            }
            return new StoredScope(applicationGroupId, clientId, scopeKey, permissions, Math.addExact(expectedRevision, 1));
        });
    }

    public void delete(String applicationGroupId, String clientId, String scopeKey, long expectedRevision) {
        validScope(scopeKey);
        target(applicationGroupId, clientId);
        if (expectedRevision < 1) throw new IllegalArgumentException("Positive revision required");
        transaction(connection -> {
            lock(connection);
            try (var statement = prepare(connection, "DELETE FROM rbac_application_scope_declaration WHERE application_group_id=? AND client_id=? AND scope_key=? AND revision=?")) {
                statement.setString(1, applicationGroupId);
                statement.setString(2, clientId);
                statement.setString(3, scopeKey);
                statement.setLong(4, expectedRevision);
                if (statement.executeUpdate() != 1) throw new Conflict();
            }
            return null;
        });
    }

    private List<ApplicationScopeDeclarationsV1.Permission> parse(String json) {
        try {
            var permissions = mapper.readValue(json, PERMISSIONS);
            new ApplicationScopeDeclarationsV1.Scope("validation", permissions);
            return List.copyOf(permissions);
        } catch (Exception failure) { throw new IllegalStateException("Invalid application permission declaration", failure); }
    }

    private String json(List<ApplicationScopeDeclarationsV1.Permission> permissions) {
        try { return mapper.writeValueAsString(permissions); }
        catch (Exception failure) { throw new IllegalArgumentException("Invalid application permission declaration", failure); }
    }

    private void requireUniquePermissionKeys(Connection connection, String applicationGroupId, String clientId,
                                             String scopeKey, List<ApplicationScopeDeclarationsV1.Permission> permissions) throws SQLException {
        var candidateKeys = permissions.stream().map(ApplicationScopeDeclarationsV1.Permission::key).collect(java.util.stream.Collectors.toSet());
        for (var existing : list(connection, applicationGroupId, clientId)) {
            if (!existing.scopeKey().equals(scopeKey) && existing.permissions().stream()
                .map(ApplicationScopeDeclarationsV1.Permission::key).anyMatch(candidateKeys::contains)) throw new Conflict();
        }
    }

    private static void valid(String applicationGroupId, String clientId, String scopeKey,
                              List<ApplicationScopeDeclarationsV1.Permission> permissions) {
        target(applicationGroupId, clientId);
        validScope(scopeKey);
        new ApplicationScopeDeclarationsV1.Scope(scopeKey, permissions);
    }

    private static void target(String applicationGroupId, String clientId) {
        if (applicationGroupId == null || !applicationGroupId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
            || clientId == null || clientId.isBlank() || clientId.length() > 512)
            throw new IllegalArgumentException("Invalid application declaration target");
    }

    private static void validScope(String scopeKey) {
        if (scopeKey == null || !scopeKey.matches("[a-z][a-z0-9-]{0,63}"))
            throw new IllegalArgumentException("Invalid scope key");
    }

    private static PreparedStatement prepare(Connection connection, String sql) throws SQLException {
        var statement = connection.prepareStatement(sql);
        statement.setQueryTimeout(5);
        return statement;
    }

    private static void lock(Connection connection) throws SQLException {
        if ("PostgreSQL".equals(connection.getMetaData().getDatabaseProductName()))
            try (var statement = prepare(connection, "SELECT pg_advisory_xact_lock(1380073797)")) { statement.execute(); }
    }

    private interface Work<Value> { Value run(Connection connection) throws SQLException; }

    private <Value> Value transaction(Work<Value> work) {
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                var result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        } catch (SQLException failure) { throw new IllegalStateException("Application declarations unavailable", failure); }
    }
}
