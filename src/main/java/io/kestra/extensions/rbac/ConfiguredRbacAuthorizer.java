package io.kestra.extensions.rbac;
import io.kestra.webserver.rbac.RbacAuthorizer;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import java.util.concurrent.*;
@Singleton
@Requires(property="kestra.rbac.enabled",value="true")
/** Compatibility entry point: never supplies permission without a trusted V2 evaluation. */
public final class ConfiguredRbacAuthorizer implements RbacAuthorizer {
    public ConfiguredRbacAuthorizer(){}
    @Override public CompletionStage<Boolean> authorize(Principal principal,Resource resource,Action action){return CompletableFuture.completedFuture(false);}
}
