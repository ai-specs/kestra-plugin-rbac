package io.kestra.extensions.rbac;

import io.micronaut.context.ApplicationContext;
import io.micronaut.http.annotation.*;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.runtime.server.EmbeddedServer;
import java.net.URI;
import java.net.http.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HttpContextTest {
    @Controller("/context-probe")
    static class Probe {
        @Get("/blocking")
        @ExecuteOn("blocking")
        String blocking() { return identity(); }
        @Get("/io")
        @ExecuteOn("io")
        String io() { return identity(); }
        private String identity() {
            var identity = RequestIdentity.current();
            return identity == null ? "LOST" : identity.principal() == null ? "HTTP_DENY" : "AUTHENTICATED";
        }
    }
    @Test void realHttpExecuteOnMustRetainHttpOrigin() throws Exception {
        try (var context = ApplicationContext.builder().packages("io.kestra.extensions.rbac")
            .properties(Map.of("kestra.rbac.enabled", true, "micronaut.server.port", -1))
            .singletons(org.mockito.Mockito.mock(io.kestra.core.repositories.ExecutionRepositoryInterface.class)).start()) {
            var server = context.getBean(EmbeddedServer.class).start();
            var client = HttpClient.newHttpClient();
            for (String executor : new String[]{"blocking", "io"}) {
                var response = client.send(java.net.http.HttpRequest.newBuilder(URI.create(server.getURL() + "/context-probe/" + executor)).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                assertEquals("HTTP_DENY", response.body(), executor);
            }
        }
    }
}
