package io.kestra.extensions.rbac;

import io.kestra.webserver.oidc.ApplicationDirectoryV1;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.kestra.webserver.oidc.ApplicationDirectoryV1.*;

/** Tests the plugin adapter, independently of the host directory implementation. */
class ProjectRuntimeDirectoryTest {
    private ResourceIntent intent(String vocabulary,String resource,Set<String> actions,DomainKind kind){
        return new ResourceIntent(vocabulary,resource,new NamespaceDomain(kind,kind==DomainKind.ALL?Set.of():Set.of("a"),Set.of()),
            new ResourceIdDomain(DomainKind.ALL,Set.of()),actions);
    }
    private ScopeDefinition scope(String name,ResourceIntent intent){
        return new ScopeDefinition("p",name,"scope",true,List.of(intent),List.of(),1);
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
        var manager=new ScopeDefinition("p","manager","Manager",true,List.of(),List.of(management),1);
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
            "main","p",Set.of(),Set.of("manager"),1,97,3);
        var runtime=new ProjectRuntimePolicies(store,directory,identities);
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
