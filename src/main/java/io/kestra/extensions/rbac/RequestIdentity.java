package io.kestra.extensions.rbac;

import io.micronaut.core.propagation.PropagatedContext;
import io.micronaut.core.propagation.PropagatedContextElement;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.context.ServerRequestContext;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.filters.SecurityFilter;
import java.util.Set;

public record RequestIdentity(HttpRequest<?> request) implements PropagatedContextElement {
    public static RequestIdentity current() {
        return PropagatedContext.getOrEmpty().find(RequestIdentity.class)
            .orElseGet(() -> ServerRequestContext.currentRequest().map(RequestIdentity::new).orElse(null));
    }
    public Principal principal() {
        Authentication authentication = request.getAttribute(SecurityFilter.AUTHENTICATION, Authentication.class).orElse(null);
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) return null;
        return new Principal(authentication.getName(), Set.copyOf(authentication.getRoles()));
    }
}
