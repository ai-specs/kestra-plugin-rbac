package io.kestra.extensions.rbac;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.webserver.oidc.services.OidcUserService;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.*;

/** Controlled local image acceptance only. Password constants are never production defaults. */
class OidcRbacFixtureTest {
    @Test void createAcceptanceUsersWithProductionService() {
        String url = System.getenv("OIDC_FIXTURE_JDBC");
        assumeTrue(url != null && !url.isBlank(), "Controlled image acceptance fixture only");
        var source = new org.postgresql.ds.PGSimpleDataSource();
        source.setURL(url); source.setUser(System.getenv().getOrDefault("OIDC_FIXTURE_USER", "kestra")); source.setPassword(System.getenv().getOrDefault("OIDC_FIXTURE_PASSWORD", "local-ci-only"));
        var service = new OidcUserService(source, new ObjectMapper());
        create(service, "rbac-reader", "ReaderPass123!", "reader");
        create(service, "rbac-writer", "WriterPass123!", "writer");
        create(service, "rbac-blocked", "BlockedPass123!", "blocked");
        create(service, "rbac-multi", "MultiPass123!", List.of("multi-reader", "multi-writer"));
        create(service, "rbac-exact", "ExactPass123!", "exact-reader");
    }
    private void create(OidcUserService service, String subject, String password, String role) {
        create(service, subject, password, List.of(role));
    }
    private void create(OidcUserService service, String subject, String password, List<String> roles) {
        service.create(new OidcUserService.CreateUserRequest(subject, subject, subject + "@example.test", password, roles));
        assertTrue(service.validateCredentials(subject, password));
        assertEquals(roles, service.bySubject(subject).orElseThrow().roles(), "Fixture DB must be clean or have identical roles");
    }
}
