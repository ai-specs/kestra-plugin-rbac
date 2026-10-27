package io.kestra.extensions.rbac.applicationgroup;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import static io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.*;

public record ApplicationPermission(String key, String name, String description, Set<String> scopeKeys,
                                   List<Grant> grants, List<ManagementGrant> managementGrants,
                                   String details, boolean enabled, long revision) {
    public ApplicationPermission {
        if (key == null || !key.matches("[a-z][a-z0-9-]{0,127}") || name == null || name.isBlank()
            || !ApplicationGroupDirectory.text(name,256) || !ApplicationGroupDirectory.text(description,4096) || scopeKeys == null
            || grants == null || managementGrants == null || !ApplicationGroupDirectory.text(details,16384)
            || revision < 1 || scopeKeys.size() > 1000 || grants.size() > 1000 || managementGrants.size() > 1000)
            throw new IllegalArgumentException("Invalid application permission");
        if(grants.stream().anyMatch(java.util.Objects::isNull) || managementGrants.stream().anyMatch(java.util.Objects::isNull))
            throw new IllegalArgumentException("Null typed grant");
        scopeKeys = Set.copyOf(scopeKeys);
        for (String scope : scopeKeys) if (!scope.matches("[a-z][a-z0-9-]{0,63}"))
            throw new IllegalArgumentException("Invalid permission scope reference");
        grants = List.copyOf(grants);
        managementGrants = List.copyOf(managementGrants);
    }

    public enum Kind {
        KESTRA_POLICY("kestra-policy"), EXTERNAL_DECLARATION("external-declaration");
        private final String value;
        Kind(String value) { this.value = value; }
        @com.fasterxml.jackson.annotation.JsonValue public String value() { return value; }
    }

    public void validate(Kind kind, Set<String> groupScopeKeys) {
        if (kind == null || !groupScopeKeys.containsAll(scopeKeys))
            throw new IllegalArgumentException("Undefined permission scope reference");
        if (kind == Kind.EXTERNAL_DECLARATION && (!grants.isEmpty() || !managementGrants.isEmpty()))
            throw new IllegalArgumentException("External declarations cannot grant Kestra access");
        if (kind == Kind.EXTERNAL_DECLARATION && !enabled)
            throw new IllegalArgumentException("External declarations do not have execution switches");
        if (kind == Kind.KESTRA_POLICY && !details.isEmpty())
            throw new IllegalArgumentException("Kestra policy requires typed grants, not executable text");
    }

    public ApplicationPermission replace(String name, String description, Set<String> scopeKeys,
                                         List<Grant> grants, List<ManagementGrant> managementGrants, String details,boolean enabled) {
        return new ApplicationPermission(key, name, description, scopeKeys, grants, managementGrants, details,enabled,
            Math.addExact(revision, 1));
    }

    public static Set<String> supportedScopes(List<ApplicationPermission> permissions) {
        var supported = new HashSet<String>();
        permissions.forEach(permission -> supported.addAll(permission.scopeKeys()));
        return Set.copyOf(supported);
    }

    public static Set<String> eligibleScopes(List<ApplicationPermission> permissions, Set<String> userScopes) {
        var eligible = new HashSet<>(supportedScopes(permissions));
        eligible.retainAll(userScopes);
        return Set.copyOf(eligible);
    }

    public static Map<String, ScopeDefinition> project(List<ApplicationPermission> permissions, Kind kind) {
        if (kind != Kind.KESTRA_POLICY) return Map.of();
        var resources = new TreeMap<String, List<Grant>>();
        var management = new TreeMap<String, List<ManagementGrant>>();
        for (var permission : permissions) {
            if (!permission.details().isEmpty()) throw new IllegalArgumentException("Invalid Kestra permission text");
            if (!permission.enabled()) continue;
            for (String scope : permission.scopeKeys()) {
                resources.computeIfAbsent(scope, ignored -> new ArrayList<>()).addAll(permission.grants());
                management.computeIfAbsent(scope, ignored -> new ArrayList<>()).addAll(permission.managementGrants());
            }
        }
        var result = new TreeMap<String, ScopeDefinition>();
        resources.forEach((scope, grants) -> {
            var managementGrants = management.get(scope);
            if (!grants.isEmpty() || !managementGrants.isEmpty())
                result.put(scope, new ScopeDefinition("", true, grants.stream().distinct().toList(),
                    managementGrants.stream().distinct().toList()));
        });
        return Map.copyOf(result);
    }

    public static boolean supportChanged(List<ApplicationPermission> previous,List<ApplicationPermission> next) {
        return !supportedScopes(previous).equals(supportedScopes(next));
    }

    public static boolean policyChanged(List<ApplicationPermission> previous,List<ApplicationPermission> next,Kind kind) {
        return !project(previous,kind).equals(project(next,kind));
    }

    public record Receipt(String key, long revision, long parentRevision) {}
    public record View(String key, String name, String description, Set<String> scopeKeys,
                       List<Grant> grants, List<ManagementGrant> managementGrants, String details,
                       @JsonProperty("implementationKind") Kind implementationKind, boolean enabled,long revision, long parentRevision) {
        public View(ApplicationPermission value, Kind kind, long parentRevision) {
            this(value.key(), value.name(), value.description(), value.scopeKeys(), value.grants(),
                value.managementGrants(), value.details(), kind,value.enabled(),value.revision(), parentRevision);
        }
    }
}
