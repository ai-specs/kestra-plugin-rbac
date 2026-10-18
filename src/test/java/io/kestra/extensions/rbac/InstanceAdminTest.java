package io.kestra.extensions.rbac;

import io.kestra.webserver.rbac.*;
import io.kestra.core.repositories.ExecutionRepositoryInterface;
import java.util.*;
import org.junit.jupiter.api.Test;
import static io.kestra.webserver.rbac.RbacAuthorizer.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InstanceAdminTest {
    static RbacConfiguration config() {
        var config = new RbacConfiguration();
        config.setRoles(Map.of("deployment-admin", List.of(), "reader", List.of()));
        config.setInstanceAdminRoles(List.of("deployment-admin"));
        config.setSubjects(Map.of("bootstrap-subject", List.of("deployment-admin")));
        config.setRoleMapping(Map.of("admin", List.of("deployment-admin")));
        return config;
    }
    private final PolicyEngine engine = new PolicyEngine(config());
    private static RbacAuthorizer.Principal principal(String subject, String... roles) {
        return new RbacAuthorizer.Principal(subject, List.of(roles), "client", "aud", List.of());
    }
    @Test void onlyExplicitMappedLocalRolesUpgrade() {
        assertTrue(engine.isInstanceAdmin(principal("bootstrap-subject")));
        assertTrue(engine.isInstanceAdmin(principal("user", "admin")));
        assertFalse(engine.isInstanceAdmin(principal("user", "deployment-admin")));
        assertFalse(engine.isInstanceAdmin(principal("user", "reader")));
        assertFalse(engine.isInstanceAdmin(principal("", "admin")));
        assertFalse(engine.isInstanceAdmin(principal(" ", "admin")));
        assertFalse(engine.isInstanceAdmin((RbacAuthorizer.Principal) null));
        var defaultConfig = config();
        defaultConfig.setInstanceAdminRoles(List.of());
        assertFalse(new PolicyEngine(defaultConfig).isInstanceAdmin(principal("bootstrap-subject", "admin")));
        var invalid = config();
        invalid.setInstanceAdminRoles(List.of("missing"));
        assertThrows(IllegalArgumentException.class, () -> new PolicyEngine(invalid));
    }
    @Test void legacyAdministratorNeverAuthorizesRuntimeResources() {
        var authorizer = new ConfiguredRbacAuthorizer();
        for (Kind kind : Kind.values()) for (Action action : Action.values()) {
            var resource = new Resource(kind, Scope.NAMESPACE, "main", "prod.team", "id");
            assertFalse(authorizer.authorize(principal("user", "admin"), resource, action).toCompletableFuture().join());
            assertFalse(authorizer.authorize(principal("user", "deployment-admin"), resource, action).toCompletableFuture().join());
        }
        for (var resource : List.of(new Resource(Kind.SYSTEM, Scope.INSTANCE, "main", null, null),
            new Resource(Kind.FLOW, Scope.INSTANCE, null, null, null),
            new Resource(Kind.SYSTEM, Scope.INSTANCE, null, null, "id"),
            new Resource(Kind.FLOW, Scope.NAMESPACE, "other", "prod", "id"),
            new Resource(Kind.FLOW, Scope.NAMESPACE, "main", "", "id"),
            new Resource(Kind.FLOW, Scope.TENANT, "main", null, "id")))
            assertFalse(authorizer.authorize(principal("user", "admin"), resource, Action.READ).toCompletableFuture().join());
        assertFalse(authorizer.authorize(principal("", "admin"), new Resource(Kind.SYSTEM, Scope.INSTANCE, null, null, null), Action.EXECUTE).toCompletableFuture().join());
    }
    @Test void legacyAdministratorCannotBypassSafeDependencies() {
        var resolver = new SafeFlowDependencies();
        var invocation = new McpAuthorizationResolver.Invocation("main", "prod", "flow", "execution",
            "id: flow\nnamespace: prod\nvariables:\n  value: \"{{ kv('key') }}\"\noutputs:\n  - value: \"{{ secret('secret') }}\"\n");
        assertNull(resolver.resolve(principal("user", "admin"), invocation).toCompletableFuture().join());
        assertNull(resolver.resolve(principal("user", "deployment-admin"), invocation).toCompletableFuture().join());
        assertNull(resolver.resolve(principal("user", "reader"), invocation).toCompletableFuture().join());
        assertNull(resolver.resolve(principal("", "admin"), invocation).toCompletableFuture().join());
        assertNull(new SafeFlowDependencies().resolve(principal("user", "admin"), invocation).toCompletableFuture().join());
        for (String source : List.of("not: [valid", "id: wrong\nnamespace: prod\n", "id: flow\nnamespace: prod\ntenantId: other\n", "id: flow\nid: flow\nnamespace: prod\n"))
            assertNull(resolver.resolve(principal("user", "admin"), new McpAuthorizationResolver.Invocation("main", "prod", "flow", "execution", source)).toCompletableFuture().join());
        assertNull(resolver.resolve(principal("user", "admin"), null).toCompletableFuture().join());
    }
}
