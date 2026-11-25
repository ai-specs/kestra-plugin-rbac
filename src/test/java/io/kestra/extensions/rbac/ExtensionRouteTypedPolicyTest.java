package io.kestra.extensions.rbac;

import io.kestra.webserver.rbac.TypedAuthorization.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static io.kestra.webserver.rbac.TypedAuthorization.CONTRACT_VERSION;

class ExtensionRouteTypedPolicyTest {
    private static Operation route(String resource, String action, String namespace, String id, Coverage coverage) {
        return new Operation(CONTRACT_VERSION, Source.REST, Resource.valueOf(resource), Action.valueOf(action), "main", namespace, id,
            new QueryDomain(java.util.List.of(namespace), java.util.List.of(), java.util.List.of(), true, false), coverage, Aggregate.NONE, SideEffect.READ_ONLY);
    }

    @Test void typedOperationUsesExactGrantAndRejectsWrongResourceOrQuery() {
        var fixture = new RuntimePoliciesTest.Fixture(Map.of("route", RuntimePoliciesTest.route("a", "abc")));
        var provider = new ConfiguredEvaluationProvider(fixture.runtime, Runnable::run);
        var evaluation = provider.begin(fixture.identity("route"), "main").toCompletableFuture().join();
        try {
            assertTrue(evaluation.authorize(route("EXTENSION_ROUTE", "EXECUTE", "a", "abc", Coverage.SINGLE)).toCompletableFuture().join().allowed());
            assertFalse(evaluation.authorize(route("EXTENSION_ROUTE", "EXECUTE", "a2", "abc", Coverage.SINGLE)).toCompletableFuture().join().allowed());
            assertFalse(evaluation.authorize(route("EXTENSION_ROUTE", "EXECUTE", "a", "other", Coverage.SINGLE)).toCompletableFuture().join().allowed());
            assertFalse(evaluation.authorize(route("FLOW", "EXECUTE", "a", "abc", Coverage.SINGLE)).toCompletableFuture().join().allowed());
            assertThrows(IllegalArgumentException.class, () -> route("EXTENSION_ROUTE", "EXECUTE", "a", "abc", Coverage.COMPLETE_QUERY));
            assertThrows(IllegalArgumentException.class, () -> route("EXTENSION_ROUTE", "READ", "a", "abc", Coverage.SINGLE));
        } finally { evaluation.close().toCompletableFuture().join(); }
    }
}
