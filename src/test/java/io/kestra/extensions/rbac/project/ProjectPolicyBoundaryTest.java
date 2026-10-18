package io.kestra.extensions.rbac.project;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProjectPolicyBoundaryTest {
    private ProjectPolicy.Grant grant(String ns,String action) {
        return new ProjectPolicy.Grant("NAMESPACE","main",ns,false,"FLOW",Set.of(action),Set.of(),true);
    }
    private ProjectPolicy.ScopeDefinition scope(ProjectPolicy.Grant... grants) {
        return new ProjectPolicy.ScopeDefinition("",true,List.of(grants));
    }
    private ProjectPolicy.Project project(Map<String,ProjectPolicy.ScopeDefinition> scopes) {
        return new ProjectPolicy.Project("https://issuer.invalid",Set.of("ui"),true,1,scopes);
    }
    private ProjectAuthorization.Directory directory(String id,ProjectPolicy.Project project) {
        return new ProjectAuthorization.Directory(project,id,Set.of("openid"));
    }
    @Test void saveValidatesEveryBindingWithoutBorrowingAnotherScopesCeiling() {
        var upper=project(Map.of("a",scope(grant("a","READ")),"b",scope(grant("b","UPDATE"))));
        var directories=Map.of("p",directory("p",upper));
        assertDoesNotThrow(()->ProjectPolicyBoundary.validate(new ProjectPolicy(2,Map.of("p",upper)),directories));
        var wrong=project(Map.of("a",scope(grant("b","UPDATE"))));
        assertThrows(IllegalArgumentException.class,()->ProjectPolicyBoundary.validate(new ProjectPolicy(2,Map.of("p",wrong)),directories));
        var unknown=project(Map.of("unknown",scope(grant("a","READ"))));
        assertThrows(IllegalArgumentException.class,()->ProjectPolicyBoundary.validate(new ProjectPolicy(2,Map.of("p",unknown)),directories));
        assertThrows(IllegalArgumentException.class,()->ProjectPolicyBoundary.validate(new ProjectPolicy(2,Map.of("other",upper)),directories));
    }
    @Test void managementTargetsNeedSameScopeUpperBoundAndActualSharedTarget() {
        var management=new ProjectPolicy.ManagementGrant(ProjectPolicy.ManagementDomain.LOCAL_POLICY,Set.of(ProjectPolicy.ManagementAction.MANAGE),Set.of("target"));
        var upper=project(Map.of("manager",new ProjectPolicy.ScopeDefinition("",true,List.of(),List.of(management))));
        var policy=new ProjectPolicy(2,Map.of("p",upper));
        assertThrows(IllegalArgumentException.class,()->ProjectPolicyBoundary.validate(policy,Map.of("p",directory("p",upper))));
        assertDoesNotThrow(()->ProjectPolicyBoundary.validate(policy,Map.of("p",directory("p",upper),"target",directory("target",project(Map.of())))));
        var expanded=new ProjectPolicy.ManagementGrant(ProjectPolicy.ManagementDomain.LOCAL_POLICY,Set.of(ProjectPolicy.ManagementAction.MANAGE),Set.of("p","target"));
        var local=project(Map.of("manager",new ProjectPolicy.ScopeDefinition("",true,List.of(),List.of(expanded))));
        assertThrows(IllegalArgumentException.class,()->ProjectPolicyBoundary.validate(new ProjectPolicy(2,Map.of("p",local)),Map.of("p",directory("p",upper),"target",directory("target",project(Map.of())))));
    }
    @Test void staleProjectAndWrongIssuerAndAudienceCannotBeConfirmed() {
        var upper=project(Map.of("a",scope(grant("a","READ"))));
        for(var invalid:List.of(
            new ProjectPolicy.Project(upper.issuer(),upper.audiences(),true,2,upper.scopes()),
            new ProjectPolicy.Project("https://other.invalid",upper.audiences(),true,1,upper.scopes()),
            new ProjectPolicy.Project(upper.issuer(),Set.of("other"),true,1,upper.scopes())))
            assertThrows(IllegalArgumentException.class,()->ProjectPolicyBoundary.validate(new ProjectPolicy(2,Map.of("p",invalid)),Map.of("p",directory("p",upper))));
    }
}
