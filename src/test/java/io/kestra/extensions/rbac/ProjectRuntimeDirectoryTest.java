package io.kestra.extensions.rbac;

import io.kestra.webserver.oidc.ApplicationDirectoryV1;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.kestra.webserver.oidc.ApplicationDirectoryV1.*;

/** Tests the plugin adapter, independently of the host directory implementation. */
class ProjectRuntimeDirectoryTest {
    private void managementTarget(boolean active,String issuer,String tenant,boolean present,boolean bound) {
        var intent=new ManagementIntent(ManagementDomain.LOCAL_POLICY,Set.of("MANAGE"),Set.of("p","new-project"));
        var manager=new ScopeDefinition("p","manager","Manager","",true,List.of(),List.of(intent),1);
        var console=new PolicyBoundary(new Project("p","main","https://issuer.example","P",true,3,97),List.of(manager),Set.of("ui"),Set.of("manager"),List.of());
        var target=new PolicyBoundary(new Project("new-project",tenant,issuer,"New",active,1,1),List.of(),Set.of(),Set.of(),List.of());
        var grant=new io.kestra.extensions.rbac.project.ProjectPolicy.ManagementGrant(io.kestra.extensions.rbac.project.ProjectPolicy.ManagementDomain.LOCAL_POLICY,
            Set.of(io.kestra.extensions.rbac.project.ProjectPolicy.ManagementAction.MANAGE),Set.of("p","new-project"));
        var scope=new io.kestra.extensions.rbac.project.ProjectPolicy.ScopeDefinition("Manager",true,List.of(),List.of(grant));
        var projects=new HashMap<String,io.kestra.extensions.rbac.project.ProjectPolicy.Project>();
        projects.put("p",new io.kestra.extensions.rbac.project.ProjectPolicy.Project("https://issuer.example",Set.of("ui"),true,3,Map.of("manager",scope)));
        if(bound)projects.put("new-project",new io.kestra.extensions.rbac.project.ProjectPolicy.Project("https://issuer.example",Set.of("api"),true,1,Map.of()));
        var policy=new io.kestra.extensions.rbac.project.ProjectPolicy(2,projects);
        var store=org.mockito.Mockito.mock(io.kestra.extensions.rbac.store.ProjectPolicyStore.class);
        org.mockito.Mockito.when(store.readCurrent()).thenReturn(new io.kestra.extensions.rbac.store.ProjectPolicyStore.Snapshot(1,0,"operator",java.time.Instant.EPOCH,policy,"{}"));
        var directory=org.mockito.Mockito.mock(ApplicationDirectoryV1.class);
        org.mockito.Mockito.when(directory.policyBoundaries()).thenReturn(present?List.of(console,target):List.of(console));
        var identity=new io.kestra.webserver.oidc.TrustedIdentityV1.Identity(1,"https://issuer.example","operator","ui-client",Set.of("ui"),"main","p",Set.of(),Set.of("manager"),0,97,3);
        var runtime=new ProjectRuntimePolicies(store,directory,org.mockito.Mockito.mock(io.kestra.webserver.oidc.TrustedIdentityV1.class));
        if(!issuer.equals("https://issuer.example") || !tenant.equals("main") || !present || bound){
            assertEquals(ProjectRuntimePolicies.Failure.INVALID_DIRECTORY,assertThrows(ProjectRuntimePolicies.Rejected.class,()->runtime.open(identity,"main")).failure());
        }else try(var context=runtime.open(identity,"main")){
            assertFalse(context.directories().containsKey("new-project"));
            assertTrue(context.authorizeManagement(io.kestra.extensions.rbac.project.ProjectPolicy.ManagementDomain.LOCAL_POLICY,io.kestra.extensions.rbac.project.ProjectPolicy.ManagementAction.MANAGE,"new-project"));
            assertFalse(context.authorizeManagement(io.kestra.extensions.rbac.project.ProjectPolicy.ManagementDomain.LOCAL_POLICY,io.kestra.extensions.rbac.project.ProjectPolicy.ManagementAction.MANAGE,"unknown"));
            context.validateCandidate(policy);
            assertThrows(ProjectRuntimePolicies.Rejected.class,()->context.bindingBoundary("new-project"));
        }
    }
    @Test void managementReferenceWithoutAudienceDoesNotCreateLoginOrBindingAuthority(){managementTarget(true,"https://issuer.example","main",true,false);}
    @Test void missingManagementReferenceFailsClosed(){managementTarget(true,"https://issuer.example","main",false,false);}
    @Test void disabledManagementReferenceRemainsManageable(){managementTarget(false,"https://issuer.example","main",true,false);}
    @Test void foreignIssuerManagementReferenceFailsClosed(){managementTarget(true,"https://other.example","main",true,false);}
    @Test void foreignTenantManagementReferenceFailsClosed(){managementTarget(true,"https://issuer.example","other",true,false);}
    @Test void actualLocalBindingStillRequiresAudience(){managementTarget(true,"https://issuer.example","main",true,true);}
    @Test void inactiveBoundTargetCanBeReconciledAndReferenceWithdrawnWithoutLoginOrBusinessAuthority(){
        var domain=io.kestra.extensions.rbac.project.ProjectPolicy.ManagementDomain.LOCAL_POLICY;
        var action=io.kestra.extensions.rbac.project.ProjectPolicy.ManagementAction.MANAGE;
        var sharedScope=new ScopeDefinition("p","manager","Manager","",true,List.of(),List.of(new ManagementIntent(ManagementDomain.LOCAL_POLICY,Set.of("MANAGE"),Set.of("p","target"))),1);
        var console=new PolicyBoundary(new Project("p","main","https://issuer.example","P",true,3,97),List.of(sharedScope),Set.of("ui"),Set.of("manager"),List.of());
        var target=new PolicyBoundary(new Project("target","main","https://issuer.example","Target",false,2,2),List.of(),Set.of("api"),Set.of(),List.of());
        var localScope=new io.kestra.extensions.rbac.project.ProjectPolicy.ScopeDefinition("Manager",true,List.of(),List.of(new io.kestra.extensions.rbac.project.ProjectPolicy.ManagementGrant(domain,Set.of(action),Set.of("p","target"))));
        var owner=new io.kestra.extensions.rbac.project.ProjectPolicy.Project("https://issuer.example",Set.of("ui"),true,3,Map.of("manager",localScope));
        var disabled=new io.kestra.extensions.rbac.project.ProjectPolicy.Project("https://issuer.example",Set.of("api"),false,1,Map.of());
        var policy=new io.kestra.extensions.rbac.project.ProjectPolicy(2,Map.of("p",owner,"target",disabled));
        var store=org.mockito.Mockito.mock(io.kestra.extensions.rbac.store.ProjectPolicyStore.class);
        org.mockito.Mockito.when(store.readCurrent()).thenReturn(new io.kestra.extensions.rbac.store.ProjectPolicyStore.Snapshot(1,0,"operator",java.time.Instant.EPOCH,policy,"{}"));
        var directory=org.mockito.Mockito.mock(ApplicationDirectoryV1.class);
        org.mockito.Mockito.when(directory.policyBoundaries()).thenReturn(List.of(console,target));
        var runtime=new ProjectRuntimePolicies(store,directory,org.mockito.Mockito.mock(io.kestra.webserver.oidc.TrustedIdentityV1.class));
        var identity=new io.kestra.webserver.oidc.TrustedIdentityV1.Identity(1,"https://issuer.example","operator","ui-client",Set.of("ui"),"main","p",Set.of(),Set.of("manager"),0,97,3);
        try(var context=runtime.open(identity,"main")){
            assertTrue(context.authorizeReconciliation("target"));
            var confirmed=new io.kestra.extensions.rbac.project.ProjectPolicy.Project(disabled.issuer(),disabled.audiences(),false,2,Map.of());
            context.validateCandidate(new io.kestra.extensions.rbac.project.ProjectPolicy(2,Map.of("p",owner,"target",confirmed)));
            var enabled=new io.kestra.extensions.rbac.project.ProjectPolicy.Project(disabled.issuer(),disabled.audiences(),true,2,Map.of());
            assertEquals(ProjectRuntimePolicies.Failure.INVALID_CANDIDATE,assertThrows(ProjectRuntimePolicies.Rejected.class,()->context.validateCandidate(new io.kestra.extensions.rbac.project.ProjectPolicy(2,Map.of("p",owner,"target",enabled)))).failure());
            var withdrawn=new io.kestra.extensions.rbac.project.ProjectPolicy.ScopeDefinition("Manager",true,List.of(),List.of(new io.kestra.extensions.rbac.project.ProjectPolicy.ManagementGrant(domain,Set.of(action),Set.of("p"))));
            var restored=new io.kestra.extensions.rbac.project.ProjectPolicy.Project(owner.issuer(),owner.audiences(),true,3,Map.of("manager",withdrawn));
            context.validateCandidate(new io.kestra.extensions.rbac.project.ProjectPolicy(2,Map.of("p",restored,"target",confirmed)));
            var businessIdentity=new io.kestra.extensions.rbac.project.ProjectAuthorization.Identity("operator",disabled.issuer(),"api","target",2,Set.of(),Set.of(),"main");
            var request=new io.kestra.extensions.rbac.project.ProjectAuthorization.Request("main","a","FLOW","READ","flow",false);
            assertFalse(new io.kestra.extensions.rbac.project.ProjectAuthorization(policy).authorize(businessIdentity,request,context.directories().get("target")).allowed());
        }
        var inactiveLogin=new io.kestra.webserver.oidc.TrustedIdentityV1.Identity(1,"https://issuer.example","operator","client",Set.of("api"),"main","target",Set.of(),Set.of(),0,2,2);
        assertEquals(ProjectRuntimePolicies.Failure.INVALID_IDENTITY,assertThrows(ProjectRuntimePolicies.Rejected.class,()->runtime.open(inactiveLogin,"main")).failure());
    }
    @Test void runtimeFactoryDoesNotBorrowConnectionsOrCreatePolicySchema() throws Exception {
        var source=org.mockito.Mockito.mock(javax.sql.DataSource.class);
        assertNotNull(new ProjectRuntimeConfiguration().projectPolicyStore(source));
        org.mockito.Mockito.verify(source,org.mockito.Mockito.never()).getConnection();
        org.mockito.Mockito.verify(source,org.mockito.Mockito.never()).getConnection(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString());
    }
    private ResourceIntent intent(String vocabulary,String resource,Set<String> actions,DomainKind kind){
        return new ResourceIntent(vocabulary,resource,new NamespaceDomain(kind,kind==DomainKind.ALL?Set.of():Set.of("a"),Set.of()),
            new ResourceIdDomain(DomainKind.ALL,Set.of()),actions);
    }
    private ScopeDefinition scope(String name,ResourceIntent intent){
        return new ScopeDefinition("p",name,"Scope","",true,List.of(intent),List.of(),1);
    }
    private PolicyBoundary boundary(List<ScopeDefinition> scopes,String tenant,List<ProtocolScope> protocols){
        return new PolicyBoundary(new Project("p",tenant,"https://issuer.example","P",true,3,97),scopes,Set.of("ui"),Set.of(),protocols);
    }
    private void rejects(PolicyBoundary boundary){
        var failure=assertThrows(ProjectRuntimePolicies.Rejected.class,()->ProjectRuntimePolicies.project(Map.of("p",boundary)));
        assertEquals(ProjectRuntimePolicies.Failure.INVALID_DIRECTORY,failure.failure());
    }
    @Test void ordinaryAllNamespaceIntentIsRejected(){
        rejects(boundary(List.of(scope("read",intent("kestra-resources-v1","FLOW",Set.of("READ"),DomainKind.ALL))),"main",List.of()));
    }
    @Test void unknownVocabularyIsRejected(){
        rejects(boundary(List.of(scope("read",intent("future","FLOW",Set.of("READ"),DomainKind.RESTRICTED))),"main",List.of()));
    }
    @Test void unknownResourceAndActionAreRejected(){
        rejects(boundary(List.of(scope("read",intent("kestra-resources-v1","FUTURE",Set.of("READ"),DomainKind.RESTRICTED))),"main",List.of()));
        rejects(boundary(List.of(scope("read",intent("kestra-resources-v1","FLOW",Set.of("FUTURE"),DomainKind.RESTRICTED))),"main",List.of()));
    }
    @Test void duplicateScopeAndProtocolOverlapAreRejected(){
        var scope=scope("read",intent("kestra-resources-v1","FLOW",Set.of("READ"),DomainKind.RESTRICTED));
        rejects(boundary(List.of(scope,scope),"main",List.of()));
        rejects(boundary(List.of(scope),"main",List.of(new ProtocolScope("read","STANDARD"))));
    }
    @Test void wrongTenantAndProjectKeyAreRejected(){
        var scope=scope("read",intent("kestra-resources-v1","FLOW",Set.of("READ"),DomainKind.RESTRICTED));
        rejects(boundary(List.of(scope),"other",List.of()));
        assertThrows(ProjectRuntimePolicies.Rejected.class,()->ProjectRuntimePolicies.project(Map.of("wrong",boundary(List.of(scope),"main",List.of()))));
    }
    @Test void concreteScopeKeepsOnlyItsOwnBoundary(){
        var scope=scope("read",intent("kestra-resources-v1","FLOW",Set.of("READ"),DomainKind.RESTRICTED));
        var projected=ProjectRuntimePolicies.project(Map.of("p",boundary(List.of(scope),"main",List.of())));
        var grant=projected.get("p").upperBound().scopes().get("read").grants().getFirst();
        assertEquals("a",grant.namespace());assertFalse(grant.descendants());
    }
    @Test void explicitTenantMetadataExceptionRemainsReadOnly(){
        var scope=scope("metadata",intent("kestra-resources-v1","METADATA",Set.of("READ","LIST"),DomainKind.ALL));
        var projected=ProjectRuntimePolicies.project(Map.of("p",boundary(List.of(scope),"main",List.of())));
        assertEquals("TENANT",projected.get("p").upperBound().scopes().get("metadata").grants().getFirst().scope());
        rejects(boundary(List.of(scope("metadata",intent("kestra-resources-v1","METADATA",Set.of("UPDATE"),DomainKind.ALL))),"main",List.of()));
    }
    @Test void unboundEmptyProjectDoesNotBlockIdentityManagementOrClientApproval(){
        var management=new ManagementIntent(ManagementDomain.IDENTITY,Set.of("MANAGE"),Set.of());
        var manager=new ScopeDefinition("p","manager","Manager","",true,List.of(),List.of(management),1);
        var console=boundary(List.of(manager),"main",List.of());
        var empty=new PolicyBoundary(new Project("new-project","main","https://issuer.example","New",true,1,1),
            List.of(),Set.of(),Set.of(),List.of());
        var localScope=new io.kestra.extensions.rbac.project.ProjectPolicy.ScopeDefinition("Manager",true,List.of(),List.of(
            new io.kestra.extensions.rbac.project.ProjectPolicy.ManagementGrant(
                io.kestra.extensions.rbac.project.ProjectPolicy.ManagementDomain.IDENTITY,
                Set.of(io.kestra.extensions.rbac.project.ProjectPolicy.ManagementAction.MANAGE))));
        var policy=new io.kestra.extensions.rbac.project.ProjectPolicy(2,Map.of("p",
            new io.kestra.extensions.rbac.project.ProjectPolicy.Project("https://issuer.example",Set.of("ui"),true,3,Map.of("manager",localScope))));
        var store=org.mockito.Mockito.mock(io.kestra.extensions.rbac.store.ProjectPolicyStore.class);
        org.mockito.Mockito.when(store.readCurrent()).thenReturn(new io.kestra.extensions.rbac.store.ProjectPolicyStore.Snapshot(
            1,0,"operator",java.time.Instant.EPOCH,policy,"{}"));
        var directory=org.mockito.Mockito.mock(ApplicationDirectoryV1.class);
        org.mockito.Mockito.when(directory.policyBoundaries()).thenReturn(List.of(console,empty));
        var identities=org.mockito.Mockito.mock(io.kestra.webserver.oidc.TrustedIdentityV1.class);
        var identity=new io.kestra.webserver.oidc.TrustedIdentityV1.Identity(1,"https://issuer.example","operator","ui-client",Set.of("ui"),
            "main","p",Set.of(),Set.of("manager"),0,97,3);
        var runtime=new ProjectRuntimePolicies(store,directory,identities);
        var negativeVersion=new io.kestra.webserver.oidc.TrustedIdentityV1.Identity(1,"https://issuer.example","operator","ui-client",Set.of("ui"),
            "main","p",Set.of(),Set.of("manager"),-1,97,3);
        assertEquals(ProjectRuntimePolicies.Failure.INVALID_IDENTITY,
            assertThrows(ProjectRuntimePolicies.Rejected.class,()->runtime.open(negativeVersion,"main")).failure());
        try(var context=runtime.open(identity,"main")){
            assertTrue(context.authorizeManagement(io.kestra.extensions.rbac.project.ProjectPolicy.ManagementDomain.IDENTITY,
                io.kestra.extensions.rbac.project.ProjectPolicy.ManagementAction.MANAGE,"new-project"));
            assertSame(empty,context.upperBound("new-project"));
            assertFalse(context.directories().containsKey("new-project"));
            var failure=assertThrows(ProjectRuntimePolicies.Rejected.class,()->context.bindingBoundary("new-project"));
            assertEquals(ProjectRuntimePolicies.Failure.INVALID_CANDIDATE,failure.failure());
            var input=new MembershipInput("non-ui-client",true,Set.of("api"),Set.of(),false);
            directory.approveClient("new-project",1,input);
            org.mockito.Mockito.verify(directory).approveClient("new-project",1,input);
        }
        // The same empty boundary is an error once it is required by a local binding.
        assertThrows(ProjectRuntimePolicies.Rejected.class,()->ProjectRuntimePolicies.project(
            Map.of("p",console,"new-project",empty),Set.of("p","new-project")));
        assertThrows(ProjectRuntimePolicies.Rejected.class,()->ProjectRuntimePolicies.project(Map.of("p",console),Set.of("p","missing-bound-project")));
    }
}
