package io.kestra.extensions.rbac;
import io.kestra.webserver.rbac.RbacAuthorizer;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import java.util.Set;
import java.util.concurrent.*;
@Singleton
@Requires(property = "kestra.rbac.enabled", value = "true")
public final class ConfiguredRbacAuthorizer implements RbacAuthorizer {
    private final RuntimePolicies policies;
    public ConfiguredRbacAuthorizer(PolicyEngine engine) { this.policies = RuntimePolicies.configurationOnly(engine); }
    @jakarta.inject.Inject
    public ConfiguredRbacAuthorizer(RuntimePolicies policies) { this.policies = policies; }
    @Override public CompletionStage<Boolean> authorize(RbacAuthorizer.Principal principal, Resource resource, Action action) {
        if (principal == null || resource == null || action == null) return CompletableFuture.completedFuture(false);
        try { return CompletableFuture.completedFuture(policies.snapshot().authorize(principal, resource, action)); }
        catch (Exception failure) { return CompletableFuture.completedFuture(false); }
    }
}
