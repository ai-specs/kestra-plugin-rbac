package io.kestra.extensions.rbac.applicationgroup;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class ApplicationGroupAuthorization {
    public record DirectoryScope(String name, String description) {
        public DirectoryScope {
            if (name == null || name.isBlank() || description == null)
                throw new IllegalArgumentException("Invalid application group scope");
        }
    }

    public record ScopeCatalog(String issuer, Set<String> audiences, boolean enabled,
                               long directoryRevision, java.util.Map<String, DirectoryScope> scopes) {
        public ScopeCatalog {
            if (issuer == null || issuer.isBlank() || audiences == null
                || audiences.stream().anyMatch(audience -> audience == null || audience.isBlank())
                || directoryRevision <= 0 || scopes == null)
                throw new IllegalArgumentException("Invalid application group catalog");
            audiences = Set.copyOf(audiences);
            scopes = java.util.Map.copyOf(scopes);
            if (scopes.keySet().stream().anyMatch(key -> !key.matches("[a-z][a-z0-9-]{0,63}")))
                throw new IllegalArgumentException("Invalid scope key");
        }
    }

    public record Directory(ScopeCatalog upperBound, String project, Set<String> protocolScopes) {
        public Directory {
            protocolScopes = protocolScopes == null ? Set.of() : Set.copyOf(protocolScopes);
        }
    }

    public record Identity(String subject, String issuer, String audience, String project,
                           long directoryRevision, Set<String> scopes, Set<String> protocolScopes, String tenant,boolean trustedLogin) {
        public Identity(String subject,String issuer,String audience,String project,long revision,Set<String> scopes,Set<String> protocols,String tenant) {
            this(subject,issuer,audience,project,revision,scopes,protocols,tenant,false);
        }
        public Identity(String subject, String issuer, String audience, String project, long directoryRevision, Set<String> scopes, Set<String> protocolScopes) {
            this(subject, issuer, audience, project, directoryRevision, scopes, protocolScopes, "main");
        }
        public Identity {
            scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
            protocolScopes = protocolScopes == null ? Set.of() : Set.copyOf(protocolScopes);
        }
    }

    public record Domain(Set<String> exact, Set<String> subtrees) {
        public Domain {
            exact = Set.copyOf(exact);
            subtrees = Set.copyOf(subtrees);
        }
        static Domain empty() { return new Domain(Set.of(), Set.of()); }
        boolean contains(String namespace) {
            return namespace != null && (exact.contains(namespace)
                || subtrees.stream().anyMatch(root -> namespace.equals(root) || namespace.startsWith(root + ".")));
        }
        Domain union(Domain other) {
            Set<String> exactValues = new HashSet<>(exact); exactValues.addAll(other.exact);
            Set<String> subtreeValues = new HashSet<>(subtrees); subtreeValues.addAll(other.subtrees);
            return new Domain(exactValues, subtreeValues);
        }
        Domain intersect(Domain other) {
            Set<String> exactValues = new HashSet<>(); Set<String> subtreeValues = new HashSet<>();
            for (String value : exact) if (other.contains(value)) exactValues.add(value);
            for (String value : other.exact) if (contains(value)) exactValues.add(value);
            for (String left : subtrees) for (String right : other.subtrees) {
                if (left.equals(right)) subtreeValues.add(left);
                else if (left.startsWith(right + ".")) subtreeValues.add(left);
                else if (right.startsWith(left + ".")) subtreeValues.add(right);
            }
            return new Domain(exactValues, subtreeValues);
        }
    }

    public record Request(String tenant, String namespace, String resource, String action,
                          String id, boolean list, Domain queryDomain) {
        public Request(String tenant, String namespace, String resource, String action, String id, boolean list) {
            this(tenant, namespace, resource, action, id, list, new Domain(namespace == null ? Set.of() : Set.of(namespace), Set.of()));
        }
        public Request {
            if (queryDomain == null) throw new IllegalArgumentException("queryDomain is required");
        }
    }

    public record Decision(boolean allowed, Domain domain) {
        static Decision deny() { return new Decision(false, Domain.empty()); }
    }

    private final ApplicationGroupPolicy policy;

    public ApplicationGroupAuthorization(ApplicationGroupPolicy policy) { this.policy = Objects.requireNonNull(policy); }

    public Decision authorize(Identity identity, Request request, Directory directory) {
        var scopes = validatedScopes(identity, request, directory);
        if (scopes == null) return Decision.deny();
        if (request.resource() == null || request.action() == null || !"main".equals(request.tenant())) return Decision.deny();
        if (request.resource().equals("METADATA")) {
            if (request.namespace() != null || request.id() != null || !Set.of("READ", "LIST").contains(request.action())) return Decision.deny();
            for (var scope : scopes) {
                boolean read = scope.grants().stream().anyMatch(grant -> grant.resource().equals("METADATA") && grant.actions().contains("READ"));
                boolean list = scope.grants().stream().anyMatch(grant -> grant.resource().equals("METADATA") && grant.actions().contains("LIST"));
                if (request.list() ? read && list : scope.grants().stream().anyMatch(grant -> grant.resource().equals("METADATA") && grant.actions().contains(request.action()))) return new Decision(true, Domain.empty());
            }
            return Decision.deny();
        }
        if (request.resource().equals("MCP_SERVER")) {
            if (request.namespace() != null) return Decision.deny();
            if (request.list()) {
                if (!Set.of("READ", "LIST").contains(request.action())) return Decision.deny();
                // Restricted-ID query domains are carried by the published host adapter; never widen them here.
                for (var scope : scopes) {
                    boolean read=scope.grants().stream().anyMatch(grant->grant.resource().equals("MCP_SERVER")&&grant.allIds()&&grant.actions().contains("READ"));
                    boolean list=scope.grants().stream().anyMatch(grant->grant.resource().equals("MCP_SERVER")&&grant.allIds()&&grant.actions().contains("LIST"));
                    if(read&&list)return new Decision(true,Domain.empty());
                }
                return Decision.deny();
            }
        }
        if (request.list()) {
            if (!Set.of("READ", "LIST").contains(request.action())) return Decision.deny();
            Domain result = Domain.empty();
            for (var scope : scopes) result = result.union(domains(scope.grants(), "READ", request).intersect(domains(scope.grants(), "LIST", request)));
            Domain bounded = result.intersect(request.queryDomain());
            if (request.namespace() != null) bounded = bounded.intersect(new Domain(Set.of(request.namespace()), Set.of()));
            return bounded.exact().isEmpty() && bounded.subtrees().isEmpty() ? Decision.deny() : new Decision(true, bounded);
        }
        for (var scope : scopes) for (var grant : scope.grants()) if (complete(grant, request)) return new Decision(true, Domain.empty());
        return Decision.deny();
    }

    public boolean authorizeManagement(Identity identity, Directory directory,
        ApplicationGroupPolicy.ManagementDomain domain, ApplicationGroupPolicy.ManagementAction action) {
        return authorizeManagement(identity,directory,domain,action,identity == null ? null : identity.project());
    }
    public boolean authorizeManagement(Identity identity, Directory directory,
        ApplicationGroupPolicy.ManagementDomain domain, ApplicationGroupPolicy.ManagementAction action, String targetProject) {
        if (!validProjectId(targetProject)) return false;
        if (domain == null || action == null) return false;
        Request probe = new Request("main", null, "FLOW", "READ", null, false, Domain.empty());
        if (!validIdentity(identity, probe, directory)) return false;
        var local = policy.applicationGroups().get(identity.project());
        var upper = directory.upperBound();
        if (!local.enabled() || !upper.enabled() || identity.directoryRevision() != upper.directoryRevision()
            || !local.issuer().equals(identity.issuer())
            || !local.issuer().equals(upper.issuer()) || !identity.trustedLogin() && !local.audiences().contains(identity.audience())
            || !upper.audiences().containsAll(local.audiences())) return false;
        for (String protocol : identity.protocolScopes()) if (!directory.protocolScopes().contains(protocol)) return false;
        boolean allowed = false;
        for (String name : identity.scopes()) {
            var scope = local.scopes().get(name); var ceiling = upper.scopes().get(name);
            if (scope == null || ceiling == null || !scope.enabled()
                || identity.protocolScopes().contains(name)) return false;
            if (scope.managementGrants().stream().anyMatch(grant -> grant.domain() == domain && grant.actions().contains(action) && managementTarget(grant,identity.project(),targetProject))) allowed = true;
        }
        return allowed;
    }
    /** Validated project scopes shared by scalar and correlated list decisions. */
    private List<ApplicationGroupPolicy.ScopeDefinition> validatedScopes(Identity identity, Request request, Directory directory) {
        if (!validIdentity(identity, request, directory)) return null;
        ApplicationGroupPolicy.ApplicationGroup local = policy.applicationGroups().get(identity.project());
        ScopeCatalog upper = directory.upperBound();
        if (!local.issuer().equals(identity.issuer()) || !identity.trustedLogin() && !local.audiences().contains(identity.audience()) || !local.enabled() || !upper.enabled() || identity.directoryRevision() != upper.directoryRevision()
            || !local.issuer().equals(upper.issuer())
            || !identity.trustedLogin() && !upper.audiences().contains(identity.audience()) || !upper.audiences().containsAll(local.audiences())) return null;
        for (String protocolScope : identity.protocolScopes()) if (!directory.protocolScopes().contains(protocolScope)) return null;
        List<ApplicationGroupPolicy.ScopeDefinition> scopes = new java.util.ArrayList<>();
        for (String scopeName : identity.scopes()) {
            if (identity.protocolScopes().contains(scopeName)) return null;
            var scope = local.scopes().get(scopeName);
            var ceiling = upper.scopes().get(scopeName);
            if (scope == null || ceiling == null || !scope.enabled()
                || scope.grants().isEmpty() && scope.managementGrants().isEmpty())
                return null;
            scopes.add(scope);
        }
        if (scopes.isEmpty()) return null;
        return List.copyOf(scopes);
    }

    /** A list domain keeps namespace and ID constraints together. It must never be flattened to independent sets. */
    public record ListTerm(String tenant, String resource, Domain namespaces, boolean tenantResource,
                           Set<String> ids, boolean allIds) {
        public ListTerm {
            Objects.requireNonNull(tenant); Objects.requireNonNull(resource); Objects.requireNonNull(namespaces);
            ids = Set.copyOf(ids);
            if (allIds && !ids.isEmpty() || !allIds && ids.isEmpty()) throw new IllegalArgumentException("Invalid ID domain");
            if (tenantResource && (!namespaces.exact().isEmpty() || !namespaces.subtrees().isEmpty()))
                throw new IllegalArgumentException("Tenant resource has no namespace");
        }
        public boolean contains(String namespace, String id) {
            return (tenantResource ? namespace == null : namespaces.contains(namespace))
                && (allIds || id != null && ids.contains(id));
        }
    }
    public record ListDecision(boolean allowed, List<ListTerm> terms) {
        public ListDecision {
            terms = List.copyOf(terms);
            if (allowed != !terms.isEmpty()) throw new IllegalArgumentException("Invalid list decision");
        }
        public boolean contains(String namespace, String id) {
            return terms.stream().anyMatch(term -> term.contains(namespace, id));
        }
    }

    /** Complete READ/LIST intersection inside each scope, then union without losing ID correlation.
     * Only adapters with an actual correlated query consumer may use this result.
     */
    public ListDecision authorizeList(Identity identity, Request request, Directory directory) {
        var scopes = validatedScopes(identity, request, directory);
        if (scopes == null || !request.list() || !"main".equals(request.tenant())
            || request.resource() == null || !Set.of("READ", "LIST").contains(request.action()))
            return new ListDecision(false, List.of());
        boolean tenantResource = Set.of("METADATA", "MCP_SERVER").contains(request.resource());
        if (tenantResource && request.namespace() != null) return new ListDecision(false, List.of());
        var terms = new java.util.LinkedHashSet<ListTerm>();
        long comparisons = 0;
        for (var scope : scopes) {
            var reads = scope.grants().stream().filter(g -> g.tenant().equals(request.tenant())
                && g.resource().equals(request.resource()) && g.actions().contains("READ")).toList();
            var lists = scope.grants().stream().filter(g -> g.tenant().equals(request.tenant())
                && g.resource().equals(request.resource()) && g.actions().contains("LIST")).toList();
            for (var read : reads) for (var list : lists) {
                // Bound work and fail the whole evaluation rather than return a partial allow.
                if (++comparisons > 100_000) return new ListDecision(false, List.of());
                boolean allIds = read.allIds() && list.allIds();
                Set<String> ids = new HashSet<>();
                if (!allIds) {
                    if (read.allIds()) ids.addAll(list.ids());
                    else if (list.allIds()) ids.addAll(read.ids());
                    else { ids.addAll(read.ids()); ids.retainAll(list.ids()); }
                    if (ids.isEmpty()) continue;
                }
                Domain domain = Domain.empty();
                if (!tenantResource) {
                    domain = grantDomain(read).intersect(grantDomain(list)).intersect(request.queryDomain());
                    if (request.namespace() != null) domain = domain.intersect(new Domain(Set.of(request.namespace()), Set.of()));
                    if (domain.exact().isEmpty() && domain.subtrees().isEmpty()) continue;
                }
                if (request.id() != null) {
                    if (!allIds && !ids.contains(request.id())) continue;
                    ids = Set.of(request.id()); allIds = false;
                }
                terms.add(new ListTerm(request.tenant(), request.resource(), domain, tenantResource, ids, allIds));
            }
        }
        return new ListDecision(!terms.isEmpty(), List.copyOf(terms));
    }
    private Domain grantDomain(ApplicationGroupPolicy.Grant grant) {
        if (grant.namespace() == null) return Domain.empty();
        return grant.descendants() ? new Domain(Set.of(), Set.of(grant.namespace()))
            : new Domain(Set.of(grant.namespace()), Set.of());
    }

    static Set<String> resolvedTargets(ApplicationGroupPolicy.ManagementGrant grant,String ownerProject) {
        return grant.applicationGroupIds().isEmpty() ? Set.of(ownerProject) : grant.applicationGroupIds();
    }
    private boolean managementTarget(ApplicationGroupPolicy.ManagementGrant grant,String ownerProject,String targetProject) {
        return grant.domain() == ApplicationGroupPolicy.ManagementDomain.IDENTITY || resolvedTargets(grant,ownerProject).contains(targetProject);
    }
    private boolean validProjectId(String id) {
        return id != null && id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}");
    }

    private boolean validIdentity(Identity identity, Request request, Directory directory) {
        return identity != null && request != null && directory != null && directory.upperBound() != null
            && "main".equals(identity.tenant()) && identity.subject() != null && !identity.subject().isBlank() && identity.project() != null
            && identity.audience() != null && identity.issuer() != null && identity.project().equals(directory.project())
            && policy.applicationGroups().containsKey(identity.project());
    }
    static boolean covers(ApplicationGroupPolicy.Grant upper, ApplicationGroupPolicy.Grant local) {
        return upper.scope().equals(local.scope()) && upper.tenant().equals(local.tenant()) && upper.resource().equals(local.resource())
            && upper.actions().containsAll(local.actions())
            && (Objects.equals(upper.namespace(), local.namespace()) && (upper.descendants() || !local.descendants())
                || upper.descendants() && local.namespace() != null && local.namespace().startsWith(upper.namespace() + ".")) && (upper.allIds() || !local.allIds() && upper.ids().containsAll(local.ids()));
    }
    private Domain domains(List<ApplicationGroupPolicy.Grant> grants, String action, Request request) {
        Set<String> exact = new HashSet<>(); Set<String> subtrees = new HashSet<>();
        for (var grant : grants) if (grant.tenant().equals(request.tenant()) && grant.resource().equals(request.resource())
            && grant.actions().contains(action) && grant.allIds() && grant.namespace() != null) {
            if (grant.descendants()) subtrees.add(grant.namespace()); else exact.add(grant.namespace());
        }
        return new Domain(exact, subtrees);
    }
    private boolean complete(ApplicationGroupPolicy.Grant grant, Request request) {
        return grant.tenant().equals(request.tenant()) && grant.resource().equals(request.resource())
            && grant.actions().contains(request.action()) && (Objects.equals(grant.namespace(), request.namespace())
            || grant.descendants() && grant.namespace() != null && request.namespace() != null && request.namespace().startsWith(grant.namespace() + "."))
            && (grant.allIds() || request.id() != null && grant.ids().contains(request.id()));
    }
}
