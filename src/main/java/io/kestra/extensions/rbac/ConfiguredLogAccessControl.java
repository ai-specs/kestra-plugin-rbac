package io.kestra.extensions.rbac;

import io.kestra.core.models.AccessScope;
import io.kestra.core.repositories.log.*;
import io.kestra.webserver.rbac.RbacAuthorizer.Kind;
import io.micronaut.context.annotation.*;
import jakarta.inject.Singleton;

@Singleton
@Replaces(GlobalLogDataStoreAccessControl.class)
@Requires(property = "kestra.rbac.enabled", value = "true")
public final class ConfiguredLogAccessControl implements LogDataStoreAccessControl {
    private final PolicyEngine engine;
    public ConfiguredLogAccessControl(PolicyEngine engine) { this.engine = engine; }
    @Override public AccessScope namespaceScope() {
        var identity = RequestIdentity.current();
        return identity == null ? AccessScope.denyAll() : engine.namespaceScope(identity.principal(), Kind.LOG);
    }
}
