package io.kestra.extensions.rbac;

import io.kestra.extensions.rbac.applicationgroup.*;
import io.kestra.extensions.rbac.store.ApplicationGroupPolicyStore;
import io.kestra.webserver.oidc.*;
import io.kestra.webserver.rbac.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static io.kestra.webserver.rbac.TypedAuthorization.*;

class RuntimePoliciesTest {
    static class Fixture {
        final ApplicationGroupPolicyStore store=mock(ApplicationGroupPolicyStore.class);
        final ApplicationGroupDirectoryReader directory=mock(ApplicationGroupDirectoryReader.class);
        final TrustedIdentityV1 identities=mock(TrustedIdentityV1.class);
        final ApplicationGroupRuntimePolicies runtime=new ApplicationGroupRuntimePolicies(directory,identities);
        ApplicationGroupPolicy policy;
        Fixture(Map<String,ApplicationGroupPolicy.ScopeDefinition> scopes){
            policy=new ApplicationGroupPolicy(2,Map.of("p",new ApplicationGroupPolicy.ApplicationGroup("https://issuer.example",Set.of("ui","api"),true,scopes)));
            when(store.readCurrent()).thenReturn(snapshot(1,policy));
            when(directory.runtimePolicy()).thenAnswer(call->store.readCurrent());
            var shared=new ArrayList<ApplicationGroupDirectoryReader.ScopeView>();
            for(var entry:scopes.entrySet()){
                shared.add(new ApplicationGroupDirectoryReader.ScopeView("p",entry.getKey(),"Scope","",1));
            }
            when(directory.boundaries()).thenReturn(List.of(new ApplicationGroupDirectoryReader.Boundary(
                new ApplicationGroupDirectoryReader.GroupView("p","main","https://issuer.example","P",true,3,97),shared,Set.of("ui","api"),scopes.keySet(),List.of(new ApplicationGroupDirectoryReader.ProtocolScope("openid","STANDARD")))));
        }
        TrustedIdentityV1.Identity identity(String... scopes){return new TrustedIdentityV1.Identity(1,"https://issuer.example","s","client",Set.of("ui"),"main","p",Set.of("openid"),Set.of(scopes),1,97,3);}
    }
    static ApplicationGroupPolicyStore.Snapshot snapshot(long revision,ApplicationGroupPolicy policy){return new ApplicationGroupPolicyStore.Snapshot(revision,revision-1,"operator",java.time.Instant.EPOCH,policy,"{}");}
    static ApplicationGroupPolicy.ScopeDefinition scope(String ns,String id,String... actions){return new ApplicationGroupPolicy.ScopeDefinition("scope",true,List.of(new ApplicationGroupPolicy.Grant("NAMESPACE","main",ns,false,"FLOW",Set.of(actions),Set.of(id),false)));}
    static ApplicationGroupPolicy.ScopeDefinition manager(ApplicationGroupPolicy.ManagementDomain domain,ApplicationGroupPolicy.ManagementAction... actions){return new ApplicationGroupPolicy.ScopeDefinition("management",true,List.of(),List.of(new ApplicationGroupPolicy.ManagementGrant(domain,Set.of(actions))));}
    static Operation op(Resource resource,Action action,String ns,String id,boolean query){return new Operation(1,Source.REST,resource,action,"main",ns,id,QueryDomain.unrestricted(),query?Coverage.COMPLETE_QUERY:Coverage.SINGLE,Aggregate.NONE,SideEffect.READ_ONLY);}
    @Test void correlatedListDoesNotProduceCartesianProductOrCrossScopeReadList(){
        var f=new Fixture(Map.of("a",scope("a","x","READ","LIST"),"b",scope("b","y","READ","LIST"),"read",scope("c","z","READ"),"list",scope("c","z","LIST")));
        try(var c=f.runtime.open(f.identity("a","b","read","list"),"main")){
            var result=c.authorize(op(Resource.FLOW,Action.LIST,null,null,true));assertTrue(result.allowed());
            assertTrue(result.scope().resourceAllowed("a","x"));assertTrue(result.scope().resourceAllowed("b","y"));
            assertFalse(result.scope().resourceAllowed("a","y"));assertFalse(result.scope().resourceAllowed("b","x"));assertFalse(result.scope().resourceAllowed("c","z"));
        }
    }
    @Test void v2PinsOneSnapshotAndClosesIt(){
        var f=new Fixture(Map.of("read",scope("a","x","READ")));
        var provider=new ConfiguredEvaluationProvider(f.runtime,Runnable::run);
        var evaluation=provider.begin(f.identity("read"),"main").toCompletableFuture().join();
        when(f.store.readCurrent()).thenThrow(new IllegalStateException("later outage"));
        assertTrue(evaluation.authorize(op(Resource.FLOW,Action.READ,"a","x",false)).toCompletableFuture().join().allowed());
        verify(f.store,times(1)).readCurrent();verify(f.directory,times(1)).boundaries();
        evaluation.close().toCompletableFuture().join();assertFalse(evaluation.authorize(op(Resource.FLOW,Action.READ,"a","x",false)).toCompletableFuture().join().allowed());
        assertThrows(RuntimeException.class,()->provider.begin(f.identity("read"),"main").toCompletableFuture().join());
    }
    @Test void pinnedEvaluationExpiresWithinAcceptedRevocationBound(){
        var fixture=new Fixture(Map.of("read",scope("a","x","READ")));
        var clock=new java.util.concurrent.atomic.AtomicLong();
        var runtime=new ApplicationGroupRuntimePolicies(fixture.directory,fixture.identities,60,clock::get);
        var provider=new ConfiguredEvaluationProvider(runtime,Runnable::run);
        var evaluation=provider.begin(fixture.identity("read"),"main").toCompletableFuture().join();
        assertTrue(evaluation.authorize(op(Resource.FLOW,Action.READ,"a","x",false)).toCompletableFuture().join().allowed());
        clock.set(java.util.concurrent.TimeUnit.SECONDS.toNanos(60));
        assertFalse(evaluation.authorize(op(Resource.FLOW,Action.READ,"a","x",false)).toCompletableFuture().join().allowed());
        evaluation.close().toCompletableFuture().join();
    }
    @Test void unknownMixedScopeAndWrongEpochDeny(){
        var f=new Fixture(Map.of("read",scope("a","x","READ")));
        try(var c=f.runtime.open(f.identity("read","unknown"),"main")){assertFalse(c.authorize(op(Resource.FLOW,Action.READ,"a","x",false)).allowed());}
        var id=f.identity("read");var wrong=new TrustedIdentityV1.Identity(1,id.issuer(),id.subject(),id.clientId(),id.audiences(),id.tenant(),id.applicationGroupId(),id.protocolScopes(),id.applicationGroupScopes(),1,3,3);
        assertThrows(ApplicationGroupRuntimePolicies.Rejected.class,()->f.runtime.open(wrong,"main"));
    }
    @Test void businessManagementUsesExplicitActionsAndNeverExtensionOrIdentity(){
        var f=new Fixture(Map.of("read",manager(ApplicationGroupPolicy.ManagementDomain.BUSINESS_INSTANCE,ApplicationGroupPolicy.ManagementAction.READ),"write",manager(ApplicationGroupPolicy.ManagementDomain.BUSINESS_INSTANCE,ApplicationGroupPolicy.ManagementAction.MANAGE)));
        try(var read=f.runtime.open(f.identity("read"),"main");var write=f.runtime.open(f.identity("write"),"main")){
            assertTrue(read.authorize(op(Resource.FLOW,Action.READ,"any","id",false)).allowed());
            assertFalse(read.authorize(op(Resource.FLOW,Action.EXECUTE,"any","id",false)).allowed());
            assertTrue(write.authorize(op(Resource.FLOW,Action.EXECUTE,"any","id",false)).allowed());
            assertTrue(write.authorize(op(Resource.FLOW,Action.ACCESS_OUTPUTS,"any","id",false)).allowed());
            assertFalse(write.authorize(op(Resource.FLOW,Action.READ,"any","id",false)).allowed());
            assertFalse(write.authorize(op(Resource.EXTENSION,Action.READ,null,"rbac",false)).allowed());
            assertFalse(write.authorizeManagement(ApplicationGroupPolicy.ManagementDomain.IDENTITY,ApplicationGroupPolicy.ManagementAction.MANAGE,"p"));
        }
    }
    @Test void allAudiencesMustBeLocallyAccepted(){
        var f=new Fixture(Map.of("read",scope("a","x","READ")));
        f.policy=new ApplicationGroupPolicy(2,Map.of("p",new ApplicationGroupPolicy.ApplicationGroup("https://issuer.example",Set.of("api"),true,f.policy.applicationGroups().get("p").scopes())));
        when(f.store.readCurrent()).thenReturn(snapshot(1,f.policy));
        var id=f.identity("read");var mixed=new TrustedIdentityV1.Identity(1,id.issuer(),id.subject(),id.clientId(),Set.of("api","ui"),"main","p",id.protocolScopes(),id.applicationGroupScopes(),1,97,3);
        try(var c=f.runtime.open(mixed,"main")){assertFalse(c.authorize(op(Resource.FLOW,Action.READ,"a","x",false)).allowed());}
    }
    @Test void extensionActivationIsExactAndDoesNotTurnReadIntoManage(){
        var f=new Fixture(Map.of("read",manager(ApplicationGroupPolicy.ManagementDomain.IDENTITY,ApplicationGroupPolicy.ManagementAction.READ),
            "manage",manager(ApplicationGroupPolicy.ManagementDomain.IDENTITY,ApplicationGroupPolicy.ManagementAction.MANAGE)));
        try(var read=f.runtime.open(f.identity("read"),"main");var manage=f.runtime.open(f.identity("manage"),"main")){
            assertTrue(read.authorize(op(Resource.EXTENSION,Action.READ,null,"rbac",false)).allowed());
            assertFalse(read.authorize(op(Resource.EXTENSION,Action.UPDATE,null,"rbac",false)).allowed());
            assertTrue(manage.authorize(op(Resource.EXTENSION,Action.UPDATE,null,"rbac",false)).allowed());
            assertFalse(manage.authorize(op(Resource.EXTENSION,Action.UPDATE,null,"other",false)).allowed());
            assertFalse(manage.authorize(op(Resource.EXTENSION,Action.DELETE,null,"rbac",false)).allowed());
        }
    }
    @Test void applicationPolicyDoesNotStoreDirectoryRevision(){
        var f=new Fixture(Map.of("recover",manager(ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,ApplicationGroupPolicy.ManagementAction.MANAGE)));
        assertFalse(new ApplicationGroupPolicyParser().canonicalJson(f.policy).contains("directoryRevision"));
        try(var c=f.runtime.open(f.identity("recover"),"main")){
            assertTrue(c.authorizeManagement(ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,ApplicationGroupPolicy.ManagementAction.MANAGE,"p"));
            assertTrue(c.authorize(op(Resource.EXTENSION,Action.READ,null,"rbac",false)).allowed());
            assertTrue(c.authorize(op(Resource.EXTENSION,Action.UPDATE,null,"rbac",false)).allowed());
            assertFalse(c.authorizeManagement(ApplicationGroupPolicy.ManagementDomain.IDENTITY,ApplicationGroupPolicy.ManagementAction.MANAGE,"p"));
            assertFalse(c.authorize(op(Resource.FLOW,Action.UPDATE,"a","x",false)).allowed());
            c.validateCandidate(f.policy);
        }
    }
    @Test void explicitManagementTargetCanPrecedeItsLocalBinding(){
        var grants=List.of(new ApplicationGroupPolicy.ManagementGrant(ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,
            Set.of(ApplicationGroupPolicy.ManagementAction.MANAGE),Set.of("p","new-target")));
        var f=new Fixture(Map.of("manage",new ApplicationGroupPolicy.ScopeDefinition("Target management",true,List.of(),grants)));
        var console=f.directory.boundaries().getFirst();
        var target=new ApplicationGroupDirectoryReader.Boundary(new ApplicationGroupDirectoryReader.GroupView("new-target","main","https://issuer.example","New",true,1,1),
            List.of(),Set.of("non-ui-api"),Set.of(),List.of());
        when(f.directory.boundaries()).thenReturn(List.of(console,target));
        try(var c=f.runtime.open(f.identity("manage"),"main")){
            assertFalse(c.directories().containsKey("new-target"));
            assertEquals("new-target",c.upperBound("new-target").applicationGroup().id());
            assertFalse(c.snapshot().policy().applicationGroups().containsKey("new-target"));
            assertTrue(c.authorizeManagement(ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,ApplicationGroupPolicy.ManagementAction.MANAGE,"new-target"));
            c.validateCandidate(f.policy);
        }
        when(f.directory.boundaries()).thenReturn(List.of(console));
        try(var current=f.runtime.open(f.identity("manage"),"main")) {
            assertFalse(current.authorizeManagement(ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,ApplicationGroupPolicy.ManagementAction.MANAGE,"new-target"));
            assertThrows(ApplicationGroupRuntimePolicies.Rejected.class,()->current.upperBound("new-target"));
        }
    }
    @Test void splitManagementOnlyTargetsActivateExtensionAndCapabilitiesWithoutCombiningAuthorization(){
        var read=new ApplicationGroupPolicy.ScopeDefinition("A read",true,List.of(),List.of(new ApplicationGroupPolicy.ManagementGrant(ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,Set.of(ApplicationGroupPolicy.ManagementAction.READ),Set.of("a"))));
        var manage=new ApplicationGroupPolicy.ScopeDefinition("B manage",true,List.of(),List.of(new ApplicationGroupPolicy.ManagementGrant(ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,Set.of(ApplicationGroupPolicy.ManagementAction.MANAGE),Set.of("b"))));
        var fixture=new Fixture(Map.of("read",read,"manage",manage));
        var console=fixture.directory.boundaries().getFirst();
        var targets=new ArrayList<ApplicationGroupDirectoryReader.Boundary>();targets.add(console);
        for(String target:List.of("a","b"))targets.add(new ApplicationGroupDirectoryReader.Boundary(new ApplicationGroupDirectoryReader.GroupView(target,"main","https://issuer.example",target,true,1,1),List.of(),Set.of(),Set.of(),List.of()));
        when(fixture.directory.boundaries()).thenReturn(targets);
        when(fixture.identities.current()).thenReturn(fixture.identity("read","manage"));
        try(var context=fixture.runtime.open(fixture.identity("read","manage"),"main")){
            assertEquals(Set.of("p","a","b"),context.managementTargets());
            assertThrows(UnsupportedOperationException.class,()->context.managementTargets().add("outside"));
            assertTrue(context.authorizeManagement(ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,ApplicationGroupPolicy.ManagementAction.READ,"a"));
            assertFalse(context.authorizeManagement(ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,ApplicationGroupPolicy.ManagementAction.MANAGE,"a"));
            assertTrue(context.authorizeManagement(ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,ApplicationGroupPolicy.ManagementAction.MANAGE,"b"));
            assertFalse(context.authorizeManagement(ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,ApplicationGroupPolicy.ManagementAction.READ,"b"));
            assertFalse(context.authorizeManagement(ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,ApplicationGroupPolicy.ManagementAction.MANAGE,"outside"));
            assertTrue(context.authorize(op(Resource.EXTENSION,Action.READ,null,"rbac",false)).allowed());
            assertTrue(context.authorize(op(Resource.EXTENSION,Action.UPDATE,null,"rbac",false)).allowed());
            assertFalse(context.authorize(op(Resource.FLOW,Action.READ,"a","flow",false)).allowed());
        }
        io.micronaut.context.BeanProvider<io.kestra.extensions.rbac.management.UserDirectoryAccess> users=mock(io.micronaut.context.BeanProvider.class);
        io.micronaut.context.BeanProvider<io.kestra.extensions.rbac.management.ApplicationDirectoryAccess> applications=mock(io.micronaut.context.BeanProvider.class);
        var controller=new io.kestra.extensions.rbac.management.ManagementController(fixture.runtime,fixture.store,users,applications);
        var response=controller.capabilities("main",io.micronaut.security.authentication.Authentication.build("s"),null);
        assertEquals(200,response.code());
        var caps=new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(response.body());
        assertTrue(caps.path("allowed").asBoolean());assertTrue(caps.path("localPolicyRead").asBoolean());assertTrue(caps.path("localPolicyManage").asBoolean());
    }
    @Test void typedTenantMetadataAndMcpListsHaveRealAllowPaths(){
        var metadata=new ApplicationGroupPolicy.ScopeDefinition("Metadata",true,List.of(new ApplicationGroupPolicy.Grant("TENANT","main",null,false,"METADATA",Set.of("READ","LIST"),Set.of(),true)));
        var servers=new ApplicationGroupPolicy.ScopeDefinition("Servers",true,List.of(new ApplicationGroupPolicy.Grant("TENANT","main",null,false,"MCP_SERVER",Set.of("READ","LIST"),Set.of("server-a"),false)));
        var f=new Fixture(Map.of("metadata",metadata,"servers",servers,"flow",scope("a","x","READ","LIST")));
        try(var c=f.runtime.open(f.identity("metadata","servers","flow"),"main")){
            assertTrue(c.authorize(op(Resource.METADATA,Action.READ,null,null,false)).allowed());
            assertTrue(c.authorize(op(Resource.METADATA,Action.LIST,null,null,true)).allowed());
            var list=c.authorize(op(Resource.MCP_SERVER,Action.LIST,null,null,true));assertTrue(list.allowed());
            assertTrue(list.scope().resourceAllowed(null,"server-a"));assertFalse(list.scope().resourceAllowed(null,"server-b"));
            assertTrue(c.authorize(op(Resource.MCP_SERVER,Action.READ,null,"server-a",false)).allowed());
            assertFalse(c.authorize(op(Resource.MCP_SERVER,Action.READ,null,"server-b",false)).allowed());
            assertTrue(c.authorize(op(Resource.FLOW,Action.READ,"a","x",false)).allowed());
            assertTrue(c.authorize(op(Resource.FLOW,Action.LIST,"a",null,true)).allowed());
            assertFalse(c.authorize(op(Resource.FLOW,Action.LIST,"outside",null,true)).allowed());
        }
        try(var c=f.runtime.open(f.identity("flow"),"main")){
            assertFalse(c.authorize(op(Resource.METADATA,Action.READ,null,null,false)).allowed());
            assertFalse(c.authorize(op(Resource.METADATA,Action.LIST,null,null,true)).allowed());
        }
    }
}
