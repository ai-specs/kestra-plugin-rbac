package io.kestra.extensions.rbac;

import org.junit.jupiter.api.Test;
import java.util.*;
import static io.kestra.webserver.rbac.RbacAuthorizer.*;
import static org.junit.jupiter.api.Assertions.*;

class PolicyEngineTest {
    static RbacConfiguration configuration() {
        var grant = new RbacConfiguration.Grant();
        grant.setScope(Scope.NAMESPACE); grant.setTenant("main"); grant.setNamespace("prod.team"); grant.setDescendants(true);
        grant.setResource(Kind.FLOW); grant.setActions(List.of(Action.READ, Action.EXECUTE)); grant.setIds(List.of("flow"));
        var config = new RbacConfiguration();
        config.setRoles(Map.of("runner", List.of(grant)));
        config.setSubjects(Map.of("alice", List.of("runner")));
        config.setRoleMapping(Map.of("trusted-runner", List.of("runner")));
        return config;
    }
    static AuthorizationRequest request(String tenant, String ns, Action action, String id) {
        return new AuthorizationRequest(tenant, ns, Kind.FLOW, action, id, Scope.NAMESPACE);
    }
    @Test void boundariesAndDefaultDeny() {
        var engine = new PolicyEngine(configuration()); var alice = new Principal("alice", Set.of());
        assertFalse(engine.authorize(new Principal("unknown", Set.of("unmapped")), request("main", "prod.team", Action.READ, "flow")));
        assertTrue(engine.authorize(alice, request("main", "prod.team", Action.READ, "flow")));
        assertTrue(engine.authorize(alice, request("main", "prod.team.child", Action.EXECUTE, "flow")));
        for (String ns : Arrays.asList("prod", "prod.team2", "prod.team.", "prod.team..child", null, "", "prod.team/child"))
            assertFalse(engine.authorize(alice, request("main", ns, Action.READ, "flow")), String.valueOf(ns));
        assertFalse(engine.authorize(alice, request("other", "prod.team", Action.READ, "flow")));
        assertFalse(engine.authorize(alice, request("main", "prod.team", Action.DELETE, "flow")));
        assertFalse(engine.authorize(alice, request("main", "prod.team", Action.READ, "other")));
        assertFalse(engine.authorize(alice, request("main", "prod.team", Action.READ, null)));
        assertFalse(engine.authorize(null, request("main", "prod.team", Action.READ, "flow")));
        assertFalse(engine.authorize(new Principal("", Set.of("trusted-runner")), request("main", "prod.team", Action.READ, "flow")));
        assertFalse(engine.authorize(alice, null));
        assertFalse(engine.authorize(new Principal("bob", Set.of("runner")), request("main", "prod.team", Action.READ, "flow")));
    }

    @Test void multiRoleAcrossNamespacesKeepsEachRoleScopeAndActions() {
        var reader = new RbacConfiguration.Grant();
        reader.setScope(Scope.NAMESPACE); reader.setTenant("main"); reader.setNamespace("domain.b");
        reader.setResource(Kind.FLOW); reader.setActions(List.of(Action.READ, Action.LIST)); reader.setAllIds(true);
        var writer = new RbacConfiguration.Grant();
        writer.setScope(Scope.NAMESPACE); writer.setTenant("main"); writer.setNamespace("domain.a");
        writer.setResource(Kind.FLOW); writer.setActions(List.of(Action.READ, Action.LIST, Action.CREATE, Action.UPDATE, Action.DELETE, Action.EXECUTE)); writer.setAllIds(true);
        var config = new RbacConfiguration();
        config.setRoles(Map.of("multi-reader", List.of(reader), "multi-writer", List.of(writer)));
        config.setRoleMapping(Map.of("multi-reader", List.of("multi-reader"), "multi-writer", List.of("multi-writer")));
        var engine = new PolicyEngine(config);
        var principal = new Principal("multi", Set.of("multi-reader", "multi-writer"));
        assertTrue(engine.authorize(principal, request("main", "domain.a", Action.UPDATE, "a")));
        assertTrue(engine.authorize(principal, request("main", "domain.b", Action.READ, "b")));
        assertFalse(engine.authorize(principal, request("main", "domain.b", Action.UPDATE, "b")));
        assertFalse(engine.authorize(principal, request("main", "domain.c", Action.READ, "c")));
    }
    @Test void trustedRoleMappingAndSnapshot() {
        var config = configuration(); var engine = new PolicyEngine(config);
        var req = request("main", "prod.team", Action.READ, "flow");
        assertTrue(engine.authorize(new Principal("bob", Set.of("trusted-runner")), req));
        assertFalse(engine.authorize(new Principal("bob", Set.of("unknown")), req));
        config.getRoles().get("runner").getFirst().setNamespace("evil");
        assertTrue(engine.authorize(new Principal("alice", Set.of()), req));
    }
    @Test void bulkMustAuthorizeEveryResolvedOperation() {
        var engine = new PolicyEngine(configuration()); var alice = new Principal("alice", Set.of());
        var ok = request("main", "prod.team", Action.READ, "flow");
        assertTrue(engine.authorizeAll(alice, List.of(ok, ok)));
        assertFalse(engine.authorizeAll(alice, List.of(ok, request("main", "secret", Action.READ, "flow"))));
        assertFalse(engine.authorizeAll(alice, List.of())); assertFalse(engine.authorizeAll(alice, null));
    }
    @Test void invalidConfigurationFailsAtStartup() {
        var config = configuration(); config.setSubjects(Map.of("alice", List.of("undefined")));
        var unknown = config;
        assertThrows(IllegalArgumentException.class, () -> new PolicyEngine(unknown));
        config = configuration(); config.getRoles().get("runner").getFirst().setNamespace("prod.*");
        var invalid = config;
        assertThrows(IllegalArgumentException.class, () -> new PolicyEngine(invalid));
    }
    @Test void authenticatedClaimsHaveStrictShape() {
        assertEquals(new Principal("bob", Set.of("trusted-runner")), AuthenticatedPrincipalMapper.from("bob", Map.of("roles", List.of("trusted-runner")), "roles"));
        assertNull(AuthenticatedPrincipalMapper.from("bob", Map.of("roles", List.of(42)), "roles"));
        assertNull(AuthenticatedPrincipalMapper.from(null, Map.of(), "roles"));
        assertEquals(Set.of(), AuthenticatedPrincipalMapper.from("bob", Map.of(), "roles").roles());
    }
    @Test void emptyIdsNeverExpandAndListNeedsCompleteDomain() {
        var config = configuration(); var grant = config.getRoles().get("runner").getFirst();
        grant.setIds(List.of());
        var who = new Principal("alice", Set.of());
        assertFalse(new PolicyEngine(config).authorize(who, request("main", "prod.team", Action.READ, "flow")));
        grant.setAllIds(true);
        grant.setActions(List.of(Action.READ, Action.LIST));
        var engine = new PolicyEngine(config);
        assertTrue(engine.authorize(who, request("main", "prod.team", Action.READ, "random")));
        assertTrue(engine.authorize(who, request("main", "prod.team", Action.LIST, null)));
        grant.setAllIds(false); grant.setIds(List.of("flow"));
        assertFalse(new PolicyEngine(config).authorize(who, request("main", "prod.team", Action.LIST, null)));
        grant.setAllIds(true);
        assertThrows(IllegalArgumentException.class, () -> new PolicyEngine(config));
    }
    @Test void scopesNeverImplicitlyPromoteNamespaceGrant() {
        var engine = new PolicyEngine(configuration());
        assertFalse(engine.authorize(new Principal("alice", Set.of()), new AuthorizationRequest("main", null, Kind.FLOW, Action.READ, "flow", Scope.TENANT)));
        assertFalse(engine.authorize(new Principal("alice", Set.of()), new AuthorizationRequest(null, null, Kind.FLOW, Action.READ, "flow", Scope.INSTANCE)));
    }
}
