package io.kestra.extensions.rbac;

import io.kestra.webserver.rbac.*;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.*;
import java.util.concurrent.*;

/** Conservative input/output dependency parser; workflow task internals are outside this contract. */
@Singleton
@Requires(property = "kestra.rbac.enabled", value = "true")
public final class SafeFlowDependencies implements McpAuthorizationResolver {
    private final PolicyEngine engine;
    public SafeFlowDependencies() { this(null); }
    @jakarta.inject.Inject
    public SafeFlowDependencies(PolicyEngine engine) { this.engine = engine; }
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory()).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    @Override public CompletionStage<List<RbacAuthorizer.Requirement>> resolve(RbacAuthorizer.Principal principal, Invocation invocation) {
        try {
            if (principal == null || principal.subject() == null || principal.subject().isBlank() || invocation == null || !"main".equals(invocation.tenant()) || invocation.flowSource() == null
                || invocation.flowSource().length() > 2_000_000) return CompletableFuture.completedFuture(null);
            JsonNode flow = yaml.readTree(invocation.flowSource());
            if (flow == null || !flow.isObject() || !flow.path("id").asText().equals(invocation.flowId())
                || !flow.path("namespace").asText().equals(invocation.namespace()) || flow.hasNonNull("tenantId") && !flow.path("tenantId").asText().equals(invocation.tenant())) return CompletableFuture.completedFuture(null);
            if (engine != null && engine.isInstanceAdmin(principal)) return CompletableFuture.completedFuture(List.of());
            // Inputs may use only literal defaults/constraints. Outputs may reference a task's output path.
            if (!safe(flow.get("inputs"), false) || !safe(flow.get("outputs"), true) || !safe(flow.get("variables"), false) || !safe(flow.get("checks"), false) || !safe(flow.get("labels"), false))
                return CompletableFuture.completedFuture(null);
            return CompletableFuture.completedFuture(List.of());
        } catch (Exception invalid) { return CompletableFuture.completedFuture(null); }
    }
    private boolean safe(JsonNode node, boolean outputs) {
        if (node == null || node.isNull()) return true;
        if (node.isContainerNode()) {
            for (JsonNode child : node) if (!safe(child, outputs)) return false;
            return true;
        }
        if (!node.isTextual()) return true;
        String value = node.asText();
        if (!value.contains("{{") && !value.contains("{%") && !value.contains("}}") && !value.contains("%}")) return true;
        // No functions, filters, operators, indexing, dynamic names, nesting or interpolation accepted.
        return outputs && value.matches("[ ]*[{][{][ ]*outputs[.][A-Za-z0-9_-]+(?:[.][A-Za-z0-9_-]+)+[ ]*[}][}][ ]*");
    }
}
