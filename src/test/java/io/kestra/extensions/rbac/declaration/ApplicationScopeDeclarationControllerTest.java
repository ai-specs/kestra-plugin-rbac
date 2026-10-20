package io.kestra.extensions.rbac.declaration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.extensions.rbac.ProjectRuntimePolicies;
import io.kestra.extensions.rbac.project.ProjectPolicy;
import io.kestra.webserver.oidc.ApplicationDirectoryV1;
import io.kestra.webserver.oidc.TrustedIdentityV1;
import io.micronaut.context.BeanProvider;
import io.micronaut.security.authentication.Authentication;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ApplicationScopeDeclarationControllerTest {
    private static final String ISSUER = "https://identity.example.invalid";
    private static final String CLIENT_KEY = "~Y2xpZW50";
    private static final byte[] CREATE = "{\"permissions\":[{\"key\":\"view-flows\",\"name\":\"View flows\"}]}".getBytes(StandardCharsets.UTF_8);

    private record Fixture(ApplicationScopeDeclarationController controller, ApplicationScopeDeclarationStore store,
                           ProjectRuntimePolicies.Context context, ApplicationDirectoryV1 directory,
                           Authentication authentication) {}

    private Fixture fixture(boolean supportsScope) {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:declaration-api-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        var store = new ApplicationScopeDeclarationStore(source, new ObjectMapper());
        store.initializeSchema();
        var policies = mock(ProjectRuntimePolicies.class);
        var context = mock(ProjectRuntimePolicies.Context.class);
        var authentication = Authentication.build("manager");
        when(policies.open(authentication, "main")).thenReturn(context);
        when(context.identity()).thenReturn(new TrustedIdentityV1.Identity(1, ISSUER, "manager", "client",
            Set.of("client"), "main", "group", Set.of("openid"), Set.of("manager"), 1, 1, 1));
        when(context.authorizeManagement(eq(ProjectPolicy.ManagementDomain.IDENTITY), any(), eq("group"))).thenReturn(true);
        var directory = mock(ApplicationDirectoryV1.class);
        when(directory.project("group")).thenReturn(Optional.of(new ApplicationDirectoryV1.Project("group", "main", ISSUER,
            "Group", true, 1, 1)));
        when(directory.clients("group", 1, 100)).thenReturn(new ApplicationDirectoryV1.Page<>(List.of(
            new ApplicationDirectoryV1.ClientMembership("group", "client", true, Set.of("client"),
                supportsScope ? Set.of("reader") : Set.of(), false, 1)), 1, 1, 100));
        when(directory.scope("group", "reader")).thenReturn(Optional.of(new ApplicationDirectoryV1.ScopeDefinition(
            "group", "reader", "Reader", "", true, List.of(), List.of(), 1)));
        BeanProvider<ApplicationDirectoryV1> provider = new BeanProvider<>() {
            public ApplicationDirectoryV1 get() { return directory; }
            public boolean isUnique() { return true; }
        };
        return new Fixture(new ApplicationScopeDeclarationController(policies, store, provider), store, context, directory,
            authentication);
    }

    @Test
    void supportsCrudWithDistinctReadManageAndStrongRevision() {
        var fixture = fixture(true);
        var controller = fixture.controller();
        assertEquals(201, controller.create("main", "group", CLIENT_KEY, "reader", fixture.authentication(), CREATE).code());
        assertEquals(409, controller.create("main", "group", CLIENT_KEY, "reader", fixture.authentication(), CREATE).code());
        assertEquals(200, controller.detail("main", "group", CLIENT_KEY, "reader", fixture.authentication()).code());
        assertEquals(200, controller.list("main", "group", CLIENT_KEY, fixture.authentication()).code());
        assertEquals(428, controller.update("main", "group", CLIENT_KEY, "reader", fixture.authentication(), null, CREATE).code());
        assertEquals(200, controller.update("main", "group", CLIENT_KEY, "reader", fixture.authentication(), "\"1\"",
            "{\"permissions\":[{\"key\":\"view-flows\",\"name\":\"Read flows\"}]}".getBytes(StandardCharsets.UTF_8)).code());
        assertEquals("Read flows", fixture.store().list("group", "client").getFirst().permissions().getFirst().name());
        assertEquals(412, controller.delete("main", "group", CLIENT_KEY, "reader", fixture.authentication(), "\"1\"").code());
        assertEquals(200, controller.delete("main", "group", CLIENT_KEY, "reader", fixture.authentication(), "\"2\"").code());
        assertTrue(fixture.store().list("group", "client").isEmpty());
        verify(fixture.context(), atLeastOnce()).authorizeManagement(ProjectPolicy.ManagementDomain.IDENTITY,
            ProjectPolicy.ManagementAction.READ, "group");
        verify(fixture.context(), atLeastOnce()).authorizeManagement(ProjectPolicy.ManagementDomain.IDENTITY,
            ProjectPolicy.ManagementAction.MANAGE, "group");
    }

    @Test
    void deniedAndUnregisteredWritesLeaveNoDeclaration() {
        var unregistered = fixture(false);
        assertEquals(409, unregistered.controller().create("main", "group", CLIENT_KEY, "reader",
            unregistered.authentication(), CREATE).code());
        assertTrue(unregistered.store().list("group", "client").isEmpty());
        var denied = fixture(true);
        when(denied.context().authorizeManagement(ProjectPolicy.ManagementDomain.IDENTITY,
            ProjectPolicy.ManagementAction.MANAGE, "group")).thenReturn(false);
        assertEquals(403, denied.controller().create("main", "group", CLIENT_KEY, "reader",
            denied.authentication(), CREATE).code());
        assertTrue(denied.store().list("group", "client").isEmpty());
        verifyNoMoreInteractions(denied.directory());
    }

    @Test
    void invalidJsonAndClientEncodingCannotWrite() {
        var fixture = fixture(true);
        assertEquals(400, fixture.controller().create("main", "group", CLIENT_KEY, "reader", fixture.authentication(),
            "{\"permissions\":[],\"permissions\":[]}".getBytes(StandardCharsets.UTF_8)).code());
        assertEquals(400, fixture.controller().create("main", "group", "client", "reader", fixture.authentication(), CREATE).code());
        assertTrue(fixture.store().list("group", "client").isEmpty());
    }
}
