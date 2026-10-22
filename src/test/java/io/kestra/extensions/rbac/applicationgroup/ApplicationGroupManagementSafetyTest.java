package io.kestra.extensions.rbac.applicationgroup;

import java.util.Map;
import java.util.Set;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ApplicationGroupManagementSafetyTest {
    private ApplicationGroupPolicy.ScopeDefinition manager(Set<String> targets) {
        return new ApplicationGroupPolicy.ScopeDefinition("",true,List.of(),List.of(new ApplicationGroupPolicy.ManagementGrant(
            ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,Set.of(ApplicationGroupPolicy.ManagementAction.MANAGE),targets)));
    }
    private ApplicationGroupPolicy.ScopeDefinition reader() {
        return new ApplicationGroupPolicy.ScopeDefinition("",true,List.of(new ApplicationGroupPolicy.Grant("NAMESPACE","main","a",false,"FLOW",Set.of("READ"),Set.of(),true)));
    }
    private ApplicationGroupPolicy.ApplicationGroup project(Map<String,ApplicationGroupPolicy.ScopeDefinition> scopes) {
        return new ApplicationGroupPolicy.ApplicationGroup("https://issuer.invalid",Set.of("ui"),true,scopes);
    }
    private ApplicationGroupManagementSafety.DirectoryState state(String id,ApplicationGroupPolicy.ApplicationGroup upper,String... reachable) {
        return new ApplicationGroupManagementSafety.DirectoryState(new ApplicationGroupAuthorization.Directory(DirectoryCatalogTestData.catalog(upper,1),id,Set.of("openid")),Set.of(reachable));
    }
    @Test void lastManagerNeedsSameScopeAuthorityAndReachableIdentity() {
        var project=project(Map.of("manage",manager(Set.of()),"read",reader()));
        var policy=new ApplicationGroupPolicy(2,Map.of("p",project));
        assertDoesNotThrow(()->ApplicationGroupManagementSafety.validate(policy,Map.of("p",state("p",project,"manage"))));
        assertThrows(IllegalArgumentException.class,()->ApplicationGroupManagementSafety.validate(policy,Map.of("p",state("p",project,"read"))));
        assertThrows(IllegalArgumentException.class,()->ApplicationGroupManagementSafety.validate(policy,Map.of("p",state("p",project))));
        var removed=project(Map.of("read",reader()));
        assertThrows(IllegalArgumentException.class,()->ApplicationGroupManagementSafety.validate(new ApplicationGroupPolicy(2,Map.of("p",removed)),Map.of("p",state("p",project,"manage"))));
        var orphaned=project(Map.of("future",manager(Set.of())));
        assertThrows(IllegalArgumentException.class,()->ApplicationGroupManagementSafety.validate(new ApplicationGroupPolicy(2,Map.of("p",orphaned)),Map.of("p",state("p",removed,"future"))));
    }
    @Test void explicitTargetRequiresManagerForEachEnabledProjectWithoutImplicitOwner() {
        var owner=project(Map.of("self",manager(Set.of()),"target",manager(Set.of("q"))));
        var target=project(Map.of());
        var policy=new ApplicationGroupPolicy(2,Map.of("p",owner,"q",target));
        var good=Map.of("p",state("p",owner,"self","target"),"q",state("q",target));
        assertDoesNotThrow(()->ApplicationGroupManagementSafety.validate(policy,good));
        assertThrows(IllegalArgumentException.class,()->ApplicationGroupManagementSafety.validate(policy,Map.of("p",state("p",owner,"self"),"q",state("q",target))));
        assertThrows(IllegalArgumentException.class,()->ApplicationGroupManagementSafety.validate(policy,Map.of("p",state("p",owner,"target"),"q",state("q",target))));
    }
    @Test void disabledLocalOrSharedManagementCannotKeepLastAdministratorAlive() {
        var definition=manager(Set.of());
        var disabled=new ApplicationGroupPolicy.ScopeDefinition("",false,List.of(),definition.managementGrants());
        var upper=project(Map.of("m",definition));
        var local=project(Map.of("m",disabled));
        assertThrows(IllegalArgumentException.class,()->ApplicationGroupManagementSafety.validate(new ApplicationGroupPolicy(2,Map.of("p",local)),Map.of("p",state("p",upper,"m"))));
        var disabledProject=new ApplicationGroupPolicy.ApplicationGroup(local.issuer(),local.audiences(),false,local.scopes());
        assertDoesNotThrow(()->ApplicationGroupManagementSafety.validate(new ApplicationGroupPolicy(2,Map.of("p",disabledProject)),Map.of("p",state("p",upper))));
    }
}
