package io.kestra.extensions.rbac.declaration;

import io.kestra.webserver.oidc.ApplicationScopeDeclarationsV1;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import java.sql.Connection;

@Singleton
@Requires(property = "kestra.rbac.enabled", value = "true")
@Requires(property = "kestra.rbac.application-groups.enabled", value = "true")
public final class ApplicationScopeDeclarationProvider implements ApplicationScopeDeclarationsV1 {
    private final ApplicationScopeDeclarationStore store;

    public ApplicationScopeDeclarationProvider(ApplicationScopeDeclarationStore store) {
        this.store = store;
    }

    @Override
    public Projection current(String applicationGroupId, String clientId) {
        return new Projection(applicationGroupId, clientId,
            store.list(applicationGroupId, clientId).stream().map(this::scope).toList());
    }

    @Override
    public Projection current(Connection connection, String applicationGroupId, String clientId) {
        try {
            return new Projection(applicationGroupId, clientId,
                store.list(connection, applicationGroupId, clientId).stream().map(this::scope).toList());
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("Application declarations unavailable", failure);
        }
    }

    private Scope scope(ApplicationScopeDeclarationStore.StoredScope stored) {
        return new Scope(stored.scopeKey(), stored.permissions());
    }
}
