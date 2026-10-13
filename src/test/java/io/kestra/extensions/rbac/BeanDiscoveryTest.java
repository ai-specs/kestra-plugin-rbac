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
        properties.put("kestra.rbac.roles", Map.of("reader", List.of(Map.of("scope", "NAMESPACE", "tenant", "main", "namespace", "prod", "resource", "FLOW", "actions", List.of("READ"), "all-ids", true))));
        properties.put("kestra.rbac.subjects.alice[0]", "reader");
        properties.put("kestra.rbac.role-mapping", Map.of("trusted", List.of("reader")));
        try (var context = ApplicationContext.builder().packages("io.kestra.extensions.rbac").properties(properties).singletons(org.mockito.Mockito.mock(io.kestra.core.repositories.ExecutionRepositoryInterface.class)).start()) {
            var config = context.getBean(RbacConfiguration.class);
            assertEquals(1, config.getRoles().get("reader").size());
            var engine = context.getBean(PolicyEngine.class);
            var req = PolicyEngineTest.request("main", "prod", io.kestra.webserver.rbac.RbacAuthorizer.Action.READ, "flow");
            assertTrue(engine.authorize(new Principal("alice", Set.of()), req));
            assertTrue(engine.authorize(new Principal("bob", Set.of("trusted")), req));
            assertNotNull(context.getBean(RbacAuthorizer.class));
            assertNotNull(context.getBean(io.kestra.webserver.rbac.RestAuthorizationResolver.class));
            assertNotNull(context.getBean(io.kestra.webserver.rbac.McpAuthorizationResolver.class));
        }
    }
    @Test void administratorConfigurationIsInjectedIntoBothResolvers() {
        var properties = Map.<String, Object>of("kestra.rbac.enabled", true,
            "kestra.rbac.roles", Map.of("deployment-admin", List.of()),
            "kestra.rbac.instance-admin-roles", List.of("deployment-admin"),
            "kestra.rbac.role-mapping", Map.of("admin", List.of("deployment-admin")));
        try (var context = ApplicationContext.builder().packages("io.kestra.extensions.rbac").properties(properties)
            .singletons(org.mockito.Mockito.mock(io.kestra.core.repositories.ExecutionRepositoryInterface.class)).start()) {
            assertEquals(List.of("deployment-admin"), context.getBean(RbacConfiguration.class).getInstanceAdminRoles());
            var principal = new RbacAuthorizer.Principal("bootstrap", List.of("admin"), "client", "aud", List.of());
            var request = new io.kestra.webserver.rbac.RestAuthorizationResolver.Request("GET", "/api/v1/configs", Map.of(), null,
                new byte[0], "io.kestra.webserver.controllers.api.ConfigController", "getConfigs", List.of());
            var plan = context.getBean(io.kestra.webserver.rbac.RestAuthorizationResolver.class).resolve(principal, request).toCompletableFuture().join();
            assertNotNull(plan);
            assertTrue(context.getBean(RbacAuthorizer.class).authorize(principal, plan.requirements().getFirst().resource(), plan.requirements().getFirst().action()).toCompletableFuture().join());
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
            assertFalse(context.containsBean(FlowRestAuthorizationResolver.class));
            assertFalse(context.containsBean(SafeFlowDependencies.class));
        }
    }
}
