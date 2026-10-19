package io.kestra.extensions.rbac;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.extensions.rbac.project.*;
import io.kestra.extensions.rbac.store.ProjectPolicyStore;
import io.kestra.webserver.oidc.ApplicationDirectoryV1;
import io.kestra.webserver.oidc.TrustedIdentityV1;
import io.micronaut.security.authentication.Authentication;
import jakarta.inject.Singleton;
import io.micronaut.context.annotation.Requires;
import java.util.*;

/** Candidate adapter. All projections in a context originate from one public directory read. */
@Singleton
@Requires(property="kestra.rbac.enabled", value="true")
public final class ProjectRuntimePolicies {
    public enum Failure { INVALID_IDENTITY, DIRECTORY_UNAVAILABLE, POLICY_UNAVAILABLE, INVALID_DIRECTORY,
        FORBIDDEN, INVALID_CANDIDATE, LAST_ADMIN, CLOSED }
    public static final class Rejected extends RuntimeException {
        private final Failure failure;
        public Rejected(Failure failure) { super(failure.name()); this.failure=failure; }
        public Failure failure() { return failure; }
    }
    private final ProjectPolicyStore store;
    private final ApplicationDirectoryV1 directory;
    private final TrustedIdentityV1 identities;
    @jakarta.inject.Inject
    public ProjectRuntimePolicies(ProjectPolicyStore store, ApplicationDirectoryV1 directory, TrustedIdentityV1 identities) {
        this.store=Objects.requireNonNull(store);this.directory=Objects.requireNonNull(directory);this.identities=Objects.requireNonNull(identities);
    }
    public Context open(Authentication authentication,String tenant) {
        if(authentication==null)throw new Rejected(Failure.INVALID_IDENTITY);
        var identity=identities.current();
        if(identity==null || !authentication.getName().equals(identity.subject()))throw new Rejected(Failure.INVALID_IDENTITY);
        return open(identity,tenant);
    }
    public Context open(TrustedIdentityV1.Identity identity,String tenant) {
        if(identity==null || identity.contractVersion()!=1 || !"main".equals(tenant)
            || !tenant.equals(identity.tenant()) || identity.subject().isBlank() || identity.clientId().isBlank()
            || identity.authenticationVersion()<0)throw new Rejected(Failure.INVALID_IDENTITY);
        List<ApplicationDirectoryV1.PolicyBoundary> boundaries;
        try { boundaries=List.copyOf(directory.policyBoundaries()); }
        catch(RuntimeException error){throw new Rejected(Failure.DIRECTORY_UNAVAILABLE);}
        var raw=new TreeMap<String,ApplicationDirectoryV1.PolicyBoundary>();
        for(var boundary:boundaries)if(raw.putIfAbsent(boundary.project().id(),boundary)!=null)throw new Rejected(Failure.INVALID_DIRECTORY);
        var current=raw.get(identity.projectId());
        if(current==null || !current.project().active() || !tenant.equals(current.project().tenant())
            || !identity.issuer().equals(current.project().issuer()) || identity.directoryRevision()!=current.project().revision()
            || identity.authorizationVersion()!=current.project().authorizationVersion()
            || identity.audiences().isEmpty() || !current.audiences().containsAll(identity.audiences()))
            throw new Rejected(Failure.INVALID_IDENTITY);
        ProjectPolicyStore.Snapshot snapshot;
        try {snapshot=store.readCurrent();}catch(RuntimeException error){throw new Rejected(Failure.POLICY_UNAVAILABLE);}
        return new Context(identity,snapshot,Map.copyOf(raw));
    }
    public static final class Context implements AutoCloseable {
        private final TrustedIdentityV1.Identity identity;
        private final ProjectPolicyStore.Snapshot snapshot;
        private final Map<String,ApplicationDirectoryV1.PolicyBoundary> boundaries;
        private final Map<String,ProjectAuthorization.Directory> directories;
        private final Map<String,ProjectManagementSafety.DirectoryState> states;
        private final Set<String> managementTargets;
        private boolean closed;
        private Context(TrustedIdentityV1.Identity identity,ProjectPolicyStore.Snapshot snapshot,
            Map<String,ApplicationDirectoryV1.PolicyBoundary> boundaries) {
            this.identity=identity;this.snapshot=snapshot;this.boundaries=boundaries;
            managementTargets=ProjectRuntimePolicies.managementTargets(snapshot.policy(),boundaries);
            directories=project(boundaries,requiredProjects(snapshot.policy(),identity.projectId()));
            var values=new TreeMap<String,ProjectManagementSafety.DirectoryState>();
            directories.forEach((id,bound)->values.put(id,new ProjectManagementSafety.DirectoryState(bound,boundaries.get(id).reachableManagementScopes())));
            states=Map.copyOf(values);
        }
        private void check(){if(closed)throw new Rejected(Failure.CLOSED);}
        public TrustedIdentityV1.Identity identity(){check();return identity;}
        public ProjectPolicyStore.Snapshot snapshot(){check();return snapshot;}
        public Map<String,ProjectAuthorization.Directory> directories(){check();return directories;}
        public Set<String> managementTargets(){check();return managementTargets;}
        public Map<String,ProjectManagementSafety.DirectoryState> directoryStates(){check();return states;}
        public ApplicationDirectoryV1.PolicyBoundary upperBound(String target){check();var value=boundaries.get(target);if(value==null)throw new Rejected(Failure.FORBIDDEN);return value;}
        /** Binding options require a usable target audience; identity-directory reads use upperBound instead. */
        public ApplicationDirectoryV1.PolicyBoundary bindingBoundary(String target){
            var boundary=upperBound(target);
            if(boundary.audiences().isEmpty())throw new Rejected(Failure.INVALID_CANDIDATE);
            return boundary;
        }
        private boolean acceptedAudiences(){
            var local=snapshot.policy().applicationProjects().get(identity.projectId());
            return local!=null && local.audiences().containsAll(identity.audiences());
        }
        private ProjectAuthorization.Identity localIdentity(){
            return new ProjectAuthorization.Identity(identity.subject(),identity.issuer(),identity.audiences().stream().sorted().findFirst().orElseThrow(),
                identity.projectId(),identity.directoryRevision(),identity.projectScopes(),identity.protocolScopes(),identity.tenant());
        }
        public boolean authorizeManagement(ProjectPolicy.ManagementDomain domain,ProjectPolicy.ManagementAction action,String target){
            check();if(!acceptedAudiences())return false;return new ProjectAuthorization(snapshot.policy()).authorizeManagement(localIdentity(),directories.get(identity.projectId()),domain,action,target);
        }
        public boolean authorizeReconciliation(String target){
            check();if(!acceptedAudiences())return false;return new ProjectAuthorization(snapshot.policy()).authorizeReconciliation(localIdentity(),directories.get(identity.projectId()),target);
        }
        public ProjectPolicy.Project visibleProject(String target){
            check();if(!authorizeManagement(ProjectPolicy.ManagementDomain.LOCAL_POLICY,ProjectPolicy.ManagementAction.READ,target))throw new Rejected(Failure.FORBIDDEN);
            var value=snapshot.policy().applicationProjects().get(target);if(value==null)throw new Rejected(Failure.FORBIDDEN);return value;
        }
        public JsonNode visiblePolicy(){
            check();var mapper=new ObjectMapper();var result=mapper.createObjectNode();result.put("revision",snapshot.revision());result.put("schemaVersion",2);
            var projects=result.putObject("applicationProjects");
            snapshot.policy().applicationProjects().forEach((id,value)->{if(authorizeManagement(ProjectPolicy.ManagementDomain.LOCAL_POLICY,ProjectPolicy.ManagementAction.READ,id))projects.set(id,mapper.valueToTree(value));});
            return result;
        }
        /** A write receipt grants no new READ authority and exposes only the explicitly authorized target. */
        public JsonNode savedTarget(ProjectPolicyStore.Snapshot saved,String target,boolean reconciliation){
            check();boolean allowed=reconciliation?authorizeReconciliation(target):authorizeManagement(ProjectPolicy.ManagementDomain.LOCAL_POLICY,ProjectPolicy.ManagementAction.MANAGE,target);
            if(!allowed || saved.revision()<=snapshot.revision())throw new Rejected(Failure.FORBIDDEN);
            var value=saved.policy().applicationProjects().get(target);if(value==null)throw new Rejected(Failure.FORBIDDEN);
            var mapper=new ObjectMapper();var result=mapper.createObjectNode();result.put("revision",saved.revision());result.put("schemaVersion",2);
            result.putObject("applicationProjects").set(target,mapper.valueToTree(value));return result;
        }
        public void validateCandidate(ProjectPolicy candidate){
            check();var targets=ProjectRuntimePolicies.managementTargets(candidate,boundaries);var projected=project(boundaries,requiredProjects(candidate,identity.projectId()));
            try{ProjectPolicyBoundary.validate(candidate,projected,targets);}catch(IllegalArgumentException error){throw new Rejected(Failure.INVALID_CANDIDATE);}
            var currentStates=new TreeMap<String,ProjectManagementSafety.DirectoryState>();
            projected.forEach((id,bound)->currentStates.put(id,new ProjectManagementSafety.DirectoryState(bound,boundaries.get(id).reachableManagementScopes())));
            try{ProjectManagementSafety.validate(candidate,currentStates,targets);}catch(IllegalArgumentException error){throw new Rejected(Failure.LAST_ADMIN);}
        }
        public io.kestra.webserver.rbac.TypedAuthorization.Decision authorize(io.kestra.webserver.rbac.TypedAuthorization.Operation operation){
            check();return ProjectTypedPolicy.authorize(this,operation);
        }
        @Override public void close(){closed=true;}
    }
    private static Set<String> requiredProjects(ProjectPolicy policy,String loginProject){
        var required=new HashSet<>(policy.applicationProjects().keySet());required.add(loginProject);
        return Set.copyOf(required);
    }
    private static Set<String> managementTargets(ProjectPolicy policy,Map<String,ApplicationDirectoryV1.PolicyBoundary> boundaries){
        var targets=new HashSet<>(policy.applicationProjects().keySet());
        for(var owner:policy.applicationProjects().values())for(var scope:owner.scopes().values())for(var grant:scope.managementGrants())for(String target:grant.projectIds()){
            var boundary=boundaries.get(target);if(boundary==null)throw new Rejected(Failure.INVALID_DIRECTORY);
            var shared=boundary.project();
            if(!target.equals(shared.id()) || !"main".equals(shared.tenant()) || !owner.issuer().equals(shared.issuer())
                || shared.revision()<=0 || shared.authorizationVersion()<=0)throw new Rejected(Failure.INVALID_DIRECTORY);
            targets.add(target);
        }
        return Set.copyOf(targets);
    }
    /** Directory projection never consumes local grants or combines scope boundaries. */
    static Map<String,ProjectAuthorization.Directory> project(Map<String,ApplicationDirectoryV1.PolicyBoundary> raw){
        return project(raw,raw.keySet());
    }
    static Map<String,ProjectAuthorization.Directory> project(Map<String,ApplicationDirectoryV1.PolicyBoundary> raw,Set<String> required){
        try {
            if(!raw.keySet().containsAll(required))throw new Rejected(Failure.INVALID_DIRECTORY);
            return projectChecked(raw,Set.copyOf(required));
        }
        catch(Rejected failure){throw failure;}
        catch(RuntimeException failure){throw new Rejected(Failure.INVALID_DIRECTORY);}
    }
    private static Map<String,ProjectAuthorization.Directory> projectChecked(Map<String,ApplicationDirectoryV1.PolicyBoundary> raw,Set<String> required){
        var result=new TreeMap<String,ProjectAuthorization.Directory>();
        for(var entry:raw.entrySet()){
            if(!required.contains(entry.getKey()))continue;
            var boundary=entry.getValue();var shared=boundary.project();
            if(!entry.getKey().equals(shared.id()) || !shared.id().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
                || !"main".equals(shared.tenant()) || shared.revision()<=0 || shared.authorizationVersion()<=0
                || shared.issuer()==null || shared.issuer().isBlank() || boundary.audiences().isEmpty())throw new Rejected(Failure.INVALID_DIRECTORY);
            var issuer=java.net.URI.create(shared.issuer());
            if(!Set.of("https","http").contains(issuer.getScheme()) || issuer.getHost()==null || issuer.getUserInfo()!=null
                || issuer.getQuery()!=null || issuer.getFragment()!=null)throw new Rejected(Failure.INVALID_DIRECTORY);
            var protocols=new HashSet<String>();
            for(var protocol:boundary.protocolScopes())if(protocol.scope()==null || protocol.scope().isBlank()
                || protocol.category()==null || protocol.category().isBlank() || !protocols.add(protocol.scope()))throw new Rejected(Failure.INVALID_DIRECTORY);
            var seenScopes=new HashSet<String>();
            var scopes=new TreeMap<String,ProjectPolicy.ScopeDefinition>();
            for(var scope:boundary.scopes()){
                if(!entry.getKey().equals(scope.projectId()) || !seenScopes.add(scope.scope()) || protocols.contains(scope.scope()) || scope.revision()<=0)throw new Rejected(Failure.INVALID_DIRECTORY);
                var grants=new ArrayList<ProjectPolicy.Grant>();
                for(var intent:scope.resourceIntents()){
                    if(!"kestra-resources-v1".equals(intent.vocabularyVersion()) || intent.actions().isEmpty())throw new Rejected(Failure.INVALID_DIRECTORY);
                    ProjectPolicy.Resource.valueOf(intent.resourceType());
                    for(String action:intent.actions())io.kestra.webserver.rbac.RbacAuthorizer.Action.valueOf(action);
                    if(intent.namespaces().kind()==ApplicationDirectoryV1.DomainKind.ALL
                        && !Set.of("METADATA","MCP_SERVER").contains(intent.resourceType()))throw new Rejected(Failure.INVALID_DIRECTORY);
                    var ids=intent.resourceIds();boolean allIds=ids.kind()==ApplicationDirectoryV1.DomainKind.ALL;
                    if(!allIds && ids.ids().isEmpty())continue;
                    var ns=intent.namespaces();
                    if(Set.of("METADATA","MCP_SERVER").contains(intent.resourceType())){
                        if(ns.kind()!=ApplicationDirectoryV1.DomainKind.ALL)throw new Rejected(Failure.INVALID_DIRECTORY);
                        grants.add(new ProjectPolicy.Grant("TENANT","main",null,false,intent.resourceType(),intent.actions(),ids.ids(),allIds));
                    }else{
                        var exact=new HashSet<>(ns.exact());var subtrees=new HashSet<>(ns.subtrees());
                        for(String name:exact)grants.add(new ProjectPolicy.Grant("NAMESPACE","main",name,false,intent.resourceType(),intent.actions(),ids.ids(),allIds));
                        for(String name:subtrees)grants.add(new ProjectPolicy.Grant("NAMESPACE","main",name,true,intent.resourceType(),intent.actions(),ids.ids(),allIds));
                    }
                }
                var seenDomains=new HashSet<ApplicationDirectoryV1.ManagementDomain>();
                for(var intent:scope.managementIntents())if(!seenDomains.add(intent.domain()) || intent.actions().isEmpty()
                    || !Set.of("READ","MANAGE").containsAll(intent.actions()))throw new Rejected(Failure.INVALID_DIRECTORY);
                var management=scope.managementIntents().stream().map(intent->new ProjectPolicy.ManagementGrant(ProjectPolicy.ManagementDomain.valueOf(intent.domain().name()),
                    intent.actions().stream().map(ProjectPolicy.ManagementAction::valueOf).collect(java.util.stream.Collectors.toSet()),intent.projectIds())).toList();
                // A directory scope with no effective intent cannot grant anything locally.
                if(grants.isEmpty()&&management.isEmpty())continue;
                scopes.put(scope.scope(),new ProjectPolicy.ScopeDefinition(scope.description(),scope.active(),grants,management));
            }
            if(!seenScopes.containsAll(boundary.reachableManagementScopes()))throw new Rejected(Failure.INVALID_DIRECTORY);
            result.put(entry.getKey(),new ProjectAuthorization.Directory(new ProjectPolicy.Project(shared.issuer(),boundary.audiences(),shared.active(),shared.revision(),scopes),entry.getKey(),
                boundary.protocolScopes().stream().map(ApplicationDirectoryV1.ProtocolScope::scope).collect(java.util.stream.Collectors.toSet())));
        }
        return Map.copyOf(result);
    }
}
