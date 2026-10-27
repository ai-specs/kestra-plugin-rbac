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
        var created=actor("first",()->fixture.published.createTemporary(new TemporaryAccountCommands.Create("new-account","","")));
        assertEquals("new-account",created.subject());assertFalse(created.temporaryPassword().isBlank());assertFalse(created.toString().contains(created.temporaryPassword()));
        assertTrue(fixture.published.find("new-account").isPresent());assertEquals(0,events().total());
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
