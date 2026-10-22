package io.kestra.extensions.rbac.applicationgroup;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ApplicationGroupPolicyBoundaryTest {
    private ApplicationGroupPolicy.Grant grant(String ns,String action) {
        return new ApplicationGroupPolicy.Grant("NAMESPACE","main",ns,false,"FLOW",Set.of(action),Set.of(),true);
    }
    private ApplicationGroupPolicy.ScopeDefinition scope(ApplicationGroupPolicy.Grant... grants) {
        return new ApplicationGroupPolicy.ScopeDefinition("",true,List.of(grants));
    }
    private ApplicationGroupPolicy.ApplicationGroup project(Map<String,ApplicationGroupPolicy.ScopeDefinition> scopes) {
        return new ApplicationGroupPolicy.ApplicationGroup("https://issuer.invalid",Set.of("ui"),true,scopes);
    }
    private ApplicationGroupAuthorization.Directory directory(String id,ApplicationGroupPolicy.ApplicationGroup project) {
        return new ApplicationGroupAuthorization.Directory(DirectoryCatalogTestData.catalog(project,1),id,Set.of("openid"));
    }
    @Test void saveValidatesStableScopeKeysWithoutMakingTheDirectoryAnApplicationPolicySource() {
        var upper=project(Map.of("a",scope(grant("a","READ")),"b",scope(grant("b","UPDATE"))));
        var directories=Map.of("p",directory("p",upper));
        assertDoesNotThrow(()->ApplicationGroupPolicyBoundary.validate(new ApplicationGroupPolicy(2,Map.of("p",upper)),directories));
        var wrong=project(Map.of("a",scope(grant("b","UPDATE"))));
        assertDoesNotThrow(()->ApplicationGroupPolicyBoundary.validate(new ApplicationGroupPolicy(2,Map.of("p",wrong)),directories));
        var unknown=project(Map.of("unknown",scope(grant("a","READ"))));
        assertDoesNotThrow(()->ApplicationGroupPolicyBoundary.validate(new ApplicationGroupPolicy(2,Map.of("p",unknown)),directories));
        assertThrows(IllegalArgumentException.class,()->ApplicationGroupPolicyBoundary.validate(new ApplicationGroupPolicy(2,Map.of("other",upper)),directories));
    }
    @Test void managementTargetsMustExistButPermissionsComeFromTheApplicationPolicy() {
        var management=new ApplicationGroupPolicy.ManagementGrant(ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,Set.of(ApplicationGroupPolicy.ManagementAction.MANAGE),Set.of("target"));
        var upper=project(Map.of("manager",new ApplicationGroupPolicy.ScopeDefinition("",true,List.of(),List.of(management))));
        var policy=new ApplicationGroupPolicy(2,Map.of("p",upper));
        assertThrows(IllegalArgumentException.class,()->ApplicationGroupPolicyBoundary.validate(policy,Map.of("p",directory("p",upper))));
        assertDoesNotThrow(()->ApplicationGroupPolicyBoundary.validate(policy,Map.of("p",directory("p",upper),"target",directory("target",project(Map.of())))));
        var expanded=new ApplicationGroupPolicy.ManagementGrant(ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,Set.of(ApplicationGroupPolicy.ManagementAction.MANAGE),Set.of("p","target"));
        var local=project(Map.of("manager",new ApplicationGroupPolicy.ScopeDefinition("",true,List.of(),List.of(expanded))));
        assertDoesNotThrow(()->ApplicationGroupPolicyBoundary.validate(new ApplicationGroupPolicy(2,Map.of("p",local)),Map.of("p",directory("p",upper),"target",directory("target",project(Map.of())))));
        assertThrows(IllegalArgumentException.class,()->ApplicationGroupPolicyBoundary.validate(new ApplicationGroupPolicy(2,Map.of("p",local)),Map.of("p",directory("p",upper))));
    }
    @Test void directoryRevisionChangesDoNotInvalidatePolicyButWrongIssuerAndAudienceAreRejected() {
        var upper=project(Map.of("a",scope(grant("a","READ"))));
        var updatedDirectory=new ApplicationGroupAuthorization.Directory(DirectoryCatalogTestData.catalog(upper,2),"p",Set.of("openid"));
        assertDoesNotThrow(()->ApplicationGroupPolicyBoundary.validate(new ApplicationGroupPolicy(2,Map.of("p",upper)),Map.of("p",updatedDirectory)));
        for(var invalid:List.of(
            new ApplicationGroupPolicy.ApplicationGroup("https://other.invalid",upper.audiences(),true,upper.scopes()),
            new ApplicationGroupPolicy.ApplicationGroup(upper.issuer(),Set.of("other"),true,upper.scopes())))
            assertThrows(IllegalArgumentException.class,()->ApplicationGroupPolicyBoundary.validate(new ApplicationGroupPolicy(2,Map.of("p",invalid)),Map.of("p",directory("p",upper))));
    }
}
