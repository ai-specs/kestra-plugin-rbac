package io.kestra.extensions.rbac.project;

import java.util.Map;
import java.util.Set;

/** Consumes only an immutable, host-verified directory projection. Does not inspect IdP tables. */
public final class ProjectManagementSafety {
    private ProjectManagementSafety() {}
    public record DirectoryState(ProjectAuthorization.Directory boundary, Set<String> reachableManagementScopes) {
        public DirectoryState { reachableManagementScopes=Set.copyOf(reachableManagementScopes); }
    }
    public static void validate(ProjectPolicy candidate, Map<String,DirectoryState> states) {
        validate(candidate,states,states.keySet());
    }
    public static void validate(ProjectPolicy candidate, Map<String,DirectoryState> states, Set<String> managementTargets) {
        states=Map.copyOf(states);
        var boundaries=new java.util.HashMap<String,ProjectAuthorization.Directory>();
        states.forEach((id,state)->boundaries.put(id,state.boundary()));
        ProjectPolicyBoundary.validate(candidate,boundaries,managementTargets);
        for(var target:candidate.applicationProjects().entrySet()) {
            if(!target.getValue().enabled())continue;
            boolean reachable=false;
            for(var owner:candidate.applicationProjects().entrySet()) {
                var local=owner.getValue(); var state=states.get(owner.getKey());
                if(!local.enabled() || state==null || !state.boundary().upperBound().enabled())continue;
                for(var scope:local.scopes().entrySet()) {
                    if(!scope.getValue().enabled() || !state.reachableManagementScopes().contains(scope.getKey()))continue;
                    var ceiling=state.boundary().upperBound().scopes().get(scope.getKey());
                    if(ceiling==null || !ceiling.enabled())continue;
                    boolean localManage=scope.getValue().managementGrants().stream().anyMatch(grant->manages(grant,owner.getKey(),target.getKey()));
                    boolean sharedManage=ceiling.managementGrants().stream().anyMatch(grant->manages(grant,owner.getKey(),target.getKey()));
                    if(localManage && sharedManage){reachable=true;break;}
                }
                if(reachable)break;
            }
            if(!reachable)throw new IllegalArgumentException("No reachable local policy administrator for project: "+target.getKey());
        }
    }
    private static boolean manages(ProjectPolicy.ManagementGrant grant,String owner,String target) {
        return grant.domain()==ProjectPolicy.ManagementDomain.LOCAL_POLICY
            && grant.actions().contains(ProjectPolicy.ManagementAction.MANAGE)
            && ProjectAuthorization.resolvedTargets(grant,owner).contains(target);
    }
}
