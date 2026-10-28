package io.kestra.extensions.rbac;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.extensions.rbac.applicationgroup.*;
import io.kestra.extensions.rbac.store.ApplicationGroupPolicyStore;
import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupDirectoryReader;
import io.kestra.webserver.oidc.TrustedIdentityV1;
import io.kestra.webserver.oidc.TrustedIdentityV2;
import io.kestra.webserver.rbac.AuthorizationUnavailableException;
import io.micronaut.security.authentication.Authentication;
import jakarta.inject.Singleton;
import io.micronaut.context.annotation.Requires;
import java.util.*;

/** Candidate adapter. All projections in a context originate from one public directory read. */
@Singleton
@Requires(property="kestra.rbac.enabled", value="true")
@Requires(property="kestra.rbac.application-groups.enabled",value="true")
public final class ApplicationGroupRuntimePolicies {
    public enum Failure { INVALID_IDENTITY, DIRECTORY_UNAVAILABLE, POLICY_UNAVAILABLE, INVALID_DIRECTORY,
        FORBIDDEN, INVALID_CANDIDATE, LAST_ADMIN, CLOSED }
    public static final class Rejected extends RuntimeException {
        private final Failure failure;
        public Rejected(Failure failure) { super(failure.name()); this.failure=failure; }
        public Failure failure() { return failure; }
    }
    private final ApplicationGroupDirectoryReader directory;
    private final TrustedIdentityV1 identities;
    private final long maximumSnapshotAgeNanos;
    private final java.util.function.LongSupplier clock;
    public ApplicationGroupRuntimePolicies(ApplicationGroupDirectoryReader directory, TrustedIdentityV1 identities) {
        this(directory,identities,60,System::nanoTime);
    }
    @jakarta.inject.Inject
    public ApplicationGroupRuntimePolicies(ApplicationGroupDirectoryReader directory,TrustedIdentityV1 identities,
        @io.micronaut.context.annotation.Value("${kestra.rbac.policy-snapshot-max-age-seconds:60}") long maximumAgeSeconds) {
        this(directory,identities,maximumAgeSeconds,System::nanoTime);
    }
    public ApplicationGroupRuntimePolicies(ApplicationGroupDirectoryReader directory,TrustedIdentityV1 identities,
        long maximumAgeSeconds,java.util.function.LongSupplier clock) {
        if(maximumAgeSeconds<1)throw new IllegalArgumentException("Positive policy snapshot lifetime required");
        this.directory=Objects.requireNonNull(directory);this.identities=Objects.requireNonNull(identities);
        this.maximumSnapshotAgeNanos=Math.multiplyExact(maximumAgeSeconds,1_000_000_000L);this.clock=Objects.requireNonNull(clock);
    }
    public Context open(Authentication authentication,String tenant) {
        if(authentication==null)throw new Rejected(Failure.INVALID_IDENTITY);
        var identity=identities instanceof TrustedIdentityV2 v2?v2.currentForAuthorization():identities.current();
        if(identity==null || !authentication.getName().equals(identity.subject()))throw new Rejected(Failure.INVALID_IDENTITY);
        return open(identity,tenant);
    }
    /** Only ordinary authorization translates explicitly classified dependency failures. */
    public Context open(TrustedIdentityV1.Identity identity,String tenant) {
        try {
            if(directory instanceof ApplicationGroupDirectoryService service)
                return service.transaction(connection->openAuthorization(connection,identity,tenant));
            return context(identity,tenant,List.copyOf(directory.boundaries()),directory.runtimePolicy());
        } catch(RuntimeException failure) {
            throw authorizationFailure(failure);
        }
    }
    private Context openAuthorization(java.sql.Connection connection,TrustedIdentityV1.Identity identity,String tenant)throws java.sql.SQLException {
        // The lifecycle borrowed-connection overload below retains its original contract.
        try {
            directory.verifyIdentity(connection,identity);
        } catch(io.kestra.webserver.oidc.IdentityLifecycleGuardV1.Rejected failure) {
            if(failure.failure()==io.kestra.webserver.oidc.IdentityLifecycleGuardV1.Failure.UNAVAILABLE)
                throw new AuthorizationUnavailableException();
            throw new Rejected(Failure.INVALID_IDENTITY);
        }
        return context(identity,tenant,directory.boundaries(connection),directory.runtimePolicy(connection));
    }
    static RuntimeException authorizationFailure(RuntimeException failure) {
        if(failure instanceof AuthorizationUnavailableException)return failure;
        if(failure instanceof io.kestra.extensions.rbac.store.ApplicationGroupDirectoryStore.Unavailable
            || failure instanceof ApplicationGroupPolicyStore.Unavailable
            || failure instanceof Rejected rejected && (rejected.failure()==Failure.DIRECTORY_UNAVAILABLE
                || rejected.failure()==Failure.POLICY_UNAVAILABLE))return new AuthorizationUnavailableException();
        return failure;
    }
    public Context open(java.sql.Connection connection,TrustedIdentityV1.Identity identity,String tenant)throws java.sql.SQLException {
        List<ApplicationGroupDirectoryReader.Boundary> boundaries;
        try {directory.verifyIdentity(connection,identity);boundaries=directory.boundaries(connection);}
        catch(io.kestra.webserver.oidc.IdentityLifecycleGuardV1.Rejected failure){throw new Rejected(Failure.INVALID_IDENTITY);}
        catch(RuntimeException failure){throw new Rejected(Failure.DIRECTORY_UNAVAILABLE);}
        ApplicationGroupPolicyStore.Snapshot snapshot;
        try {snapshot=directory.runtimePolicy(connection);}
        catch(RuntimeException failure){throw new Rejected(Failure.POLICY_UNAVAILABLE);}
        return context(identity,tenant,boundaries,snapshot);
    }
    public void validatePolicyChange(java.sql.Connection connection,TrustedIdentityV1.Identity identity,long expectedRevision,String target,ApplicationGroupPolicy candidate)throws java.sql.SQLException {
        try(var context=open(connection,identity,"main")) {
            if(context.snapshot().revision()!=expectedRevision)throw new ApplicationGroupPolicyStore.Conflict();
            if(!context.authorizeManagement(ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,ApplicationGroupPolicy.ManagementAction.MANAGE,target))throw new Rejected(Failure.FORBIDDEN);
            context.validateCandidate(candidate);directory.validatePolicy(connection,candidate);
        }
    }
    private Context context(TrustedIdentityV1.Identity identity,String tenant,List<ApplicationGroupDirectoryReader.Boundary> boundaries,
        ApplicationGroupPolicyStore.Snapshot snapshot) {
        if(identity==null || identity.contractVersion()!=1 || !"main".equals(tenant)
            || !tenant.equals(identity.tenant()) || identity.subject().isBlank() || identity.clientId().isBlank()
            || identity.authenticationVersion()<0)throw new Rejected(Failure.INVALID_IDENTITY);
        var raw=new TreeMap<String,ApplicationGroupDirectoryReader.Boundary>();
        for(var boundary:boundaries)if(raw.putIfAbsent(boundary.applicationGroup().id(),boundary)!=null)throw new Rejected(Failure.INVALID_DIRECTORY);
        var current=raw.get(identity.applicationGroupId());
        if(current==null || !current.applicationGroup().active() || !tenant.equals(current.applicationGroup().tenant())
            || !identity.issuer().equals(current.applicationGroup().issuer())
            || identity.authorizationVersion()!=current.applicationGroup().authorizationVersion()
            || identity.audiences().isEmpty() || !(identity.clientId().equals(current.loginClientId()) && identity.audiences().equals(Set.of(identity.clientId()))) && !current.audiences().containsAll(identity.audiences()))
            throw new Rejected(Failure.INVALID_IDENTITY);
        return new Context(identity,snapshot,Map.copyOf(raw));
    }
    public final class Context implements AutoCloseable {
        private final TrustedIdentityV1.Identity identity;
        private final ApplicationGroupPolicyStore.Snapshot snapshot;
        private final Map<String,ApplicationGroupDirectoryReader.Boundary> boundaries;
        private final Map<String,ApplicationGroupAuthorization.Directory> directories;
        private final Map<String,ApplicationGroupManagementSafety.DirectoryState> states;
        private final Set<String> managementTargets;
        private boolean closed;
        private final long openedAt=clock.getAsLong();
        private Context(TrustedIdentityV1.Identity identity,ApplicationGroupPolicyStore.Snapshot snapshot,
            Map<String,ApplicationGroupDirectoryReader.Boundary> boundaries) {
            this.identity=identity;this.snapshot=snapshot;this.boundaries=boundaries;
            managementTargets=ApplicationGroupRuntimePolicies.managementTargets(snapshot.policy(),boundaries);
            directories=projectBoundaries(boundaries,requiredGroups(snapshot.policy(),identity.applicationGroupId(),boundaries.keySet()));
            var values=new TreeMap<String,ApplicationGroupManagementSafety.DirectoryState>();
            directories.forEach((id,bound)->values.put(id,new ApplicationGroupManagementSafety.DirectoryState(bound,boundaries.get(id).reachableManagementScopes())));
            states=Map.copyOf(values);
        }
        private void check(){if(closed || clock.getAsLong()-openedAt>=maximumSnapshotAgeNanos)throw new Rejected(Failure.CLOSED);}
        public TrustedIdentityV1.Identity identity(){check();return identity;}
        public ApplicationGroupPolicyStore.Snapshot snapshot(){check();return snapshot;}
        public Map<String,ApplicationGroupAuthorization.Directory> directories(){check();return directories;}
        public Set<String> managementTargets(){check();return managementTargets;}
        public Map<String,ApplicationGroupManagementSafety.DirectoryState> directoryStates(){check();return states;}
        public ApplicationGroupDirectoryReader.Boundary upperBound(String target){check();var value=boundaries.get(target);if(value==null)throw new Rejected(Failure.FORBIDDEN);return value;}
        public java.util.Optional<ApplicationGroupDirectoryReader.Boundary> directoryBoundary(String target){check();return java.util.Optional.ofNullable(boundaries.get(target));}
        /** Binding options require a usable target audience; identity-directory reads use upperBound instead. */
        public ApplicationGroupDirectoryReader.Boundary bindingBoundary(String target){
            var boundary=upperBound(target);
            if(boundary.audiences().isEmpty() && boundary.loginClientId()==null)throw new Rejected(Failure.INVALID_CANDIDATE);
            return boundary;
        }
        private boolean acceptedAudiences(){
            var local=snapshot.policy().applicationGroups().get(identity.applicationGroupId());
            return local!=null && (hasTrustedLoginAudience() || local.audiences().containsAll(identity.audiences()));
        }
        public boolean hasTrustedLoginAudience() {
            check();var boundary=boundaries.get(identity.applicationGroupId());
            return boundary!=null && identity.clientId().equals(boundary.loginClientId()) && identity.audiences().equals(Set.of(identity.clientId()));
        }
        private ApplicationGroupAuthorization.Identity localIdentity(){
            return new ApplicationGroupAuthorization.Identity(identity.subject(),identity.issuer(),identity.audiences().stream().sorted().findFirst().orElseThrow(),
                identity.applicationGroupId(),boundaries.get(identity.applicationGroupId()).applicationGroup().revision(),identity.applicationGroupScopes(),identity.protocolScopes(),identity.tenant(),hasTrustedLoginAudience());
        }
        public boolean authorizeManagement(ApplicationGroupPolicy.ManagementDomain domain,ApplicationGroupPolicy.ManagementAction action,String target){
            check();var destination=boundaries.get(target);
            if(!acceptedAudiences() || destination==null || !identity.issuer().equals(destination.applicationGroup().issuer()))return false;
            return new ApplicationGroupAuthorization(snapshot.policy()).authorizeManagement(localIdentity(),directories.get(identity.applicationGroupId()),domain,action,target);
        }
        public ApplicationGroupPolicy.ApplicationGroup visibleGroup(String target){
            check();if(!authorizeManagement(ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,ApplicationGroupPolicy.ManagementAction.READ,target))throw new Rejected(Failure.FORBIDDEN);
            var value=snapshot.policy().applicationGroups().get(target);if(value==null)throw new Rejected(Failure.FORBIDDEN);return value;
        }
        public JsonNode visiblePolicy(){
            check();var mapper=new ObjectMapper();var result=mapper.createObjectNode();result.put("revision",snapshot.revision());result.put("schemaVersion",2);
            var groups=result.putObject("applicationGroups");
            snapshot.policy().applicationGroups().forEach((id,value)->{if(authorizeManagement(ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,ApplicationGroupPolicy.ManagementAction.READ,id))groups.set(id,mapper.valueToTree(value));});
            return result;
        }
        public void validateCandidate(ApplicationGroupPolicy candidate){
            check();var targets=ApplicationGroupRuntimePolicies.managementTargets(candidate,boundaries);var projected=projectBoundaries(boundaries,requiredGroups(candidate,identity.applicationGroupId(),boundaries.keySet()));
            try{ApplicationGroupPolicyBoundary.validate(candidate,projected,targets,snapshot.policy());}catch(IllegalArgumentException error){throw new Rejected(Failure.INVALID_CANDIDATE);}
            var currentStates=new TreeMap<String,ApplicationGroupManagementSafety.DirectoryState>();
            projected.forEach((id,bound)->currentStates.put(id,new ApplicationGroupManagementSafety.DirectoryState(bound,boundaries.get(id).reachableManagementScopes())));
            try{ApplicationGroupManagementSafety.validate(candidate,currentStates,targets,snapshot.policy());}catch(IllegalArgumentException error){throw new Rejected(Failure.LAST_ADMIN);}
        }
        public io.kestra.webserver.rbac.TypedAuthorization.Decision authorize(io.kestra.webserver.rbac.TypedAuthorization.Operation operation){
            check();return ApplicationGroupTypedPolicy.authorize(this,operation);
        }
        @Override public void close(){closed=true;}
    }
    private static Set<String> requiredGroups(ApplicationGroupPolicy policy,String loginGroup,Set<String> existing){
        var required=new HashSet<>(policy.applicationGroups().keySet());required.retainAll(existing);required.add(loginGroup);
        return Set.copyOf(required);
    }
    private static Set<String> managementTargets(ApplicationGroupPolicy policy,Map<String,ApplicationGroupDirectoryReader.Boundary> boundaries){
        var targets=new HashSet<>(policy.applicationGroups().keySet());targets.retainAll(boundaries.keySet());
        for(var ownerEntry:policy.applicationGroups().entrySet()){
            if(!boundaries.containsKey(ownerEntry.getKey()))continue;
            var owner=ownerEntry.getValue();
            for(var scope:owner.scopes().values())for(var grant:scope.managementGrants())for(String target:grant.applicationGroupIds()){
            var boundary=boundaries.get(target);if(boundary==null)continue;
            var shared=boundary.applicationGroup();
            if(!target.equals(shared.id()) || !"main".equals(shared.tenant()) || !owner.issuer().equals(shared.issuer())
                || shared.revision()<=0 || shared.authorizationVersion()<=0)throw new Rejected(Failure.INVALID_DIRECTORY);
            targets.add(target);
            }
        }
        return Set.copyOf(targets);
    }
    /** Directory projection never consumes local grants or combines scope boundaries. */
    static Map<String,ApplicationGroupAuthorization.Directory> projectBoundaries(Map<String,ApplicationGroupDirectoryReader.Boundary> raw){
        return projectBoundaries(raw,raw.keySet());
    }
    static Map<String,ApplicationGroupAuthorization.Directory> projectBoundaries(Map<String,ApplicationGroupDirectoryReader.Boundary> raw,Set<String> required){
        try {
            if(!raw.keySet().containsAll(required))throw new Rejected(Failure.INVALID_DIRECTORY);
            return projectChecked(raw,Set.copyOf(required));
        }
        catch(Rejected failure){throw failure;}
        catch(RuntimeException failure){throw new Rejected(Failure.INVALID_DIRECTORY);}
    }
    private static Map<String,ApplicationGroupAuthorization.Directory> projectChecked(Map<String,ApplicationGroupDirectoryReader.Boundary> raw,Set<String> required){
        var result=new TreeMap<String,ApplicationGroupAuthorization.Directory>();
        for(var entry:raw.entrySet()){
            if(!required.contains(entry.getKey()))continue;
            var boundary=entry.getValue();var shared=boundary.applicationGroup();
            if(!entry.getKey().equals(shared.id()) || !shared.id().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
                || !"main".equals(shared.tenant()) || shared.revision()<=0 || shared.authorizationVersion()<=0
                || shared.issuer()==null || shared.issuer().isBlank() || boundary.audiences().isEmpty() && boundary.loginClientId()==null)throw new Rejected(Failure.INVALID_DIRECTORY);
            var issuer=java.net.URI.create(shared.issuer());
            if(!Set.of("https","http").contains(issuer.getScheme()) || issuer.getHost()==null || issuer.getUserInfo()!=null
                || issuer.getQuery()!=null || issuer.getFragment()!=null)throw new Rejected(Failure.INVALID_DIRECTORY);
            var protocols=new HashSet<String>();
            for(var protocol:boundary.protocolScopes())if(protocol.scope()==null || protocol.scope().isBlank()
                || protocol.category()==null || protocol.category().isBlank() || !protocols.add(protocol.scope()))throw new Rejected(Failure.INVALID_DIRECTORY);
            var seenScopes=new HashSet<String>();
            var scopes=new TreeMap<String,ApplicationGroupAuthorization.DirectoryScope>();
            for(var scope:boundary.scopes()){
                if(!entry.getKey().equals(scope.applicationGroupId()) || !seenScopes.add(scope.key()) || protocols.contains(scope.key()) || scope.revision()<=0)throw new Rejected(Failure.INVALID_DIRECTORY);
                if(scope.name()==null || scope.name().isBlank() || scope.description()==null)throw new Rejected(Failure.INVALID_DIRECTORY);
                scopes.put(scope.key(),new ApplicationGroupAuthorization.DirectoryScope(scope.name(),scope.description()));
            }
            if(!seenScopes.containsAll(boundary.reachableManagementScopes()))throw new Rejected(Failure.INVALID_DIRECTORY);
            result.put(entry.getKey(),new ApplicationGroupAuthorization.Directory(new ApplicationGroupAuthorization.ScopeCatalog(shared.issuer(),boundary.audiences(),shared.active(),shared.revision(),scopes),entry.getKey(),
                boundary.protocolScopes().stream().map(ApplicationGroupDirectoryReader.ProtocolScope::scope).collect(java.util.stream.Collectors.toSet())));
        }
        return Map.copyOf(result);
    }
}
