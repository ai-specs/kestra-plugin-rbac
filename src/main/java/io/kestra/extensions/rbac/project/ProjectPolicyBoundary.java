package io.kestra.extensions.rbac.project;

import java.util.Map;
import java.util.Objects;

/** Validates every local binding against one caller-supplied immutable directory snapshot.
 * This contains no IdP storage access and cannot authenticate a subject or protect the last
 * administrator by itself. The published SPI adapter must provide those operations.
 */
public final class ProjectPolicyBoundary {
    private ProjectPolicyBoundary() {}
    public static void validate(ProjectPolicy candidate, Map<String, ProjectAuthorization.Directory> directories) {
        Objects.requireNonNull(candidate); directories=Map.copyOf(directories);
        for (var entry : candidate.applicationProjects().entrySet()) {
            String id=entry.getKey(); var local=entry.getValue(); var directory=directories.get(id);
            if(directory==null || !id.equals(directory.project()) || directory.upperBound()==null)
                throw new IllegalArgumentException("Missing shared project: "+id);
            var upper=directory.upperBound();
            if(!local.issuer().equals(upper.issuer()) || !upper.audiences().containsAll(local.audiences())
                || local.directoryRevision()!=upper.directoryRevision() || local.enabled() && !upper.enabled())
                throw new IllegalArgumentException("Project exceeds shared directory: "+id);
            for(var scopeEntry:local.scopes().entrySet()) {
                var scope=scopeEntry.getValue(); var ceiling=upper.scopes().get(scopeEntry.getKey());
                if(ceiling==null || directory.protocolScopes().contains(scopeEntry.getKey())
                    || scope.enabled() && !ceiling.enabled()
                    || !ProjectAuthorization.managementCovered(scope,ceiling,id)
                    || scope.grants().stream().anyMatch(grant -> ceiling.grants().stream().noneMatch(bound -> ProjectAuthorization.covers(bound,grant))))
                    throw new IllegalArgumentException("Scope exceeds shared directory: "+id+"/"+scopeEntry.getKey());
                for(var grant:scope.managementGrants()) for(String target:grant.projectIds())
                    if(!directories.containsKey(target))throw new IllegalArgumentException("Unknown management target: "+target);
            }
        }
    }
}
