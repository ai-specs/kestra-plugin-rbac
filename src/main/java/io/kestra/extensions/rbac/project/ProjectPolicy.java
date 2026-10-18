package io.kestra.extensions.rbac.project;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record ProjectPolicy(int schemaVersion, Map<String, Project> applicationProjects) {
    public ProjectPolicy {
        if (schemaVersion != 2 || applicationProjects == null || applicationProjects.isEmpty()) throw new IllegalArgumentException("Invalid schemaVersion 2 policy");
        applicationProjects = Map.copyOf(applicationProjects);
        applicationProjects.keySet().forEach(key -> { if (!key.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")) throw new IllegalArgumentException("Invalid project ID"); });
    }
    public record Project(String issuer, Set<String> audiences, boolean enabled, long directoryRevision, Map<String, ScopeDefinition> scopes) {
        public Project {
            if (issuer == null || issuer.isBlank() || audiences == null || audiences.isEmpty() || audiences.stream().anyMatch(a -> a == null || a.isBlank()) || directoryRevision <= 0 || scopes == null) throw new IllegalArgumentException("Invalid project");
            audiences = Set.copyOf(audiences);
            scopes = Map.copyOf(scopes);
            for (String audience : audiences) if (!audience.equals(audience.trim()) || audience.contains("*") || audience.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Exact audience required");
            scopes.keySet().forEach(key -> { if (key.isEmpty() || key.chars().anyMatch(value -> value < 0x21 || value > 0x7e || value == 34 || value == 92)) throw new IllegalArgumentException("Invalid scope identifier"); });
        }
    }
    public enum ManagementDomain { IDENTITY, LOCAL_POLICY, BUSINESS_INSTANCE }
    public enum ManagementAction { READ, MANAGE }
    public record ManagementGrant(ManagementDomain domain, Set<ManagementAction> actions, Set<String> projectIds) {
        public ManagementGrant(ManagementDomain domain, Set<ManagementAction> actions) { this(domain,actions,Set.of()); }
        public ManagementGrant {
            if (domain == null || actions == null || actions.isEmpty() || projectIds == null)
                throw new IllegalArgumentException("Management domain/actions/targets required");
            actions = Set.copyOf(actions); projectIds = Set.copyOf(projectIds);
            if (domain != ManagementDomain.LOCAL_POLICY && !projectIds.isEmpty())
                throw new IllegalArgumentException("Only LOCAL_POLICY accepts explicit project targets");
            for (String id : projectIds) if (!id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))
                throw new IllegalArgumentException("Exact management project ID required");
        }
    }
    public record ScopeDefinition(String description, boolean enabled, List<Grant> grants, List<ManagementGrant> managementGrants) {
        public ScopeDefinition(String description, boolean enabled, List<Grant> grants) { this(description, enabled, grants, List.of()); }
        public ScopeDefinition {
            if (description == null || grants == null || managementGrants == null || grants.isEmpty() && managementGrants.isEmpty()) throw new IllegalArgumentException("Scope grants must be non-empty");
            grants = List.copyOf(grants);
            managementGrants = List.copyOf(managementGrants);
        }
    }
    public enum Resource { FLOW, EXECUTION, TRIGGER, NAMESPACE, KVSTORE, SECRET, NAMESPACE_FILE, LOG, METRIC, DASHBOARD, BLUEPRINT, MCP_SERVER, AI, METADATA }
    public record Grant(String scope, String tenant, String namespace, boolean descendants, String resource, Set<String> actions, Set<String> ids, boolean allIds) {
        public Grant {
            if (scope == null || tenant == null || resource == null || actions == null || actions.isEmpty() || ids == null || allIds && !ids.isEmpty()) throw new IllegalArgumentException("Invalid complete grant");
            actions = Set.copyOf(actions);
            ids = Set.copyOf(ids);
            for (String id : ids) if (id.isBlank() || !id.equals(id.trim()) || id.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid resource ID");
            Resource.valueOf(resource);
            if (resource.equals("METADATA")) {
                if (!scope.equals("TENANT") || !tenant.equals("main") || namespace != null || descendants
                    || !Set.of("READ", "LIST").containsAll(actions) || !ids.isEmpty() || !allIds)
                    throw new IllegalArgumentException("METADATA requires an explicit read-only main tenant grant");
            } else if (resource.equals("MCP_SERVER")) {
                if (!scope.equals("TENANT") || !tenant.equals("main") || namespace != null || descendants
                    || !Set.of("READ", "LIST", "CREATE", "UPDATE", "DELETE").containsAll(actions))
                    throw new IllegalArgumentException("MCP_SERVER requires an explicit main tenant configuration grant");
            } else if (!scope.equals("NAMESPACE") || !tenant.equals("main") || namespace == null
                || !namespace.matches("[A-Za-z0-9_-]+(?:[.][A-Za-z0-9_-]+)*")) {
                throw new IllegalArgumentException("Business grants require a concrete main namespace");
            }
            actions.forEach(action -> io.kestra.webserver.rbac.RbacAuthorizer.Action.valueOf(action));
            if (!allIds && ids.isEmpty()) throw new IllegalArgumentException("ID selection required");
        }
    }
}
