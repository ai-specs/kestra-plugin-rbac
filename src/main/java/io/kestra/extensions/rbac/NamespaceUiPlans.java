package io.kestra.extensions.rbac;

import io.kestra.core.repositories.ExecutionRepositoryInterface;
import io.kestra.webserver.rbac.*;
import static io.kestra.webserver.rbac.RbacAuthorizer.*;
import static io.kestra.webserver.rbac.RestAuthorizationResolver.*;
import java.util.*;

final class NamespaceUiPlans {
    private final ExecutionRepositoryInterface executions;
    private final PolicyEngine engine;
    NamespaceUiPlans(ExecutionRepositoryInterface executions, PolicyEngine engine) {
        this.executions = executions;
        this.engine = engine;
    }
    Plan resolve(RbacAuthorizer.Principal principal, Request request) {
        String controller = request.controller();
        String handler = request.handler();
        if (controller == null || handler == null || !controller.startsWith("io.kestra.webserver.controllers.api.")) return null;
        String simple = controller.substring(controller.lastIndexOf('.') + 1);
        String[] segments = request.path().substring(8).split("/", -1);
        boolean read = "GET".equals(request.method());
        if (simple.equals("MiscController") && read && Set.of("getConfiguration", "getExpressionFilters", "getExpressionFunctions").contains(handler)
            && Set.of("/api/v1/configs", "/api/v1/pebble/filters", "/api/v1/pebble/functions").contains(request.path())) return metadata();
        if (simple.equals("PluginController") && read && Set.of("getSchemasFromType", "getPropertiesFromType", "getAllInputTypes", "getSchemaFromInputType", "listTriggerPlugins", "getPluginIcons", "getPluginIcon", "getPluginIconSvg", "getPluginGroupIcons", "getPluginDocumentation", "getPluginDocumentationFromVersion", "getPluginVersions", "getPluginBySubgroups", "getPluginUi").contains(handler)
            && request.path().startsWith("/api/v1/plugins/")) return metadata();
        if (simple.equals("PluginController") && read && handler.equals("listPlugins")
            && Set.of("/api/v1/plugins", "/api/v1/plugins/").contains(request.path())) return metadata();
        if (segments.length < 2 || !segments[0].equals("main")) return null;
        var local = new Principal(principal.subject(), Set.copyOf(principal.roles()));
        if (simple.equals("DashboardController") && read && handler.equals("getDefaultDashboardDefinitions")
            && request.path().equals("/api/v1/main/dashboards/defaults/definitions")) return metadata();
        if (simple.equals("FlowController") && segments[1].equals("flows")) {
            if (read && matchesCollection(request, Map.of("searchFlows", "/search", "searchFlowsBySourceCode", "/source", "listDistinctNamespaces", "/distinct-namespaces", "listDeprecated", "/deprecated"), "/api/v1/main/flows"))
                return collection(local, Kind.FLOW);
            if (read && segments.length >= 4 && Set.of("getFlow", "listFlowRevisions", "getTaskFromFlow", "generateFlowGraph", "getFlowDependencies").contains(handler))
                return single(Kind.FLOW, segments[2], segments[3], Action.READ);
            if (read && handler.equals("listFlowsByNamespace") && segments.length == 3) return domain(Kind.FLOW, segments[2]);
            if (request.method().equals("DELETE") && handler.equals("deleteRevisions") && segments.length == 5)
                return single(Kind.FLOW, segments[2], segments[3], Action.DELETE);
        }
        if (simple.equals("NamespaceController") && segments[1].equals("namespaces")) {
            if (read && handler.equals("searchNamespaces") && request.path().equals("/api/v1/main/namespaces/search")
                || request.method().equals("POST") && handler.equals("autocompleteNamespaces") && request.path().equals("/api/v1/main/namespaces/autocomplete"))
                return collection(local, Kind.NAMESPACE);
            if (read && segments.length >= 3 && Set.of("getNamespace", "getFlowDependenciesFromNamespace").contains(handler))
                return single(Kind.NAMESPACE, segments[2], null, Action.READ);
        }
        if (simple.equals("KVController")) {
            if (read && handler.equals("listAllKeys") && request.path().equals("/api/v1/main/kv")) return collection(local, Kind.KVSTORE);
            if (segments.length >= 5 && segments[1].equals("namespaces") && segments[3].equals("kv")) {
                if (read && handler.equals("listKeysWithInheritence")) return domain(Kind.KVSTORE, segments[2]);
                if (read && handler.equals("getKeyValue")) return single(Kind.KVSTORE, segments[2], segments[4], Action.READ);
                if (request.method().equals("PUT") && handler.equals("setKeyValue")) return new Plan(List.of(
                    requirement(Kind.KVSTORE, segments[2], segments[4], Action.CREATE), requirement(Kind.KVSTORE, segments[2], segments[4], Action.UPDATE)), Coverage.SINGLE);
                if (request.method().equals("DELETE") && handler.equals("deleteKeyValue")) return single(Kind.KVSTORE, segments[2], segments[4], Action.DELETE);
            }
        }
        if (simple.equals("NamespaceFileController") && segments.length >= 4 && segments[1].equals("namespaces") && segments[3].equals("files")) {
            String file = canonicalFile(request);
            if (read && Set.of("searchNamespaceFiles", "listNamespaceDirectoryFiles").contains(handler)) return domain(Kind.NAMESPACE_FILE, segments[2]);
            if (file == null) return null;
            if (read && Set.of("getFileContent", "getFileMetadatas", "getFileRevisions").contains(handler)) return single(Kind.NAMESPACE_FILE, segments[2], file, Action.READ);
            if (request.method().equals("POST") && Set.of("createNamespaceFile", "createNamespaceDirectory").contains(handler)) return single(Kind.NAMESPACE_FILE, segments[2], file, Action.CREATE);
            if (request.method().equals("DELETE") && handler.equals("deleteFileDirectory")) return single(Kind.NAMESPACE_FILE, segments[2], file, Action.DELETE);
        }
        if (simple.equals("ExecutionController") && segments[1].equals("executions")) {
            if (read && matchesCollection(request, Map.of("searchExecutions", "/search", "findDistinctFieldValues", "/distinct-field-values", "listExecutableDistinctNamespaces", "/namespaces"), "/api/v1/main/executions")) return collection(local, Kind.EXECUTION);
            if (read && segments.length >= 4 && segments[2].equals("namespaces") && Set.of("listFlowExecutionsByNamespace", "getExecutionAverageDuration").contains(handler)) return domain(Kind.EXECUTION, segments[3]);
            if (request.method().equals("POST") && handler.equals("validateNewExecutionInputs") && segments.length == 5)
                return new Plan(List.of(requirement(Kind.FLOW, segments[2], segments[3], Action.READ), requirement(Kind.FLOW, segments[2], segments[3], Action.EXECUTE)), Coverage.SINGLE);
            if (segments.length >= 3 && read && Set.of("getExecution", "getExecutionFlowGraph", "getFlowFromExecutionById", "followExecution", "followDependenciesExecutions").contains(handler))
                return execution(segments[2], Kind.EXECUTION, Action.READ, Action.ACCESS_OUTPUTS);
            if (segments.length >= 3 && request.method().equals("POST") && Set.of("restartExecution", "replayExecution", "replayExecutionWithinputs", "resumeExecution", "resumeExecutionFromBreakpoint", "forceRunExecution", "unqueueExecution").contains(handler))
                return execution(segments[2], Kind.EXECUTION, Action.EXECUTE);
            if (segments.length >= 3 && request.method().equals("POST") && Set.of("updateTaskRunState", "updateExecutionStatus", "pauseExecution", "setLabelsOnTerminatedExecution").contains(handler))
                return execution(segments[2], Kind.EXECUTION, Action.UPDATE);
            if (segments.length >= 3 && request.method().equals("DELETE") && handler.equals("killExecution")) return execution(segments[2], Kind.EXECUTION, Action.UPDATE);
            if (segments.length == 3 && request.method().equals("DELETE") && handler.equals("deleteExecution")) return execution(segments[2], Kind.EXECUTION, Action.DELETE);
        }
        if (simple.equals("LogController") && segments[1].equals("logs")) {
            if (read && handler.equals("searchLogs")) return collection(local, Kind.LOG);
            if (read && segments.length >= 3 && Set.of("listLogsFromExecution", "downloadLogsFromExecution", "followLogsFromExecution").contains(handler)) return execution(segments[2], Kind.LOG, Action.READ);
            if (request.method().equals("DELETE") && handler.equals("deleteLogsFromExecution") && segments.length == 3) return execution(segments[2], Kind.LOG, Action.DELETE);
            if (request.method().equals("DELETE") && handler.equals("deleteLogsFromFlow") && segments.length == 4) return single(Kind.LOG, segments[2], segments[3], Action.DELETE);
        }
        if (simple.equals("MetricController") && read && segments[1].equals("metrics")) {
            if (handler.equals("searchByExecution") && segments.length == 3) return execution(segments[2], Kind.METRIC, Action.READ);
            if (segments.length >= 5 && Set.of("listFlowMetrics", "listTaskMetrics", "listTasksWithMetrics", "aggregateMetricsFromFlow", "aggregateMetricsFromTask").contains(handler))
                return single(Kind.METRIC, segments[3], segments[4], Action.READ);
        }
        if (simple.equals("TriggerController") && segments[1].equals("triggers")) {
            if (read && handler.equals("searchTriggers")) return collection(local, Kind.TRIGGER);
            if (read && handler.equals("searchTriggersForFlow") && segments.length == 4) return domain(Kind.TRIGGER, segments[2]);
            if (request.method().equals("POST") && Set.of("unlockTrigger", "restartTrigger").contains(handler) && segments.length == 6) return single(Kind.TRIGGER, segments[2], segments[4], Action.UPDATE);
            if (request.method().equals("DELETE") && handler.equals("deleteTrigger") && segments.length == 5) return single(Kind.TRIGGER, segments[2], segments[4], Action.DELETE);
        }
        return null;
    }
    private Plan collection(Principal principal, Kind kind) {
        if (engine.namespaceScope(principal, kind).kind() == io.kestra.core.models.AccessScope.Kind.DENY_ALL) return null;
        return new Plan(List.of(new Requirement(new Resource(kind, Scope.TENANT, "main", null, null), Action.LIST),
            new Requirement(new Resource(kind, Scope.TENANT, "main", null, null), Action.READ)), Coverage.COMPLETE_QUERY);
    }
    private static boolean matchesCollection(Request request, Map<String, String> routes, String base) {
        String suffix = routes.get(request.handler());
        return suffix != null && request.path().equals(base + suffix);
    }
    private Plan execution(String id, Kind kind, Action... actions) {
        var execution = executions.findByIdWithoutAcl("main", id).orElse(null);
        if (execution == null || !"main".equals(execution.getTenantId())) return null;
        return new Plan(Arrays.stream(actions).map(action -> requirement(kind, execution.getNamespace(), id, action)).toList(), Coverage.SINGLE);
    }
    private static Plan metadata() {
        return new Plan(List.of(new Requirement(new Resource(Kind.SYSTEM, Scope.TENANT, "main", null, "public-metadata"), Action.READ)), Coverage.SINGLE);
    }
    private static Requirement requirement(Kind kind, String namespace, String id, Action action) {
        return new Requirement(new Resource(kind, Scope.NAMESPACE, "main", namespace, id), action);
    }
    private static Plan single(Kind kind, String namespace, String id, Action action) {
        return new Plan(List.of(requirement(kind, namespace, id, action)), Coverage.SINGLE);
    }
    private static Plan domain(Kind kind, String namespace) {
        return new Plan(List.of(requirement(kind, namespace, null, Action.LIST), requirement(kind, namespace, null, Action.READ)), Coverage.COMPLETE_QUERY);
    }
    private static String canonicalFile(Request request) {
        var paths = request.query().get("path");
        if (paths == null || paths.size() != 1) return null;
        String path = paths.getFirst();
        if (!path.startsWith("/") || path.contains("%") || path.contains("\\") || path.contains("//")
            || Arrays.stream(path.split("/")).anyMatch(part -> part.equals(".") || part.equals(".."))) return null;
        return path;
    }
}
