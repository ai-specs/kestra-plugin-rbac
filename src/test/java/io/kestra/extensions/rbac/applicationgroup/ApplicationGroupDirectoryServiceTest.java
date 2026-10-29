package io.kestra.extensions.rbac.applicationgroup;

import io.kestra.extensions.rbac.store.ApplicationGroupDirectoryStore;
import io.kestra.extensions.rbac.store.ApplicationGroupPolicyStore;
import io.kestra.webserver.oidc.ApplicationGroupProtocolProjectionV1;
import io.kestra.webserver.oidc.IdentityLifecycleGuardV1;
import io.kestra.webserver.oidc.OidcProtocolStateV1;
import io.kestra.webserver.oidc.TrustedIdentityV1;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.kestra.extensions.rbac.applicationgroup.TestApplicationFactory.registerApplication;
import static io.kestra.extensions.rbac.applicationgroup.TestApplicationFactory.applicationSupport;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static io.kestra.extensions.rbac.applicationgroup.ApplicationGroupChanges.*;
import static io.kestra.extensions.rbac.applicationgroup.ApplicationGroupDirectory.*;

class ApplicationGroupDirectoryServiceTest {
    private static final String ISSUER="https://issuer.example";
    private static final Set<String> ADMIN=Set.of("manager","policy-manager");

    static final class Fixture {
        final javax.sql.DataSource source;
        final OidcProtocolStateV1 protocol=mock(OidcProtocolStateV1.class);
        final ApplicationGroupDirectoryStore store;
        final ApplicationGroupPolicyStore policies;
        final ApplicationGroupDirectoryService service;
        final Map<String,Boolean> accounts=new java.util.HashMap<>(Map.of("first",true,"second",true));
        Fixture()throws Exception {
            var jdbc=System.getenv("RBAC_STORE_TEST_JDBC");
            if(jdbc!=null && !jdbc.isBlank()) {
                var database=new org.postgresql.ds.PGSimpleDataSource();database.setURL(jdbc);database.setUser("postgres");database.setPassword("policy-test-only");
                String schema="group_service_"+java.util.UUID.randomUUID().toString().replace("-","");
                try(var connection=database.getConnection();var statement=connection.createStatement()){statement.execute("CREATE SCHEMA "+schema);}
                database.setCurrentSchema(schema);source=database;
            }else {
                var database=new JdbcDataSource();database.setURL("jdbc:h2:mem:group_service_"+java.util.UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");source=database;
            }
            try(var connection=source.getConnection()) {
                String backend=connection.getMetaData().getDatabaseProductName();
                assertEquals(jdbc!=null&&!jdbc.isBlank()?"PostgreSQL":"H2",backend);
                System.out.println("Application group service backend: "+backend);
            }
            doAnswer(call->{
                Connection connection=call.getArgument(0);
                if("PostgreSQL".equals(connection.getMetaData().getDatabaseProductName()))
                    try(var statement=connection.createStatement()){statement.execute("SELECT pg_advisory_xact_lock(1380073797)");}
                return null;
            }).when(protocol).lockSecurityState(any());
            ApplicationGroupDirectoryStore.ConnectionGuard guard=connection->{
                assertFalse(connection.getAutoCommit());assertEquals(Connection.TRANSACTION_READ_COMMITTED,connection.getTransactionIsolation());
                protocol.lockSecurityState(connection);
            };
            store=new ApplicationGroupDirectoryStore(source,guard);store.initializeSchema();
            var all=Set.of("manager","policy-manager","reader");
            var identity=new ApplicationGroupPolicy.ScopeDefinition("Identity manager",true,List.of(),List.of(new ApplicationGroupPolicy.ManagementGrant(
                ApplicationGroupPolicy.ManagementDomain.IDENTITY,Set.of(ApplicationGroupPolicy.ManagementAction.READ,ApplicationGroupPolicy.ManagementAction.MANAGE))));
            var policy=new ApplicationGroupPolicy.ScopeDefinition("Policy manager",true,List.of(),List.of(new ApplicationGroupPolicy.ManagementGrant(
                ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,Set.of(ApplicationGroupPolicy.ManagementAction.READ,ApplicationGroupPolicy.ManagementAction.MANAGE))));
            store.initializeBuiltin(new Snapshot(new Group("group","Group",true,1,1),
                List.of(new Scope("manager","Identity manager","",1),new Scope("policy-manager","Policy manager","",1),new Scope("reader","Reader","",1)),
                List.of(TestApplicationFactory.withPolicy(TestApplicationFactory.application("ui",true,Set.of("ui"),all,1),Map.of("manager",identity,"policy-manager",policy))),
                List.of(new UserGrant(ISSUER,"first",all,1),new UserGrant(ISSUER,"second",all,1))));
            policies=new ApplicationGroupPolicyStore(source,new ApplicationGroupPolicyParser(),guard);policies.initializeSchema();
            var document=new ApplicationGroupPolicy(2,Map.of("group",new ApplicationGroupPolicy.ApplicationGroup(ISSUER,Set.of("ui"),true,Map.of("manager",identity,"policy-manager",policy))));
            policies.initialize(new ApplicationGroupPolicyParser().canonicalJson(document).getBytes(java.nio.charset.StandardCharsets.UTF_8),"installer");
            when(protocol.client(any(),eq(ISSUER),eq("ui"))).thenReturn(Optional.of(new OidcProtocolStateV1.Client(ISSUER,"ui",true,Set.of("openid","kestra:application-group-scopes"),true)));
            when(protocol.account(any(),eq(ISSUER),anyString())).thenAnswer(call->{
                String subject=call.getArgument(2);Boolean active=accounts.get(subject);
                return active==null?Optional.empty():Optional.of(new OidcProtocolStateV1.Account(ISSUER,subject,active,active,0));
            });
            service=new ApplicationGroupDirectoryService(store,policies,protocol,ISSUER,"ui");
        }
        Snapshot current(){return store.readAll().getFirst();}
        TrustedIdentityV1.Identity identity(Set<String> scopes){
            var group=current().group();return new TrustedIdentityV1.Identity(1,ISSUER,"first","ui",Set.of("ui"),"main","group",Set.of("openid"),scopes,0,group.authorizationVersion(),group.revision());
        }
        Snapshot update(java.util.function.UnaryOperator<Snapshot> change) {
            long revision=current().group().revision();
            return service.transaction(connection->service.update(connection,"group",revision,change));
        }
        void guard(IdentityLifecycleGuardV1.Kind kind,String subject,String client) {
            service.transaction(connection->{service.beforeChange(connection,new IdentityLifecycleGuardV1.Change(kind,ISSUER,subject,client));return null;});
        }
    }

    @Test void protocolProjectionUsesOnlyCurrentSupportAndUserGrantsOnTheBorrowedConnection()throws Exception {
        var fixture=new Fixture();
        fixture.service.transaction(connection->{
            var request=new ApplicationGroupProtocolProjectionV1.Request(ISSUER,"main","ui","first","group");
            var result=fixture.service.currentResources(connection,request);assertTrue(result.activated());
            assertEquals(2,result.contractVersion());
            assertThrows(java.sql.SQLException.class,()->fixture.service.current(connection,request));
            assertEquals(Set.of("manager","policy-manager","reader"),result.projection().orElseThrow().eligibleScopeKeys(request));
            verify(fixture.protocol,never()).account(eq(connection),anyString(),anyString());
            assertFalse(connection.isClosed());assertFalse(connection.getAutoCommit());return null;
        });
    }

    @Test void projectionRejectsForeignIssuerWrongGroupAndDuplicateClientMembership()throws Exception {
        var fixture=new Fixture();var before=fixture.current();
        assertThrows(IllegalArgumentException.class,()->fixture.service.transaction(connection->fixture.service.currentResources(connection,
            new ApplicationGroupProtocolProjectionV1.Request("https://other.example","main","ui","first",null))));
        assertThrows(IllegalArgumentException.class,()->fixture.service.transaction(connection->fixture.service.currentResources(connection,
            new ApplicationGroupProtocolProjectionV1.Request(ISSUER,"main","ui","first","other"))));
        assertEquals(UniquenessRejected.Failure.CLIENT_ALREADY_ASSIGNED,assertThrows(UniquenessRejected.class,()->fixture.store.create(new Snapshot(new Group("other","Other",true,1,1),before.scopes(),before.applications(),List.of()))).failure());
        assertEquals(List.of(before),fixture.store.readAll());
    }

    @Test void metadataUpdateKeepsStableKeyWithoutRevokingCredentials()throws Exception {
        var fixture=new Fixture();var before=fixture.current();
        var saved=fixture.update(current->scopeMetadata(current,"reader",1,"Observer","Changed description"));
        var reader=saved.scopes().stream().filter(scope->scope.key().equals("reader")).findFirst().orElseThrow();
        assertEquals("Observer",reader.name());assertEquals(2,reader.revision());
        assertEquals(before.applications(),saved.applications());assertEquals(before.userGrants(),saved.userGrants());
        assertEquals(2,saved.group().revision());assertEquals(before.group().authorizationVersion(),saved.group().authorizationVersion());
    }

    @Test void scopeDeletionRequiresEveryApplicationAndUserToReleaseTheReference()throws Exception {
        var fixture=new Fixture();var before=fixture.current();
        assertThrows(ApplicationGroupChanges.ScopeInUse.class,()->fixture.update(current->deleteScope(current,"reader",1)));
        assertEquals(before,fixture.current());
        fixture.update(current->applicationSupport(current,"ui",1,true,Set.of("ui"),ADMIN));
        assertThrows(ApplicationGroupChanges.ScopeInUse.class,()->fixture.update(current->deleteScope(current,"reader",1)));
        fixture.update(current->userScopes(current,ISSUER,"first",1,ADMIN));
        fixture.update(current->userScopes(current,ISSUER,"second",1,ADMIN));
        var saved=fixture.update(current->deleteScope(current,"reader",1));
        assertEquals(2,saved.scopes().size());assertTrue(saved.scopes().stream().noneMatch(scope->scope.key().equals("reader")));
    }

    @Test void accountGuardPreservesTheLastReachableAdministratorAfterEarlierRevocation()throws Exception {
        var fixture=new Fixture();
        fixture.guard(IdentityLifecycleGuardV1.Kind.ACCOUNT_DEACTIVATE,"first",null);
        fixture.accounts.put("second",false);
        var before=fixture.current();
        var rejected=assertThrows(IdentityLifecycleGuardV1.Rejected.class,()->fixture.guard(IdentityLifecycleGuardV1.Kind.ACCOUNT_DEACTIVATE,"first",null));
        assertEquals(IdentityLifecycleGuardV1.Failure.LAST_ADMIN,rejected.failure());assertEquals(before,fixture.current());
    }

    @Test void identityAndPolicyAdministratorsCanBeDifferentAccountsButNeitherCanBeLost()throws Exception {
        var fixture=new Fixture();
        fixture.update(current->userScopes(current,ISSUER,"first",1,Set.of("manager")));
        fixture.update(current->userScopes(current,ISSUER,"second",1,Set.of("policy-manager")));
        for(String subject:List.of("first","second"))assertThrows(IdentityLifecycleGuardV1.Rejected.class,
            ()->fixture.guard(IdentityLifecycleGuardV1.Kind.ACCOUNT_DEACTIVATE,subject,null));
        assertThrows(IdentityLifecycleGuardV1.Rejected.class,()->fixture.guard(IdentityLifecycleGuardV1.Kind.CLIENT_DELETE,null,"ui"));
    }

    @Test void grantMutationAndLastAdministratorProtectionUseOneTransaction()throws Exception {
        var fixture=new Fixture();fixture.update(current->userScopes(current,ISSUER,"first",1,Set.of()));
        var before=fixture.current();
        assertThrows(IdentityLifecycleGuardV1.Rejected.class,()->fixture.update(current->userScopes(current,ISSUER,"second",1,Set.of())));
        assertEquals(before,fixture.current());
    }

    @Test void revokedActorCannotPassTheLifecycleGuardWithAnEarlierTrustedSnapshot()throws Exception {
        var fixture=new Fixture();var identity=fixture.identity(ADMIN);
        fixture.update(current->userScopes(current,ISSUER,"first",1,Set.of()));
        try(var mutation=ApplicationGroupMutationContext.bind(identity)) {
            var failure=assertThrows(IdentityLifecycleGuardV1.Rejected.class,()->fixture.guard(IdentityLifecycleGuardV1.Kind.PASSWORD_RESET,"second",null));
            assertEquals(IdentityLifecycleGuardV1.Failure.FORBIDDEN,failure.failure());
        }
        assertNull(ApplicationGroupMutationContext.current());
    }

    @Test void mixedUnknownScopeAndIncorrectAuthenticationVersionCannotAuthorizeMutation()throws Exception {
        var fixture=new Fixture();var mixed=fixture.identity(Set.of("manager","unknown"));
        assertThrows(IdentityLifecycleGuardV1.Rejected.class,()->fixture.service.transaction(connection->{fixture.service.verifyIdentity(connection,mixed);return null;}));
        var identity=fixture.identity(ADMIN);var stale=new TrustedIdentityV1.Identity(1,ISSUER,"first","ui",Set.of("ui"),"main","group",Set.of("openid"),ADMIN,1,identity.authorizationVersion(),identity.directoryRevision());
        assertThrows(IdentityLifecycleGuardV1.Rejected.class,()->fixture.service.transaction(connection->{fixture.service.verifyIdentity(connection,stale);return null;}));
    }

    @Test void administratorGuardUsesAllRegisteredApplicationAudiencesWithoutGivingActorAnotherAudience()throws Exception {
        var fixture=new Fixture();
        when(fixture.protocol.client(any(),eq(ISSUER),eq("api"))).thenReturn(Optional.of(new OidcProtocolStateV1.Client(ISSUER,"api",true,Set.of("openid"),false)));
        fixture.update(current->registerApplication(current,"api",true,Set.of("api-resource"),Set.of("reader")));
        var original=fixture.policies.readCurrent();var group=original.policy().applicationGroups().get("group");
        var policy=new ApplicationGroupPolicy(2,Map.of("group",new ApplicationGroupPolicy.ApplicationGroup(ISSUER,Set.of("ui","api-resource"),true,group.scopes())));
        fixture.policies.replace(original.revision(),new ApplicationGroupPolicyParser().canonicalJson(policy).getBytes(java.nio.charset.StandardCharsets.UTF_8),"test");
        var identity=fixture.identity(ADMIN);assertEquals(Set.of("ui"),identity.audiences());
        try(var mutation=ApplicationGroupMutationContext.bind(identity)) {
            fixture.guard(IdentityLifecycleGuardV1.Kind.PASSWORD_RESET,"second",null);
        }
    }

    @Test void boundariesExcludeDisabledAccountsAndNeverExposeApplicationPermissionGrants()throws Exception {
        var fixture=new Fixture();fixture.accounts.replaceAll((subject,active)->false);
        var boundary=fixture.service.boundaries().getFirst();assertEquals(Set.of(),boundary.reachableManagementScopes());
        assertTrue(boundary.applicationGroup().builtIn());
        assertEquals(3,boundary.scopes().size());assertEquals(Set.of("ui"),boundary.audiences());
        assertEquals(List.of("applicationGroupId","key","name","description","revision"),java.util.Arrays.stream(ApplicationGroupDirectoryReader.ScopeView.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList());
    }

    @Test void failureAfterDirectoryMutationRollsBackMetadataEpochAndRevision()throws Exception {
        var fixture=new Fixture();var before=fixture.current();
        assertThrows(ApplicationGroupDirectoryStore.Unavailable.class,()->fixture.service.transaction(connection->{
            fixture.service.update(connection,"group",1,current->groupMetadata(current,"Changed",true));
            throw new java.sql.SQLException("Deliberate transaction failure");
        }));
        assertEquals(before,fixture.current());
    }

    @Test void mutationContextRestoresNestedBindingsAndDoesNotPropagateToUnownedThreads()throws Exception {
        var fixture=new Fixture();var first=fixture.identity(ADMIN);var second=fixture.identity(Set.of("reader"));
        assertNull(ApplicationGroupMutationContext.current());
        try(var outer=ApplicationGroupMutationContext.bind(first)) {
            try(var inner=ApplicationGroupMutationContext.bind(second)){assertSame(second,ApplicationGroupMutationContext.current());}
            assertSame(first,ApplicationGroupMutationContext.current());
            assertNull(java.util.concurrent.CompletableFuture.supplyAsync(ApplicationGroupMutationContext::current).get());
        }
        assertNull(ApplicationGroupMutationContext.current());
    }
}
