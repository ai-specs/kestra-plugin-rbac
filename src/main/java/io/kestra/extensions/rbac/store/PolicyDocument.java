package io.kestra.extensions.rbac.store;

import java.util.List;
import java.util.Map;
import io.kestra.webserver.rbac.RbacAuthorizer.Action;
import io.kestra.webserver.rbac.RbacAuthorizer.Kind;
import io.kestra.webserver.rbac.RbacAuthorizer.Scope;

public record PolicyDocument(int schemaVersion, List<Role> roles,
                             Map<String, List<RoleId>> subjects, Map<String, List<RoleId>> roleMapping) {
    public PolicyDocument {
        roles = List.copyOf(roles);
        subjects = copyBindings(subjects);
        roleMapping = copyBindings(roleMapping);
    }

    private static Map<String, List<RoleId>> copyBindings(Map<String, List<RoleId>> bindings) {
        var copy = new java.util.TreeMap<String, List<RoleId>>();
        bindings.forEach((subject, identifiers) -> copy.put(subject, List.copyOf(identifiers)));
        return java.util.Collections.unmodifiableMap(copy);
    }

    public record RoleId(String value) {}

    public record Role(RoleId id, String name, String description, List<Grant> grants) {
        public Role { grants = List.copyOf(grants); }
    }

    public record Grant(Scope scope, String tenant, String namespace, boolean descendants,
                        Kind resource, List<Action> actions, List<String> ids, boolean allIds) {
        public Grant {
            actions = List.copyOf(actions);
            ids = List.copyOf(ids);
        }
    }
}
