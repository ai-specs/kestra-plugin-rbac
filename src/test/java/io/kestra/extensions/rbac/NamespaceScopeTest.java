package io.kestra.extensions.rbac;

import io.kestra.core.models.AccessScope;
import io.kestra.webserver.rbac.RbacAuthorizer.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NamespaceScopeTest {
    private static RbacConfiguration.Grant grant(String namespace, boolean descendants, Kind resource) {
        var grant = new RbacConfiguration.Grant();
        grant.setScope(Scope.NAMESPACE);
        grant.setTenant("main");
        grant.setNamespace(namespace);
        grant.setDescendants(descendants);
        grant.setResource(resource);
        grant.setActions(List.of(Action.READ, Action.LIST));
        grant.setAllIds(true);
        return grant;
    }
    @Test void unionPreservesExactAndResourceBoundaries() {
        var config = new RbacConfiguration();
        config.setRoles(Map.of("reader", List.of(grant("rbac.allowed", true, Kind.FLOW)),
            "other-reader", List.of(grant("rbac.other", true, Kind.FLOW)),
            "exact-reader", List.of(grant("rbac.allowed", false, Kind.FLOW)),
            "kv-reader", List.of(grant("rbac.kv", true, Kind.KVSTORE))));
        config.setRoleMapping(Map.of("reader", List.of("reader"), "other-reader", List.of("other-reader"),
            "exact-reader", List.of("exact-reader"), "kv-reader", List.of("kv-reader")));
        var engine = new PolicyEngine(config);
        var multi = new Principal("multi", Set.of("reader", "other-reader", "kv-reader"));
        var scope = engine.namespaceScope(multi, Kind.FLOW);
        for (String namespace : List.of("rbac.allowed", "rbac.allowed.child", "rbac.other", "rbac.other.child"))
            assertTrue(scope.namespaceAllowed(namespace), namespace);
        for (String namespace : List.of("rbac.denied", "rbac.allowed2", "rbac.kv"))
            assertFalse(scope.namespaceAllowed(namespace), namespace);
        var exact = engine.namespaceScope(new Principal("exact", Set.of("exact-reader")), Kind.FLOW);
        assertTrue(exact.namespaceAllowed("rbac.allowed"));
        assertFalse(exact.namespaceAllowed("rbac.allowed.child"));
        assertEquals(List.of("rbac.allowed"), exact.exactNamespaces());
        assertEquals(List.of(), exact.namespaces());
        assertEquals(AccessScope.Kind.DENY_ALL, engine.namespaceScope(new Principal("", Set.of("reader")), Kind.FLOW).kind());
        assertEquals(AccessScope.Kind.DENY_ALL, engine.namespaceScope(multi, Kind.EXECUTION).kind());
        var namespaces = engine.namespaceScope(multi, Kind.NAMESPACE);
        assertTrue(namespaces.namespaceAllowed("rbac.kv"));
        assertTrue(namespaces.namespaceAllowed("rbac.other.child"));
        assertFalse(namespaces.namespaceAllowed("rbac.denied"));
        assertTrue(engine.authorize(multi, new AuthorizationRequest("main", "rbac.kv", Kind.NAMESPACE, Action.READ, null, Scope.NAMESPACE)));
        assertFalse(engine.authorize(multi, new AuthorizationRequest("main", "rbac.kv", Kind.NAMESPACE, Action.UPDATE, null, Scope.NAMESPACE)));
    }
    @Test void restrictedIdsAndIncompleteCollectionGrantsNeverBecomeNamespaceScope() {
        var grant = grant("rbac.allowed", true, Kind.FLOW);
        grant.setAllIds(false);
        grant.setIds(List.of("approved"));
        var config = new RbacConfiguration();
        config.setRoles(Map.of("reader", List.of(grant)));
        config.setSubjects(Map.of("reader", List.of("reader")));
        var principal = new Principal("reader", Set.of());
        assertEquals(AccessScope.Kind.DENY_ALL, new PolicyEngine(config).namespaceScope(principal, Kind.FLOW).kind());
        grant.setAllIds(true);
        grant.setIds(List.of());
        grant.setActions(List.of(Action.READ));
        assertEquals(AccessScope.Kind.DENY_ALL, new PolicyEngine(config).namespaceScope(principal, Kind.FLOW).kind());
    }
}
