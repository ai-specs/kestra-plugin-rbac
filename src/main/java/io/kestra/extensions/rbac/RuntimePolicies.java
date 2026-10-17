package io.kestra.extensions.rbac;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import io.kestra.extensions.rbac.store.JdbcPolicyStore;
import io.kestra.extensions.rbac.store.PolicySourceSelection;
import io.kestra.webserver.rbac.RbacAuthorizer;
import io.kestra.webserver.rbac.TypedAuthorization;
import io.micronaut.context.BeanProvider;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Order;
import io.micronaut.core.order.Ordered;
import jakarta.inject.Singleton;

@Singleton
@Context
@Order(Ordered.LOWEST_PRECEDENCE)
@Requires(property = "kestra.rbac.enabled", value = "true")
public final class RuntimePolicies {
    private final PolicyEngine configured;
    private final PolicyEngine administratorPolicy;
    private final Set<String> administrators;
    private final JdbcPolicyStore store;

    @jakarta.inject.Inject
    public RuntimePolicies(RbacConfiguration configuration, PolicyEngine configured, BeanProvider<JdbcPolicyStore> stores,
        @Value("${kestra.rbac.evaluation.enabled:false}") boolean evaluation,
        @Value("${kestra.rbac.typed.enabled:false}") boolean typed) {
        this.configured = configured;
        boolean database = "database".equals(configuration.getPolicySource());
        if (!database) throw new IllegalArgumentException("Only database policy source is supported");
        var selection = new PolicySourceSelection(PolicySourceSelection.Source.DATABASE,
            Set.copyOf(configuration.getManagementAdminSubjects()));
        selection.validateConfiguration(configuration);
        administrators = selection.managementAdminSubjects();
        if (!evaluation || !typed || !stores.isUnique()) throw new IllegalArgumentException("Database policy requires typed evaluation and one policy store");
        store = stores.get();
        administratorPolicy = administrativePolicy(administrators);
        if (store != null) store.readCurrent();
    }
    private RuntimePolicies(PolicyEngine configured) {
        this.configured = configured;
        administratorPolicy = configured;
        administrators = Set.of();
        store = null;
    }
    static RuntimePolicies compiledPolicy(PolicyEngine engine) { return new RuntimePolicies(engine); }
    private static PolicyEngine administrativePolicy(Set<String> subjects) {
        var configuration = new RbacConfiguration();
        configuration.setRoles(Map.of("internal-management-root", List.of()));
        configuration.setInstanceAdminRoles(List.of("internal-management-root"));
        configuration.setSubjects(subjects.stream().collect(Collectors.toMap(subject -> subject, subject -> List.of("internal-management-root"))));
        return new PolicyEngine(configuration);
    }
    public boolean database() { return store != null; }
    public Set<String> protectedSubjects() { return administrators; }
    public JdbcPolicyStore store() {
        if (store == null) throw new IllegalStateException("Configuration policy is read-only");
        return store;
    }
    public boolean isAdministrator(RbacAuthorizer.Principal principal) {
        return principal != null && (store == null ? configured != null && configured.isInstanceAdmin(principal) : administrators.contains(principal.subject()));
    }
    public Snapshot snapshot() {
        if (store == null) return new Snapshot(0, configured);
        var current = store.readCurrent();
        return new Snapshot(current.revision(), current.engine());
    }
    public final class Snapshot {
        private final long revision;
        private final PolicyEngine engine;
        private Snapshot(long revision, PolicyEngine engine) { this.revision = revision; this.engine = engine; }
        public long revision() { return revision; }
        private PolicyEngine policy(RbacAuthorizer.Principal principal) {
            return isAdministrator(principal) ? administratorPolicy : engine;
        }
        public TypedAuthorization.Decision authorize(RbacAuthorizer.Principal principal, TypedAuthorization.Operation operation) {
            return policy(principal).authorizeTyped(principal, operation);
        }
        public boolean authorize(RbacAuthorizer.Principal principal, RbacAuthorizer.Resource resource, RbacAuthorizer.Action action) {
            return policy(principal).authorize(new Principal(principal.subject(), Set.copyOf(principal.roles())),
                new AuthorizationRequest(resource.tenant(), resource.namespace(), resource.kind(), action, resource.id(), resource.scope()));
        }
    }
}
