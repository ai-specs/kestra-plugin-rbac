package io.kestra.extensions.rbac.applicationgroup;

import java.util.Map;
import java.util.Set;

/** Consumes only an immutable, host-verified directory projection. Does not inspect IdP tables. */
public final class ApplicationGroupManagementSafety {
    private ApplicationGroupManagementSafety() {}
    public record DirectoryState(ApplicationGroupAuthorization.Directory boundary, Set<String> reachableManagementScopes) {
        public DirectoryState { reachableManagementScopes=Set.copyOf(reachableManagementScopes); }
    }
    public static void validate(ApplicationGroupPolicy candidate, Map<String,DirectoryState> states) {
        validate(candidate,states,states.keySet());
    }
    public static void validate(ApplicationGroupPolicy candidate, Map<String,DirectoryState> states, Set<String> managementTargets) {
        validate(candidate,states,managementTargets,null);
    }
    public static void validate(ApplicationGroupPolicy candidate, Map<String,DirectoryState> states, Set<String> managementTargets,ApplicationGroupPolicy previous) {
        states=Map.copyOf(states);
        var boundaries=new java.util.HashMap<String,ApplicationGroupAuthorization.Directory>();
        states.forEach((id,state)->boundaries.put(id,state.boundary()));
        ApplicationGroupPolicyBoundary.validate(candidate,boundaries,managementTargets,previous);
        for(var target:candidate.applicationGroups().entrySet()) {
            if(!target.getValue().enabled() || !states.containsKey(target.getKey()))continue;
            boolean reachable=false;
            for(var owner:candidate.applicationGroups().entrySet()) {
                var local=owner.getValue(); var state=states.get(owner.getKey());
                if(!local.enabled() || state==null || !state.boundary().upperBound().enabled())continue;
                for(var scope:local.scopes().entrySet()) {
                    if(!scope.getValue().enabled() || !state.reachableManagementScopes().contains(scope.getKey()))continue;
                    var ceiling=state.boundary().upperBound().scopes().get(scope.getKey());
                    if(ceiling==null)continue;
                    boolean localManage=scope.getValue().managementGrants().stream().anyMatch(grant->manages(grant,owner.getKey(),target.getKey()));
                    if(localManage){reachable=true;break;}
                }
                if(reachable)break;
            }
            if(!reachable)throw new IllegalArgumentException("No reachable local policy administrator for project: "+target.getKey());
        }
    }
    private static boolean manages(ApplicationGroupPolicy.ManagementGrant grant,String owner,String target) {
        return grant.domain()==ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY
            && grant.actions().contains(ApplicationGroupPolicy.ManagementAction.MANAGE)
            && ApplicationGroupAuthorization.resolvedTargets(grant,owner).contains(target);
    }
}
