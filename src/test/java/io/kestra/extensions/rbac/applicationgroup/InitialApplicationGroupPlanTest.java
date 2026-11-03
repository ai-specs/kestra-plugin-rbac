package io.kestra.extensions.rbac.applicationgroup;

import java.util.Set;
import org.junit.jupiter.api.Test;
import static io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.*;
import static org.junit.jupiter.api.Assertions.*;

class InitialApplicationGroupPlanTest {
    private static ApplicationGroupInstallationPlan plan(String subject) {
        return InitialApplicationGroupPlan.create("https://issuer.example","28e9352d-ea66-4a24-888f-e76fda93fef0","configured-ui",subject);
    }

    @Test void createsOneBuiltinGroupWithInitialVersionsAndOneSubject() {
        var initial=plan("first-user");var snapshot=initial.applicationGroups().getFirst().snapshot();
        assertEquals("main",initial.builtInApplicationGroupId());assertEquals(1,initial.applicationGroups().size());
        assertEquals(1,snapshot.group().revision());assertEquals(1,snapshot.group().authorizationVersion());
        assertEquals(1,snapshot.scopes().size());assertEquals(1,snapshot.applications().size());assertEquals(1,snapshot.userGrants().size());
        assertEquals("first-user",snapshot.userGrants().getFirst().subject());
        assertEquals(Set.of("admin"),snapshot.eligibleScopeKeys("configured-ui","https://issuer.example","first-user"));
        assertEquals(Set.of(),snapshot.eligibleScopeKeys("configured-ui","https://issuer.example","admin"));
        assertEquals(Set.of(),snapshot.eligibleScopeKeys("another-client","https://issuer.example","first-user"));
        assertEquals(1,snapshot.scopes().getFirst().revision());assertEquals(1,snapshot.applications().getFirst().revision());
        assertEquals(1,snapshot.userGrants().getFirst().revision());
    }

    @Test void usesOneActualTypedPermissionAndExplicitManagementTargets() {
        var snapshot=plan("first-user").applicationGroups().getFirst().snapshot();
        var application=snapshot.applications().getFirst();assertEquals(1,application.permissions().size());
        var permission=application.permissions().getFirst();assertEquals("initial-administrator",permission.key());
        assertTrue(permission.enabled());assertEquals(1,permission.revision());assertEquals("",permission.details());
        permission.validate(ApplicationPermission.Kind.KESTRA_POLICY,Set.of("admin"));
        assertEquals(Set.of(ManagementDomain.values()),permission.managementGrants().stream().map(ManagementGrant::domain).collect(java.util.stream.Collectors.toSet()));
        for(var grant:permission.managementGrants()) {
            assertEquals(Set.of(ManagementAction.READ,ManagementAction.MANAGE),grant.actions());
            assertEquals(grant.domain()==ManagementDomain.LOCAL_POLICY?Set.of("main"):Set.of(),grant.applicationGroupIds());
        }
        assertEquals(1,ApplicationPermission.project(application.permissions(),ApplicationPermission.Kind.KESTRA_POLICY).size());
        assertTrue(ApplicationPermission.project(application.permissions(),ApplicationPermission.Kind.EXTERNAL_DECLARATION).isEmpty());
    }

    @Test void confinesBusinessGrantsToCompanyAndPreservesTenantMetadataRestrictions() {
        var permission=plan("first-user").applicationGroups().getFirst().applications().getFirst().permissions().getFirst();
        assertEquals(Set.of(Resource.values()),permission.grants().stream().map(grant->Resource.valueOf(grant.resource())).collect(java.util.stream.Collectors.toSet()));
        for(var grant:permission.grants()) {
            if(grant.resource().equals("METADATA")) {
                assertEquals("TENANT",grant.scope());assertNull(grant.namespace());assertFalse(grant.descendants());
                assertEquals(Set.of("READ","LIST"),grant.actions());
            } else if(grant.resource().equals("MCP_SERVER")) {
                assertEquals("TENANT",grant.scope());assertNull(grant.namespace());assertFalse(grant.descendants());
                assertEquals(Set.of("READ","LIST","CREATE","UPDATE","DELETE"),grant.actions());
            } else {assertEquals("NAMESPACE",grant.scope());assertEquals("company",grant.namespace());assertTrue(grant.descendants());}
            assertEquals("main",grant.tenant());assertTrue(grant.allIds());assertTrue(grant.ids().isEmpty());
        }
        var scopes=ApplicationPermission.project(java.util.List.of(permission),ApplicationPermission.Kind.KESTRA_POLICY);
        var audiences=Set.of("https://issuer.example/api/v1/main/mcp/default");
        var local=new ApplicationGroupPolicy.ApplicationGroup("https://issuer.example",audiences,true,scopes);
        var authorizer=new ApplicationGroupAuthorization(new ApplicationGroupPolicy(2,java.util.Map.of("main",local)));
        var upper=new ApplicationGroupAuthorization.ScopeCatalog("https://issuer.example",audiences,true,1,
            java.util.Map.of("admin",new ApplicationGroupAuthorization.DirectoryScope("Administrator","Initial account")));
        var directory=new ApplicationGroupAuthorization.Directory(upper,"main",Set.of("openid"));
        var identity=new ApplicationGroupAuthorization.Identity("first-user","https://issuer.example","configured-ui","main",1,
            Set.of("admin"),Set.of("openid"),"main",true);
        for(var namespace:java.util.List.of("company","company.team"))
            assertTrue(authorizer.authorize(identity,new ApplicationGroupAuthorization.Request("main",namespace,"FLOW","CREATE","flow",false),directory).allowed());
        for(var namespace:java.util.List.of("other","company-other","other.company"))
            assertFalse(authorizer.authorize(identity,new ApplicationGroupAuthorization.Request("main",namespace,"FLOW","CREATE","flow",false),directory).allowed());
        for(var domain:ManagementDomain.values())for(var action:ManagementAction.values())
            assertTrue(authorizer.authorizeManagement(identity,directory,domain,action));
        assertFalse(authorizer.authorizeManagement(identity,directory,ManagementDomain.LOCAL_POLICY,ManagementAction.MANAGE,"another-group"));
    }

    @Test void neverRegistersAnExternalApplicationOrWildcardAudience() {
        var snapshot=plan("first-user").applicationGroups().getFirst().snapshot();
        assertEquals(java.util.List.of("configured-ui"),snapshot.applications().stream().map(ApplicationGroupDirectory.Application::clientId).toList());
        assertEquals(Set.of("https://issuer.example/api/v1/main/mcp/default"),snapshot.applications().getFirst().audiences());
    }

    @Test void initialSubjectIsNotInferredFromAdministratorName() {
        var snapshot=plan("another-account").applicationGroups().getFirst().snapshot();
        assertEquals("another-account",snapshot.userGrants().getFirst().subject());
        assertTrue(snapshot.eligibleScopeKeys("configured-ui","https://issuer.example","admin").isEmpty());
    }

    @Test void rejectsMalformedTrustedContext() {
        assertThrows(IllegalArgumentException.class,()->plan("invalid subject"));
        assertThrows(IllegalArgumentException.class,()->InitialApplicationGroupPlan.create("https://issuer.example","not-a-uuid","ui","first-user"));
        assertThrows(IllegalArgumentException.class,()->InitialApplicationGroupPlan.create("https://issuer.example?query=1","28e9352d-ea66-4a24-888f-e76fda93fef0","ui","first-user"));
    }
}
