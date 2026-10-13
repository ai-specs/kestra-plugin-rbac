package io.kestra.extensions.rbac;

import io.micronaut.core.convert.ConversionContext;
import io.micronaut.core.convert.TypeConverter;
import jakarta.inject.Singleton;
import java.util.*;

/** Strict conversion for the map/list configuration shape; invalid entries must fail startup. */
@Singleton
public final class GrantConverter implements TypeConverter<Map, RbacConfiguration.Grant> {
    @Override
    public Optional<RbacConfiguration.Grant> convert(Map value, Class<RbacConfiguration.Grant> target, ConversionContext context) {
        var grant = new RbacConfiguration.Grant();
        var keys = Set.of("tenant", "namespace", "descendants", "resource", "actions", "ids", "scope", "all-ids", "allIds");
        if (!keys.containsAll(value.keySet())) throw new IllegalArgumentException("Unknown grant field");
        grant.setScope(io.kestra.webserver.rbac.RbacAuthorizer.Scope.valueOf(text(value.get("scope"))));
        Object all = value.containsKey("all-ids") ? value.get("all-ids") : value.get("allIds");
        if (all != null) {
            if (!(all instanceof Boolean) && !Set.of("true", "false").contains(all)) throw new IllegalArgumentException("all-ids must be boolean");
            grant.setAllIds(Boolean.parseBoolean(all.toString()));
        }
        grant.setTenant(text(value.get("tenant")));
        grant.setNamespace(text(value.get("namespace")));
        if (value.containsKey("descendants")) {
            Object descendants = value.get("descendants");
            if (!(descendants instanceof Boolean) && !Set.of("true", "false").contains(descendants))
                throw new IllegalArgumentException("descendants must be boolean");
            grant.setDescendants(Boolean.parseBoolean(descendants.toString()));
        }
        grant.setResource(io.kestra.webserver.rbac.RbacAuthorizer.Kind.valueOf(text(value.get("resource"))));
        grant.setActions(strings(value.get("actions")).stream().map(io.kestra.webserver.rbac.RbacAuthorizer.Action::valueOf).toList());
        if (value.containsKey("ids")) grant.setIds(strings(value.get("ids")));
        return Optional.of(grant);
    }
    private static String text(Object value) {
        if (value == null) return null;
        if (!(value instanceof String s)) throw new IllegalArgumentException("Expected string");
        return s;
    }
    private static List<String> strings(Object value) {
        if (!(value instanceof Collection<?> list)) throw new IllegalArgumentException("Expected list");
        return list.stream().map(GrantConverter::text).toList();
    }
}
