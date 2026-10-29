package io.kestra.extensions.rbac.applicationgroup;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static io.kestra.extensions.rbac.applicationgroup.ApplicationGroupDirectory.*;
import static io.kestra.extensions.rbac.applicationgroup.ApplicationGroupChanges.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.kestra.extensions.rbac.applicationgroup.TestApplicationFactory.registerApplication;
import static io.kestra.extensions.rbac.applicationgroup.TestApplicationFactory.applicationSupport;

class ApplicationGroupChangesTest {
    private static final String ISSUER="https://issuer.example";

    private static Snapshot empty() {
        return new Snapshot(new Group("group","Group",true,1,1),List.of(),List.of(),List.of());
    }

    private static Snapshot scopes() {
        return createScope(createScope(empty(),"read","Reader","Can read"),"write","Writer","Can write");
    }

    @Test void scopeMetadataCannotRenameKeyOrGrantApplicationPermissions() {
        var current=scopes();var updated=scopeMetadata(current,"read",1,"Observer","Updated description");
        var scope=updated.scopes().getFirst();
        assertEquals("read",scope.key());assertEquals("Observer",scope.name());assertEquals(2,scope.revision());
        assertEquals("Reader",current.scopes().getFirst().name());
        assertTrue(updated.applications().isEmpty());assertTrue(updated.userGrants().isEmpty());
        assertThrows(Conflict.class,()->scopeMetadata(updated,"read",1,"Other","Stale"));
        assertThrows(NotFound.class,()->scopeMetadata(updated,"renamed",2,"Observer","Invalid key"));
        assertEquals(UniquenessRejected.Failure.SCOPE_NAME_EXISTS,assertThrows(UniquenessRejected.class,()->scopeMetadata(updated,"read",2,"Writer","Duplicate")).failure());
        assertEquals("Observer",scopeMetadata(updated,"read",2,"Observer","Same name allowed").scopes().getFirst().name());
    }

    @Test void creatingScopeOnlyRegistersRoleMetadata() {
        var updated=createScope(empty(),"read","Reader","Read role");
        assertEquals(1,updated.scopes().size());assertTrue(updated.applications().isEmpty());assertTrue(updated.userGrants().isEmpty());
        assertThrows(Conflict.class,()->createScope(updated,"read","Other","Duplicate key"));
        assertEquals(UniquenessRejected.Failure.SCOPE_NAME_EXISTS,assertThrows(UniquenessRejected.class,()->createScope(updated,"another","Reader","Duplicate name")).failure());
        assertThrows(IllegalArgumentException.class,()->createScope(updated,"OLD_KEY","Other","Invalid stable key"));
    }

    @Test void scopeDeletionRequiresEveryApplicationAndUserToReleaseIt() {
        var supported=registerApplication(scopes(),"client-a",true,Set.of("resource-a"),Set.of("read"));
        var both=registerApplication(supported,"client-b",false,Set.of(),Set.of("read"));
        var assigned=grantUser(both,ISSUER,"subject",Set.of("read"));
        assertThrows(ScopeInUse.class,()->deleteScope(assigned,"read",1));
        var firstRemoved=applicationSupport(assigned,"client-a",1,true,Set.of("resource-a"),Set.of());
        assertThrows(ScopeInUse.class,()->deleteScope(firstRemoved,"read",1));
        var allRemoved=removeApplication(firstRemoved,"client-b",1);
        assertThrows(ScopeInUse.class,()->deleteScope(allRemoved,"read",1));
        var released=userScopes(allRemoved,ISSUER,"subject",1,Set.of());
        var deleted=deleteScope(released,"read",1);
        assertEquals(List.of("write"),deleted.scopes().stream().map(Scope::key).toList());
        assertEquals(1,deleted.applications().size());assertEquals(1,deleted.userGrants().size());
    }

    @Test void applicationSupportAndUserGrantsRemainIndependent() {
        var supported=registerApplication(scopes(),"client",true,Set.of("resource"),Set.of("read"));
        var assigned=grantUser(supported,ISSUER,"subject",Set.of("read","write"));
        assertEquals(Set.of("read"),assigned.eligibleScopeKeys("client",ISSUER,"subject"));
        var expanded=applicationSupport(assigned,"client",1,true,Set.of("resource"),Set.of("read","write"));
        assertEquals(Set.of("read","write"),expanded.eligibleScopeKeys("client",ISSUER,"subject"));
        var revoked=userScopes(expanded,ISSUER,"subject",1,Set.of("write"));
        assertEquals(Set.of("write"),revoked.eligibleScopeKeys("client",ISSUER,"subject"));
        assertEquals(expanded.applications(),revoked.applications());
        assertEquals(Set.of(),removeApplication(revoked,"client",2).eligibleScopeKeys("client",ISSUER,"subject"));
        assertEquals(Set.of(),revokeUser(revoked,ISSUER,"subject",2).eligibleScopeKeys("client",ISSUER,"subject"));
    }

    @Test void userTargetsUseExactIssuerAndSubjectAndRevisions() {
        var current=grantUser(scopes(),ISSUER,"subject",Set.of("read"));
        var another=grantUser(current,ISSUER+"/other","subject",Set.of("write"));
        assertEquals(2,another.userGrants().size());
        assertThrows(Conflict.class,()->grantUser(current,ISSUER,"subject",Set.of("write")));
        assertThrows(Conflict.class,()->userScopes(current,ISSUER,"subject",2,Set.of()));
        assertThrows(Conflict.class,()->revokeUser(current,ISSUER,"subject",0));
        assertThrows(NotFound.class,()->revokeUser(current,ISSUER,"other",1));
        assertThrows(IllegalArgumentException.class,()->userScopes(current,ISSUER,"subject",1,Set.of("undefined")));
    }

    @Test void applicationTargetsAndSupportedScopesAreValidated() {
        var current=registerApplication(scopes(),"client",true,Set.of("resource"),Set.of("read"));
        assertThrows(Conflict.class,()->registerApplication(current,"client",false,Set.of(),Set.of()));
        assertThrows(Conflict.class,()->applicationSupport(current,"client",0,true,Set.of("resource"),Set.of("read")));
        assertThrows(Conflict.class,()->removeApplication(current,"client",2));
        assertThrows(NotFound.class,()->removeApplication(current,"other",1));
        assertThrows(IllegalArgumentException.class,()->applicationSupport(current,"client",1,true,Set.of("resource"),Set.of("undefined")));
        assertThrows(IllegalArgumentException.class,()->applicationSupport(current,"client",1,true,Set.of(),Set.of("read")));
        assertTrue(applicationSupport(current,"client",1,false,Set.of("resource"),Set.of("read"))
            .eligibleScopeKeys("client",ISSUER,"subject").isEmpty());
    }

    @Test void groupMetadataPreservesIdentityAndCannotCreateScopesOrMembership() {
        var current=scopes();var changed=groupMetadata(current,"Renamed",false);
        assertEquals("group",changed.group().id());assertEquals(1,changed.group().revision());
        assertEquals(1,changed.group().authorizationVersion());assertEquals(current.scopes(),changed.scopes());
        assertTrue(changed.applications().isEmpty());assertTrue(changed.userGrants().isEmpty());
        assertThrows(IllegalArgumentException.class,()->groupMetadata(current," ",true));
    }
}
