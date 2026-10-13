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
    private static RestAuthorizationResolver.Request request(String method, String path, String handler) {
        return new RestAuthorizationResolver.Request(method, path, Map.of("arbitrary", List.of("value")), null, new byte[0],
            "io.kestra.webserver.controllers.api.AnyController", handler, List.of());
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
    @Test void completeInstancePlanCoversGlobalQueriesAndMutationsWithoutRepositoryReads() {
        var repository = mock(ExecutionRepositoryInterface.class);
        var resolver = new FlowRestAuthorizationResolver(repository, engine);
        var authorizer = new ConfiguredRbacAuthorizer(engine);
        for (var r : List.of(request("GET", "/api/v1/configs", "getConfigs"),
            request("GET", "/api/v1/main/executions/search", "searchExecutions"),
            request("POST", "/api/v1/main/flows/import", "importFlows"),
            request("DELETE", "/api/v1/main/executions", "deleteExecutions"),
            request("PUT", "/api/v1/main/namespaces/prod/kv/key", "putKeyValue"),
            request("GET", "/api/v1/plugins", "getPlugins"),
            request("GET", "/ui/flows", "index"))) {
            var plan = resolver.resolve(principal("user", "admin"), r).toCompletableFuture().join();
            assertNotNull(plan, r.path());
            assertEquals(RestAuthorizationResolver.Coverage.COMPLETE_BATCH, plan.coverage());
            assertEquals(List.of(new Requirement(new Resource(Kind.SYSTEM, Scope.INSTANCE, null, null, null), Action.EXECUTE)), plan.requirements());
            var requirement = plan.requirements().getFirst();
            assertTrue(authorizer.authorize(principal("user", "admin"), requirement.resource(), requirement.action()).toCompletableFuture().join());
            assertNull(resolver.resolve(principal("user", "reader"), r).toCompletableFuture().join());
            assertNull(resolver.resolve(principal("user", "deployment-admin"), r).toCompletableFuture().join());
        }
        assertNull(resolver.resolve(principal("", "admin"), request("GET", "/api/v1/configs", "getConfigs")).toCompletableFuture().join());
        assertNull(resolver.resolve(principal("user", "admin"), request("GET", "/api/v1/configs", "")).toCompletableFuture().join());
        assertNull(new FlowRestAuthorizationResolver(repository).resolve(principal("user", "admin"), request("GET", "/api/v1/configs", "getConfigs")).toCompletableFuture().join());
        verifyNoInteractions(repository);
    }
    @Test void administratorAllowsEveryValidResourceAndActionButRejectsInvalidResources() {
        var authorizer = new ConfiguredRbacAuthorizer(engine);
        for (Kind kind : Kind.values()) for (Action action : Action.values()) {
            var resource = new Resource(kind, Scope.NAMESPACE, "main", "prod.team", "id");
            assertTrue(authorizer.authorize(principal("user", "admin"), resource, action).toCompletableFuture().join());
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
    @Test void dynamicDependenciesAreExplicitlyEmptyOnlyForMappedAdministrator() {
        var resolver = new SafeFlowDependencies(engine);
        var invocation = new McpAuthorizationResolver.Invocation("main", "prod", "flow", "execution",
            "id: flow\nnamespace: prod\nvariables:\n  value: \"{{ kv('key') }}\"\noutputs:\n  - value: \"{{ secret('secret') }}\"\n");
        assertEquals(List.of(), resolver.resolve(principal("user", "admin"), invocation).toCompletableFuture().join());
        assertNull(resolver.resolve(principal("user", "deployment-admin"), invocation).toCompletableFuture().join());
        assertNull(resolver.resolve(principal("user", "reader"), invocation).toCompletableFuture().join());
        assertNull(resolver.resolve(principal("", "admin"), invocation).toCompletableFuture().join());
        assertNull(new SafeFlowDependencies().resolve(principal("user", "admin"), invocation).toCompletableFuture().join());
        for (String source : List.of("not: [valid", "id: wrong\nnamespace: prod\n", "id: flow\nnamespace: prod\ntenantId: other\n", "id: flow\nid: flow\nnamespace: prod\n"))
            assertNull(resolver.resolve(principal("user", "admin"), new McpAuthorizationResolver.Invocation("main", "prod", "flow", "execution", source)).toCompletableFuture().join());
        assertNull(resolver.resolve(principal("user", "admin"), null).toCompletableFuture().join());
    }
}
