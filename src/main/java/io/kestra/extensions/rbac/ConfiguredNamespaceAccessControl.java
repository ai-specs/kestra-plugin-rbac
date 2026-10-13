package io.kestra.extensions.rbac;

import io.kestra.core.models.AccessScope;
import io.kestra.core.models.QueryFilter;
import io.kestra.core.repositories.*;
import io.kestra.webserver.rbac.RbacAuthorizer.Kind;
import io.micronaut.context.annotation.*;
import jakarta.inject.Singleton;

@Singleton
@Replaces(GlobalNamespaceAccessControl.class)
@Requires(property = "kestra.rbac.enabled", value = "true")
public final class ConfiguredNamespaceAccessControl implements NamespaceAccessControl {
    private final PolicyEngine engine;
    public ConfiguredNamespaceAccessControl(PolicyEngine engine) { this.engine = engine; }
    @Override public AccessScope namespaceScope(QueryFilter.Resource resource) {
        var identity = RequestIdentity.current();
        if (identity == null || resource == null) return AccessScope.denyAll();
        Kind kind = switch (resource) {
            case FLOW -> Kind.FLOW;
            case EXECUTION -> Kind.EXECUTION;
            case NAMESPACE -> Kind.NAMESPACE;
            case LOG -> Kind.LOG;
            case TRIGGER -> Kind.TRIGGER;
            case KV_METADATA -> Kind.KVSTORE;
            case NAMESPACE_FILE_METADATA -> Kind.NAMESPACE_FILE;
            case SECRET_METADATA -> Kind.SECRET;
            default -> null;
        };
        return engine.namespaceScope(identity.principal(), kind);
    }
}
