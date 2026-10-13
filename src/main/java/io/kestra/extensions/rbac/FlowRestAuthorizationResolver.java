package io.kestra.extensions.rbac;

import io.kestra.webserver.rbac.*;
import static io.kestra.webserver.rbac.RbacAuthorizer.*;
import io.kestra.core.repositories.ExecutionRepositoryInterface;
import io.kestra.core.storages.StorageContext;
import java.net.URI;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.*;
import java.util.concurrent.*;

@Singleton
@Requires(property = "kestra.rbac.enabled", value = "true")
public final class FlowRestAuthorizationResolver implements RestAuthorizationResolver {
    private final ExecutionRepositoryInterface executions;
    private final PolicyEngine engine;
    private final NamespaceUiPlans uiPlans;
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory()).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public FlowRestAuthorizationResolver(ExecutionRepositoryInterface executions) { this(executions, null); }
    @jakarta.inject.Inject
    public FlowRestAuthorizationResolver(ExecutionRepositoryInterface executions, PolicyEngine engine) {
        this.executions = executions;
        this.engine = engine;
        this.uiPlans = engine == null ? null : new NamespaceUiPlans(executions, engine);
    }
    @Override public CompletionStage<Plan> resolve(RbacAuthorizer.Principal principal, Request request) {
        try { return CompletableFuture.completedFuture(plan(principal, request)); }
        catch (Exception failure) { return CompletableFuture.completedFuture(null); }
    }
    private Plan plan(RbacAuthorizer.Principal principal, Request r) throws Exception {
        if (principal == null || principal.subject() == null || principal.subject().isBlank() || r == null) return null;
        if (engine != null && engine.isInstanceAdmin(principal)) {
            if (r.path() == null || !r.path().startsWith("/") || r.controller() == null || r.controller().isBlank()
                || r.handler() == null || r.handler().isBlank() || r.method() == null
                || !Set.of("GET", "HEAD", "OPTIONS", "POST", "PUT", "PATCH", "DELETE").contains(r.method())) return null;
            return new Plan(List.of(new Requirement(new Resource(Kind.SYSTEM, Scope.INSTANCE, null, null, null), Action.EXECUTE)), Coverage.COMPLETE_BATCH);
        }
        String path = r.path();
        // Encoded separators, normalization ambiguity and suffix routes are never guessed.
        if (path == null || path.contains("%") || path.contains("//") || Arrays.stream(path.split("/")).anyMatch(segment -> segment.equals(".") || segment.equals("..")) || !path.startsWith("/api/v1/")) return null;
        if (uiPlans != null) {
            var plan = uiPlans.resolve(principal, r);
            if (plan != null) return plan;
        }
        String[] p = path.substring(8).split("/", -1);
        if (p.length < 2 || !name(p[0])) return null;
        String tenant = p[0];
        if (!tenant.equals("main")) return null;
        if (p[1].equals("flows") && controller(r, "FlowController")) {
            if (p.length == 2 && r.query().keySet().stream().allMatch(k -> Set.of("draft").contains(k)) && r.method().equals("POST") && r.handler().equals("createFlow")) {
                JsonNode body = flowBody(r);
                if (body == null) return null;
                return single(Kind.FLOW, tenant, body.get("namespace").asText(), body.get("id").asText(), Action.CREATE);
            }
            if (r.method().equals("GET") && p.length == 3 && p[2].equals("search") && r.handler().equals("searchFlows")) {
                String key = "filters[namespace][EQUALS]";
                if (!r.query().keySet().stream().allMatch(k -> Set.of(key, "page", "size", "sort").contains(k))) return null;
                var values = r.query().get(key);
                if (values == null || values.size() != 1 || !namespace(values.getFirst())) return null;
                return query(Kind.FLOW, tenant, values.getFirst());
            }
            if (p.length == 3 && r.method().equals("GET") && r.handler().equals("listFlowsByNamespace") && namespace(p[2]))
                return query(Kind.FLOW, tenant, p[2]);
            if (p.length != 4 || !namespace(p[2]) || !flowId(p[3])) return null;
            if (r.method().equals("GET") && r.handler().equals("getFlow")) return single(Kind.FLOW, tenant, p[2], p[3], Action.READ);
            if (r.method().equals("DELETE") && r.handler().equals("deleteFlow")) return single(Kind.FLOW, tenant, p[2], p[3], Action.DELETE);
            if (r.method().equals("PUT") && r.handler().equals("updateFlow") && r.query().keySet().stream().allMatch(k -> Set.of("draft").contains(k))) {
                JsonNode body = flowBody(r);
                if (body == null || !p[2].equals(body.get("namespace").asText()) || !p[3].equals(body.get("id").asText())) return null;
                return single(Kind.FLOW, tenant, p[2], p[3], Action.UPDATE);
            }
        }
        if (p[1].equals("executions") && controller(r, "ExecutionController")) {
            if (p.length == 4 && r.method().equals("POST") && r.handler().equals("createExecution") && namespace(p[2]) && flowId(p[3])) {
                var resource = new Resource(Kind.FLOW, Scope.NAMESPACE, tenant, p[2], p[3]);
                if (!r.query().keySet().stream().allMatch(k -> Set.of("wait").contains(k))) return null;
                boolean outputs = false;
                if (r.query().containsKey("wait")) {
                    var values = r.query().get("wait");
                    if (values.size() != 1 || !Set.of("true", "false").contains(values.getFirst())) return null;
                    outputs = values.getFirst().equals("true");
                }
                // Final runtime/source/id authorization is repeated by the host execution gate before inputs/queue.
                var execution = new Resource(Kind.EXECUTION, Scope.NAMESPACE, tenant, p[2], null);
                var requirements = new ArrayList<>(List.of(new Requirement(resource, Action.READ), new Requirement(resource, Action.EXECUTE),
                    new Requirement(execution, Action.CREATE)));
                if (outputs) requirements.add(new Requirement(execution, Action.ACCESS_OUTPUTS));
                return new Plan(requirements, Coverage.SINGLE);
            }
            boolean read = p.length == 3 && r.method().equals("GET") && r.handler().equals("getExecution");
            boolean output = r.method().equals("GET") && ((p.length == 4 && p[3].equals("file") && r.handler().equals("downloadFileFromExecution"))
                || (p.length == 5 && p[3].equals("file") && p[4].equals("metas") && r.handler().equals("getFileMetadatasFromExecution")));
            if ((!read && !output) || !name(p[2])) return null;
            var execution = executions.findById("main", p[2]).orElse(null);
            if (execution == null || !Objects.equals("main", execution.getTenantId()) || !namespace(execution.getNamespace())) return null;
            if (output && !safeFile(r, execution)) return null;
            var resource = new Resource(Kind.EXECUTION, Scope.NAMESPACE, tenant, execution.getNamespace(), p[2]);
            var requirements = new ArrayList<Requirement>();
            requirements.add(new Requirement(resource, Action.READ));
            requirements.add(new Requirement(resource, Action.ACCESS_OUTPUTS));
            return new Plan(requirements, Coverage.SINGLE);
        }
        if (p[1].equals("mcp-servers") && controller(r, "McpServerController")) {
            Action action = null;
            if (p.length == 2 && r.method().equals("GET") && r.handler().equals("listMcps"))
                return query(Kind.MCP_SERVER, tenant, null);
            if (p.length == 2 && r.method().equals("POST") && r.handler().equals("createMcp")) action = Action.CREATE;
            if (p.length == 3 && r.method().equals("GET") && r.handler().equals("getMcp")) action = Action.READ;
            if (p.length == 3 && r.method().equals("PUT") && r.handler().equals("updateMcp")) action = Action.UPDATE;
            if (action != null) return new Plan(List.of(new Requirement(new Resource(Kind.MCP_SERVER, Scope.TENANT, tenant, null, p.length == 3 ? p[2] : null), action)), Coverage.SINGLE);
        }
        return null;
    }
    private static boolean safeFile(Request request, io.kestra.core.models.executions.Execution execution) {
        if (!request.query().keySet().stream().allMatch(k -> Set.of("path", "format").contains(k))) return false;
        var paths = request.query().get("path");
        if (paths == null || paths.size() != 1) return false;
        String raw = paths.getFirst();
        if (raw.contains("%") || raw.contains("\\")) return false;
        try {
            URI uri = URI.create(raw);
            if (!"kestra".equals(uri.getScheme()) || uri.isOpaque() || uri.getRawAuthority() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) return false;
            String path = uri.getPath();
            if (path == null || path.contains("//") || !path.startsWith("/") || !uri.normalize().equals(uri)) return false;
            for (String segment : path.split("/")) if (segment.equals(".") || segment.equals("..")) return false;
            String prefix = StorageContext.forExecution(execution).getExecutionStorageURI().getPath();
            prefix = prefix.endsWith("/") ? prefix : prefix + "/";
            return path.startsWith(prefix) && path.length() > prefix.length();
        } catch (Exception invalid) { return false; }
    }
    private JsonNode flowBody(Request r) throws Exception {
        if (r.contentType() == null || !r.contentType().split(";", 2)[0].trim().equals("application/x-yaml")
            && !r.contentType().split(";", 2)[0].trim().equals("application/yaml")) return null;
        if (r.body().length == 0 || r.body().length > 2_000_000) return null;
        JsonNode body = yaml.readTree(r.body());
        if (body == null || !body.isObject() || !body.path("namespace").isTextual() || !body.path("id").isTextual()
            || !namespace(body.get("namespace").asText()) || !flowId(body.get("id").asText()) || body.has("tenantId")) return null;
        return body;
    }
    private static Plan query(Kind kind, String tenant, String ns) {
        var resource = new Resource(kind, ns == null ? Scope.TENANT : Scope.NAMESPACE, tenant, ns, null);
        return new Plan(List.of(new Requirement(resource, Action.LIST), new Requirement(resource, Action.READ)), Coverage.COMPLETE_QUERY);
    }
    private static boolean controller(Request r, String simple) {
        return ("io.kestra.webserver.controllers.api." + simple).equals(r.controller());
    }
    private static boolean flowId(String s) { return s != null && s.matches("[a-zA-Z0-9][a-zA-Z0-9._-]*"); }
    private static boolean name(String s) { return s != null && s.matches("[A-Za-z0-9_-]+"); }
    private static boolean namespace(String s) { return s != null && s.matches("[A-Za-z0-9_-]+(?:[.][A-Za-z0-9_-]+)*"); }
    private static Plan single(Kind kind, String tenant, String ns, String id, Action action) {
        return new Plan(List.of(new Requirement(new Resource(kind, Scope.NAMESPACE, tenant, ns, id), action)), Coverage.SINGLE);
    }
}
