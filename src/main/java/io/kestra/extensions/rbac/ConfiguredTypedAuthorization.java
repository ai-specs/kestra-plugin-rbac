package io.kestra.extensions.rbac;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import io.kestra.webserver.rbac.RbacAuthorizer;
import io.kestra.webserver.rbac.TypedAuthorization;
import io.kestra.webserver.rbac.TypedAuthorizationResolver;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

@Singleton
@Requires(property = "kestra.rbac.enabled", value = "true")
public final class ConfiguredTypedAuthorization implements TypedAuthorizationResolver {
    private final PolicyEngine engine;

    public ConfiguredTypedAuthorization(PolicyEngine engine) {
        this.engine = engine;
    }

    @Override
    public CompletionStage<TypedAuthorization.Decision> authorize(RbacAuthorizer.Principal principal,
        TypedAuthorization.Operation operation) {
        try {
            return CompletableFuture.completedFuture(engine.authorizeTyped(principal, operation));
        } catch (Exception failure) {
            return CompletableFuture.completedFuture(TypedAuthorization.Decision.deny("Invalid policy input"));
        }
    }
}
