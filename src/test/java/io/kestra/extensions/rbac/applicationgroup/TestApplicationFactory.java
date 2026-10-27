package io.kestra.extensions.rbac.applicationgroup;

import java.util.List;
import java.util.Set;

public final class TestApplicationFactory {
    private TestApplicationFactory() {}

    public static List<ApplicationPermission> declarations(Set<String> scopeKeys) {
        return scopeKeys.stream().sorted().map(scope->new ApplicationPermission("allow-"+scope,scope,"",Set.of(scope),
            List.of(),List.of(),"",true,1)).toList();
    }

    public static ApplicationGroupDirectory.Application application(String clientId,boolean legacyApproved,
        Set<String> audiences,Set<String> scopeKeys,long revision) {
        return new ApplicationGroupDirectory.Application(clientId,"",audiences.isEmpty()?Set.of(clientId):audiences,
            declarations(scopeKeys),revision);
    }

    public static ApplicationGroupDirectory.Application withPolicy(ApplicationGroupDirectory.Application application,
        java.util.Map<String,ApplicationGroupPolicy.ScopeDefinition> policy) {
        var permissions=application.permissions().stream().map(value->{
            var scope=value.scopeKeys().iterator().next();var definition=policy.get(scope);
            return definition==null?value:new ApplicationPermission(value.key(),value.name(),definition.description(),value.scopeKeys(),
                definition.grants(),definition.managementGrants(),"",definition.enabled(),value.revision());
        }).toList();
        return new ApplicationGroupDirectory.Application(application.clientId(),application.description(),application.audiences(),permissions,application.revision());
    }

    public static ApplicationGroupDirectory.Snapshot registerApplication(ApplicationGroupDirectory.Snapshot current,
        String clientId,boolean legacyApproved,Set<String> audiences,Set<String> scopeKeys) {
        var registered=ApplicationGroupChanges.registerApplication(current,clientId,"",audiences.isEmpty()?Set.of(clientId):audiences);
        var application=registered.applications().stream().filter(value->value.clientId().equals(clientId)).findFirst().orElseThrow();
        var applications=registered.applications().stream().map(value->value==application?
            new ApplicationGroupDirectory.Application(clientId,"",application.audiences(),declarations(scopeKeys),1):value).toList();
        return new ApplicationGroupDirectory.Snapshot(current.group(),current.scopes(),applications,current.userGrants());
    }

    public static ApplicationGroupDirectory.Snapshot applicationSupport(ApplicationGroupDirectory.Snapshot current,
        String clientId,long expectedRevision,boolean legacyApproved,Set<String> audiences,Set<String> scopeKeys) {
        var application=current.applications().stream().filter(value->value.clientId().equals(clientId)).findFirst().orElseThrow();
        if(!application.audiences().equals(audiences)) {
            if(audiences.isEmpty())throw new IllegalArgumentException("Audience required");
            throw new IllegalArgumentException("Use explicit application metadata operation");
        }
        var previous=application.permissions();
        var permissions=scopeKeys.stream().sorted().map(scope->previous.stream().filter(value->value.scopeKeys().contains(scope)).findFirst()
            .orElseGet(()->declarations(Set.of(scope)).getFirst())).toList();
        return ApplicationGroupChanges.permissions(current,clientId,expectedRevision,permissions);
    }
}
