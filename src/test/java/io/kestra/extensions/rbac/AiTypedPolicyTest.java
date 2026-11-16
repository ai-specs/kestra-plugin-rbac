package io.kestra.extensions.rbac;

import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy;
import io.kestra.webserver.rbac.AuthorizationEvaluationV1;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.kestra.webserver.rbac.TypedAuthorization.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiTypedPolicyTest {
    private static ApplicationGroupPolicy.ScopeDefinition ai(String... actions) {
        return new ApplicationGroupPolicy.ScopeDefinition("AI entry", true, List.of(
            new ApplicationGroupPolicy.Grant("TENANT", "main", null, false, "AI", Set.of(actions), Set.of(), true)));
    }

    private static Operation operation(Action action) {
        return new Operation(CONTRACT_VERSION, Source.REST, Resource.AI, action, "main", null, null,
            QueryDomain.unrestricted(), action == Action.EXECUTE ? Coverage.COMPLETE_BATCH : Coverage.SINGLE,
            Aggregate.NONE, action == Action.EXECUTE ? SideEffect.EXTERNAL : SideEffect.READ_ONLY);
    }

    private static AuthorizationEvaluationV1 begin(RuntimePoliciesTest.Fixture fixture, String... scopes) {
        return new ConfiguredEvaluationProvider(fixture.runtime, Runnable::run)
            .begin(fixture.identity(scopes), "main").toCompletableFuture().join();
    }

    private static boolean allowed(AuthorizationEvaluationV1 evaluation, Action action) {
        return evaluation.authorize(operation(action)).toCompletableFuture().join().allowed();
    }

    @Test void shouldKeepTypedAiReadAndExecuteIndependent() {
        var fixture = new RuntimePoliciesTest.Fixture(Map.of("read", ai("READ"), "execute", ai("EXECUTE")));
        var read = begin(fixture, "read");
        var execute = begin(fixture, "execute");
        var both = begin(fixture, "read", "execute");
        try {
            assertTrue(allowed(read, Action.READ));
            assertFalse(allowed(read, Action.EXECUTE));
            assertTrue(allowed(execute, Action.EXECUTE));
            assertFalse(allowed(execute, Action.READ));
            assertTrue(allowed(both, Action.READ));
            assertTrue(allowed(both, Action.EXECUTE));
            assertFalse(both.authorize(RuntimePoliciesTest.op(Resource.FLOW, Action.READ, "company", "flow", false))
                .toCompletableFuture().join().allowed());
        } finally {
            read.close().toCompletableFuture().join();
            execute.close().toCompletableFuture().join();
            both.close().toCompletableFuture().join();
        }
    }

    @Test void shouldDenyLegacyNamespaceAiForTypedTenantEntry() {
        var legacy = new ApplicationGroupPolicy.ScopeDefinition("Legacy", true, List.of(
            new ApplicationGroupPolicy.Grant("NAMESPACE", "main", "company", true, "AI",
                Set.of("READ", "LIST", "EXECUTE", "UPDATE"), Set.of(), true)));
        var fixture = new RuntimePoliciesTest.Fixture(Map.of("legacy", legacy));
        var evaluation = begin(fixture, "legacy");
        try {
            assertFalse(allowed(evaluation, Action.READ));
            assertFalse(allowed(evaluation, Action.EXECUTE));
        } finally { evaluation.close().toCompletableFuture().join(); }
    }

    @Test void shouldNotTurnBusinessInstanceOrMetadataGrantsIntoTypedAiPermission() {
        var metadata = new ApplicationGroupPolicy.ScopeDefinition("Metadata", true, List.of(
            new ApplicationGroupPolicy.Grant("TENANT", "main", null, false, "METADATA",
                Set.of("READ", "LIST"), Set.of(), true)));
        var fixture = new RuntimePoliciesTest.Fixture(Map.of(
            "manager", RuntimePoliciesTest.manager(ApplicationGroupPolicy.ManagementDomain.BUSINESS_INSTANCE,
                ApplicationGroupPolicy.ManagementAction.READ, ApplicationGroupPolicy.ManagementAction.MANAGE),
            "metadata", metadata));
        var evaluation = begin(fixture, "manager", "metadata");
        try {
            assertFalse(allowed(evaluation, Action.READ));
            assertFalse(allowed(evaluation, Action.EXECUTE));
        } finally { evaluation.close().toCompletableFuture().join(); }
    }

    @Test void shouldDenyTypedAiForZeroGrantedScopes() {
        var fixture = new RuntimePoliciesTest.Fixture(Map.of("available", ai("READ", "EXECUTE")));
        var evaluation = begin(fixture);
        try {
            assertFalse(allowed(evaluation, Action.READ));
            assertFalse(allowed(evaluation, Action.EXECUTE));
        } finally { evaluation.close().toCompletableFuture().join(); }
    }

    @Test void shouldRejectUnknownTypedAiActionsAndIds() {
        var fixture = new RuntimePoliciesTest.Fixture(Map.of("entry", ai("READ", "EXECUTE")));
        var evaluation = begin(fixture, "entry");
        try {
            for (Action action : List.of(Action.LIST, Action.CREATE, Action.UPDATE, Action.DELETE, Action.ACCESS_OUTPUTS))
                assertFalse(allowed(evaluation, action), action.name());
            var withId = new Operation(CONTRACT_VERSION, Source.REST, Resource.AI, Action.READ, "main", null, "thread",
                QueryDomain.unrestricted(), Coverage.SINGLE, Aggregate.NONE, SideEffect.READ_ONLY);
            assertFalse(evaluation.authorize(withId).toCompletableFuture().join().allowed());
        } finally { evaluation.close().toCompletableFuture().join(); }
    }

    @Test void shouldUseRevokedPolicyForNewEvaluationAndDenyClosedEvaluation() {
        var fixture = new RuntimePoliciesTest.Fixture(Map.of("entry", ai("READ", "EXECUTE")));
        var original = begin(fixture, "entry");
        var revoked = new ApplicationGroupPolicy(2, Map.of("p", new ApplicationGroupPolicy.ApplicationGroup(
            "https://issuer.example", Set.of("ui", "api"), true,
            Map.of("entry", new ApplicationGroupPolicy.ScopeDefinition("Revoked", false, ai("READ", "EXECUTE").grants())))));
        when(fixture.store.readCurrent()).thenReturn(RuntimePoliciesTest.snapshot(2, revoked));
        var next = begin(fixture, "entry");
        try {
            assertTrue(allowed(original, Action.EXECUTE));
            assertFalse(allowed(next, Action.READ));
            assertFalse(allowed(next, Action.EXECUTE));
        } finally {
            original.close().toCompletableFuture().join();
            next.close().toCompletableFuture().join();
        }
        assertFalse(allowed(original, Action.READ));
        assertFalse(allowed(original, Action.EXECUTE));
    }

    @Test void shouldRejectNewEvaluationAfterGrantedScopeIsWithdrawn() {
        var fixture = new RuntimePoliciesTest.Fixture(Map.of("entry", ai("READ", "EXECUTE")));
        var original = begin(fixture, "entry");
        var withdrawn = begin(fixture);
        try {
            assertTrue(allowed(original, Action.READ));
            assertTrue(allowed(original, Action.EXECUTE));
            assertFalse(allowed(withdrawn, Action.READ));
            assertFalse(allowed(withdrawn, Action.EXECUTE));
        } finally {
            original.close().toCompletableFuture().join();
            withdrawn.close().toCompletableFuture().join();
        }
    }
}
