package io.kestra.extensions.rbac.management;

import java.util.Set;

public record ApplicationGroupApplication(String applicationGroupId,String clientId,String description,
                                         Set<String> audiences,Set<String> supportedScopeKeys,long revision,
                                         io.kestra.extensions.rbac.applicationgroup.ApplicationPermission.Kind implementationKind) {
    public ApplicationGroupApplication(String applicationGroupId,String clientId,String description,Set<String> audiences,Set<String> supportedScopeKeys,long revision) {
        this(applicationGroupId,clientId,description,audiences,supportedScopeKeys,revision,io.kestra.extensions.rbac.applicationgroup.ApplicationPermission.Kind.EXTERNAL_DECLARATION);
    }
    public ApplicationGroupApplication {
        if(applicationGroupId==null || !applicationGroupId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
            || clientId==null || clientId.isBlank() || !clientId.equals(clientId.trim()) || clientId.chars().anyMatch(Character::isISOControl)
            || description==null || audiences==null || supportedScopeKeys==null || implementationKind==null || revision<=0)
            throw new IllegalArgumentException("Invalid application group member");
        audiences=Set.copyOf(audiences);supportedScopeKeys=Set.copyOf(supportedScopeKeys);
        if(audiences.stream().anyMatch(value->value.isBlank() || !value.equals(value.trim()) || value.chars().anyMatch(Character::isISOControl))
            || supportedScopeKeys.stream().anyMatch(key->!key.matches("[a-z][a-z0-9-]{0,63}")))
            throw new IllegalArgumentException("Invalid application support registration");
    }
}
