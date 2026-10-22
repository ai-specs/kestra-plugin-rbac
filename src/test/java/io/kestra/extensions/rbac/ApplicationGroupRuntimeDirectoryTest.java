package io.kestra.extensions.rbac;

import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupDirectoryReader;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.kestra.extensions.rbac.applicationgroup.ApplicationGroupDirectoryReader.*;

/** Tests the plugin adapter, independently of the host directory implementation. */
class ApplicationGroupRuntimeDirectoryTest {
    private void managementTarget(boolean active,String issuer,String tenant,boolean present,boolean bound) {
        var manager=new ScopeView("p","manager","Manager","",1);
        var console=new Boundary(new GroupView("p","main","https://issuer.example","P",true,3,97),List.of(manager),Set.of("ui"),Set.of("manager"),List.of());
        var target=new Boundary(new GroupView("new-group",tenant,issuer,"New",active,1,1),List.of(),Set.of(),Set.of(),List.of());
        var grant=new io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.ManagementGrant(io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,
            Set.of(io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.ManagementAction.MANAGE),Set.of("p","new-group"));
        var scope=new io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.ScopeDefinition("Manager",true,List.of(),List.of(grant));
        var projects=new HashMap<String,io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.ApplicationGroup>();
        projects.put("p",new io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.ApplicationGroup("https://issuer.example",Set.of("ui"),true,Map.of("manager",scope)));
        if(bound)projects.put("new-group",new io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.ApplicationGroup("https://issuer.example",Set.of("api"),true,Map.of()));
        var policy=new io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy(2,projects);
        var store=org.mockito.Mockito.mock(io.kestra.extensions.rbac.store.ApplicationGroupPolicyStore.class);
        org.mockito.Mockito.when(store.readCurrent()).thenReturn(new io.kestra.extensions.rbac.store.ApplicationGroupPolicyStore.Snapshot(1,0,"operator",java.time.Instant.EPOCH,policy,"{}"));
        var directory=org.mockito.Mockito.mock(ApplicationGroupDirectoryReader.class);
        org.mockito.Mockito.when(directory.boundaries()).thenReturn(present?List.of(console,target):List.of(console));
        var identity=new io.kestra.webserver.oidc.TrustedIdentityV1.Identity(1,"https://issuer.example","operator","ui-client",Set.of("ui"),"main","p",Set.of(),Set.of("manager"),0,97,3);
        var runtime=new ApplicationGroupRuntimePolicies(store,directory,org.mockito.Mockito.mock(io.kestra.webserver.oidc.TrustedIdentityV1.class));
        if(!issuer.equals("https://issuer.example") || !tenant.equals("main") || bound){
            assertEquals(ApplicationGroupRuntimePolicies.Failure.INVALID_DIRECTORY,assertThrows(ApplicationGroupRuntimePolicies.Rejected.class,()->runtime.open(identity,"main")).failure());
        }else try(var context=runtime.open(identity,"main")){
            assertFalse(context.directories().containsKey("new-group"));
            assertEquals(present,context.authorizeManagement(io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.ManagementAction.MANAGE,"new-group"));
            assertFalse(context.authorizeManagement(io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.ManagementAction.MANAGE,"unknown"));
            context.validateCandidate(policy);
            assertThrows(ApplicationGroupRuntimePolicies.Rejected.class,()->context.bindingBoundary("new-group"));
        }
    }
    @Test void managementReferenceWithoutAudienceDoesNotCreateLoginOrBindingAuthority(){managementTarget(true,"https://issuer.example","main",true,false);}
    @Test void missingManagementReferenceFailsClosed(){managementTarget(true,"https://issuer.example","main",false,false);}
    @Test void disabledManagementReferenceRemainsManageable(){managementTarget(false,"https://issuer.example","main",true,false);}
    @Test void foreignIssuerManagementReferenceFailsClosed(){managementTarget(true,"https://other.example","main",true,false);}
    @Test void foreignTenantManagementReferenceFailsClosed(){managementTarget(true,"https://issuer.example","other",true,false);}
    @Test void actualLocalBindingStillRequiresAudience(){managementTarget(true,"https://issuer.example","main",true,true);}
    @Test void inactiveBoundTargetCanBeReconciledAndReferenceWithdrawnWithoutLoginOrBusinessAuthority(){
        var domain=io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY;
        var action=io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.ManagementAction.MANAGE;
        var sharedScope=new ScopeView("p","manager","Manager","",1);
        var console=new Boundary(new GroupView("p","main","https://issuer.example","P",true,3,97),List.of(sharedScope),Set.of("ui"),Set.of("manager"),List.of());
        var target=new Boundary(new GroupView("target","main","https://issuer.example","Target",false,2,2),List.of(),Set.of("api"),Set.of(),List.of());
        var localScope=new io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.ScopeDefinition("Manager",true,List.of(),List.of(new io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.ManagementGrant(domain,Set.of(action),Set.of("p","target"))));
        var owner=new io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.ApplicationGroup("https://issuer.example",Set.of("ui"),true,Map.of("manager",localScope));
        var disabled=new io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.ApplicationGroup("https://issuer.example",Set.of("api"),false,Map.of());
        var policy=new io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy(2,Map.of("p",owner,"target",disabled));
        var store=org.mockito.Mockito.mock(io.kestra.extensions.rbac.store.ApplicationGroupPolicyStore.class);
        org.mockito.Mockito.when(store.readCurrent()).thenReturn(new io.kestra.extensions.rbac.store.ApplicationGroupPolicyStore.Snapshot(1,0,"operator",java.time.Instant.EPOCH,policy,"{}"));
        var directory=org.mockito.Mockito.mock(ApplicationGroupDirectoryReader.class);
        org.mockito.Mockito.when(directory.boundaries()).thenReturn(List.of(console,target));
        var runtime=new ApplicationGroupRuntimePolicies(store,directory,org.mockito.Mockito.mock(io.kestra.webserver.oidc.TrustedIdentityV1.class));
        var identity=new io.kestra.webserver.oidc.TrustedIdentityV1.Identity(1,"https://issuer.example","operator","ui-client",Set.of("ui"),"main","p",Set.of(),Set.of("manager"),0,97,3);
        try(var context=runtime.open(identity,"main")){
            assertTrue(context.authorizeManagement(domain,action,"target"));
            var confirmed=new io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.ApplicationGroup(disabled.issuer(),disabled.audiences(),false,Map.of());
            context.validateCandidate(new io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy(2,Map.of("p",owner,"target",confirmed)));
            var enabled=new io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.ApplicationGroup(disabled.issuer(),disabled.audiences(),true,Map.of());
            assertEquals(ApplicationGroupRuntimePolicies.Failure.INVALID_CANDIDATE,assertThrows(ApplicationGroupRuntimePolicies.Rejected.class,()->context.validateCandidate(new io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy(2,Map.of("p",owner,"target",enabled)))).failure());
            var withdrawn=new io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.ScopeDefinition("Manager",true,List.of(),List.of(new io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.ManagementGrant(domain,Set.of(action),Set.of("p"))));
            var restored=new io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.ApplicationGroup(owner.issuer(),owner.audiences(),true,Map.of("manager",withdrawn));
            context.validateCandidate(new io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy(2,Map.of("p",restored,"target",confirmed)));
            var businessIdentity=new io.kestra.extensions.rbac.applicationgroup.ApplicationGroupAuthorization.Identity("operator",disabled.issuer(),"api","target",2,Set.of(),Set.of(),"main");
            var request=new io.kestra.extensions.rbac.applicationgroup.ApplicationGroupAuthorization.Request("main","a","FLOW","READ","flow",false);
            assertFalse(new io.kestra.extensions.rbac.applicationgroup.ApplicationGroupAuthorization(policy).authorize(businessIdentity,request,context.directories().get("target")).allowed());
        }
        var inactiveLogin=new io.kestra.webserver.oidc.TrustedIdentityV1.Identity(1,"https://issuer.example","operator","client",Set.of("api"),"main","target",Set.of(),Set.of(),0,2,2);
        assertEquals(ApplicationGroupRuntimePolicies.Failure.INVALID_IDENTITY,assertThrows(ApplicationGroupRuntimePolicies.Rejected.class,()->runtime.open(inactiveLogin,"main")).failure());
    }
    @Test void runtimeFactoryDoesNotBorrowConnectionsOrCreatePolicySchema() throws Exception {
        var source=org.mockito.Mockito.mock(javax.sql.DataSource.class);
        assertNotNull(new ApplicationGroupRuntimeConfiguration().applicationGroupPolicyStore(source,org.mockito.Mockito.mock(io.kestra.webserver.oidc.OidcProtocolStateV1.class)));
        org.mockito.Mockito.verify(source,org.mockito.Mockito.never()).getConnection();
        org.mockito.Mockito.verify(source,org.mockito.Mockito.never()).getConnection(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString());
    }
    private ScopeView scope(String name) {
        return new ScopeView("p",name,"Scope","",1);
    }
    private Boundary boundary(List<ScopeView> scopes,String tenant,List<ProtocolScope> protocols){
        return new Boundary(new GroupView("p",tenant,"https://issuer.example","P",true,3,97),scopes,Set.of("ui"),Set.of(),protocols);
    }
    private void rejects(Boundary boundary){
        var failure=assertThrows(ApplicationGroupRuntimePolicies.Rejected.class,()->ApplicationGroupRuntimePolicies.projectBoundaries(Map.of("p",boundary)));
        assertEquals(ApplicationGroupRuntimePolicies.Failure.INVALID_DIRECTORY,failure.failure());
    }
    @Test void duplicateScopeAndProtocolOverlapAreRejected() {
        var scope=scope("read");
        rejects(boundary(List.of(scope,scope),"main",List.of()));
        rejects(boundary(List.of(scope),"main",List.of(new ProtocolScope("read","STANDARD"))));
    }
    @Test void wrongTenantAndProjectKeyAreRejected() {
        var scope=scope("read");
        rejects(boundary(List.of(scope),"other",List.of()));
        assertThrows(ApplicationGroupRuntimePolicies.Rejected.class,()->ApplicationGroupRuntimePolicies.projectBoundaries(Map.of("wrong",boundary(List.of(scope),"main",List.of()))));
    }
    @Test void groupScopeProjectsNoKestraResourcePermissions() {
        var projected=ApplicationGroupRuntimePolicies.projectBoundaries(Map.of("p",boundary(List.of(scope("read")),"main",List.of())));
        var shared=projected.get("p").upperBound().scopes().get("read");
        assertEquals("Scope",shared.name());
        assertEquals("",shared.description());
    }
    @Test void unboundEmptyGroupDoesNotBlockIdentityManagement(){
        var manager=new ScopeView("p","manager","Manager","",1);
        var console=boundary(List.of(manager),"main",List.of());
        var empty=new Boundary(new GroupView("new-group","main","https://issuer.example","New",true,1,1),
            List.of(),Set.of(),Set.of(),List.of());
        var localScope=new io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.ScopeDefinition("Manager",true,List.of(),List.of(
            new io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.ManagementGrant(
                io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.ManagementDomain.IDENTITY,
                Set.of(io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.ManagementAction.MANAGE))));
        var policy=new io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy(2,Map.of("p",
            new io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.ApplicationGroup("https://issuer.example",Set.of("ui"),true,Map.of("manager",localScope))));
        var store=org.mockito.Mockito.mock(io.kestra.extensions.rbac.store.ApplicationGroupPolicyStore.class);
        org.mockito.Mockito.when(store.readCurrent()).thenReturn(new io.kestra.extensions.rbac.store.ApplicationGroupPolicyStore.Snapshot(
            1,0,"operator",java.time.Instant.EPOCH,policy,"{}"));
        var directory=org.mockito.Mockito.mock(ApplicationGroupDirectoryReader.class);
        org.mockito.Mockito.when(directory.boundaries()).thenReturn(List.of(console,empty));
        var identities=org.mockito.Mockito.mock(io.kestra.webserver.oidc.TrustedIdentityV1.class);
        var identity=new io.kestra.webserver.oidc.TrustedIdentityV1.Identity(1,"https://issuer.example","operator","ui-client",Set.of("ui"),
            "main","p",Set.of(),Set.of("manager"),0,97,3);
        var runtime=new ApplicationGroupRuntimePolicies(store,directory,identities);
        var negativeVersion=new io.kestra.webserver.oidc.TrustedIdentityV1.Identity(1,"https://issuer.example","operator","ui-client",Set.of("ui"),
            "main","p",Set.of(),Set.of("manager"),-1,97,3);
        assertEquals(ApplicationGroupRuntimePolicies.Failure.INVALID_IDENTITY,
            assertThrows(ApplicationGroupRuntimePolicies.Rejected.class,()->runtime.open(negativeVersion,"main")).failure());
        try(var context=runtime.open(identity,"main")){
            assertTrue(context.authorizeManagement(io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.ManagementDomain.IDENTITY,
                io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.ManagementAction.MANAGE,"new-group"));
            assertSame(empty,context.upperBound("new-group"));
            assertFalse(context.directories().containsKey("new-group"));
            var failure=assertThrows(ApplicationGroupRuntimePolicies.Rejected.class,()->context.bindingBoundary("new-group"));
            assertEquals(ApplicationGroupRuntimePolicies.Failure.INVALID_CANDIDATE,failure.failure());
        }
        // The same empty boundary is an error once it is required by a local binding.
        assertThrows(ApplicationGroupRuntimePolicies.Rejected.class,()->ApplicationGroupRuntimePolicies.projectBoundaries(
            Map.of("p",console,"new-group",empty),Set.of("p","new-group")));
        assertThrows(ApplicationGroupRuntimePolicies.Rejected.class,()->ApplicationGroupRuntimePolicies.projectBoundaries(Map.of("p",console),Set.of("p","missing-bound-project")));
    }
}
