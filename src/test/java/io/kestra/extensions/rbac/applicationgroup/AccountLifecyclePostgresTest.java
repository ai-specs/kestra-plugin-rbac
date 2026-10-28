package io.kestra.extensions.rbac.applicationgroup;

import io.kestra.extensions.rbac.ApplicationGroupRuntimePolicies;
import io.kestra.extensions.rbac.management.PublishedIdentityLifecycleParticipant;
import io.kestra.extensions.rbac.management.PublishedUserDirectoryAccess;
import io.kestra.extensions.rbac.store.ApplicationGroupDirectoryStore;
import io.kestra.extensions.rbac.store.ManagementAuditStore;
import io.kestra.webserver.oidc.*;
import io.kestra.webserver.oidc.services.*;
import io.micronaut.context.BeanProvider;
import io.micronaut.http.HttpRequest;
import io.micronaut.security.authentication.Authentication;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static io.kestra.extensions.rbac.applicationgroup.ApplicationGroupDirectory.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("postgres-lifecycle")
class AccountLifecyclePostgresTest {
    static final String ISSUER="https://issuer.example";
    @TempDir java.nio.file.Path temporary;
    final org.postgresql.ds.PGSimpleDataSource source=new org.postgresql.ds.PGSimpleDataSource();
    final ManagementAuditStore audit=new ManagementAuditStore();
    ApplicationGroupDirectoryStore store;
    ApplicationGroupDirectoryService service;
    OidcProtocolState protocol;
    OidcTrustedIdentity trusted;
    OidcUserDirectory users;
    PublishedUserDirectoryAccess published;
    javax.sql.DataSource ownedSource;
    IdentityLifecycleParticipantV1 participant;
    boolean failParticipant,failHost,participantFaultReached,hostFaultReached;
    final java.util.concurrent.ConcurrentLinkedQueue<Throwable> diagnostics=new java.util.concurrent.ConcurrentLinkedQueue<>();

    static <T> BeanProvider<T> provider(java.util.function.Supplier<T> value) {
        return new BeanProvider<>() {
            public T get(){return value.get();}public boolean isUnique(){return value.get()!=null;}public boolean isPresent(){return value.get()!=null;}
        };
    }
    @BeforeEach void initialize()throws Exception {
        String jdbc=System.getenv("RBAC_STORE_TEST_JDBC");if(jdbc==null || jdbc.isBlank())throw new IllegalStateException("Real lifecycle PostgreSQL required");
        source.setURL(jdbc);source.setUser("postgres");source.setPassword("policy-test-only");
        String schema="published_lifecycle_"+java.util.UUID.randomUUID().toString().replace("-","");
        try(var connection=source.getConnection();var statement=connection.createStatement()) {
            assertEquals("PostgreSQL",connection.getMetaData().getDatabaseProductName());statement.execute("CREATE SCHEMA "+schema);
        }
        source.setCurrentSchema(schema);
        for(String name:List.of("V2_0_90OidcServerJwkMigration","V2_0_91OidcServerDirectoryMigration","V2_0_92OidcServerTokenMigration",
            "V2_0_93OidcServerSetupMigration","V2_0_94OidcServerInstallationMigration","V2_0_95OidcServerOwnerMigration","V2_0_96OidcServerResourceMigration",
            "V2_0_97OidcServerRateMigration","V2_0_98OidcServerClientSecretMigration","V2_0_99OidcUserDirectoryMigration",
            "V2_0_99OidcAccountDeletionMigration","V2_0_99OidcClientCredentialsMigration","V2_0_99OidcPasswordChangeMigration")) {
            var migration=(io.kestra.jdbc.migration.AbstractSQLMigrationScript)Class.forName("io.kestra.webserver.oidc.migration."+name).getConstructor(javax.sql.DataSource.class).newInstance(source);
            migration.migrate();
        }
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var userService=new OidcUserService(source,mapper);
        for(String subject:List.of("first","second"))userService.create(new OidcUserService.CreateUserRequest(subject,"","","isolated-test-password",List.of()));
        var configuration=new OidcConfiguration();configuration.setIssuer(ISSUER);configuration.setMcpScopes(List.of());
        var environment=mock(io.micronaut.context.env.Environment.class);
        var properties=Map.of("micronaut.security.oauth2.clients.kestra.client-id","ui","micronaut.security.oauth2.clients.kestra.client-secret","isolated-test-secret",
            "micronaut.security.oauth2.clients.kestra.openid.issuer",ISSUER,"kestra.oidc.bootstrap.receipt-path",temporary.resolve("receipt").toString(),"kestra.rbac.application-groups.enabled","true");
        when(environment.getProperty(anyString(),eq(String.class))).thenAnswer(call->java.util.Optional.ofNullable(properties.get(call.getArgument(0))));
        when(environment.getProperty(eq("kestra.rbac.application-groups.enabled"),eq(Boolean.class))).thenReturn(java.util.Optional.of(true));
        assertTrue(new OidcSetupService(source,configuration,environment).ready());
        protocol=new OidcProtocolState(ISSUER,"ui",new OidcInstallationReceipt(temporary.resolve("receipt")),mapper);
        store=new ApplicationGroupDirectoryStore(source,protocol::lockSecurityState);store.initializeSchema();
        var permissions=List.of(new ApplicationPermission("admin","Admin","",Set.of("admin"),List.of(),List.of(
            new ApplicationGroupPolicy.ManagementGrant(ApplicationGroupPolicy.ManagementDomain.IDENTITY,Set.of(ApplicationGroupPolicy.ManagementAction.READ,ApplicationGroupPolicy.ManagementAction.MANAGE)),
            new ApplicationGroupPolicy.ManagementGrant(ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,Set.of(ApplicationGroupPolicy.ManagementAction.READ,ApplicationGroupPolicy.ManagementAction.MANAGE))),"",true,1));
        store.initializeBuiltin(new Snapshot(new Group("main","Main",true,1,1),List.of(new Scope("admin","Admin","",1)),List.of(new Application("ui","",Set.of("ui"),permissions,1)),
            List.of(new UserGrant(ISSUER,"first",Set.of("admin"),1),new UserGrant(ISSUER,"second",Set.of("admin"),1))));
        store.create(new Snapshot(new Group("other","Other",true,1,1),List.of(),List.of(),List.of(new UserGrant(ISSUER,"first",Set.of(),1),new UserGrant(ISSUER,"second",Set.of(),1))));
        service=new ApplicationGroupDirectoryService(store,protocol,ISSUER,"ui");
        trusted=spy(new OidcTrustedIdentity(provider(()->service),protocol,configuration,source));
        doAnswer(call->{try{return call.callRealMethod();}catch(Throwable failure){diagnostics.add(failure);throw failure;}}).when(trusted).current(any(Connection.class));
        var policies=new ApplicationGroupRuntimePolicies(service,trusted);
        var actual=new PublishedIdentityLifecycleParticipant(service,store,policies,provider(()->trusted),audit);
        participant=(connection,change)->{
            try {actual.beforeChange(connection,change);}catch(Throwable failure){diagnostics.add(failure);throw failure;}
            assertFalse(connection.getAutoCommit());assertFalse(connection.isClosed());
            verify(connection,never()).commit();verify(connection,never()).rollback();verify(connection,never()).close();
            if(failParticipant) {assertTrue(audit.readIssuer(connection,ISSUER,1,100).total()>0);participantFaultReached=true;throw new SQLException("Injected after participant append");}
        };
        var lifecycle=new OidcLifecycleParticipants(provider(()->participant),provider(()->trusted),configuration);
        ownedSource=mock(javax.sql.DataSource.class);
        when(ownedSource.getConnection()).thenAnswer(call->{
            Connection connection=spy(source.getConnection());
            doAnswer(commit->{if(failHost){hostFaultReached=true;throw new SQLException("Injected host commit failure");}commit.callRealMethod();return null;}).when(connection).commit();
            return connection;
        });
        users=new OidcUserDirectory(ownedSource,provider(()->service),protocol,configuration,true,lifecycle);
        published=new PublishedUserDirectoryAccess(provider(()->users));
        var provenance=mapper.readTree(java.nio.file.Files.readString(java.nio.file.Path.of(".integration/host-provenance.json")));
        System.out.println("Account lifecycle backend: PostgreSQL; business: plugin-directory; host: published-adapter-participant; executable: "+provenance.path("sha256").textValue()+"; image: "+provenance.path("image").textValue());
    }

    void change(String actor,String target,boolean delete) {
        change(authentication(actor),target,delete);
    }
    void change(Authentication authentication,String target,boolean delete) {
        var request=HttpRequest.GET("/api/v1/main/extensions/rbac/users").setAttribute(io.micronaut.security.filters.SecurityFilter.AUTHENTICATION,authentication);
        try(var scope=io.micronaut.core.propagation.PropagatedContext.getOrEmpty().plus(new io.kestra.core.repositories.AuthorizationInvocationContext(request,false)).propagate()) {
            long revision=published.find(target).orElseThrow().revision();
            if(delete)published.delete(target,revision);else published.setActive(target,revision,false);
        }
    }
    Authentication authentication(String subject) {
        return store.transaction(connection->{
            var group=service.find(connection,"main");var account=protocol.account(connection,ISSUER,subject).orElseThrow();
            return Authentication.build(subject,List.of(),Map.of("iss",ISSUER,"aud",List.of("ui"),"authentication_version",account.authenticationVersion(),
                "kestra_application_group_v1",Map.of("client_id","ui","application_group_id","main","authorization_version",group.group().authorizationVersion(),
                    "application_group_scopes",group.eligibleScopeKeys("ui",ISSUER,subject).stream().sorted().toList(),"protocol_scopes",List.of("openid"),"audience_contract_version",2)));
        });
    }
    long auditCount(){return store.transaction(connection->audit.read(connection,ISSUER,Set.of("main","other"),1,100)).total();}
    long activeAccounts(){return users.list(1,100).items().stream().filter(UserDirectoryV1.User::active).count();}

    @Test void participantWriteThenThrowRollsBackAllGroups() {
        var before=store.readAll();failParticipant=true;
        assertThrows(RuntimeException.class,()->change("first","second",true));
        assertTrue(participantFaultReached);
        assertEquals(before,store.readAll());assertEquals(2,activeAccounts());assertEquals(0,auditCount());
    }
    @Test void hostDeleteFailureRollsBackParticipantWrites() {
        var before=store.readAll();failHost=true;
        assertThrows(RuntimeException.class,()->change("first","second",true));failHost=false;
        assertTrue(hostFaultReached);
        assertEquals(before,store.readAll());assertEquals(2,activeAccounts());assertEquals(0,auditCount());
    }
    @Test void allGroupCleanupAndLastAdministratorUseBorrowedConnection() {
        try {change("first","second",true);}catch(RuntimeException failure){for(var diagnostic:diagnostics)diagnostic.printStackTrace();throw failure;}assertEquals(1,activeAccounts());assertEquals(2,auditCount());
        assertTrue(store.readAll().stream().allMatch(snapshot->snapshot.userGrants().stream().noneMatch(grant->grant.subject().equals("second"))));
        var retained=store.transaction(connection->audit.read(connection,ISSUER,Set.of("main","other"),1,100)).items();
        assertTrue(retained.stream().allMatch(event->event.actorSubject().equals("first") && event.targetSubject().equals("second")));
        assertThrows(RuntimeException.class,()->change("second","first",true));assertEquals(2,auditCount());
    }
    @Test void lastAdministratorRejectionRollsBackGrantRemoval() {
        var initial=service.transaction(connection->service.find(connection,"main"));
        store.update("main",initial.group().revision(),current->{
            var scope=new Scope("identity","Identity only","",1);
            var permission=new ApplicationPermission("identity-only","Identity only","",Set.of("identity"),List.of(),List.of(
                new ApplicationGroupPolicy.ManagementGrant(ApplicationGroupPolicy.ManagementDomain.IDENTITY,Set.of(ApplicationGroupPolicy.ManagementAction.MANAGE))),"",true,1);
            var application=current.applications().getFirst();var permissions=new java.util.ArrayList<>(application.permissions());permissions.add(permission);
            var scopes=new java.util.ArrayList<>(current.scopes());scopes.add(scope);
            return new Snapshot(current.group(),scopes,List.of(new Application(application.clientId(),application.description(),application.audiences(),permissions,2)),
                List.of(new UserGrant(ISSUER,"first",Set.of("identity"),2),new UserGrant(ISSUER,"second",Set.of("admin"),1)));
        });
        var before=store.readAll();
        var rejected=assertThrows(io.kestra.extensions.rbac.management.UserDirectoryAccess.DirectoryFailure.class,()->change("first","second",true));
        assertEquals(io.kestra.extensions.rbac.management.UserDirectoryAccess.Failure.LAST_ADMIN,rejected.failure());
        assertEquals(before,store.readAll());assertEquals(2,activeAccounts());assertEquals(0,auditCount());
    }
    @Test void concurrentMutualDeletionRechecksActorAfterSecurityLock()throws Exception {concurrent(true);}
    @Test void concurrentMutualDeactivationRechecksActorAfterSecurityLock()throws Exception {concurrent(false);}
    private void concurrent(boolean delete)throws Exception {
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var tasks=List.of(executor.submit(()->attempt("first","second",delete,ready,start)),executor.submit(()->attempt("second","first",delete,ready,start)));
            assertTrue(ready.await(5,TimeUnit.SECONDS));start.countDown();int successes=0;
            for(var task:tasks)if(task.get(20,TimeUnit.SECONDS))successes++;
            assertEquals(1,successes);assertEquals(1,activeAccounts());assertEquals(2,auditCount());
            assertEquals(delete?2:4,store.readAll().stream().mapToLong(snapshot->snapshot.userGrants().size()).sum());
        }
    }
    private boolean attempt(String actor,String target,boolean delete,CountDownLatch ready,CountDownLatch start)throws Exception {
        var identity=authentication(actor);ready.countDown();assertTrue(start.await(5,TimeUnit.SECONDS));
        try {change(identity,target,delete);return true;}
        catch(io.kestra.extensions.rbac.management.UserDirectoryAccess.DirectoryFailure rejected) {
            assertTrue(java.util.Set.of(io.kestra.extensions.rbac.management.UserDirectoryAccess.Failure.FORBIDDEN,
                io.kestra.extensions.rbac.management.UserDirectoryAccess.Failure.LAST_ADMIN,
                io.kestra.extensions.rbac.management.UserDirectoryAccess.Failure.CONFLICT).contains(rejected.failure()),"Unexpected concurrent failure: "+rejected.failure());
            return false;
        }
    }
}
