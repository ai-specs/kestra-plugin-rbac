package io.kestra.extensions.rbac.project;

import java.util.Map;
import java.util.Set;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProjectManagementSafetyTest {
    private ProjectPolicy.ScopeDefinition manager(Set<String> targets) {
        return new ProjectPolicy.ScopeDefinition("",true,List.of(),List.of(new ProjectPolicy.ManagementGrant(
            ProjectPolicy.ManagementDomain.LOCAL_POLICY,Set.of(ProjectPolicy.ManagementAction.MANAGE),targets)));
    }
    private ProjectPolicy.ScopeDefinition reader() {
        return new ProjectPolicy.ScopeDefinition("",true,List.of(new ProjectPolicy.Grant("NAMESPACE","main","a",false,"FLOW",Set.of("READ"),Set.of(),true)));
    }
    private ProjectPolicy.Project project(Map<String,ProjectPolicy.ScopeDefinition> scopes) {
        return new ProjectPolicy.Project("https://issuer.invalid",Set.of("ui"),true,1,scopes);
    }
    private ProjectManagementSafety.DirectoryState state(String id,ProjectPolicy.Project upper,String... reachable) {
        return new ProjectManagementSafety.DirectoryState(new ProjectAuthorization.Directory(upper,id,Set.of("openid")),Set.of(reachable));
    }
    @Test void lastManagerNeedsSameScopeAuthorityAndReachableIdentity() {
        var project=project(Map.of("manage",manager(Set.of()),"read",reader()));
        var policy=new ProjectPolicy(2,Map.of("p",project));
        assertDoesNotThrow(()->ProjectManagementSafety.validate(policy,Map.of("p",state("p",project,"manage"))));
        assertThrows(IllegalArgumentException.class,()->ProjectManagementSafety.validate(policy,Map.of("p",state("p",project,"read"))));
        assertThrows(IllegalArgumentException.class,()->ProjectManagementSafety.validate(policy,Map.of("p",state("p",project))));
        var removed=project(Map.of("read",reader()));
        assertThrows(IllegalArgumentException.class,()->ProjectManagementSafety.validate(new ProjectPolicy(2,Map.of("p",removed)),Map.of("p",state("p",project,"manage"))));
    }
    @Test void explicitTargetRequiresManagerForEachEnabledProjectWithoutImplicitOwner() {
        var owner=project(Map.of("self",manager(Set.of()),"target",manager(Set.of("q"))));
        var target=project(Map.of());
        var policy=new ProjectPolicy(2,Map.of("p",owner,"q",target));
        var good=Map.of("p",state("p",owner,"self","target"),"q",state("q",target));
        assertDoesNotThrow(()->ProjectManagementSafety.validate(policy,good));
        assertThrows(IllegalArgumentException.class,()->ProjectManagementSafety.validate(policy,Map.of("p",state("p",owner,"self"),"q",state("q",target))));
        assertThrows(IllegalArgumentException.class,()->ProjectManagementSafety.validate(policy,Map.of("p",state("p",owner,"target"),"q",state("q",target))));
    }
    @Test void disabledLocalOrSharedManagementCannotKeepLastAdministratorAlive() {
        var definition=manager(Set.of());
        var disabled=new ProjectPolicy.ScopeDefinition("",false,List.of(),definition.managementGrants());
        var upper=project(Map.of("m",definition));
        var local=project(Map.of("m",disabled));
        assertThrows(IllegalArgumentException.class,()->ProjectManagementSafety.validate(new ProjectPolicy(2,Map.of("p",local)),Map.of("p",state("p",upper,"m"))));
        var disabledProject=new ProjectPolicy.Project(local.issuer(),local.audiences(),false,1,local.scopes());
        assertDoesNotThrow(()->ProjectManagementSafety.validate(new ProjectPolicy(2,Map.of("p",disabledProject)),Map.of("p",state("p",upper))));
    }
}
