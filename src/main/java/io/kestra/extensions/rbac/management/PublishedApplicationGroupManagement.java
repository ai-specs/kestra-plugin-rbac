package io.kestra.extensions.rbac.management;

import io.kestra.extensions.rbac.project.ProjectPolicy;
import io.kestra.extensions.rbac.store.ProjectPolicyStore;
import io.kestra.webserver.oidc.ApplicationGroupManagementV1;
import io.kestra.webserver.oidc.TrustedIdentityV1;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import java.util.Set;

@Singleton
@Requires(property = "kestra.rbac.enabled", value = "true")
@Requires(property = "kestra.rbac.application-groups.enabled", value = "true")
public final class PublishedApplicationGroupManagement implements ApplicationGroupManagementV1 {
    private final ProjectPolicyStore store;

    public PublishedApplicationGroupManagement(ProjectPolicyStore store) {
        this.store = store;
    }

    @Override
    public boolean authorize(TrustedIdentityV1.Identity identity, String targetApplicationGroupId, Action action) {
        if (identity == null || identity.contractVersion() != 1 || !"main".equals(identity.tenant())) return false;
        return permits(identity.projectId(), identity.issuer(), identity.audiences(), identity.projectScopes(),
            targetApplicationGroupId, action);
    }

    @Override
    public boolean reachable(Candidate candidate, String targetApplicationGroupId, Action action) {
        if (candidate == null) return false;
        return permits(candidate.applicationGroupId(), candidate.issuer(), candidate.audiences(), candidate.scopeKeys(),
            targetApplicationGroupId, action);
    }

    private boolean permits(String applicationGroupId, String issuer, Set<String> audiences, Set<String> scopeKeys,
                            String targetApplicationGroupId, Action action) {
        if (applicationGroupId == null || issuer == null || audiences == null || audiences.isEmpty()
            || scopeKeys == null || scopeKeys.isEmpty() || targetApplicationGroupId == null
            || !targetApplicationGroupId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}") || action == null) return false;
        ProjectPolicy policy = store.readCurrent().policy();
        var application = policy.applicationProjects().get(applicationGroupId);
        if (application == null || !application.enabled() || !issuer.equals(application.issuer())
            || !application.audiences().containsAll(audiences)) return false;
        var requiredAction = ProjectPolicy.ManagementAction.valueOf(action.name());
        boolean allowed = false;
        for (String scopeKey : scopeKeys) {
            var scope = application.scopes().get(scopeKey);
            if (scope == null || !scope.enabled()) return false;
            if (scope.managementGrants().stream().anyMatch(grant -> grant.domain() == ProjectPolicy.ManagementDomain.IDENTITY
                && grant.actions().contains(requiredAction))) allowed = true;
        }
        return allowed;
    }
}
