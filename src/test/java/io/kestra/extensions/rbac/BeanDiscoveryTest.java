package io.kestra.extensions.rbac;

import io.micronaut.context.ApplicationContext;
import io.kestra.webserver.rbac.RbacAuthorizer;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BeanDiscoveryTest {
    @Test void actualConfigurationBindingAndBeanDiscovery() {
        var properties = new HashMap<String, Object>();
        properties.put("kestra.rbac.enabled", true);
        properties.put("kestra.rbac.typed.enabled", true);
        properties.put("kestra.rbac.evaluation.enabled", true);
        properties.put("kestra.rbac.policy-source", "database");
        properties.put("kestra.rbac.management-admin-subjects", List.of("owner"));
        properties.put("kestra.rbac.roles", Map.of("reader", List.of(Map.of("scope", "NAMESPACE", "tenant", "main", "namespace", "prod", "resource", "FLOW", "actions", List.of("READ"), "all-ids", true))));
        properties.put("kestra.rbac.subjects.alice[0]", "reader");
        properties.put("kestra.rbac.role-mapping", Map.of("trusted", List.of("reader")));
        var source = new org.h2.jdbcx.JdbcDataSource();
        source.setURL("jdbc:h2:mem:bean-reader;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        var store = new io.kestra.extensions.rbac.store.JdbcPolicyStore(source, new io.kestra.extensions.rbac.store.PolicyDocumentValidator());
        store.initializeSchema();
        store.importInitial("{\"schemaVersion\":1,\"roles\":[],\"subjects\":{},\"roleMapping\":{}}".getBytes(java.nio.charset.StandardCharsets.UTF_8), "owner");
        properties.remove("kestra.rbac.roles"); properties.remove("kestra.rbac.subjects.alice[0]"); properties.remove("kestra.rbac.role-mapping");
        try (var context = ApplicationContext.builder().packages("io.kestra.extensions.rbac").properties(properties).singletons(store, org.mockito.Mockito.mock(io.kestra.core.repositories.ExecutionRepositoryInterface.class)).start()) {
            var config = context.getBean(RbacConfiguration.class);
            assertEquals("database", config.getPolicySource());
            var engine = context.getBean(PolicyEngine.class);
            var req = PolicyEngineTest.request("main", "prod", io.kestra.webserver.rbac.RbacAuthorizer.Action.READ, "flow");
            assertFalse(engine.authorize(new Principal("alice", Set.of()), req));
            assertFalse(engine.authorize(new Principal("bob", Set.of("trusted")), req));
            assertNotNull(context.getBean(RbacAuthorizer.class));
            assertNotNull(context.getBean(io.kestra.webserver.rbac.TypedAuthorizationResolver.class));
            assertFalse(context.containsBean(io.kestra.webserver.rbac.RestAuthorizationResolver.class));
            assertNotNull(context.getBean(io.kestra.webserver.rbac.McpAuthorizationResolver.class));
        }
    }
    @Test void administratorConfigurationIsInjectedIntoBothResolvers() {
        var properties = Map.<String, Object>of("kestra.rbac.enabled", true,
            "kestra.rbac.typed.enabled", true, "kestra.rbac.evaluation.enabled", true,
            "kestra.rbac.policy-source", "database", "kestra.rbac.management-admin-subjects", List.of("bootstrap"));
        var source = new org.h2.jdbcx.JdbcDataSource();
        source.setURL("jdbc:h2:mem:bean-admin;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        var store = new io.kestra.extensions.rbac.store.JdbcPolicyStore(source, new io.kestra.extensions.rbac.store.PolicyDocumentValidator());
        store.initializeSchema();
        store.importInitial("{\"schemaVersion\":1,\"roles\":[],\"subjects\":{},\"roleMapping\":{}}".getBytes(java.nio.charset.StandardCharsets.UTF_8), "bootstrap");
        try (var context = ApplicationContext.builder().packages("io.kestra.extensions.rbac").properties(properties)
            .singletons(store, org.mockito.Mockito.mock(io.kestra.core.repositories.ExecutionRepositoryInterface.class)).start()) {
            assertEquals(List.of("bootstrap"), context.getBean(RbacConfiguration.class).getManagementAdminSubjects());
            var principal = new RbacAuthorizer.Principal("bootstrap", List.of("admin"), "client", "aud", List.of());
            var operation = new io.kestra.webserver.rbac.TypedAuthorization.Operation(1,
                io.kestra.webserver.rbac.TypedAuthorization.Source.REST, io.kestra.webserver.rbac.TypedAuthorization.Resource.SYSTEM,
                io.kestra.webserver.rbac.TypedAuthorization.Action.EXECUTE, "main", null, null,
                io.kestra.webserver.rbac.TypedAuthorization.QueryDomain.unrestricted(),
                io.kestra.webserver.rbac.TypedAuthorization.Coverage.COMPLETE_BATCH,
                io.kestra.webserver.rbac.TypedAuthorization.Aggregate.NONE, io.kestra.webserver.rbac.TypedAuthorization.SideEffect.EXTERNAL);
            assertTrue(context.getBean(io.kestra.webserver.rbac.TypedAuthorizationResolver.class).authorize(principal, operation).toCompletableFuture().join().allowed());
            var invocation = new io.kestra.webserver.rbac.McpAuthorizationResolver.Invocation("main", "prod", "flow", "execution",
                "id: flow\nnamespace: prod\nvariables:\n  value: \"{{ kv('key') }}\"\n");
            assertEquals(List.of(), context.getBean(io.kestra.webserver.rbac.McpAuthorizationResolver.class).resolve(principal, invocation).toCompletableFuture().join());
        }
    }
    @Test void undefinedAdministratorRoleFailsContextStartup() {
        var context = ApplicationContext.builder().packages("io.kestra.extensions.rbac").properties(Map.of(
            "kestra.rbac.enabled", true, "kestra.rbac.instance-admin-roles", List.of("missing"))).build();
        try {
            var failure = assertThrows(io.micronaut.context.exceptions.BeanInstantiationException.class, context::start);
            assertTrue(failure.getMessage().contains("Unknown instance-admin role"));
        } finally { context.close(); }
    }
    @Test void disabledDoesNotRegisterAuthorizer() {
        try (var context = ApplicationContext.builder().packages("io.kestra.extensions.rbac").properties(Map.of("kestra.rbac.enabled", false)).start()) {
            assertFalse(context.containsBean(ConfiguredRbacAuthorizer.class));
            assertFalse(context.containsBean(PolicyEngine.class));
            assertFalse(context.containsBean(SafeFlowDependencies.class));
            assertFalse(context.containsBean(ConfiguredTypedAuthorization.class));
        }
    }
}
