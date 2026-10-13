package io.kestra.extensions.rbac;

import java.util.Set;
/** Identity supplied exclusively by the host authentication layer; roles are trusted claim values. */
public record Principal(String subject, Set<String> roles) {
    public Principal { roles = roles == null ? Set.of() : Set.copyOf(roles); }
}
