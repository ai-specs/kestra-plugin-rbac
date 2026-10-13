package io.kestra.extensions.rbac;

import io.micronaut.context.annotation.ConfigurationProperties;
import java.util.List;
import java.util.Map;

@ConfigurationProperties("kestra.rbac")
public class RbacConfiguration {
    private List<String> instanceAdminRoles = List.of();
    public List<String> getInstanceAdminRoles() { return instanceAdminRoles; }
    public void setInstanceAdminRoles(List<String> value) { instanceAdminRoles = List.copyOf(value); }
    private Map<String, List<Grant>> roles = Map.of();
    private Map<String, List<String>> subjects = Map.of();
    private Map<String, List<String>> roleMapping = Map.of();
    public Map<String, List<Grant>> getRoles() { return roles; }
    public void setRoles(Map<String, List<Grant>> value) { roles = Map.copyOf(value); }
    public Map<String, List<String>> getSubjects() { return subjects; }
    public void setSubjects(Map<String, List<String>> value) { subjects = Map.copyOf(value); }
    public Map<String, List<String>> getRoleMapping() { return roleMapping; }
    public void setRoleMapping(Map<String, List<String>> value) { roleMapping = Map.copyOf(value); }

    @io.micronaut.core.annotation.Introspected
    public static class Grant {
        private io.kestra.webserver.rbac.RbacAuthorizer.Scope scope;
        private boolean allIds;
        public io.kestra.webserver.rbac.RbacAuthorizer.Scope getScope() { return scope; }
        public void setScope(io.kestra.webserver.rbac.RbacAuthorizer.Scope value) { scope = value; }
        public boolean isAllIds() { return allIds; }
        public void setAllIds(boolean value) { allIds = value; }
        private String tenant;
        private String namespace;
        private boolean descendants;
        private io.kestra.webserver.rbac.RbacAuthorizer.Kind resource;
        private List<io.kestra.webserver.rbac.RbacAuthorizer.Action> actions = List.of();
        private List<String> ids = List.of();
        public String getTenant() { return tenant; }
        public void setTenant(String value) { tenant = value; }
        public String getNamespace() { return namespace; }
        public void setNamespace(String value) { namespace = value; }
        public boolean isDescendants() { return descendants; }
        public void setDescendants(boolean value) { descendants = value; }
        public io.kestra.webserver.rbac.RbacAuthorizer.Kind getResource() { return resource; }
        public void setResource(io.kestra.webserver.rbac.RbacAuthorizer.Kind value) { resource = value; }
        public List<io.kestra.webserver.rbac.RbacAuthorizer.Action> getActions() { return actions; }
        public void setActions(List<io.kestra.webserver.rbac.RbacAuthorizer.Action> value) { actions = List.copyOf(value); }
        public List<String> getIds() { return ids; }
        public void setIds(List<String> value) { ids = List.copyOf(value); }
    }
}
