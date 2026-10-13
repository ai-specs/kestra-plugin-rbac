package io.kestra.extensions.rbac;

import io.micronaut.core.propagation.PropagatedContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.filters.SecurityFilter;
import java.util.Set;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RequestIdentityTest {
    @Test void missingIdentityAndMissingAuthenticationNeverGrantGlobalAccess() {
        var engine = new PolicyEngine(new RbacConfiguration());
        var namespaces = new ConfiguredNamespaceAccessControl(engine);
        var logs = new ConfiguredLogAccessControl(engine);
        assertEquals(io.kestra.core.models.AccessScope.Kind.DENY_ALL, namespaces.namespaceScope(io.kestra.core.models.QueryFilter.Resource.FLOW).kind());
        assertEquals(io.kestra.core.models.AccessScope.Kind.DENY_ALL, logs.namespaceScope().kind());
        var context = PropagatedContext.getOrEmpty().plus(new RequestIdentity(HttpRequest.GET("/api/v1/main/flows/search")));
        try (var scope = context.propagate()) {
            assertEquals(io.kestra.core.models.AccessScope.Kind.DENY_ALL, namespaces.namespaceScope(io.kestra.core.models.QueryFilter.Resource.FLOW).kind());
            assertEquals(io.kestra.core.models.AccessScope.Kind.DENY_ALL, logs.namespaceScope().kind());
        }
        assertEquals(io.kestra.core.models.AccessScope.Kind.DENY_ALL, namespaces.namespaceScope(io.kestra.core.models.QueryFilter.Resource.FLOW).kind());
        assertEquals(io.kestra.core.models.AccessScope.Kind.DENY_ALL, logs.namespaceScope().kind());
    }
    @Test void wrappedExecutorRetainsIdentityAndUnauthenticatedMarkerDenies() throws Exception {
        var request = HttpRequest.GET("/api/v1/main/flows/search");
        var context = PropagatedContext.getOrEmpty().plus(new RequestIdentity(request));
        try (var scope = context.propagate(); var executor = Executors.newSingleThreadExecutor()) {
            assertNull(RequestIdentity.current().principal());
            request.setAttribute(SecurityFilter.AUTHENTICATION, Authentication.build("reader", Set.of("reader")));
            assertEquals("reader", executor.submit(context.wrap((java.util.concurrent.Callable<String>) () -> RequestIdentity.current().principal().subject())).get());
            assertNull(executor.submit(() -> RequestIdentity.current()).get());
        }
        assertNull(RequestIdentity.current());
    }
}
