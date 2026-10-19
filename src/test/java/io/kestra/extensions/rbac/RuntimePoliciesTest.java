package io.kestra.extensions.rbac;

import io.kestra.extensions.rbac.project.*;
import io.kestra.extensions.rbac.store.ProjectPolicyStore;
import io.kestra.webserver.oidc.*;
import io.kestra.webserver.rbac.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static io.kestra.webserver.rbac.TypedAuthorization.*;

class RuntimePoliciesTest {
    static class Fixture {
        final ProjectPolicyStore store=mock(ProjectPolicyStore.class);
        final ApplicationDirectoryV1 directory=mock(ApplicationDirectoryV1.class);
        final TrustedIdentityV1 identities=mock(TrustedIdentityV1.class);
        final ProjectRuntimePolicies runtime=new ProjectRuntimePolicies(store,directory,identities);
        ProjectPolicy policy;
        Fixture(Map<String,ProjectPolicy.ScopeDefinition> scopes){
            policy=new ProjectPolicy(2,Map.of("p",new ProjectPolicy.Project("https://issuer.example",Set.of("ui","api"),true,3,scopes)));
            when(store.readCurrent()).thenReturn(snapshot(1,policy));
            var shared=new ArrayList<ApplicationDirectoryV1.ScopeDefinition>();
            for(var entry:scopes.entrySet()){
                var intents=new ArrayList<ApplicationDirectoryV1.ResourceIntent>();
                for(var grant:entry.getValue().grants())intents.add(new ApplicationDirectoryV1.ResourceIntent("kestra-resources-v1",grant.resource(),
                    new ApplicationDirectoryV1.NamespaceDomain(grant.namespace()==null?ApplicationDirectoryV1.DomainKind.ALL:ApplicationDirectoryV1.DomainKind.RESTRICTED,
                        grant.namespace()!=null&&!grant.descendants()?Set.of(grant.namespace()):Set.of(),grant.descendants()?Set.of(grant.namespace()):Set.of()),
                    new ApplicationDirectoryV1.ResourceIdDomain(grant.allIds()?ApplicationDirectoryV1.DomainKind.ALL:ApplicationDirectoryV1.DomainKind.RESTRICTED,grant.ids()),grant.actions()));
                var management=entry.getValue().managementGrants().stream().map(g->new ApplicationDirectoryV1.ManagementIntent(ApplicationDirectoryV1.ManagementDomain.valueOf(g.domain().name()),g.actions().stream().map(Enum::name).collect(java.util.stream.Collectors.toSet()),g.projectIds())).toList();
                shared.add(new ApplicationDirectoryV1.ScopeDefinition("p",entry.getKey(),"scope",true,intents,management,1));
            }
            when(directory.policyBoundaries()).thenReturn(List.of(new ApplicationDirectoryV1.PolicyBoundary(
                new ApplicationDirectoryV1.Project("p","main","https://issuer.example","P",true,3,97),shared,Set.of("ui","api"),scopes.keySet(),List.of(new ApplicationDirectoryV1.ProtocolScope("openid","STANDARD")))));
        }
        TrustedIdentityV1.Identity identity(String... scopes){return new TrustedIdentityV1.Identity(1,"https://issuer.example","s","client",Set.of("ui"),"main","p",Set.of("openid"),Set.of(scopes),1,97,3);}
    }
    static ProjectPolicyStore.Snapshot snapshot(long revision,ProjectPolicy policy){return new ProjectPolicyStore.Snapshot(revision,revision-1,"operator",java.time.Instant.EPOCH,policy,"{}");}
    static ProjectPolicy.ScopeDefinition scope(String ns,String id,String... actions){return new ProjectPolicy.ScopeDefinition("scope",true,List.of(new ProjectPolicy.Grant("NAMESPACE","main",ns,false,"FLOW",Set.of(actions),Set.of(id),false)));}
    static ProjectPolicy.ScopeDefinition manager(ProjectPolicy.ManagementDomain domain,ProjectPolicy.ManagementAction... actions){return new ProjectPolicy.ScopeDefinition("management",true,List.of(),List.of(new ProjectPolicy.ManagementGrant(domain,Set.of(actions))));}
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
        verify(f.store,times(1)).readCurrent();verify(f.directory,times(1)).policyBoundaries();
        evaluation.close().toCompletableFuture().join();assertFalse(evaluation.authorize(op(Resource.FLOW,Action.READ,"a","x",false)).toCompletableFuture().join().allowed());
        assertThrows(RuntimeException.class,()->provider.begin(f.identity("read"),"main").toCompletableFuture().join());
    }
    @Test void unknownMixedScopeAndWrongEpochDeny(){
        var f=new Fixture(Map.of("read",scope("a","x","READ")));
        try(var c=f.runtime.open(f.identity("read","unknown"),"main")){assertFalse(c.authorize(op(Resource.FLOW,Action.READ,"a","x",false)).allowed());}
        var id=f.identity("read");var wrong=new TrustedIdentityV1.Identity(1,id.issuer(),id.subject(),id.clientId(),id.audiences(),id.tenant(),id.projectId(),id.protocolScopes(),id.projectScopes(),1,3,3);
        assertThrows(ProjectRuntimePolicies.Rejected.class,()->f.runtime.open(wrong,"main"));
    }
    @Test void businessManagementUsesExplicitActionsAndNeverExtensionOrIdentity(){
        var f=new Fixture(Map.of("read",manager(ProjectPolicy.ManagementDomain.BUSINESS_INSTANCE,ProjectPolicy.ManagementAction.READ),"write",manager(ProjectPolicy.ManagementDomain.BUSINESS_INSTANCE,ProjectPolicy.ManagementAction.MANAGE)));
        try(var read=f.runtime.open(f.identity("read"),"main");var write=f.runtime.open(f.identity("write"),"main")){
            assertTrue(read.authorize(op(Resource.FLOW,Action.READ,"any","id",false)).allowed());
            assertFalse(read.authorize(op(Resource.FLOW,Action.EXECUTE,"any","id",false)).allowed());
            assertTrue(write.authorize(op(Resource.FLOW,Action.EXECUTE,"any","id",false)).allowed());
            assertTrue(write.authorize(op(Resource.FLOW,Action.ACCESS_OUTPUTS,"any","id",false)).allowed());
            assertFalse(write.authorize(op(Resource.FLOW,Action.READ,"any","id",false)).allowed());
            assertFalse(write.authorize(op(Resource.EXTENSION,Action.READ,null,"rbac",false)).allowed());
            assertFalse(write.authorizeManagement(ProjectPolicy.ManagementDomain.IDENTITY,ProjectPolicy.ManagementAction.MANAGE,"p"));
        }
    }
    @Test void allAudiencesMustBeLocallyAccepted(){
        var f=new Fixture(Map.of("read",scope("a","x","READ")));
        f.policy=new ProjectPolicy(2,Map.of("p",new ProjectPolicy.Project("https://issuer.example",Set.of("api"),true,3,f.policy.applicationProjects().get("p").scopes())));
        when(f.store.readCurrent()).thenReturn(snapshot(1,f.policy));
        var id=f.identity("read");var mixed=new TrustedIdentityV1.Identity(1,id.issuer(),id.subject(),id.clientId(),Set.of("api","ui"),"main","p",id.protocolScopes(),id.projectScopes(),1,97,3);
        try(var c=f.runtime.open(mixed,"main")){assertFalse(c.authorize(op(Resource.FLOW,Action.READ,"a","x",false)).allowed());}
    }
    @Test void extensionActivationIsExactAndDoesNotTurnReadIntoManage(){
        var f=new Fixture(Map.of("read",manager(ProjectPolicy.ManagementDomain.IDENTITY,ProjectPolicy.ManagementAction.READ),
            "manage",manager(ProjectPolicy.ManagementDomain.IDENTITY,ProjectPolicy.ManagementAction.MANAGE)));
        try(var read=f.runtime.open(f.identity("read"),"main");var manage=f.runtime.open(f.identity("manage"),"main")){
            assertTrue(read.authorize(op(Resource.EXTENSION,Action.READ,null,"rbac",false)).allowed());
            assertFalse(read.authorize(op(Resource.EXTENSION,Action.UPDATE,null,"rbac",false)).allowed());
            assertTrue(manage.authorize(op(Resource.EXTENSION,Action.UPDATE,null,"rbac",false)).allowed());
            assertFalse(manage.authorize(op(Resource.EXTENSION,Action.UPDATE,null,"other",false)).allowed());
            assertFalse(manage.authorize(op(Resource.EXTENSION,Action.DELETE,null,"rbac",false)).allowed());
        }
    }
    @Test void staleBindingOnlyOpensRecoveryAndExtensionActivation(){
        var f=new Fixture(Map.of("recover",manager(ProjectPolicy.ManagementDomain.LOCAL_POLICY,ProjectPolicy.ManagementAction.MANAGE)));
        var old=f.policy.applicationProjects().get("p");
        var stale=new ProjectPolicy(2,Map.of("p",new ProjectPolicy.Project(old.issuer(),old.audiences(),true,2,old.scopes())));
        when(f.store.readCurrent()).thenReturn(snapshot(1,stale));
        try(var c=f.runtime.open(f.identity("recover"),"main")){
            assertTrue(c.authorizeReconciliation("p"));
            assertTrue(c.authorize(op(Resource.EXTENSION,Action.READ,null,"rbac",false)).allowed());
            assertTrue(c.authorize(op(Resource.EXTENSION,Action.UPDATE,null,"rbac",false)).allowed());
            assertFalse(c.authorizeManagement(ProjectPolicy.ManagementDomain.LOCAL_POLICY,ProjectPolicy.ManagementAction.MANAGE,"p"));
            assertFalse(c.authorizeManagement(ProjectPolicy.ManagementDomain.IDENTITY,ProjectPolicy.ManagementAction.MANAGE,"p"));
            assertFalse(c.authorize(op(Resource.FLOW,Action.UPDATE,"a","x",false)).allowed());
            c.validateCandidate(f.policy);
        }
    }
    @Test void explicitManagementTargetCanPrecedeItsLocalBinding(){
        var grants=List.of(new ProjectPolicy.ManagementGrant(ProjectPolicy.ManagementDomain.LOCAL_POLICY,
            Set.of(ProjectPolicy.ManagementAction.MANAGE),Set.of("p","new-target")));
        var f=new Fixture(Map.of("manage",new ProjectPolicy.ScopeDefinition("Target management",true,List.of(),grants)));
        var console=f.directory.policyBoundaries().getFirst();
        var target=new ApplicationDirectoryV1.PolicyBoundary(new ApplicationDirectoryV1.Project("new-target","main","https://issuer.example","New",true,1,1),
            List.of(),Set.of("non-ui-api"),Set.of(),List.of());
        when(f.directory.policyBoundaries()).thenReturn(List.of(console,target));
        try(var c=f.runtime.open(f.identity("manage"),"main")){
            assertFalse(c.directories().containsKey("new-target"));
            assertEquals("new-target",c.upperBound("new-target").project().id());
            assertFalse(c.snapshot().policy().applicationProjects().containsKey("new-target"));
            assertTrue(c.authorizeManagement(ProjectPolicy.ManagementDomain.LOCAL_POLICY,ProjectPolicy.ManagementAction.MANAGE,"new-target"));
            c.validateCandidate(f.policy);
        }
        when(f.directory.policyBoundaries()).thenReturn(List.of(console));
        assertThrows(ProjectRuntimePolicies.Rejected.class,()->f.runtime.open(f.identity("manage"),"main"));
    }
    @Test void typedTenantMetadataAndMcpListsHaveRealAllowPaths(){
        var metadata=new ProjectPolicy.ScopeDefinition("Metadata",true,List.of(new ProjectPolicy.Grant("TENANT","main",null,false,"METADATA",Set.of("READ","LIST"),Set.of(),true)));
        var servers=new ProjectPolicy.ScopeDefinition("Servers",true,List.of(new ProjectPolicy.Grant("TENANT","main",null,false,"MCP_SERVER",Set.of("READ","LIST"),Set.of("server-a"),false)));
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
