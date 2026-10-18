package io.kestra.extensions.rbac;
import io.kestra.webserver.rbac.*;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import java.util.concurrent.*;
@Singleton
@Requires(property="kestra.rbac.enabled",value="true")
/** Compatibility entry point: never supplies permission without a trusted V2 evaluation. */
public final class ConfiguredTypedAuthorization implements TypedAuthorizationResolver {
    public ConfiguredTypedAuthorization(){}
    @Override public CompletionStage<TypedAuthorization.Decision> authorize(RbacAuthorizer.Principal principal,TypedAuthorization.Operation operation){
        return CompletableFuture.completedFuture(TypedAuthorization.Decision.deny("Trusted project evaluation required"));
    }
}
