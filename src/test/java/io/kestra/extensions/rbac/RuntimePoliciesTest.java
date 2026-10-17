package io.kestra.extensions.rbac;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import io.kestra.extensions.rbac.store.JdbcPolicyStore;
import io.kestra.extensions.rbac.store.PolicyDocumentValidator;
import io.kestra.webserver.rbac.RbacAuthorizer;
import io.kestra.webserver.rbac.TypedAuthorization;
import io.micronaut.context.BeanProvider;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuntimePoliciesTest {
    private static byte[] policy(String namespace) {
        return ("{\"schemaVersion\":1,\"roles\":[{\"id\":\"viewer\",\"name\":\"Viewer\",\"description\":\"\",\"grants\":[{"
            + "\"scope\":\"NAMESPACE\",\"tenant\":\"main\",\"namespace\":\"" + namespace
            + "\",\"descendants\":false,\"resource\":\"FLOW\",\"actions\":[\"READ\"],\"ids\":[],\"allIds\":true}]}],"
            + "\"subjects\":{\"user\":[\"viewer\"]},\"roleMapping\":{}}").getBytes(StandardCharsets.UTF_8);
    }
    private static TypedAuthorization.Operation read(String namespace) {
        return new TypedAuthorization.Operation(1, TypedAuthorization.Source.REST, TypedAuthorization.Resource.FLOW,
            TypedAuthorization.Action.READ, "main", namespace, "flow", TypedAuthorization.QueryDomain.unrestricted(),
            TypedAuthorization.Coverage.SINGLE, TypedAuthorization.Aggregate.NONE, TypedAuthorization.SideEffect.READ_ONLY);
    }
    private static RbacAuthorizer.Principal principal(String subject) {
        return new RbacAuthorizer.Principal(subject, List.of("admin"), null, null, List.of());
    }
    private static BeanProvider<JdbcPolicyStore> provider(JdbcPolicyStore store) {
        return new BeanProvider<>() {
            public JdbcPolicyStore get() { return store; }
            public boolean isUnique() { return store != null; }
            public boolean isPresent() { return store != null; }
        };
    }
    private static RbacConfiguration configuration() {
        var configuration = new RbacConfiguration();
        configuration.setPolicySource("database");
        configuration.setManagementAdminSubjects(List.of("owner"));
        return configuration;
    }
    private static JdbcPolicyStore store() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:runtime-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        var store = new JdbcPolicyStore(source, new PolicyDocumentValidator());
        store.initializeSchema();
        store.importInitial(policy("team.a"), "owner");
        return store;
    }
    @Test void oldSnapshotCannotCombinePermissionsWithNextVersion() {
        var store = store();
        var configuration = configuration();
        var runtime = new RuntimePolicies(configuration, new PolicyEngine(configuration), provider(store), true, true);
        var first = runtime.snapshot();
        store.replace(1, policy("team.b"), "owner");
        var second = runtime.snapshot();
        assertEquals(1, first.revision()); assertEquals(2, second.revision());
        assertTrue(first.authorize(principal("user"), read("team.a")).allowed());
        assertFalse(first.authorize(principal("user"), read("team.b")).allowed());
        assertFalse(second.authorize(principal("user"), read("team.a")).allowed());
        assertTrue(second.authorize(principal("user"), read("team.b")).allowed());
        assertTrue(second.authorize(principal("owner"), read("outside")).allowed());
        assertFalse(runtime.isAdministrator(principal("user")));
        assertEquals(Set.of("owner"), runtime.protectedSubjects());
    }
    @Test void databaseStartupRequiresEvaluationAndRejectsMixedConfiguration() {
        var store = store();
        var configuration = configuration();
        var engine = new PolicyEngine(configuration);
        configuration.setPolicySource("configuration");
        assertThrows(IllegalArgumentException.class, () -> new RuntimePolicies(configuration, engine, provider(store), true, true));
        configuration.setPolicySource("database");
        assertThrows(IllegalArgumentException.class, () -> new RuntimePolicies(configuration, engine, provider(store), false, true));
        assertThrows(IllegalArgumentException.class, () -> new RuntimePolicies(configuration, engine, provider(null), true, true));
        configuration.setRoles(java.util.Map.of("old-role", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new RuntimePolicies(configuration, engine, provider(store), true, true));
        var missing = configuration();
        missing.setManagementAdminSubjects(List.of());
        assertThrows(IllegalArgumentException.class, () -> new RuntimePolicies(missing, engine, provider(store), true, true));
    }
    @Test void publicEvaluationRetainsOnePolicyUntilCloseAndNextEvaluationReadsNewVersion() {
        var store = store();
        var configuration = configuration();
        var runtime = new RuntimePolicies(configuration, new PolicyEngine(configuration), provider(store), true, true);
        var evaluations = new ConfiguredEvaluationProvider(runtime, Runnable::run);
        var first = evaluations.begin(principal("user"), "main").toCompletableFuture().join();
        assertTrue(first.authorize(read("team.a")).toCompletableFuture().join().allowed());
        store.replace(1, policy("team.b"), "owner");
        assertFalse(first.authorize(read("team.b")).toCompletableFuture().join().allowed());
        assertTrue(first.authorize(read("team.a")).toCompletableFuture().join().allowed());
        var second = evaluations.begin(principal("user"), "main").toCompletableFuture().join();
        assertFalse(second.authorize(read("team.a")).toCompletableFuture().join().allowed());
        assertTrue(second.authorize(read("team.b")).toCompletableFuture().join().allowed());
        first.close().toCompletableFuture().join();
        first.close().toCompletableFuture().join();
        assertFalse(first.authorize(read("team.a")).toCompletableFuture().join().allowed());
        second.close().toCompletableFuture().join();
        assertThrows(java.util.concurrent.CompletionException.class, () -> evaluations.begin(null, "main").toCompletableFuture().join());
        assertThrows(java.util.concurrent.CompletionException.class, () -> evaluations.begin(principal("user"), "other").toCompletableFuture().join());
    }
}
