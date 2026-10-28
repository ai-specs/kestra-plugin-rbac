package io.kestra.extensions.rbac;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import io.kestra.webserver.rbac.AuthorizationEvaluationProviderV2;
import io.kestra.webserver.rbac.AuthorizationEvaluationV1;
import io.kestra.webserver.rbac.RbacAuthorizer;
import io.kestra.webserver.rbac.TypedAuthorization;
import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.TaskExecutors;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

@Singleton
@Requires(property = "kestra.rbac.enabled", value = "true")
@Requires(property = "kestra.rbac.typed.enabled", value = "true")
@Requires(property = "kestra.rbac.evaluation.enabled", value = "true")
public final class ConfiguredEvaluationProvider implements AuthorizationEvaluationProviderV2 {
    private final ApplicationGroupRuntimePolicies policies;
    private final Executor executor;

    public ConfiguredEvaluationProvider(ApplicationGroupRuntimePolicies policies, @Named(TaskExecutors.IO) Executor executor) {
        this.policies = policies;
        this.executor = executor;
    }

    @Override
    public CompletionStage<AuthorizationEvaluationV1> begin(io.kestra.webserver.oidc.TrustedIdentityV1.Identity principal, String tenant) {
        if (principal == null || principal.subject() == null || principal.subject().isBlank()
            || tenant != null && !"main".equals(tenant)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid evaluation identity or tenant"));
        }
        return CompletableFuture.supplyAsync(() -> {
            final AtomicReference<ApplicationGroupRuntimePolicies.Context> snapshot;
            try {snapshot = new AtomicReference<>(policies.open(principal,tenant));}
            catch(RuntimeException failure){throw ApplicationGroupRuntimePolicies.authorizationFailure(failure);}
            return new AuthorizationEvaluationV1() {
                @Override
                public CompletionStage<TypedAuthorization.Decision> authorize(TypedAuthorization.Operation operation) {
                    var current = snapshot.get();
                    if (current == null) return CompletableFuture.completedFuture(TypedAuthorization.Decision.deny("Evaluation is closed"));
                    try {
                        return CompletableFuture.completedFuture(current.authorize(operation));
                    } catch (RuntimeException failure) {
                        var classified=ApplicationGroupRuntimePolicies.authorizationFailure(failure);
                        if(classified instanceof io.kestra.webserver.rbac.AuthorizationUnavailableException)
                            return CompletableFuture.failedFuture(classified);
                        return CompletableFuture.completedFuture(TypedAuthorization.Decision.deny("Invalid policy input"));
                    }
                }
                @Override
                public CompletionStage<Void> close() {
                    var previous=snapshot.getAndSet(null);
                    if(previous!=null)previous.close();
                    return CompletableFuture.completedFuture(null);
                }
            };
        }, executor);
    }
}
