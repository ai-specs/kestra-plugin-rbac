package io.kestra.extensions.rbac.management;

import java.util.Set;

public record ApplicationGroupScopeGrant(String applicationGroupId,String issuer,String subject,
                                        Set<String> scopeKeys,long revision,long authorizationVersion) {
    public ApplicationGroupScopeGrant {
        if(applicationGroupId==null || !applicationGroupId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
            || issuer==null || issuer.isBlank() || subject==null || subject.isBlank() || !subject.equals(subject.trim())
            || subject.chars().anyMatch(Character::isISOControl)
            || scopeKeys==null || revision<=0 || authorizationVersion<=0)
            throw new IllegalArgumentException("Invalid user scope grant");
        scopeKeys=Set.copyOf(scopeKeys);
        if(scopeKeys.stream().anyMatch(key->!key.matches("[a-z][a-z0-9-]{0,63}")))
            throw new IllegalArgumentException("Invalid granted scope key");
    }
}
