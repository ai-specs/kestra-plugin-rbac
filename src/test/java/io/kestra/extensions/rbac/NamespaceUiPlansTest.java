package io.kestra.extensions.rbac;

import io.kestra.core.models.executions.Execution;
import io.kestra.core.repositories.ExecutionRepositoryInterface;
import io.kestra.webserver.rbac.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static io.kestra.webserver.rbac.RbacAuthorizer.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NamespaceUiPlansTest {
    private static RbacConfiguration.Grant grant(String namespace, boolean descendants, Kind kind) {
        var grant = new RbacConfiguration.Grant();
        grant.setScope(Scope.NAMESPACE);
        grant.setTenant("main");
        grant.setNamespace(namespace);
        grant.setDescendants(descendants);
        grant.setResource(kind);
        grant.setActions(List.of(Action.READ, Action.LIST, Action.ACCESS_OUTPUTS));
        grant.setAllIds(true);
        return grant;
    }
    private static PolicyEngine engine() {
        var config = new RbacConfiguration();
        var roles = new HashMap<String, List<RbacConfiguration.Grant>>();
        for (String role : List.of("reader", "other-reader", "exact-reader")) {
            String namespace = role.equals("other-reader") ? "rbac.other" : "rbac.allowed";
            roles.put(role, Arrays.stream(new Kind[]{Kind.FLOW, Kind.EXECUTION, Kind.NAMESPACE})
                .map(kind -> grant(namespace, !role.equals("exact-reader"), kind)).toList());
        }
        config.setRoles(roles);
        config.setRoleMapping(Map.of("reader", List.of("reader"), "other-reader", List.of("other-reader"), "exact-reader", List.of("exact-reader")));
        return new PolicyEngine(config);
    }
    private static RbacAuthorizer.Principal principal(String... roles) {
        return new RbacAuthorizer.Principal("user", List.of(roles), null, null, List.of());
    }
    private static RestAuthorizationResolver.Request request(String path, String controller, String handler) {
        return new RestAuthorizationResolver.Request("GET", path, Map.of(), null, new byte[0],
            "io.kestra.webserver.controllers.api." + controller, handler, List.of());
    }
    private static boolean allowed(PolicyEngine engine, RbacAuthorizer.Principal principal, RestAuthorizationResolver.Plan plan) {
        if (plan == null) return false;
        var authorizer = new ConfiguredRbacAuthorizer(engine);
        return plan.requirements().stream().allMatch(requirement -> authorizer.authorize(principal, requirement.resource(), requirement.action()).toCompletableFuture().join());
    }
    @Test void unfilteredCollectionsUseScopedCapabilityAndUnknownRoutesStillDeny() {
        var engine = engine();
        var repository = mock(ExecutionRepositoryInterface.class);
        var resolver = new FlowRestAuthorizationResolver(repository, engine);
        for (var request : List.of(request("/api/v1/main/flows/search", "FlowController", "searchFlows"),
            request("/api/v1/main/executions/search", "ExecutionController", "searchExecutions"),
            request("/api/v1/main/namespaces/search", "NamespaceController", "searchNamespaces"))) {
            var plan = resolver.resolve(principal("reader", "other-reader"), request).toCompletableFuture().join();
            assertEquals(RestAuthorizationResolver.Coverage.COMPLETE_QUERY, plan.coverage());
            assertTrue(allowed(engine, principal("reader", "other-reader"), plan));
            assertFalse(allowed(engine, principal("unmapped"), plan));
        }
        assertNull(resolver.resolve(principal("reader"), request("/api/v1/main/unknown", "UnknownController", "unknown")).toCompletableFuture().join());
        assertNull(resolver.resolve(principal("reader"), request("/api/v1/main/flows/search", "FlowController", "unknown")).toCompletableFuture().join());
        verifyNoInteractions(repository);
    }
    @Test void flowAndExecutionDetailsRespectUnionExactAndOwningNamespace() {
        var engine = engine();
        var repository = mock(ExecutionRepositoryInterface.class);
        var resolver = new FlowRestAuthorizationResolver(repository, engine);
        for (String namespace : List.of("rbac.allowed", "rbac.allowed.child", "rbac.other", "rbac.denied", "rbac.allowed2")) {
            when(repository.findByIdWithoutAcl("main", namespace)).thenReturn(Optional.of(Execution.builder().id(namespace).tenantId("main").namespace(namespace).flowId("flow").build()));
            for (var request : List.of(request("/api/v1/main/flows/" + namespace + "/flow", "FlowController", "getFlow"),
                request("/api/v1/main/executions/" + namespace, "ExecutionController", "getExecution"))) {
                var plan = resolver.resolve(principal("reader", "other-reader"), request).toCompletableFuture().join();
                assertEquals(Set.of("rbac.allowed", "rbac.allowed.child", "rbac.other").contains(namespace), allowed(engine, principal("reader", "other-reader"), plan), namespace);
                assertEquals(namespace.equals("rbac.allowed"), allowed(engine, principal("exact-reader"), plan), namespace);
            }
        }
        when(repository.findByIdWithoutAcl("main", "missing")).thenReturn(Optional.empty());
        assertNull(resolver.resolve(principal("reader"), request("/api/v1/main/executions/missing", "ExecutionController", "getExecution")).toCompletableFuture().join());
    }
    @Test void defaultMetadataDoesNotAllowArbitraryChartPreview() {
        var resolver = new FlowRestAuthorizationResolver(mock(ExecutionRepositoryInterface.class), engine());
        for (var request : List.of(request("/api/v1/configs", "MiscController", "getConfiguration"),
            request("/api/v1/plugins/inputs", "PluginController", "getAllInputTypes"),
            request("/api/v1/main/dashboards/defaults/definitions", "DashboardController", "getDefaultDashboardDefinitions")))
            assertTrue(allowed(engine(), principal("reader"), resolver.resolve(principal("reader"), request).toCompletableFuture().join()));
        assertNull(resolver.resolve(principal("reader"), new RestAuthorizationResolver.Request("POST", "/api/v1/main/dashboards/charts/preview", Map.of(), "application/json", "{}".getBytes(),
            "io.kestra.webserver.controllers.api.DashboardController", "previewChart", List.of())).toCompletableFuture().join());
    }
}
