package io.kestra.extensions.rbac;
import io.kestra.webserver.rbac.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SafeFlowDependenciesTest {
    private final SafeFlowDependencies resolver = new SafeFlowDependencies();
    private final RbacAuthorizer.Principal principal = new RbacAuthorizer.Principal("bob", List.of("writer"), "client", "aud", List.of());
    private List<RbacAuthorizer.Requirement> resolve(String addition) {
        return resolver.resolve(principal, new McpAuthorizationResolver.Invocation("main", "rbac.allowed", "flow", "execution",
            "id: flow\nnamespace: rbac.allowed\n" + addition)).toCompletableFuture().join();
    }
    @Test void runtimeTenantAndSafeTaskOutput() {
        assertEquals(List.of(), resolve("tenantId: main\noutputs:\n  - id: value\n    value: '{{ outputs.result.value }}'\n"));
        assertEquals(List.of(), resolve("tenantId: null\ninputs: []\n"));
        assertNull(resolve("tenantId: other\n"));
        assertEquals(List.of(), resolve("outputs:\n  - value: literal\n"));
    }
    @Test void extraReadsAndUnresolvedExpressionsDeny() {
        for (String expr : List.of("{{ secret('x') }}", "{{ kv('x') }}", "{{ read('file') }}", "{{ namespace.x }}", "{{ outputs.result.value | default(secret('x')) }}", "prefix {{ outputs.result.value }}", "{% include 'file' %}"))
            assertNull(resolve("outputs:\n  - value: \"" + expr.replace("\"", "\\\"") + "\"\n"), expr);
        assertNull(resolve("inputs:\n  - defaults: \"{{ secret('x') }}\"\n"));
        assertNull(resolve("checks:\n  - condition: \"{{ kv('x') }}\"\n"));
        assertNull(resolve("variables:\n  x: \"{{ read('file') }}\"\n"));
        assertNull(resolve("id: duplicate\n"));
    }
}
