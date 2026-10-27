package io.kestra.extensions.rbac.declaration;

import io.kestra.webserver.oidc.ApplicationScopeDeclarationsV1;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import java.sql.Connection;

@Singleton
@Requires(property = "kestra.rbac.enabled", value = "true")
@Requires(property = "kestra.rbac.application-groups.enabled", value = "true")
public final class ApplicationScopeDeclarationProvider implements ApplicationScopeDeclarationsV1 {
    private final io.kestra.extensions.rbac.applicationgroup.ApplicationGroupDirectoryService directory;

    public ApplicationScopeDeclarationProvider(io.kestra.extensions.rbac.applicationgroup.ApplicationGroupDirectoryService directory) {
        this.directory = directory;
    }

    @Override
    public Projection current(String applicationGroupId, String clientId) {
        return directory.transaction(connection->current(connection,applicationGroupId,clientId));
    }

    @Override
    public Projection current(Connection connection, String applicationGroupId, String clientId) {
        try {
            var application=directory.find(connection,applicationGroupId).applications().stream().filter(value->value.clientId().equals(clientId)).findFirst()
                .orElseThrow(io.kestra.extensions.rbac.applicationgroup.ApplicationGroupChanges.NotFound::new);
            var scopes=application.supportedScopeKeys().stream().sorted().map(key->new Scope(key,application.permissions().stream()
                .filter(value->value.scopeKeys().contains(key)).map(value->new Permission(value.key(),value.name())).toList())).toList();
            return new Projection(applicationGroupId,clientId,scopes);
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("Application declarations unavailable", failure);
        }
    }

}
