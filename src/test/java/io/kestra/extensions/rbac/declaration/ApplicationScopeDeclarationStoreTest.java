package io.kestra.extensions.rbac.declaration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.webserver.oidc.ApplicationScopeDeclarationsV1;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ApplicationScopeDeclarationStoreTest {
    private final DataSource source = source();
    private final ApplicationScopeDeclarationStore store = new ApplicationScopeDeclarationStore(source, new ObjectMapper());

    private static DataSource source() {
        String postgres = System.getenv("RBAC_STORE_TEST_JDBC");
        if (postgres != null && !postgres.isBlank()) {
            var source = new org.postgresql.ds.PGSimpleDataSource();
            source.setURL(postgres);
            source.setUser("postgres");
            source.setPassword("policy-test-only");
            String schema = "declaration_" + UUID.randomUUID().toString().replace("-", "");
            try (var connection = source.getConnection(); var statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA " + schema);
            } catch (java.sql.SQLException failure) { throw new IllegalStateException("Cannot prepare declaration schema", failure); }
            source.setCurrentSchema(schema);
            return source;
        }
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:application-declarations-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        return source;
    }

    private static List<ApplicationScopeDeclarationsV1.Permission> permissions(String name) {
        return List.of(new ApplicationScopeDeclarationsV1.Permission("view-flows", name));
    }

    @Test
    void declarationChangesDoNotGrantAnotherApplicationAndUseRevisionCas() throws Exception {
        store.initializeSchema();
        store.initializeSchema();
        var created = store.create("group", "kestra-ui", "reader", permissions("View flows"));
        assertEquals(1, created.revision());
        assertThrows(ApplicationScopeDeclarationStore.Conflict.class,
            () -> store.create("group", "kestra-ui", "reader", permissions("Duplicate")));
        assertThrows(ApplicationScopeDeclarationStore.Conflict.class,
            () -> store.create("group", "kestra-ui", "writer", permissions("Same key in another scope")));
        assertTrue(store.list("group", "other-app").isEmpty());
        assertTrue(store.list("another-group", "kestra-ui").isEmpty());
        var provider = new ApplicationScopeDeclarationProvider(store);
        assertEquals(List.of("reader"), provider.current("group", "kestra-ui").supportedScopeKeys().stream().toList());
        try (var connection = source.getConnection()) {
            assertEquals("View flows", provider.current(connection, "group", "kestra-ui")
                .scopes().getFirst().permissions().getFirst().name());
        }
        var renamed = store.replace("group", "kestra-ui", "reader", 1, permissions("Read flows"));
        assertEquals(2, renamed.revision());
        assertThrows(ApplicationScopeDeclarationStore.Conflict.class,
            () -> store.replace("group", "kestra-ui", "reader", 1, permissions("Stale")));
        assertEquals("Read flows", store.list("group", "kestra-ui").getFirst().permissions().getFirst().name());
        assertThrows(ApplicationScopeDeclarationStore.Conflict.class,
            () -> store.delete("group", "kestra-ui", "reader", 1));
        assertEquals(1, store.list("group", "kestra-ui").size());
        store.delete("group", "kestra-ui", "reader", 2);
        assertTrue(provider.current("group", "kestra-ui").supportedScopeKeys().isEmpty());
    }

    @Test
    void rejectsInvalidKeysAndDuplicatePermissionDeclarations() {
        store.initializeSchema();
        assertThrows(IllegalArgumentException.class,
            () -> store.create("group", "client", "Bad Scope", permissions("View flows")));
        assertThrows(IllegalArgumentException.class,
            () -> store.create("group", "client", "reader", List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> store.create("group", "client", "reader", List.of(
                new ApplicationScopeDeclarationsV1.Permission("view", "View"),
                new ApplicationScopeDeclarationsV1.Permission("view", "Duplicate"))));
        assertTrue(store.list("group", "client").isEmpty());
    }
}
