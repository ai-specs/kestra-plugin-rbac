package io.kestra.extensions.rbac;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import java.util.*;
import io.kestra.webserver.rbac.RbacAuthorizer.*;
import io.kestra.webserver.rbac.TypedAuthorization;

@Singleton
@io.micronaut.context.annotation.Context
@Requires(property = "kestra.rbac.enabled", value = "true")
public final class PolicyEngine {
    private record Grant(String tenant, String namespace, boolean descendants,
                         Kind resource, Set<Action> actions, Set<String> ids, Scope scope, boolean allIds) {}
    private final Set<String> instanceAdminRoles;
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
        config.getInstanceAdminRoles().forEach(PolicyEngine::requireName);
        if (!roles.keySet().containsAll(config.getInstanceAdminRoles())) throw new IllegalArgumentException("Unknown instance-admin role");
        instanceAdminRoles = Set.copyOf(config.getInstanceAdminRoles());
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
        if (isInstanceAdmin(principal)) {
            if (request.scope() == Scope.INSTANCE) return request.resource() == Kind.SYSTEM && request.id() == null;
            if (request.scope() == Scope.TENANT) return Set.of(Kind.SYSTEM, Kind.MCP_SERVER, Kind.AI, Kind.DASHBOARD, Kind.BLUEPRINT).contains(request.resource())
                || request.id() == null && (request.action() == Action.LIST || request.action() == Action.READ);
            return true;
        }
        return effectiveRoles(principal).stream().flatMap(role -> roles.getOrDefault(role, List.of()).stream()).anyMatch(g ->
            Objects.equals(g.tenant(), request.tenant()) && g.scope() == request.scope() && g.resource() == request.resource() && g.actions().contains(request.action())
            && (g.namespace() == null || g.namespace().equals(request.namespace())
                || g.descendants() && request.namespace().startsWith(g.namespace() + "."))
            && (request.action() == Action.LIST
                ? g.allIds() && g.actions().contains(Action.READ)
                : g.allIds() || request.id() != null && g.ids().contains(request.id())));

    }
    private Set<String> effectiveRoles(Principal principal) {
        var effective = new HashSet<>(subjects.getOrDefault(principal.subject(), Set.of()));
        principal.roles().forEach(role -> effective.addAll(mapping.getOrDefault(role, Set.of())));
        return effective;
    }
    public boolean isInstanceAdmin(Principal principal) {
        return principal != null && principal.subject() != null && !principal.subject().isBlank()
            && effectiveRoles(principal).stream().anyMatch(instanceAdminRoles::contains);
    }
    public boolean isInstanceAdmin(io.kestra.webserver.rbac.RbacAuthorizer.Principal principal) {
        return principal != null && isInstanceAdmin(new Principal(principal.subject(), Set.copyOf(principal.roles())));
    }
    public TypedAuthorization.Decision authorizeTyped(
        io.kestra.webserver.rbac.RbacAuthorizer.Principal subject,
        TypedAuthorization.Operation operation) {
        if (subject == null || subject.subject() == null || subject.subject().isBlank() || operation == null
            || operation.contractVersion() != TypedAuthorization.CONTRACT_VERSION
            || !"main".equals(operation.tenant())) return typedDeny();
        var principal = new Principal(subject.subject(), Set.copyOf(subject.roles()));
        if (isInstanceAdmin(principal)) return TypedAuthorization.Decision.allow(operation.queryDomain());
        if (operation.resource() == TypedAuthorization.Resource.SYSTEM) return typedDeny();
        if (operation.resource() == TypedAuthorization.Resource.METADATA) {
            boolean reader = effectiveRoles(principal).stream().flatMap(role -> roles.getOrDefault(role, List.of()).stream())
                .anyMatch(grant -> "main".equals(grant.tenant()) && grant.actions().contains(Action.READ));
            return reader ? TypedAuthorization.Decision.allow(operation.queryDomain()) : typedDeny();
        }
        Kind resource = Kind.valueOf(operation.resource().name());
        Action action = Action.valueOf(operation.action().name());
        if (operation.coverage() == TypedAuthorization.Coverage.COMPLETE_QUERY) {
            if (action != Action.READ && action != Action.LIST || operation.sideEffect() != TypedAuthorization.SideEffect.READ_ONLY)
                return typedDeny();
            var grants = effectiveRoles(principal).stream().flatMap(role -> roles.getOrDefault(role, List.of()).stream())
                .filter(grant -> "main".equals(grant.tenant()) && grant.resource() == resource && grant.allIds()).toList();
            if (grants.stream().noneMatch(grant -> grant.actions().contains(Action.READ))
                || grants.stream().noneMatch(grant -> grant.actions().contains(Action.LIST))) return typedDeny();
            var scope = queryScope(grants, Action.READ).intersect(queryScope(grants, Action.LIST));
            var intersection = operation.queryDomain().intersect(scope);
            if (operation.namespace() != null && !intersection.contains(operation.namespace())) return typedDeny();
            return TypedAuthorization.Decision.allow(intersection);
        }
        Scope scope = operation.namespace() == null ? Scope.TENANT : Scope.NAMESPACE;
        boolean allowed = authorize(principal, new AuthorizationRequest(operation.tenant(), operation.namespace(), resource,
            action, operation.resourceId(), scope));
        return allowed ? TypedAuthorization.Decision.allow(operation.queryDomain()) : typedDeny();
    }
    private TypedAuthorization.Decision typedDeny() {
        return TypedAuthorization.Decision.deny("Permission denied");
    }
    private TypedAuthorization.QueryDomain queryScope(List<Grant> grants, Action action) {
        var applicable = grants.stream().filter(grant -> grant.actions().contains(action)).toList();
        if (applicable.stream().anyMatch(grant -> grant.scope() == Scope.TENANT))
            return TypedAuthorization.QueryDomain.unrestricted();
        var exact = applicable.stream().filter(grant -> grant.scope() == Scope.NAMESPACE && !grant.descendants())
            .map(Grant::namespace).distinct().toList();
        var subtrees = applicable.stream().filter(grant -> grant.scope() == Scope.NAMESPACE && grant.descendants())
            .map(Grant::namespace).distinct().toList();
        return new TypedAuthorization.QueryDomain(exact, subtrees, List.of(), true, false);
    }
    /** Bulk authorization must finish before any mutation; empty/unresolved plans fail closed. */
    public boolean authorizeAll(Principal principal, List<AuthorizationRequest> requests) {
        return requests != null && !requests.isEmpty() && requests.stream().allMatch(r -> authorize(principal, r));
    }
}
