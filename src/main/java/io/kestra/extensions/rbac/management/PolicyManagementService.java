package io.kestra.extensions.rbac.management;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import io.kestra.extensions.rbac.store.JdbcPolicyStore;
import io.kestra.extensions.rbac.store.PolicyDocument;
import io.kestra.extensions.rbac.store.PolicyDocumentValidator;
import io.kestra.webserver.rbac.RbacAuthorizer;

public final class PolicyManagementService {
    private final JdbcPolicyStore store;
    private final PolicyDocumentValidator validator;
    private final Set<String> administrators;

    public static final class Denied extends RuntimeException {
        public Denied() { super("Management access denied"); }
    }
    public static final class Conflict extends RuntimeException {
        public Conflict() { super("Role or binding conflict"); }
    }
    public static final class Missing extends RuntimeException {
        public Missing() { super("Role not found"); }
    }
    public PolicyManagementService(JdbcPolicyStore store, PolicyDocumentValidator validator, Set<String> administrators) {
        this.store = store;
        this.validator = validator;
        this.administrators = Set.copyOf(administrators);
        if (this.administrators.isEmpty()) throw new IllegalArgumentException("Management administrators required");
    }
    public boolean isAdministrator(RbacAuthorizer.Principal principal) {
        return principal != null && principal.subject() != null && administrators.contains(principal.subject());
    }
    public boolean protectedSubject(String subject) { return administrators.contains(subject); }
    private void authorized(RbacAuthorizer.Principal principal) {
        if (!isAdministrator(principal)) throw new Denied();
    }
    public JdbcPolicyStore.Snapshot read(RbacAuthorizer.Principal principal) {
        authorized(principal);
        return store.readCurrent();
    }
    private JdbcPolicyStore.Snapshot update(RbacAuthorizer.Principal principal, long revision,
        UnaryOperator<PolicyDocument> change) {
        authorized(principal);
        var previous = store.readCurrent();
        if (revision != previous.revision()) throw new JdbcPolicyStore.RevisionConflictException();
        var candidate = validator.validate(change.apply(previous.document()));
        return store.replace(revision, candidate.canonicalJson().getBytes(StandardCharsets.UTF_8), principal.subject());
    }
    public JdbcPolicyStore.Snapshot createRole(RbacAuthorizer.Principal principal, long revision, PolicyDocument.Role role) {
        return update(principal, revision, document -> {
            if (document.roles().stream().anyMatch(existing -> existing.id().equals(role.id()) || existing.name().equals(role.name()))) throw new Conflict();
            var roles = new ArrayList<>(document.roles());
            roles.add(role);
            return new PolicyDocument(1, roles, document.subjects(), document.roleMapping());
        });
    }
    public JdbcPolicyStore.Snapshot replaceRole(RbacAuthorizer.Principal principal, long revision,
        String identifier, PolicyDocument.Role replacement) {
        if (replacement.id() == null || !identifier.equals(replacement.id().value())) throw new IllegalArgumentException("Role ID is immutable");
        return update(principal, revision, document -> {
            var roles = new ArrayList<PolicyDocument.Role>();
            boolean found = false;
            for (var existing : document.roles()) {
                if (identifier.equals(existing.id().value())) { roles.add(replacement); found = true; }
                else roles.add(existing);
            }
            if (!found) throw new Missing();
            return new PolicyDocument(1, roles, document.subjects(), document.roleMapping());
        });
    }
    public JdbcPolicyStore.Snapshot deleteRole(RbacAuthorizer.Principal principal, long revision, String identifier) {
        return update(principal, revision, document -> {
            var id = new PolicyDocument.RoleId(identifier);
            if (document.subjects().values().stream().anyMatch(roles -> roles.contains(id))
                || document.roleMapping().values().stream().anyMatch(roles -> roles.contains(id))) throw new Conflict();
            var roles = document.roles().stream().filter(role -> !role.id().equals(id)).toList();
            if (roles.size() == document.roles().size()) throw new Missing();
            return new PolicyDocument(1, roles, document.subjects(), document.roleMapping());
        });
    }
    public JdbcPolicyStore.Snapshot bindSubject(RbacAuthorizer.Principal principal, long revision, String subject, List<String> roles) {
        return bindings(principal, revision, subject, roles, false);
    }
    public JdbcPolicyStore.Snapshot mapTrustedRole(RbacAuthorizer.Principal principal, long revision, String role, List<String> roles) {
        return bindings(principal, revision, role, roles, true);
    }
    private JdbcPolicyStore.Snapshot bindings(RbacAuthorizer.Principal principal, long revision,
        String key, List<String> roleIds, boolean mapping) {
        return update(principal, revision, document -> {
            Map<String, List<PolicyDocument.RoleId>> changed = new TreeMap<>(mapping ? document.roleMapping() : document.subjects());
            if (key == null || key.isBlank() || !key.equals(key.trim()) || key.contains("*")) throw new IllegalArgumentException("Exact binding required");
            if (roleIds.isEmpty()) changed.remove(key);
            else changed.put(key, roleIds.stream().map(PolicyDocument.RoleId::new).toList());
            return new PolicyDocument(1, document.roles(), mapping ? document.subjects() : changed,
                mapping ? changed : document.roleMapping());
        });
    }
}
