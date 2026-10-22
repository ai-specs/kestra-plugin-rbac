package io.kestra.extensions.rbac.applicationgroup;

import java.util.Map;
import java.util.Objects;

/** Validates every local binding against one caller-supplied immutable directory snapshot.
 * This contains no IdP storage access and cannot authenticate a subject or protect the last
 * administrator by itself. The published SPI adapter must provide those operations.
 */
public final class ApplicationGroupPolicyBoundary {
    private ApplicationGroupPolicyBoundary() {}
    public static void validate(ApplicationGroupPolicy candidate, Map<String, ApplicationGroupAuthorization.Directory> directories) {
        validate(candidate,directories,directories.keySet());
    }
    public static void validate(ApplicationGroupPolicy candidate, Map<String, ApplicationGroupAuthorization.Directory> directories, java.util.Set<String> managementTargets) {
        validate(candidate,directories,managementTargets,null);
    }
    public static void validate(ApplicationGroupPolicy candidate, Map<String, ApplicationGroupAuthorization.Directory> directories, java.util.Set<String> managementTargets,ApplicationGroupPolicy previous) {
        Objects.requireNonNull(candidate); directories=Map.copyOf(directories);
        for (var entry : candidate.applicationGroups().entrySet()) {
            String id=entry.getKey(); var local=entry.getValue(); var directory=directories.get(id);
            var prior=previous==null?null:previous.applicationGroups().get(id);
            if(directory==null && Objects.equals(local,prior))continue;
            if(directory==null || !id.equals(directory.project()) || directory.upperBound()==null)
                throw new IllegalArgumentException("Missing shared project: "+id);
            var upper=directory.upperBound();
            if(!local.issuer().equals(upper.issuer()) || !upper.audiences().containsAll(local.audiences())
                || local.enabled() && !upper.enabled())
                throw new IllegalArgumentException("Project exceeds shared directory: "+id);
            for(var scopeEntry:local.scopes().entrySet()) {
                var scope=scopeEntry.getValue();
                if(directory.protocolScopes().contains(scopeEntry.getKey())
                    || !scopeEntry.getKey().matches("[a-z][a-z0-9-]{0,63}"))
                    throw new IllegalArgumentException("Scope exceeds shared directory: "+id+"/"+scopeEntry.getKey());
                for(var grant:scope.managementGrants()) for(String target:grant.applicationGroupIds())
                    if(!managementTargets.contains(target)) {
                        var oldScope=prior==null?null:prior.scopes().get(scopeEntry.getKey());
                        if(oldScope==null || !oldScope.managementGrants().contains(grant))throw new IllegalArgumentException("Unknown management target: "+target);
                    }
            }
        }
    }
}
