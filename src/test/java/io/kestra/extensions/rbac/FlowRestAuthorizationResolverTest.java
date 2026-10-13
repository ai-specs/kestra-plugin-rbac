package io.kestra.extensions.rbac;
import io.kestra.webserver.rbac.*;
import io.kestra.core.repositories.ExecutionRepositoryInterface;
import io.kestra.core.models.executions.Execution;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static io.kestra.webserver.rbac.RbacAuthorizer.*;

class FlowRestAuthorizationResolverTest {
    private final RbacAuthorizer.Principal principal = new RbacAuthorizer.Principal("writer", List.of("writer"), "client", "aud", List.of());
    private final ExecutionRepositoryInterface executions = mock(ExecutionRepositoryInterface.class);
    private final FlowRestAuthorizationResolver resolver = new FlowRestAuthorizationResolver(executions);
    private RestAuthorizationResolver.Plan resolve(String method, String path, String controller, String handler, Map<String, List<String>> query, String body) {
        return resolver.resolve(principal, new RestAuthorizationResolver.Request(method, path, query, "application/x-yaml", body.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            "io.kestra.webserver.controllers.api." + controller, handler, List.of())).toCompletableFuture().join();
    }
    @Test void yamlCreateUpdateAndConcreteRead() {
        String body = "id: flow\nnamespace: rbac.allowed\ntasks: []\n";
        var create = resolve("POST", "/api/v1/main/flows", "FlowController", "createFlow", Map.of(), body);
        assertEquals(Action.CREATE, create.requirements().getFirst().action());
        assertEquals("rbac.allowed", create.requirements().getFirst().resource().namespace());
        assertNotNull(resolve("PUT", "/api/v1/main/flows/rbac.allowed/flow", "FlowController", "updateFlow", Map.of(), body));
        assertNull(resolve("PUT", "/api/v1/main/flows/rbac.denied/flow", "FlowController", "updateFlow", Map.of(), body));
        assertNull(resolve("POST", "/api/v1/main/flows", "FlowController", "createFlow", Map.of("force", List.of("true")), body));
        assertNull(resolve("POST", "/api/v1/main/flows", "FlowController", "createFlow", Map.of(), body + "id: duplicate\n"));
        assertNull(resolve("POST", "/api/v1/main/flows", "FlowController", "createFlow", Map.of(), body + "tenantId: main\n"));
        assertNotNull(resolve("GET", "/api/v1/main/flows/rbac.allowed/flow", "FlowController", "getFlow", Map.of("source", List.of("true")), ""));
    }
    @Test void exactQueryDomainRequiresListAndRead() {
        var query = Map.of("filters[namespace][EQUALS]", List.of("rbac.allowed"), "page", List.of("1"));
        var plan = resolve("GET", "/api/v1/main/flows/search", "FlowController", "searchFlows", query, "");
        assertEquals(RestAuthorizationResolver.Coverage.COMPLETE_QUERY, plan.coverage());
        assertEquals(List.of(Action.LIST, Action.READ), plan.requirements().stream().map(Requirement::action).toList());
        assertEquals("rbac.allowed", plan.requirements().getFirst().resource().namespace());
        for (var invalid : List.of(Map.<String,List<String>>of(), Map.of("namespace", List.of("rbac.allowed")),
            Map.of("filters[namespace][CONTAINS]", List.of("rbac")), Map.of("filters[namespace][EQUALS]", List.of("rbac.allowed", "rbac.denied")),
            Map.of("filters[namespace][EQUALS]", List.of("rbac.allowed"), "filters[or][0][namespace][EQUALS]", List.of("rbac.denied"))))
            assertNull(resolve("GET", "/api/v1/main/flows/search", "FlowController", "searchFlows", invalid, ""));
    }
    @Test void filterShapeMatchesRealHostBinder() throws Exception {
        var method = io.kestra.webserver.converters.QueryFilterFormatBinder.class.getDeclaredMethod("getQueryFilters", Map.class);
        method.setAccessible(true);
        var parsed = (List<?>) method.invoke(null, Map.of("filters[namespace][EQUALS]", List.of("rbac.allowed")));
        assertEquals(1, parsed.size());
        var filter = (io.kestra.core.models.QueryFilter) parsed.getFirst();
        assertEquals(io.kestra.core.models.QueryFilter.Field.NAMESPACE, filter.field());
        assertEquals(io.kestra.core.models.QueryFilter.Op.EQUALS, filter.operation());
        assertEquals("rbac.allowed", filter.value());
    }
    @Test void executionLookupsAndOutputsUseOwningNamespace() {
        when(executions.findById("main", "exec")).thenReturn(Optional.of(Execution.builder().id("exec").tenantId("main").namespace("rbac.allowed").flowId("flow").build()));
        var plan = resolve("GET", "/api/v1/main/executions/exec", "ExecutionController", "getExecution", Map.of(), "");
        assertEquals(List.of(Action.READ, Action.ACCESS_OUTPUTS), plan.requirements().stream().map(Requirement::action).toList());
        assertEquals("rbac.allowed", plan.requirements().getFirst().resource().namespace());
        String prefix = io.kestra.core.storages.StorageContext.forExecution(Execution.builder().id("exec").tenantId("main").namespace("rbac.allowed").flowId("flow").build()).getExecutionStorageURI().getPath();
        prefix = prefix.endsWith("/") ? prefix : prefix + "/";
        for (String uri : List.of("kestra:" + prefix + "result.txt", "kestra://" + prefix + "result.txt")) {
            assertNotNull(resolve("GET", "/api/v1/main/executions/exec/file", "ExecutionController", "downloadFileFromExecution", Map.of("path", List.of(uri)), ""));
            assertNotNull(resolve("GET", "/api/v1/main/executions/exec/file/metas", "ExecutionController", "getFileMetadatasFromExecution", Map.of("path", List.of(uri)), ""));
        }
        for (String unsafe : List.of("nsfile:/rbac.allowed/file", "file:/tmp/file", "kestra:" + prefix + "../other/file", "kestra:" + prefix + "%2e%2e/file", "kestra://host" + prefix + "file", "kestra:" + prefix + "file?q=x", "kestra:" + prefix.replace("exec", "otherexec") + "file", "kestra:/state/file"))
            assertNull(resolve("GET", "/api/v1/main/executions/exec/file", "ExecutionController", "downloadFileFromExecution", Map.of("path", List.of(unsafe)), ""), unsafe);
        when(executions.findById("main", "missing")).thenReturn(Optional.empty());
        assertNull(resolve("GET", "/api/v1/main/executions/missing", "ExecutionController", "getExecution", Map.of(), ""));
        assertNull(resolve("GET", "/api/v1/other/executions/exec", "ExecutionController", "getExecution", Map.of(), ""));
        verify(executions, never()).findById("other", "exec");
    }
    @Test void unknownBatchAsyncAndUnresolvedRuntimeDeny() {
        assertNull(resolve("DELETE", "/api/v1/main/flows/delete/by-ids", "FlowController", "deleteFlowsByIds", Map.of(), "[]"));
        var create = resolve("POST", "/api/v1/main/executions/rbac.allowed/flow", "ExecutionController", "createExecution", Map.of(), "");
        assertEquals(List.of(Action.READ, Action.EXECUTE, Action.CREATE), create.requirements().stream().map(Requirement::action).toList());
        for (String unsafe : List.of("revision", "kind", "scheduleDate", "breakpoints"))
            assertNull(resolve("POST", "/api/v1/main/executions/rbac.allowed/flow", "ExecutionController", "createExecution", Map.of(unsafe, List.of("true")), ""));
        assertNull(resolve("GET", "/api/v1/main/flows/rbac.allowed/flow", "FlowController", "wrongHandler", Map.of(), ""));
        assertNull(resolve("GET", "/api/v1/main/flows/rbac%2Eallowed/flow", "FlowController", "getFlow", Map.of(), ""));
        assertNull(resolve("GET", "/api/v1/main/unknown", "UnknownController", "get", Map.of(), ""));
    }
    @Test void mcpServerManagementIsExplicitTenantScope() {
        var get = resolve("GET", "/api/v1/main/mcp-servers/default", "McpServerController", "getMcp", Map.of(), "");
        assertEquals(Kind.MCP_SERVER, get.requirements().getFirst().resource().kind());
        assertEquals(Scope.TENANT, get.requirements().getFirst().resource().scope());
        assertNotNull(resolve("POST", "/api/v1/main/mcp-servers", "McpServerController", "createMcp", Map.of(), "{}"));
        assertNotNull(resolve("PUT", "/api/v1/main/mcp-servers/default", "McpServerController", "updateMcp", Map.of(), "{}"));
        assertNull(resolve("GET", "/api/v1/main/mcp-servers/default/tools", "McpServerController", "listTools", Map.of(), ""));
    }
    @Test void waitTruePlanRequiresOutputsAndRejectsAmbiguousValues() {
        var plan = resolve("POST", "/api/v1/main/executions/rbac.allowed/flow", "ExecutionController", "createExecution", Map.of("wait", List.of("true")), "");
        assertEquals(List.of(Action.READ, Action.EXECUTE, Action.CREATE, Action.ACCESS_OUTPUTS), plan.requirements().stream().map(Requirement::action).toList());
        for (var values : List.of(List.of("true", "false"), List.of("TRUE"), List.of("1")))
            assertNull(resolve("POST", "/api/v1/main/executions/rbac.allowed/flow", "ExecutionController", "createExecution", Map.of("wait", values), ""));
    }
    @Test void dottedFlowIdMatchesBaseAndBothTransportDecisions() {
        String id = "a.b";
        String body = "id: " + id + "\nnamespace: prod.team\ntasks: []\n";
        var config = PolicyEngineTest.configuration();
        config.getRoles().get("runner").getFirst().setIds(List.of(id));
        config.getRoles().get("runner").getFirst().setActions(List.of(Action.READ, Action.CREATE, Action.UPDATE, Action.DELETE, Action.EXECUTE));
        var authorizer = new ConfiguredRbacAuthorizer(new PolicyEngine(config));
        var identity = new RbacAuthorizer.Principal("alice", List.of(), null, null, List.of());
        for (String method : List.of("GET", "PUT", "DELETE", "POST")) {
            String handler = switch (method) { case "GET" -> "getFlow"; case "PUT" -> "updateFlow"; case "DELETE" -> "deleteFlow"; default -> "createFlow"; };
            var plan = resolver.resolve(identity, new RestAuthorizationResolver.Request(method, "/api/v1/main/flows" + (method.equals("POST") ? "" : "/prod.team/" + id), Map.of(), "application/x-yaml", body.getBytes(java.nio.charset.StandardCharsets.UTF_8), "io.kestra.webserver.controllers.api.FlowController", handler, List.of())).toCompletableFuture().join();
            assertNotNull(plan, method);
            var requirement = plan.requirements().getFirst();
            assertEquals(id, requirement.resource().id());
            assertTrue(authorizer.authorize(identity, requirement.resource(), requirement.action()).toCompletableFuture().join());
            var mcp = new RbacAuthorizer.Principal("alice", List.of(), "client", "aud", List.of("mcp:flows:read"));
            assertEquals(authorizer.authorize(identity, requirement.resource(), Action.READ).toCompletableFuture().join(), authorizer.authorize(mcp, requirement.resource(), Action.READ).toCompletableFuture().join());
        }
        assertNull(resolve("GET", "/api/v1/main/flows/prod.team/..", "FlowController", "getFlow", Map.of(), ""));
        assertNull(resolve("GET", "/api/v1/main/flows/prod.team/.bad", "FlowController", "getFlow", Map.of(), ""));
    }
}
