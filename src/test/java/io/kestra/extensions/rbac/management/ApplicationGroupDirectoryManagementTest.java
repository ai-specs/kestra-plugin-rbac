package io.kestra.extensions.rbac.management;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.extensions.rbac.ApplicationGroupRuntimePolicies;
import io.kestra.extensions.rbac.applicationgroup.*;
import io.kestra.extensions.rbac.store.ApplicationGroupDirectoryStore;
import io.kestra.webserver.oidc.IdentityLifecycleGuardV1;
import io.kestra.webserver.oidc.TrustedIdentityV1;
import java.sql.Connection;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static io.kestra.extensions.rbac.management.ApplicationDirectoryAccess.*;
import static io.kestra.extensions.rbac.applicationgroup.ApplicationGroupDirectory.*;

class ApplicationGroupDirectoryManagementTest {
    private final ApplicationGroupDirectoryService directory=mock(ApplicationGroupDirectoryService.class);
    private final ApplicationGroupRuntimePolicies policies=mock(ApplicationGroupRuntimePolicies.class);
    private final ApplicationGroupRuntimePolicies.Context context=mock(ApplicationGroupRuntimePolicies.Context.class);
    private final Connection connection=mock(Connection.class);
    private final ApplicationGroupDirectoryManagement management=new ApplicationGroupDirectoryManagement(directory,policies);
    private final TrustedIdentityV1.Identity identity=new TrustedIdentityV1.Identity(1,"https://issuer.example","manager","ui",Set.of("ui"),"main","group",Set.of("openid"),Set.of("manager"),0,1,3);
    private final Snapshot current=new Snapshot(new Group("group","Group",true,3,1),List.of(new Scope("reader","Reader","Read",1)),
        List.of(io.kestra.extensions.rbac.applicationgroup.TestApplicationFactory.application("client",true,Set.of("api"),Set.of("reader"),1)),List.of(new UserGrant("https://issuer.example","user",Set.of("reader"),1)));

    private void setup()throws Exception {
        when(directory.transaction(any())).thenAnswer(call->((ApplicationGroupDirectoryStore.Work<?>)call.getArgument(0)).run(connection));
        when(policies.open(connection,identity,"main")).thenReturn(context);
        when(context.authorizeManagement(any(),any(),eq("group"))).thenReturn(true);
        when(directory.find(connection,"group")).thenReturn(current);
        when(directory.kind(anyString())).thenReturn(ApplicationPermission.Kind.EXTERNAL_DECLARATION);
    }
    private Request request(Resource resource,Operation operation,String key,Long revision,String body)throws Exception {
        return new Request(resource,operation,identity,"group",key,1,100,revision,3,body==null?null:new ObjectMapper().readTree(body));
    }

    @Test void clientDetailPreservesExactOpaqueIdentifierAndOnlyOrdinaryMemberFields()throws Exception {
        setup();var value=(ApplicationGroupApplication)management.invoke(request(Resource.CLIENT,Operation.FIND,"client",null,null));
        assertEquals("group",value.applicationGroupId());assertEquals("client",value.clientId());assertEquals(Set.of("reader"),value.supportedScopeKeys());
        assertEquals(7,new ObjectMapper().valueToTree(value).size());verify(context).close();
    }
    @Test void changedGroupRevisionRejectsBeforeAnyWrite()throws Exception {
        setup();when(directory.find(connection,"group")).thenReturn(new Snapshot(new Group("group","Group",true,4,2),List.of(),List.of(),List.of()));
        var failure=assertThrows(DirectoryFailure.class,()->management.invoke(request(Resource.SCOPE,Operation.DELETE,"reader",1L,null)));
        assertEquals(409,failure.status());assertEquals("directory_changed",failure.code());verify(directory,never()).update(any(),anyString(),anyLong(),any());
    }
    @Test void lastAdministratorAndObjectRevisionConflictsHaveDistinctResponses()throws Exception {
        setup();when(directory.update(any(),anyString(),anyLong(),any())).thenThrow(new IdentityLifecycleGuardV1.Rejected(IdentityLifecycleGuardV1.Failure.LAST_ADMIN));
        assertEquals("last_administrator",assertThrows(DirectoryFailure.class,()->management.invoke(request(Resource.ASSIGNMENT,Operation.DELETE,"user",1L,null))).code());
        doThrow(new ApplicationGroupChanges.Conflict()).when(directory).update(any(),anyString(),anyLong(),any());
        assertEquals(412,assertThrows(DirectoryFailure.class,()->management.invoke(request(Resource.CLIENT,Operation.DELETE,"client",1L,null))).status());
    }
    @Test void scopeMetadataUsesStableKeyAndCallerTransaction()throws Exception {
        setup();when(directory.update(eq(connection),eq("group"),eq(3L),any())).thenAnswer(call->{
            java.util.function.UnaryOperator<Snapshot> change=call.getArgument(3);return change.apply(current);
        });
        var value=(java.util.Map<?,?>)management.invoke(request(Resource.SCOPE,Operation.UPDATE,"reader",1L,"{\"key\":\"reader\",\"name\":\"Observer\",\"description\":\"Changed\"}"));
        assertEquals("reader",value.get("key"));assertEquals(2L,value.get("revision"));
        assertEquals(Set.of("key","revision","parentRevision"),value.keySet());
        assertEquals(400,assertThrows(DirectoryFailure.class,()->management.invoke(request(Resource.SCOPE,Operation.UPDATE,"reader",1L,"{\"key\":\"other\",\"name\":\"Other\",\"description\":\"\"}"))).status());
    }
    @Test void legacyAndUnknownScopeFieldsNeverReachBusinessMutation()throws Exception {
        setup();for(String body:List.of("{\"scope\":\"reader\",\"name\":\"Reader\",\"description\":\"\"}","{\"key\":\"reader\",\"name\":\"Reader\",\"description\":\"\",\"active\":true}"))
            assertEquals(400,assertThrows(DirectoryFailure.class,()->management.invoke(request(Resource.SCOPE,Operation.CREATE,null,3L,body))).status());
        verifyNoInteractions(policies);verify(directory,never()).update(any(),anyString(),anyLong(),any());
    }
    @Test void ordinaryApplicationWritesRejectFirstPartyAndOldSupportAliases()throws Exception {
        setup();for(String extra:List.of("firstPartyUi","allowedProjectScopes")) {
            String body="{\"clientId\":\"client\",\"approved\":true,\"audiences\":[\"api\"],\"supportedScopeKeys\":[\"reader\"],\""+extra+"\":true}";
            assertEquals(400,assertThrows(DirectoryFailure.class,()->management.invoke(request(Resource.CLIENT,Operation.UPDATE,"client",1L,body))).status());
        }
        verifyNoInteractions(policies);
    }
    @Test void userGrantWritesRejectOldKeysAndCrossIssuerTargets()throws Exception {
        setup();String old="{\"issuer\":\"https://issuer.example\",\"subject\":\"user\",\"projectScopes\":[\"reader\"]}";
        assertEquals(400,assertThrows(DirectoryFailure.class,()->management.invoke(request(Resource.ASSIGNMENT,Operation.UPDATE,"user",1L,old))).status());
        String foreign="{\"issuer\":\"https://other.example\",\"subject\":\"user\",\"scopeKeys\":[\"reader\"]}";
        assertEquals(403,assertThrows(DirectoryFailure.class,()->management.invoke(request(Resource.ASSIGNMENT,Operation.UPDATE,"user",1L,foreign))).status());
        verify(directory,never()).update(any(),anyString(),anyLong(),any());
    }
    @Test void currentReadAuthorizationIsSeparateFromManageAndUnavailableFailsClosed()throws Exception {
        setup();when(context.authorizeManagement(any(),eq(ApplicationGroupPolicy.ManagementAction.MANAGE),eq("group"))).thenReturn(false);
        assertNotNull(management.invoke(request(Resource.CLIENT,Operation.FIND,"client",null,null)));
        assertEquals(403,assertThrows(DirectoryFailure.class,()->management.invoke(request(Resource.CLIENT,Operation.DELETE,"client",1L,null))).status());
        when(policies.open(connection,identity,"main")).thenThrow(new ApplicationGroupRuntimePolicies.Rejected(ApplicationGroupRuntimePolicies.Failure.DIRECTORY_UNAVAILABLE));
        assertThrows(ApplicationGroupRuntimePolicies.Rejected.class,()->management.invoke(request(Resource.CLIENT,Operation.FIND,"client",null,null)));
    }
}
