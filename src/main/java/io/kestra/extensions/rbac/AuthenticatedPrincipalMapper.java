package io.kestra.extensions.rbac;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Host adapters call this only with authenticated attributes, never raw headers or decoded JWTs. */
public final class AuthenticatedPrincipalMapper {
    private AuthenticatedPrincipalMapper() {}
    public static Principal from(String subject, Map<String, ?> attributes, String rolesClaim) {
        if (subject == null || subject.isBlank() || attributes == null || rolesClaim == null || rolesClaim.isBlank()) return null;
        Object value = attributes.get(rolesClaim);
        if (value == null) return new Principal(subject, Set.of());
        var roles = new HashSet<String>();
        if (value instanceof String role && !role.isBlank()) roles.add(role);
        else if (value instanceof Collection<?> values) {
            for (Object role : values) {
                if (!(role instanceof String s) || s.isBlank()) return null;
                roles.add(s);
            }
        } else return null;
        return new Principal(subject, roles);
    }
}
