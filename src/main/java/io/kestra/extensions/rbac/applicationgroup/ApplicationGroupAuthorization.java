package io.kestra.extensions.rbac.applicationgroup;

import java.util.HashSet;
import io.kestra.core.models.NamespaceDomain;
import io.kestra.core.models.NamespaceDomain.Term;
import io.kestra.core.models.NamespaceDomain.Universe;
import io.kestra.core.models.NamespaceDomain.IdScope;
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

    /** Namespace-only view for legacy internal callers; all algebra is the published V2 implementation. */
    public record Domain(NamespaceDomain value) {
        public Domain(Set<String> exact, Set<String> subtrees) {
            this(new NamespaceDomain(List.of(new Term(Universe.NAMESPACE, false, List.copyOf(exact),
                List.copyOf(subtrees), List.of(), List.of(), IdScope.ALL_IDS, List.of()))));
        }
        static Domain empty() { return new Domain(new NamespaceDomain(List.of())); }
        public static Domain all() { return new Domain(new NamespaceDomain(List.of(new Term(Universe.NAMESPACE,
            true, List.of(), List.of(), List.of(), List.of(), IdScope.ALL_IDS, List.of())))); }
        boolean contains(String namespace) { return value.contains(namespace); }
        boolean isEmpty() { return value.terms().isEmpty(); }
        Domain union(Domain other) { return new Domain(value.union(other.value)); }
        Domain intersect(Domain other) { return new Domain(value.intersect(other.value)); }
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
        if (request.resource().equals("AI")) {
            if (request.namespace() != null || request.id() != null || request.list()
                || !Set.of("READ", "EXECUTE").contains(request.action())) return Decision.deny();
            for (var scope : scopes) for (var grant : scope.grants())
                if (grant.scope().equals("TENANT") && grant.tenant().equals(request.tenant())
                    && grant.resource().equals("AI") && grant.actions().contains(request.action()))
                    return new Decision(true, Domain.empty());
            return Decision.deny();
        }
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
            return bounded.isEmpty() ? Decision.deny() : new Decision(true, bounded);
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

    /** Complete correlated namespace × ID domain; no independent namespace/ID projections. */
    public record ListDecision(boolean allowed, NamespaceDomain domain) {
        public ListDecision {
            Objects.requireNonNull(domain);
            if (allowed != !domain.terms().isEmpty()) throw new IllegalArgumentException("Invalid list decision");
        }
        public boolean contains(String namespace, String id) { return domain.allows(namespace, id); }
        static ListDecision deny() { return new ListDecision(false, new NamespaceDomain(List.of())); }
    }

    /** READ/LIST intersection within each scope, followed by union of complete scope domains. */
    public ListDecision authorizeList(Identity identity, Request request, Directory directory) {
        if (request != null && "AI".equals(request.resource())) return ListDecision.deny();
        var scopes = validatedScopes(identity, request, directory);
        if (scopes == null || !request.list() || !"main".equals(request.tenant())
            || request.resource() == null || !Set.of("READ", "LIST").contains(request.action()))
            return ListDecision.deny();
        boolean tenantResource = Set.of("METADATA", "MCP_SERVER").contains(request.resource());
        if (tenantResource && request.namespace() != null) return ListDecision.deny();
        try {
            var result = new NamespaceDomain(List.of());
            for (var scope : scopes) {
                var reads = actionDomain(scope.grants(), "READ", request);
                var lists = actionDomain(scope.grants(), "LIST", request);
                result = result.union(reads.intersect(lists));
            }
            if (!tenantResource) {
                result = result.intersect(request.queryDomain().value());
                if (request.namespace() != null)
                    result = result.intersect(new Domain(Set.of(request.namespace()), Set.of()).value());
            }
            if (request.id() != null) {
                var id = new Term(tenantResource ? Universe.TENANT : Universe.NAMESPACE, !tenantResource,
                    List.of(), List.of(), List.of(), List.of(), IdScope.RESTRICTED_IDS, List.of(request.id()));
                result = result.intersect(new NamespaceDomain(List.of(id)));
            }
            return new ListDecision(!result.terms().isEmpty(), result);
        } catch (IllegalArgumentException invalidOrOverLimit) {
            return ListDecision.deny();
        }
    }

    private NamespaceDomain actionDomain(List<ApplicationGroupPolicy.Grant> grants, String action, Request request) {
        return new NamespaceDomain(grants.stream().filter(g -> g.tenant().equals(request.tenant())
            && g.resource().equals(request.resource()) && g.actions().contains(action)).map(ApplicationGroupAuthorization::grantTerm).toList());
    }

    private static Term grantTerm(ApplicationGroupPolicy.Grant grant) {
        boolean tenant = grant.scope().equals("TENANT");
        boolean complement = !tenant && grant.namespace().startsWith("!");
        String name = tenant ? null : complement ? grant.namespace().substring(1) : grant.namespace();
        return new Term(tenant ? Universe.TENANT : Universe.NAMESPACE, complement,
            !tenant && !complement && !grant.descendants() ? List.of(name) : List.of(),
            !tenant && !complement && grant.descendants() ? List.of(name) : List.of(),
            complement && !grant.descendants() ? List.of(name) : List.of(),
            complement && grant.descendants() ? List.of(name) : List.of(),
            grant.allIds() ? IdScope.ALL_IDS : IdScope.RESTRICTED_IDS, List.copyOf(grant.ids()));
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
        if (!upper.scope().equals(local.scope()) || !upper.tenant().equals(local.tenant())
            || !upper.resource().equals(local.resource()) || !upper.actions().containsAll(local.actions())) return false;
        if (!upper.allIds() && (local.allIds() || !upper.ids().containsAll(local.ids()))) return false;
        if (upper.scope().equals("TENANT")) return true;
        boolean upperNegative=upper.namespace().startsWith("!");
        boolean localNegative=local.namespace().startsWith("!");
        String u=upperNegative?upper.namespace().substring(1):upper.namespace();
        String l=localNegative?local.namespace().substring(1):local.namespace();
        if (!upperNegative && !localNegative) return selectorContains(u,upper.descendants(),l,local.descendants());
        if (upperNegative && !localNegative) return !selectorsOverlap(u,upper.descendants(),l,local.descendants());
        if (!upperNegative) return false; // A finite rooted selector cannot contain a complement in U.
        return selectorContains(l,local.descendants(),u,upper.descendants()); // U\L subset U\U iff U subset L.
    }
    private static boolean selectorContains(String upper,boolean upperTree,String local,boolean localTree) {
        return upper.equals(local) ? upperTree || !localTree : upperTree && local.startsWith(upper+".");
    }
    private static boolean selectorsOverlap(String left,boolean leftTree,String right,boolean rightTree) {
        return left.equals(right) || leftTree && right.startsWith(left+".") || rightTree && left.startsWith(right+".");
    }
    private Domain domains(List<ApplicationGroupPolicy.Grant> grants, String action, Request request) {
        return new Domain(new NamespaceDomain(grants.stream().filter(grant -> grant.tenant().equals(request.tenant())
            && grant.resource().equals(request.resource()) && grant.actions().contains(action)
            && grant.allIds() && grant.namespace() != null).map(ApplicationGroupAuthorization::grantTerm).toList()));
    }
    private boolean complete(ApplicationGroupPolicy.Grant grant, Request request) {
        return grant.tenant().equals(request.tenant()) && grant.resource().equals(request.resource())
            && grant.actions().contains(request.action()) && grantTerm(grant).allows(request.namespace(), request.id());
    }
}
