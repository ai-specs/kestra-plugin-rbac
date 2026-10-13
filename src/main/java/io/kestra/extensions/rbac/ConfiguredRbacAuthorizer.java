package io.kestra.extensions.rbac;
import io.kestra.webserver.rbac.RbacAuthorizer;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import java.util.Set;
import java.util.concurrent.*;
@Singleton
@Requires(property = "kestra.rbac.enabled", value = "true")
public final class ConfiguredRbacAuthorizer implements RbacAuthorizer {
    private final PolicyEngine engine;
    public ConfiguredRbacAuthorizer(PolicyEngine engine) { this.engine = engine; }
    @Override public CompletionStage<Boolean> authorize(RbacAuthorizer.Principal principal, Resource resource, Action action) {
        if (principal == null || resource == null || action == null) return CompletableFuture.completedFuture(false);
        return CompletableFuture.completedFuture(engine.authorize(new io.kestra.extensions.rbac.Principal(principal.subject(), Set.copyOf(principal.roles())),
            new AuthorizationRequest(resource.tenant(), resource.namespace(), resource.kind(), action, resource.id(), resource.scope())));
    }
}
