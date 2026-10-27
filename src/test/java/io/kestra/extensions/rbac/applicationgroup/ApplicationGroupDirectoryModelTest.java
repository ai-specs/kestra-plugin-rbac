package io.kestra.extensions.rbac.applicationgroup;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.kestra.extensions.rbac.applicationgroup.ApplicationGroupDirectory.*;

class ApplicationGroupDirectoryModelTest {
    private final Group group=new Group("group","应用组",true,1,97);
    private final Scope reader=new Scope("reader","读取者","Read resources",1);
    private final Scope writer=new Scope("writer","写入者","Write resources",1);
    private final Scope auditor=new Scope("auditor","审计者","Audit resources",1);

    @Test void roleMetadataHasStableKeyAndCannotContainApplicationRuntimeGrants() {
        var changed=reader.withMetadata("管理员","All resources");
        assertEquals(reader.key(),changed.key());assertEquals(2,changed.revision());assertEquals("管理员",changed.name());
        assertEquals(List.of("key","name","description","revision"),java.util.Arrays.stream(Scope.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList());
        for(String key:List.of("","Reader","role_key","a:b","a".repeat(65)))assertThrows(IllegalArgumentException.class,()->new Scope(key,"Role","",1));
        assertThrows(IllegalArgumentException.class,()->new Scope("reader"," ","",1));
        assertThrows(ArithmeticException.class,()->new Scope("reader","Role","",Long.MAX_VALUE).withMetadata("Changed",""));
    }

    @Test void eligibleScopesAreOnlyApprovedApplicationSupportIntersectedWithUserGrants() {
        var app=io.kestra.extensions.rbac.applicationgroup.TestApplicationFactory.application("current-app",true,Set.of("current-app"),Set.of("reader","writer"),1);
        var grant=new UserGrant("https://issuer.example","user",Set.of("reader","auditor"),1);
        var snapshot=new Snapshot(group,List.of(reader,writer,auditor),List.of(app),List.of(grant));
        assertEquals(Set.of("reader"),snapshot.eligibleScopeKeys("current-app",grant.issuer(),grant.subject()));
        var renamed=new Snapshot(group,List.of(reader.withMetadata("Administrator","May do everything"),writer,auditor),List.of(app),List.of(grant));
        assertEquals(Set.of("reader"),renamed.eligibleScopeKeys("current-app",grant.issuer(),grant.subject()));
        for(String client:List.of("admin","kestra-ui","unknown"))assertEquals(Set.of(),snapshot.eligibleScopeKeys(client,grant.issuer(),grant.subject()));
        assertEquals(Set.of(),snapshot.eligibleScopeKeys("current-app","https://other.example","user"));
        assertEquals(Set.of(),snapshot.eligibleScopeKeys("current-app",grant.issuer(),"other-user"));
        var unsupported=new Snapshot(group,List.of(reader),List.of(new Application("current-app","",Set.of("current-app"),List.of(),1)),List.of(new UserGrant(grant.issuer(),"user",Set.of("reader"),1)));
        assertEquals(Set.of(),unsupported.eligibleScopeKeys("current-app",grant.issuer(),"user"));
    }

    @Test void duplicateKeysNamesClientsUsersAndUndefinedReferencesAreRejected() {
        assertThrows(IllegalArgumentException.class,()->new Snapshot(group,List.of(reader,reader),List.of(),List.of()));
        assertThrows(IllegalArgumentException.class,()->new Snapshot(group,List.of(reader,new Scope("other",reader.name(),"",1)),List.of(),List.of()));
        assertDoesNotThrow(()->new Snapshot(group,List.of(new Scope("first","Role","",1),new Scope("second","role","",1)),List.of(),List.of()));
        var app=io.kestra.extensions.rbac.applicationgroup.TestApplicationFactory.application("app",true,Set.of("app"),Set.of("reader"),1);
        assertThrows(IllegalArgumentException.class,()->new Snapshot(group,List.of(reader),List.of(app,app),List.of()));
        assertThrows(IllegalArgumentException.class,()->new Snapshot(group,List.of(reader),List.of(io.kestra.extensions.rbac.applicationgroup.TestApplicationFactory.application("app",true,Set.of("app"),Set.of("unknown"),1)),List.of()));
        var grant=new UserGrant("https://issuer.example","user",Set.of("reader"),1);
        assertThrows(IllegalArgumentException.class,()->new Snapshot(group,List.of(reader),List.of(),List.of(grant,grant)));
        assertThrows(IllegalArgumentException.class,()->new Snapshot(group,List.of(reader),List.of(),List.of(new UserGrant(grant.issuer(),"user",Set.of("unknown"),1))));
        assertThrows(IllegalArgumentException.class,()->io.kestra.extensions.rbac.applicationgroup.TestApplicationFactory.application("app",true,Set.of("*"),Set.of(),1));
    }

    @Test void snapshotsOwnTheirCollectionsAndDisabledGroupsCannotYieldScopes() {
        var keys=new HashSet<>(Set.of("reader"));var app=io.kestra.extensions.rbac.applicationgroup.TestApplicationFactory.application("app",true,Set.of("app"),keys,1);
        var scopes=new ArrayList<>(List.of(reader));var applications=new ArrayList<>(List.of(app));
        var grant=new UserGrant("https://issuer.example","user",keys,1);
        var snapshot=new Snapshot(group,scopes,applications,List.of(grant));
        keys.clear();scopes.clear();applications.clear();
        assertEquals(Set.of("reader"),snapshot.eligibleScopeKeys("app",grant.issuer(),"user"));
        assertThrows(UnsupportedOperationException.class,()->snapshot.scopes().clear());
        assertThrows(UnsupportedOperationException.class,()->snapshot.applications().getFirst().supportedScopeKeys().clear());
        assertThrows(UnsupportedOperationException.class,()->snapshot.userGrants().getFirst().scopeKeys().clear());
        var inactive=new Snapshot(new Group(group.id(),group.displayName(),false,2,98),snapshot.scopes(),snapshot.applications(),snapshot.userGrants());
        assertEquals(Set.of(),inactive.eligibleScopeKeys("app",grant.issuer(),"user"));
    }
}
