package io.kestra.extensions.rbac;

import io.kestra.webserver.rbac.RbacAuthorizer;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConfiguredRbacAuthorizerTest {
    @Test void subjectBindingAndScopesCannotBecomeRoles() {
        var adapter = new ConfiguredRbacAuthorizer(new PolicyEngine(PolicyEngineTest.configuration()));
        var flow = new RbacAuthorizer.Resource(RbacAuthorizer.Kind.FLOW, RbacAuthorizer.Scope.NAMESPACE, "main", "prod.team", "flow");
        assertTrue(adapter.authorize(new RbacAuthorizer.Principal("alice", List.of(), "client", "aud", List.of()), flow, RbacAuthorizer.Action.READ).toCompletableFuture().join());
        assertFalse(adapter.authorize(new RbacAuthorizer.Principal("bob", List.of(), "trusted-runner", "aud", List.of("trusted-runner")), flow, RbacAuthorizer.Action.EXECUTE).toCompletableFuture().join());
        assertFalse(adapter.authorize(null, flow, RbacAuthorizer.Action.READ).toCompletableFuture().join());
        assertFalse(adapter.authorize(new RbacAuthorizer.Principal("alice", List.of(), "client", "aud", List.of()), null, RbacAuthorizer.Action.READ).toCompletableFuture().join());
    }
    @Test void trustedRolesSamePolicyForBothTransports() {
        var adapter = new ConfiguredRbacAuthorizer(new PolicyEngine(PolicyEngineTest.configuration()));
        var resource = new RbacAuthorizer.Resource(RbacAuthorizer.Kind.FLOW, RbacAuthorizer.Scope.NAMESPACE, "main", "prod.team", "flow");
        var rest = new RbacAuthorizer.Principal("bob", List.of("trusted-runner"), null, null, List.of());
        var mcp = new RbacAuthorizer.Principal("bob", List.of("trusted-runner"), "client", "aud", List.of("mcp:flows:read"));
        assertTrue(adapter.authorize(rest, resource, RbacAuthorizer.Action.EXECUTE).toCompletableFuture().join());
        assertEquals(adapter.authorize(rest, resource, RbacAuthorizer.Action.EXECUTE).toCompletableFuture().join(), adapter.authorize(mcp, resource, RbacAuthorizer.Action.EXECUTE).toCompletableFuture().join());
    }
}
