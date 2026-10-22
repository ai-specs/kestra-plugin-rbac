package io.kestra.extensions.rbac.declaration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.extensions.rbac.ApplicationGroupRuntimePolicies;
import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy;
import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupDirectoryService;
import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupDirectory;
import io.kestra.extensions.rbac.store.ApplicationGroupDirectoryStore;
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
                           ApplicationGroupRuntimePolicies.Context context, ApplicationGroupDirectoryService directory,
                           Authentication authentication) {}

    private Fixture fixture(boolean supportsScope) {
        try {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:declaration-api-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        var store = new ApplicationScopeDeclarationStore(source, new ObjectMapper());
        store.initializeSchema();
        var policies = mock(ApplicationGroupRuntimePolicies.class);
        var context = mock(ApplicationGroupRuntimePolicies.Context.class);
        var authentication = Authentication.build("manager");
        when(policies.open(authentication, "main")).thenReturn(context);
        when(context.identity()).thenReturn(new TrustedIdentityV1.Identity(1, ISSUER, "manager", "client",
            Set.of("client"), "main", "group", Set.of("openid"), Set.of("manager"), 1, 1, 1));
        when(context.authorizeManagement(eq(ApplicationGroupPolicy.ManagementDomain.IDENTITY), any(), eq("group"))).thenReturn(true);
        var directory=mock(ApplicationGroupDirectoryService.class);
        when(directory.transaction(any())).thenAnswer(call->{
            try(var connection=source.getConnection()) {
                connection.setAutoCommit(false);connection.setTransactionIsolation(java.sql.Connection.TRANSACTION_READ_COMMITTED);
                try {var value=((ApplicationGroupDirectoryStore.Work<?>)call.getArgument(0)).run(connection);connection.commit();return value;}
                catch(Exception failure){connection.rollback();throw failure;}
            }
        });
        when(policies.open(any(java.sql.Connection.class),any(TrustedIdentityV1.Identity.class),eq("main"))).thenReturn(context);
        var snapshot=new ApplicationGroupDirectory.Snapshot(new ApplicationGroupDirectory.Group("group","Group",true,1,1),
            List.of(new ApplicationGroupDirectory.Scope("reader","Reader","",1)),
            List.of(new ApplicationGroupDirectory.Application("client",true,Set.of("client"),supportsScope?Set.of("reader"):Set.of(),1)),List.of());
        when(directory.find(any(),eq("group"))).thenReturn(snapshot);
        return new Fixture(new ApplicationScopeDeclarationController(policies,store,directory),store,context,directory,authentication);
        }catch(Exception failure){throw new AssertionError(failure);}
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
        verify(fixture.context(), atLeastOnce()).authorizeManagement(ApplicationGroupPolicy.ManagementDomain.IDENTITY,
            ApplicationGroupPolicy.ManagementAction.READ, "group");
        verify(fixture.context(), atLeastOnce()).authorizeManagement(ApplicationGroupPolicy.ManagementDomain.IDENTITY,
            ApplicationGroupPolicy.ManagementAction.MANAGE, "group");
    }

    @Test
    void deniedAndUnregisteredWritesLeaveNoDeclaration()throws Exception {
        var unregistered = fixture(false);
        assertEquals(409, unregistered.controller().create("main", "group", CLIENT_KEY, "reader",
            unregistered.authentication(), CREATE).code());
        assertTrue(unregistered.store().list("group", "client").isEmpty());
        var denied = fixture(true);
        when(denied.context().authorizeManagement(ApplicationGroupPolicy.ManagementDomain.IDENTITY,
            ApplicationGroupPolicy.ManagementAction.MANAGE, "group")).thenReturn(false);
        assertEquals(403, denied.controller().create("main", "group", CLIENT_KEY, "reader",
            denied.authentication(), CREATE).code());
        assertTrue(denied.store().list("group", "client").isEmpty());
        verify(denied.directory(),never()).find(any(),anyString());
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
