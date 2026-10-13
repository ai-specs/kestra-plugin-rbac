package io.kestra.extensions.rbac;

import io.micronaut.context.ApplicationContext;
import io.micronaut.http.annotation.*;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.runtime.server.EmbeddedServer;
import java.net.URI;
import java.net.http.*;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HttpContextTest {
    @Controller("/context-probe")
    static class Probe {
        private final io.kestra.core.repositories.NamespaceAccessControl namespaces;
        private final io.kestra.core.repositories.log.LogDataStoreAccessControl logs;
        Probe(io.kestra.core.repositories.NamespaceAccessControl namespaces, io.kestra.core.repositories.log.LogDataStoreAccessControl logs) {
            this.namespaces = namespaces;
            this.logs = logs;
        }
        @Get("/blocking")
        @ExecuteOn("blocking")
        String blocking() { return identity(); }
        @Get("/io")
        @ExecuteOn("io")
        String io() { return identity(); }
        @Get("/authenticated")
        @ExecuteOn("io")
        String authenticated(io.micronaut.http.HttpRequest<?> request) {
            request.setAttribute(io.micronaut.security.filters.SecurityFilter.AUTHENTICATION,
                io.micronaut.security.authentication.Authentication.build("fixture", List.of("reader")));
            var scope = namespaces.namespaceScope(io.kestra.core.models.QueryFilter.Resource.FLOW);
            return scope.namespaceAllowed("rbac.allowed") + ":" + scope.namespaceAllowed("rbac.allowed.child")
                + ":" + scope.namespaceAllowed("rbac.denied") + ":" + logs.namespaceScope().kind();
        }
        private String identity() {
            var identity = RequestIdentity.current();
            return identity == null ? "LOST" : identity.principal() == null ? "HTTP_DENY" : "AUTHENTICATED";
        }
    }
    @Test void realHttpExecuteOnMustRetainHttpOrigin() throws Exception {
        try (var context = ApplicationContext.builder().packages("io.kestra.extensions.rbac")
            .properties(Map.of("kestra.rbac.enabled", true, "micronaut.server.port", -1,
                "kestra.rbac.roles", Map.of("reader", List.of(Map.of("scope", "NAMESPACE", "tenant", "main", "namespace", "rbac.allowed", "resource", "FLOW", "actions", List.of("READ", "LIST"), "all-ids", true))),
                "kestra.rbac.role-mapping", Map.of("reader", List.of("reader"))))
            .singletons(org.mockito.Mockito.mock(io.kestra.core.repositories.ExecutionRepositoryInterface.class)).start()) {
            var server = context.getBean(EmbeddedServer.class).start();
            var client = HttpClient.newHttpClient();
            for (String executor : new String[]{"blocking", "io"}) {
                var response = client.send(java.net.http.HttpRequest.newBuilder(URI.create(server.getURL() + "/context-probe/" + executor)).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                assertEquals("HTTP_DENY", response.body(), executor);
            }
            var authenticated = client.send(java.net.http.HttpRequest.newBuilder(URI.create(server.getURL() + "/context-probe/authenticated")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, authenticated.statusCode());
            assertEquals("true:false:false:DENY_ALL", authenticated.body());
        }
    }
}
