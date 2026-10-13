package io.kestra.extensions.rbac;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import java.util.*;
import io.kestra.webserver.rbac.RbacAuthorizer.*;

@Singleton
@Requires(property = "kestra.rbac.enabled", value = "true")
public final class PolicyEngine {
    private record Grant(String tenant, String namespace, boolean descendants,
                         Kind resource, Set<Action> actions, Set<String> ids, Scope scope, boolean allIds) {}
    private final Map<String, List<Grant>> roles;
    private final Map<String, Set<String>> subjects;
    private final Map<String, Set<String>> mapping;

    public PolicyEngine(RbacConfiguration config) {
        var compiled = new HashMap<String, List<Grant>>();
        config.getRoles().forEach((name, grants) -> {
            requireName(name);
            compiled.put(name, grants.stream().map(g -> {
                if (g.getScope() == null || g.getResource() == null || g.getActions().isEmpty()) throw new IllegalArgumentException("Explicit scope/resource/actions required");
                if (g.getScope() == Scope.INSTANCE) {
                    if (g.getTenant() != null || g.getNamespace() != null) throw new IllegalArgumentException("INSTANCE has no tenant/namespace");
                } else requireName(g.getTenant());
                if (g.getScope() == Scope.NAMESPACE && !namespace(g.getNamespace())) throw new IllegalArgumentException("Concrete namespace required");
                if (g.getScope() != Scope.NAMESPACE && (g.getNamespace() != null || g.isDescendants())) throw new IllegalArgumentException("Only NAMESPACE has namespace/descendants");
                if (g.isAllIds() && !g.getIds().isEmpty()) throw new IllegalArgumentException("Use ids or all-ids, not both");
                g.getIds().forEach(PolicyEngine::requireName);
                return new Grant(g.getTenant(), g.getNamespace(), g.isDescendants(), g.getResource(), Set.copyOf(g.getActions()), Set.copyOf(g.getIds()), g.getScope(), g.isAllIds());
            }).toList());
        });
        roles = Map.copyOf(compiled);
        subjects = bindings(config.getSubjects());
        mapping = bindings(config.getRoleMapping());
    }
    private Map<String, Set<String>> bindings(Map<String, List<String>> source) {
        var result = new HashMap<String, Set<String>>();
        source.forEach((key, values) -> {
            requireName(key);
            if (!roles.keySet().containsAll(values)) throw new IllegalArgumentException("Unknown role in binding: " + key);
            result.put(key, Set.copyOf(values));
        });
        return Map.copyOf(result);
    }
    private static void requireName(String s) {
        if (s == null || s.isBlank() || !s.equals(s.trim()) || s.contains("*")) throw new IllegalArgumentException("Invalid concrete name");
    }
    private static boolean namespace(String s) {
        return s != null && s.matches("[A-Za-z0-9_-]+(?:[.][A-Za-z0-9_-]+)*");
    }
    public boolean authorize(Principal principal, AuthorizationRequest request) {
        if (principal == null || principal.subject() == null || principal.subject().isBlank() || request == null
            || request.resource() == null || request.action() == null || request.scope() == null) return false;
        if (request.scope() == Scope.INSTANCE && (request.tenant() != null || request.namespace() != null)) return false;
        if (request.scope() != Scope.INSTANCE && !"main".equals(request.tenant())) return false;
        if (request.scope() != Scope.INSTANCE && (request.tenant() == null || request.tenant().isBlank())) return false;
        if (request.scope() == Scope.NAMESPACE && !namespace(request.namespace())) return false;
        if (request.scope() != Scope.NAMESPACE && request.namespace() != null) return false;
        var effective = new HashSet<>(subjects.getOrDefault(principal.subject(), Set.of()));
        principal.roles().forEach(role -> effective.addAll(mapping.getOrDefault(role, Set.of())));
        return effective.stream().flatMap(role -> roles.getOrDefault(role, List.of()).stream()).anyMatch(g ->
            Objects.equals(g.tenant(), request.tenant()) && g.scope() == request.scope() && g.resource() == request.resource() && g.actions().contains(request.action())
            && (g.namespace() == null || g.namespace().equals(request.namespace())
                || g.descendants() && request.namespace().startsWith(g.namespace() + "."))
            && (request.action() == Action.LIST
                ? g.allIds() && g.actions().contains(Action.READ)
                : g.allIds() || request.id() != null && g.ids().contains(request.id())));

    }
    /** Bulk authorization must finish before any mutation; empty/unresolved plans fail closed. */
    public boolean authorizeAll(Principal principal, List<AuthorizationRequest> requests) {
        return requests != null && !requests.isEmpty() && requests.stream().allMatch(r -> authorize(principal, r));
    }
}
