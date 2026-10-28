package io.kestra.extensions.rbac.applicationgroup;

import io.kestra.extensions.rbac.ApplicationGroupRuntimePolicies;
import io.kestra.extensions.rbac.management.*;
import io.kestra.extensions.rbac.store.ManagementAuditStore;
import io.kestra.webserver.oidc.*;
import io.kestra.webserver.oidc.services.*;
import io.micronaut.http.HttpRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.util.List;
import java.util.Set;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("postgres-lifecycle")
class PublishedManagementPostgresTest {
    @TempDir java.nio.file.Path temporary;
    AccountLifecyclePostgresTest fixture;
    @BeforeEach void initialize()throws Exception {
        fixture=new AccountLifecyclePostgresTest();fixture.temporary=temporary;fixture.initialize();
    }
    <T> T actor(String subject,java.util.function.Supplier<T> work) {
        var request=HttpRequest.GET("/api/v1/main/extensions/rbac").setAttribute(io.micronaut.security.filters.SecurityFilter.AUTHENTICATION,fixture.authentication(subject));
        try(var scope=io.micronaut.core.propagation.PropagatedContext.getOrEmpty().plus(new io.kestra.core.repositories.AuthorizationInvocationContext(request,false)).propagate()) {return work.get();}
    }
    ManagementAuditStore.Page events(){return fixture.store.transaction(connection->fixture.audit.readIssuer(connection,AccountLifecyclePostgresTest.ISSUER,1,100));}

    ManagementController lifecycleController() {
        var policies=new ApplicationGroupRuntimePolicies(fixture.service,fixture.trusted);
        return new ManagementController(policies,AccountLifecyclePostgresTest.provider(()->fixture.published),
            AccountLifecyclePostgresTest.provider(()->new ApplicationGroupDirectoryManagement(fixture.service,policies)));
    }

    @Test void concurrentControllerMutualDeletionReturnsOne204AndOneSafeRejection()throws Exception {controllerRace(true);}
    @Test void concurrentControllerMutualDeactivationReturnsOne200AndOneSafeRejection()throws Exception {controllerRace(false);}

    void controllerRace(boolean delete)throws Exception {
        var controller=lifecycleController();
        var ready=new java.util.concurrent.CountDownLatch(2);var start=new java.util.concurrent.CountDownLatch(1);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var futures=new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for(String subject:List.of("first","second")) {
                String target=subject.equals("first")?"second":"first";
                var authentication=fixture.authentication(subject);long revision=fixture.published.find(target).orElseThrow().revision();
                futures.add(executor.submit(()->{
                    var request=HttpRequest.DELETE("/api/v1/main/extensions/rbac/users/detail").setAttribute(io.micronaut.security.filters.SecurityFilter.AUTHENTICATION,authentication);
                    try(var scope=io.micronaut.core.propagation.PropagatedContext.getOrEmpty().plus(new io.kestra.core.repositories.AuthorizationInvocationContext(request,false)).propagate()) {
                        ready.countDown();assertTrue(start.await(5,java.util.concurrent.TimeUnit.SECONDS));
                        return delete?controller.deleteUser("main",authentication,target,"\""+revision+"\"").code():
                            controller.state("main",authentication,"\""+revision+"\"",("{\"subject\":\""+target+"\",\"active\":false}").getBytes(java.nio.charset.StandardCharsets.UTF_8)).code();
                    }
                }));
            }
            assertTrue(ready.await(5,java.util.concurrent.TimeUnit.SECONDS));start.countDown();
            var statuses=new java.util.ArrayList<Integer>();for(var future:futures)statuses.add(future.get(20,java.util.concurrent.TimeUnit.SECONDS));
            System.out.println("Published controller "+(delete?"mutual-delete":"mutual-deactivate")+" statuses: "+statuses);
            int success=delete?204:200;assertEquals(1,statuses.stream().filter(status->status==success).count());
            assertTrue(statuses.stream().allMatch(status->status==success||Set.of(401,403,409,412).contains(status)),"Unexpected controller race statuses: "+statuses);
            assertEquals(1,fixture.activeAccounts());assertEquals(2,events().total());
            assertEquals(delete?2:4,fixture.store.readAll().stream().mapToLong(snapshot->snapshot.userGrants().size()).sum());
        }
    }

    @Test void controllerDependencyFailuresRemain503AndLeaveAllLifecycleStateUnchanged()throws Exception {
        var controller=lifecycleController();var policies=mock(ApplicationGroupRuntimePolicies.class);
        fixture.participant=new PublishedIdentityLifecycleParticipant(fixture.service,fixture.store,policies,
            AccountLifecyclePostgresTest.provider(()->fixture.trusted),fixture.audit);
        var before=fixture.store.readAll();var accounts=fixture.published.list(1,100);var auditBefore=events();var storageBefore=lifecycleStorageState();
        for(var failure:List.of(ApplicationGroupRuntimePolicies.Failure.POLICY_UNAVAILABLE,ApplicationGroupRuntimePolicies.Failure.DIRECTORY_UNAVAILABLE)) {
            doThrow(new ApplicationGroupRuntimePolicies.Rejected(failure)).when(policies).open(any(java.sql.Connection.class),any(),eq("main"));
            assertEquals(503,actor("first",()->controller.deleteUser("main",fixture.authentication("first"),"second","\"0\"")).code());
            assertEquals(before,fixture.store.readAll());assertEquals(accounts,fixture.published.list(1,100));assertEquals(auditBefore,events());
            assertEquals(storageBefore,lifecycleStorageState());
        }
        for(var failure:List.of(ApplicationGroupRuntimePolicies.Failure.INVALID_IDENTITY,ApplicationGroupRuntimePolicies.Failure.FORBIDDEN,ApplicationGroupRuntimePolicies.Failure.LAST_ADMIN)) {
            doThrow(new ApplicationGroupRuntimePolicies.Rejected(failure)).when(policies).open(any(java.sql.Connection.class),any(),eq("main"));
            assertEquals(failure==ApplicationGroupRuntimePolicies.Failure.LAST_ADMIN?409:403,actor("first",()->controller.deleteUser("main",fixture.authentication("first"),"second","\"0\"")).code());
            assertEquals(storageBefore,lifecycleStorageState());
        }
        doThrow(new java.sql.SQLException("Injected policy query failure")).when(policies).open(any(java.sql.Connection.class),any(),eq("main"));
        assertEquals(503,actor("first",()->controller.deleteUser("main",fixture.authentication("first"),"second","\"0\"")).code());
        assertEquals(before,fixture.store.readAll());assertEquals(accounts,fixture.published.list(1,100));assertEquals(auditBefore,events());
        assertEquals(storageBefore,lifecycleStorageState());
    }

    String lifecycleStorageState()throws Exception {
        var rows=new java.util.ArrayList<String>();
        try(var connection=fixture.source.getConnection()) {
            for(String table:List.of("oidc_user","oidc_user_auth_method","oidc_authorization_code","oidc_token","oidc_password_change","rbac_application_group_state","rbac_application_group_invalidation","rbac_management_audit")) {
                try(var statement=connection.createStatement();var result=statement.executeQuery("SELECT row_to_json(record)::text FROM "+table+" record ORDER BY row_to_json(record)::text")) {
                    while(result.next())rows.add(table+":"+result.getString(1));
                }
            }
        }
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(String.join("\n",rows).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test void passwordResetAndActorDeletionRetainOriginalCommittedAuditSubjects() {
        actor("first",()->fixture.published.resetPassword("second",fixture.published.find("second").orElseThrow().revision(),"new-isolated-password"));
        var original=events().items();assertEquals(2,original.size());assertTrue(original.stream().allMatch(event->event.operation()==ManagementAuditStore.Operation.PASSWORD_RESET && event.actorSubject().equals("first") && event.targetSubject().equals("second")));
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        new OidcUserService(fixture.source,mapper).create(new OidcUserService.CreateUserRequest("third","","","third-isolated-password",List.of()));
        var current=fixture.store.readAll().stream().filter(snapshot->snapshot.group().id().equals("main")).findFirst().orElseThrow();
        fixture.store.update("main",current.group().revision(),snapshot->ApplicationGroupChanges.grantUser(snapshot,AccountLifecyclePostgresTest.ISSUER,"third",Set.of("admin")));
        actor("third",()->{fixture.published.delete("first",fixture.published.find("first").orElseThrow().revision());return null;});
        assertTrue(fixture.published.find("first").isEmpty());assertTrue(events().items().containsAll(original));assertEquals(4,events().total());
    }

    @Test void missingParticipantRefusesMutationAndLeavesAuditAndGrantsUnchanged() {
        var participant=fixture.participant;fixture.participant=null;var before=fixture.store.readAll();
        var failure=assertThrows(UserDirectoryAccess.DirectoryFailure.class,()->fixture.change("first","second",true));
        assertEquals(UserDirectoryAccess.Failure.UNAVAILABLE,failure.failure());assertEquals(before,fixture.store.readAll());assertEquals(0,events().total());
        assertTrue(fixture.published.find("second").isPresent());
        fixture.participant=participant;
        fixture.store.transaction(connection->{try(var statement=connection.createStatement()){statement.execute("DROP TABLE rbac_management_audit");}return null;});
        assertThrows(UserDirectoryAccess.DirectoryFailure.class,()->fixture.change("first","second",true));
        assertEquals(before,fixture.store.readAll());assertTrue(fixture.published.find("second").isPresent());
        fixture.store.transaction(connection->{ManagementAuditStore.initializeSchema(connection);return null;});assertEquals(0,events().total());
    }

    @Test void issuerAuditReaderSupportsCrossGroupAndRetiredHistoryButManageDoesNotImplyRead() {
        actor("first",()->fixture.published.resetPassword("second",fixture.published.find("second").orElseThrow().revision(),"new-isolated-password"));
        var other=fixture.store.readAll().stream().filter(snapshot->snapshot.group().id().equals("other")).findFirst().orElseThrow();
        var empty=fixture.store.update("other",other.group().revision(),snapshot->new ApplicationGroupDirectory.Snapshot(snapshot.group(),List.of(),List.of(),List.of()));fixture.store.delete("other",empty.group().revision());
        var controller=new ManagementAuditController(new ApplicationGroupRuntimePolicies(fixture.service,fixture.trusted),fixture.service,fixture.audit);
        var authentication=fixture.authentication("first");
        var allowed=actor("first",()->controller.list("main",authentication,1,25));
        assertEquals(200,allowed.code());assertEquals(2,((ManagementAuditStore.Page)allowed.body()).total());
        var current=fixture.store.readAll().getFirst();var app=current.applications().getFirst();
        var permissions=app.permissions().stream().map(permission->permission.replace(permission.name(),permission.description(),permission.scopeKeys(),permission.grants(),
            permission.managementGrants().stream().map(grant->new ApplicationGroupPolicy.ManagementGrant(grant.domain(),Set.of(ApplicationGroupPolicy.ManagementAction.MANAGE),grant.applicationGroupIds())).toList(),permission.details(),permission.enabled())).toList();
        fixture.store.update("main",current.group().revision(),snapshot->ApplicationGroupChanges.permissions(snapshot,"ui",app.revision(),permissions));
        assertEquals(403,actor("first",()->controller.list("main",fixture.authentication("first"),1,25)).code());
        assertEquals(404,controller.list("foreign",authentication,1,25).code());assertEquals(401,controller.list("main",null,1,25).code());
    }

    @Test void emptyResourceAudiencesUseV2WithoutInventingClientAudience() {
        var current=fixture.store.readAll().stream().filter(snapshot->snapshot.group().id().equals("main")).findFirst().orElseThrow();
        fixture.store.update("main",current.group().revision(),snapshot->ApplicationGroupChanges.applicationMetadata(snapshot,"ui",1,"",Set.of()));
        fixture.store.transaction(connection->{
            var result=fixture.service.currentResources(connection,new ApplicationGroupProtocolProjectionV1.Request(AccountLifecyclePostgresTest.ISSUER,"main","ui","first","main"));
            assertEquals(2,result.contractVersion());assertTrue(result.projection().orElseThrow().resourceAudiences().isEmpty());return null;
        });
        try {actor("first",()->fixture.published.resetPassword("second",fixture.published.find("second").orElseThrow().revision(),"new-isolated-password"));}
        catch(RuntimeException failure) {fixture.diagnostics.forEach(Throwable::printStackTrace);throw failure;}
        assertEquals(2,events().total());
    }

    @Test void credentialControllerReturnsExactStatusAndRejectsLoginRotationMissingRevisionAndUnexpectedBody() {
        var config=new OidcConfiguration();config.setIssuer(AccountLifecyclePostgresTest.ISSUER);config.setMcpScopes(List.of());
        var participants=new OidcLifecycleParticipants(AccountLifecyclePostgresTest.provider(()->fixture.participant),AccountLifecyclePostgresTest.provider(()->fixture.trusted),config);
        var host=new OidcClientCredentials(fixture.ownedSource,fixture.protocol,config,participants);
        var initial=host.status("ui");System.out.println("Published credential status: revision="+initial.credentialRevision()+", type="+initial.clientType()+", mode="+initial.rotationMode());
        var adapter=new PublishedClientCredentials(AccountLifecyclePostgresTest.provider(()->host));
        var controller=new ClientCredentialController(new ApplicationGroupRuntimePolicies(fixture.service,fixture.trusted),fixture.service,AccountLifecyclePostgresTest.provider(()->adapter));
        String key="~dWk";
        actor("first",()->{
            var status=controller.status("main","main",key,fixture.authentication("first"));assertEquals(200,status.code());
            var fields=(Map<?,?>)status.body();assertEquals(Set.of("clientId","clientType","secretSet","maskedHint","credentialRevision","rotationMode"),fields.keySet());
            assertEquals("deployment-maintenance",fields.get("rotationMode"));assertEquals("no-store",status.header("Cache-Control"));
            String match="\""+fields.get("credentialRevision")+"\"";
            assertEquals(428,controller.rotate("main","main",key,fixture.authentication("first"),null,null).code());
            assertEquals(400,controller.rotate("main","main",key,fixture.authentication("first"),match,new byte[]{1}).code());
            var denied=controller.rotate("main","main",key,fixture.authentication("first"),match,null);assertEquals(409,denied.code());
            assertEquals("maintenance_required",((Map<?,?>)denied.body()).get("code"));return null;
        });
        assertEquals(0,events().total());
    }

    @Test void ordinaryRotationUsesPublishedAdapterAndAuditsNoSecretOrHint() {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();var clients=new OidcClientService(fixture.source,mapper,new HashedOidcClientSecretCodec());
        clients.create("ordinary","old-isolated-secret",List.of("https://issuer.example/ordinary"),List.of("authorization_code"),List.of("openid"));clients.updateActive("ordinary",true);
        var current=fixture.store.readAll().stream().filter(snapshot->snapshot.group().id().equals("main")).findFirst().orElseThrow();
        fixture.store.update("main",current.group().revision(),snapshot->ApplicationGroupChanges.registerApplication(snapshot,"ordinary","",Set.of()));
        var config=new OidcConfiguration();config.setIssuer(AccountLifecyclePostgresTest.ISSUER);config.setMcpScopes(List.of());
        var participants=new OidcLifecycleParticipants(AccountLifecyclePostgresTest.provider(()->fixture.participant),AccountLifecyclePostgresTest.provider(()->fixture.trusted),config);
        var host=new OidcClientCredentials(fixture.ownedSource,fixture.protocol,config,participants);
        var adapter=new PublishedClientCredentials(AccountLifecyclePostgresTest.provider(()->host));var target=new ClientCredentialManagement.Target("main","ordinary");
        var initial=adapter.state(target);assertEquals(ClientCredentialManagement.RotationMode.ONLINE,initial.rotationMode());
        var receipt=actor("first",()->adapter.rotate(target,initial.credentialRevision()));assertEquals(initial.credentialRevision()+1,receipt.credentialRevision());
        var status=adapter.state(target);assertEquals(receipt.credentialRevision(),status.credentialRevision());assertNotNull(status.maskedHint());
        assertTrue(clients.authenticate("ordinary",receipt.clientSecret()));assertFalse(clients.authenticate("ordinary","old-isolated-secret"));
        var rows=events().items();assertEquals(1,rows.size());assertEquals(ManagementAuditStore.Operation.CLIENT_SECRET_ROTATE,rows.getFirst().operation());
        var serialized=rows.toString();assertFalse(serialized.contains(receipt.clientSecret()));assertFalse(serialized.contains(status.maskedHint()));assertFalse(serialized.contains("old-isolated-secret"));
        assertThrows(ApplicationDirectoryAccess.DirectoryFailure.class,()->actor("first",()->adapter.rotate(target,initial.credentialRevision())));assertEquals(1,events().total());
    }

    @Test void temporaryUserUsesPublishedV2AndDoesNotFabricateCreateAudit() {
        var directoryBefore=fixture.store.readAll();
        var created=actor("first",()->fixture.published.createTemporary(new TemporaryAccountCommands.Create("new-account","","")));
        assertEquals("new-account",created.subject());assertFalse(created.temporaryPassword().isBlank());assertFalse(created.toString().contains(created.temporaryPassword()));
        assertTrue(fixture.published.find("new-account").isPresent());assertEquals(0,events().total());
        var initial=fixture.published.find("new-account").orElseThrow();
        assertEquals("",initial.name());assertEquals("",initial.email());assertEquals(directoryBefore,fixture.store.readAll());
        var populated=actor("first",()->fixture.published.update(created.subject(),initial.revision(),"Profile Name","profile@example.invalid"));
        assertEquals(initial.revision()+1,populated.revision());
        var populatedRead=fixture.published.find(created.subject()).orElseThrow();
        assertEquals("Profile Name",populatedRead.name());assertEquals("profile@example.invalid",populatedRead.email());
        var cleared=actor("first",()->fixture.published.update(created.subject(),populatedRead.revision(),"",""));
        assertEquals(populatedRead.revision()+1,cleared.revision());
        var clearedRead=fixture.published.find(created.subject()).orElseThrow();
        assertEquals("",clearedRead.name());assertEquals("",clearedRead.email());
        assertEquals(initial.subject(),clearedRead.subject());assertEquals(initial.active(),clearedRead.active());
        assertEquals(directoryBefore,fixture.store.readAll());assertEquals(0,events().total());
    }

    @Test void issuerWideDirectoryCrudMissingTargetsAndZeroAuthorizationUseRealTransactions() {
        actor("first",()->fixture.published.createTemporary(new TemporaryAccountCommands.Create("new-account","","")));
        var policies=new ApplicationGroupRuntimePolicies(fixture.service,fixture.trusted);
        var management=new ApplicationGroupDirectoryManagement(fixture.service,policies);
        var controller=new ManagementController(policies,AccountLifecyclePostgresTest.provider(()->fixture.published),AccountLifecyclePostgresTest.provider(()->management));
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var before=fixture.store.readAll();
        actor("first",()->{
            var authentication=fixture.authentication("first");
            assertEquals(404,controller.applicationGroup("main","diagnostic-absent",authentication).code());
            assertEquals(404,controller.deleteApplicationGroup("main","diagnostic-absent",authentication,"\"1\"").code());
            assertEquals(403,controller.capabilities("main",authentication,"diagnostic-absent").code());
            return null;
        });
        assertEquals(before,fixture.store.readAll());
        actor("new-account",()->{
            for(String target:List.of("main","diagnostic-absent")) {
                assertEquals(403,controller.applicationGroup("main",target,fixture.authentication("new-account")).code());
                assertEquals(403,controller.deleteApplicationGroup("main",target,fixture.authentication("new-account"),"\"1\"").code());
            }
            return null;
        });
        assertEquals(before,fixture.store.readAll());
        actor("first",()->{
            var authentication=fixture.authentication("first");
            var created=controller.createApplicationGroup("main",authentication,"{\"id\":\"diagnostic-created\",\"tenant\":\"main\",\"displayName\":\"Diagnostic\",\"description\":\"\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(200,created.code());
            long revision=mapper.valueToTree(created.body()).path("revision").longValue();
            assertEquals(200,controller.applicationGroup("main","diagnostic-created",authentication).code());
            var updated=controller.updateApplicationGroup("main","diagnostic-created",authentication,"\""+revision+"\"","{\"displayName\":\"Diagnostic Changed\",\"description\":\"saved\",\"active\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(200,updated.code());
            revision=mapper.valueToTree(updated.body()).path("revision").longValue();
            var saved=controller.applicationGroup("main","diagnostic-created",authentication);
            assertEquals("Diagnostic Changed",mapper.valueToTree(saved.body()).path("displayName").textValue());
            assertEquals(200,controller.deleteApplicationGroup("main","diagnostic-created",authentication,"\""+revision+"\"").code());
            var afterDelete=fixture.store.readAll();
            assertEquals(404,controller.applicationGroup("main","diagnostic-created",authentication).code());
            assertEquals(404,controller.updateApplicationGroup("main","diagnostic-created",authentication,"\""+revision+"\"","{\"displayName\":\"No write\",\"description\":\"\",\"active\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8)).code());
            assertEquals(404,controller.deleteApplicationGroup("main","diagnostic-created",authentication,"\""+revision+"\"").code());
            assertEquals(afterDelete,fixture.store.readAll());
            return null;
        });
        assertEquals(before,fixture.store.readAll());assertEquals(0,events().total());
    }

    void firstIdentityActions(Set<ApplicationGroupPolicy.ManagementAction> actions) {
        var current=fixture.store.readAll().stream().filter(snapshot->snapshot.group().id().equals("main")).findFirst().orElseThrow();
        var app=current.applications().getFirst();var extra=new ApplicationPermission("restricted","Restricted","",Set.of("restricted"),List.of(),List.of(
            new ApplicationGroupPolicy.ManagementGrant(ApplicationGroupPolicy.ManagementDomain.IDENTITY,actions)),"",true,1);
        var permissions=new java.util.ArrayList<>(app.permissions());permissions.add(extra);
        var scopes=new java.util.ArrayList<>(current.scopes());scopes.add(new ApplicationGroupDirectory.Scope("restricted","Restricted","",1));
        fixture.store.update("main",current.group().revision(),snapshot->new ApplicationGroupDirectory.Snapshot(snapshot.group(),scopes,
            snapshot.applications().stream().map(application->application.clientId().equals(app.clientId())?new ApplicationGroupDirectory.Application(app.clientId(),app.description(),app.audiences(),permissions,app.revision()+1):application).toList(),
            snapshot.userGrants().stream().map(grant->grant.subject().equals("first")?new ApplicationGroupDirectory.UserGrant(grant.issuer(),grant.subject(),Set.of("restricted"),grant.revision()+1):grant).toList()));
    }

    @Test void readOnlyAuditAndCredentialStatusNeverGrantManageAndForeignIssuerCannotRead() {
        actor("second",()->fixture.published.resetPassword("first",fixture.published.find("first").orElseThrow().revision(),"reset-before-read-only"));
        firstIdentityActions(Set.of(ApplicationGroupPolicy.ManagementAction.READ));
        var controller=new ManagementAuditController(new ApplicationGroupRuntimePolicies(fixture.service,fixture.trusted),fixture.service,fixture.audit);
        assertEquals(200,actor("first",()->controller.list("main",fixture.authentication("first"),1,25)).code());
        var auditBefore=events();
        assertThrows(UserDirectoryAccess.DirectoryFailure.class,()->actor("first",()->fixture.published.resetPassword("second",fixture.published.find("second").orElseThrow().revision(),"denied-read-only-password")));
        assertEquals(auditBefore,events());
        var valid=fixture.authentication("first");var attributes=new java.util.HashMap<>(valid.getAttributes());attributes.put("iss","https://foreign.example");
        var foreign=io.micronaut.security.authentication.Authentication.build("first",List.of(),attributes);
        var request=HttpRequest.GET("/api/v1/main/extensions/rbac/audit").setAttribute(io.micronaut.security.filters.SecurityFilter.AUTHENTICATION,foreign);
        try(var scope=io.micronaut.core.propagation.PropagatedContext.getOrEmpty().plus(new io.kestra.core.repositories.AuthorizationInvocationContext(request,false)).propagate()) {
            var denied=controller.list("main",foreign,1,25);assertNotEquals(200,denied.code());assertFalse(denied.body() instanceof ManagementAuditStore.Page);
        }
    }

    @Test void manageOnlyOrdinaryRotationReturnsOneTimeReceiptWithoutReadAccess() {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();var clients=new OidcClientService(fixture.source,mapper,new HashedOidcClientSecretCodec());
        clients.create("ordinary","old-isolated-secret",List.of("https://issuer.example/ordinary"),List.of("authorization_code"),List.of("openid"));clients.updateActive("ordinary",true);
        var current=fixture.store.readAll().stream().filter(snapshot->snapshot.group().id().equals("main")).findFirst().orElseThrow();
        fixture.store.update("main",current.group().revision(),snapshot->ApplicationGroupChanges.registerApplication(snapshot,"ordinary","",Set.of()));
        firstIdentityActions(Set.of(ApplicationGroupPolicy.ManagementAction.MANAGE));
        var config=new OidcConfiguration();config.setIssuer(AccountLifecyclePostgresTest.ISSUER);config.setMcpScopes(List.of());
        var participants=new OidcLifecycleParticipants(AccountLifecyclePostgresTest.provider(()->fixture.participant),AccountLifecyclePostgresTest.provider(()->fixture.trusted),config);
        var adapter=new PublishedClientCredentials(AccountLifecyclePostgresTest.provider(()->new OidcClientCredentials(fixture.ownedSource,fixture.protocol,config,participants)));
        var target=new ClientCredentialManagement.Target("main","ordinary");long revision=adapter.state(target).credentialRevision();
        var controller=new ClientCredentialController(new ApplicationGroupRuntimePolicies(fixture.service,fixture.trusted),fixture.service,AccountLifecyclePostgresTest.provider(()->adapter));
        actor("first",()->{
            var authentication=fixture.authentication("first");assertEquals(403,controller.status("main","main","~b3JkaW5hcnk",authentication).code());
            var result=controller.rotate("main","main","~b3JkaW5hcnk",authentication,"\""+revision+"\"",null);
            assertEquals(200,result.code());assertEquals("no-store",result.header("Cache-Control"));
            var receipt=(ClientCredentialManagement.Rotation)result.body();assertEquals(revision+1,receipt.credentialRevision());assertTrue(clients.authenticate("ordinary",receipt.clientSecret()));
            assertEquals("\""+receipt.credentialRevision()+"\"",result.header("ETag"));return null;
        });
        assertEquals(1,events().total());
    }
}
