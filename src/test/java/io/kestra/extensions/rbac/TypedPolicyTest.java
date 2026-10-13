package io.kestra.extensions.rbac;

import java.util.List;
import java.util.Map;
import io.kestra.webserver.rbac.RbacAuthorizer;
import org.junit.jupiter.api.Test;

import static io.kestra.webserver.rbac.TypedAuthorization.*;
import static org.junit.jupiter.api.Assertions.*;

class TypedPolicyTest {
    @Test
    void exactAndSubtreeRolesContributeUnionWithoutPrefixCollision() {
        var engine = new PolicyEngine(configuration());
        var result = engine.authorizeTyped(principal("reader", "writer"), query(QueryDomain.unrestricted()));
        assertTrue(result.allowed());
        for (String namespace : List.of("team.exact", "team.tree", "team.tree.child")) assertTrue(result.scope().contains(namespace), namespace);
        for (String namespace : List.of("team.exact.child", "team.exact2", "team.tree2", "other")) assertFalse(result.scope().contains(namespace), namespace);
        var requested = new QueryDomain(List.of("team.tree.child", "other"), List.of(), List.of(), true, true);
        var restricted = engine.authorizeTyped(principal("reader", "writer"), query(requested));
        assertTrue(restricted.scope().contains("team.tree.child"));
        assertFalse(restricted.scope().contains("team.tree"));
        assertFalse(restricted.scope().contains("other"));
    }

    @Test
    void writesRequireConcreteResourcePermissionAndUnknownSubjectsDeny() {
        var engine = new PolicyEngine(configuration());
        assertFalse(engine.authorizeTyped(principal("reader"), single("team.exact", Action.UPDATE)).allowed());
        assertTrue(engine.authorizeTyped(principal("writer"), single("team.tree.child", Action.UPDATE)).allowed());
        assertFalse(engine.authorizeTyped(principal("writer"), single("team.exact", Action.UPDATE)).allowed());
        assertFalse(engine.authorizeTyped(principal("unknown"), query(QueryDomain.unrestricted())).allowed());
        assertFalse(engine.authorizeTyped(null, query(QueryDomain.unrestricted())).allowed());
        assertFalse(engine.authorizeTyped(principal("reader"), null).allowed());
        var system = new Operation(CONTRACT_VERSION, Source.REST, Resource.SYSTEM, Action.EXECUTE, "main", null, null,
            QueryDomain.unrestricted(), Coverage.COMPLETE_BATCH, Aggregate.NONE, SideEffect.EXTERNAL);
        assertFalse(engine.authorizeTyped(principal("reader", "writer"), system).allowed());
        assertTrue(engine.authorizeTyped(principal("admin"), system).allowed());
        assertFalse(engine.authorizeTyped(principal("deployment-admin"), system).allowed());
    }

    @Test
    void idRestrictedGrantsCannotRevealQueriesCountsOrAggregates() {
        var configuration = configuration();
        configuration.getRoles().get("exact").getFirst().setAllIds(false);
        configuration.getRoles().get("exact").getFirst().setIds(List.of("flow"));
        var engine = new PolicyEngine(configuration);
        assertFalse(engine.authorizeTyped(principal("reader"), query(QueryDomain.unrestricted())).allowed());
        assertTrue(engine.authorizeTyped(principal("reader"), single("team.exact", Action.READ)).allowed());
    }

    private RbacConfiguration configuration() {
        var exact = grant("team.exact", false, List.of(RbacAuthorizer.Action.READ, RbacAuthorizer.Action.LIST));
        var tree = grant("team.tree", true, List.of(RbacAuthorizer.Action.READ, RbacAuthorizer.Action.LIST, RbacAuthorizer.Action.UPDATE));
        var configuration = new RbacConfiguration();
        configuration.setRoles(Map.of("exact", List.of(exact), "tree", List.of(tree), "deployment-admin", List.of()));
        configuration.setRoleMapping(Map.of("reader", List.of("exact"), "writer", List.of("tree"), "admin", List.of("deployment-admin")));
        configuration.setInstanceAdminRoles(List.of("deployment-admin"));
        return configuration;
    }

    private RbacConfiguration.Grant grant(String namespace, boolean descendants, List<RbacAuthorizer.Action> actions) {
        var grant = new RbacConfiguration.Grant();
        grant.setScope(RbacAuthorizer.Scope.NAMESPACE);
        grant.setTenant("main");
        grant.setNamespace(namespace);
        grant.setDescendants(descendants);
        grant.setResource(RbacAuthorizer.Kind.FLOW);
        grant.setActions(actions);
        grant.setAllIds(true);
        return grant;
    }

    private RbacAuthorizer.Principal principal(String... roles) {
        return new RbacAuthorizer.Principal("subject", List.of(roles), null, null, List.of());
    }

    private Operation query(QueryDomain domain) {
        return new Operation(CONTRACT_VERSION, Source.REST, Resource.FLOW, Action.LIST, "main", null, null,
            domain, Coverage.COMPLETE_QUERY, Aggregate.NONE, SideEffect.READ_ONLY);
    }

    private Operation single(String namespace, Action action) {
        return new Operation(CONTRACT_VERSION, Source.REST, Resource.FLOW, action, "main", namespace, "flow",
            new QueryDomain(List.of(namespace), List.of(), List.of(), true, false), Coverage.SINGLE, Aggregate.NONE,
            action == Action.READ ? SideEffect.READ_ONLY : SideEffect.STORAGE_WRITE);
    }
}
