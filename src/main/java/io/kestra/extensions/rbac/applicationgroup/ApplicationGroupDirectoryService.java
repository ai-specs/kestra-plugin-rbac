package io.kestra.extensions.rbac.applicationgroup;

import io.kestra.extensions.rbac.store.ApplicationGroupDirectoryStore;
import io.kestra.extensions.rbac.store.ApplicationGroupPolicyStore;
import io.kestra.webserver.oidc.ApplicationGroupProtocolProjectionV1;
import io.kestra.webserver.oidc.IdentityLifecycleGuardV1;
import io.kestra.webserver.oidc.OidcProtocolStateV1;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import static io.kestra.extensions.rbac.applicationgroup.ApplicationGroupDirectory.*;
import static io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.ManagementAction.MANAGE;
import static io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.ManagementDomain.*;

@Singleton
@Requires(property="kestra.rbac.enabled",value="true")
@Requires(property="kestra.rbac.application-groups.enabled",value="true")
public final class ApplicationGroupDirectoryService implements ApplicationGroupDirectoryReader,
    ApplicationGroupProtocolProjectionV1,IdentityLifecycleGuardV1 {
    private final ApplicationGroupDirectoryStore store;
    private final ApplicationGroupPolicyStore policies;
    private final OidcProtocolStateV1 protocol;
    private final String issuer;
    private final String loginClientId;
    private static final List<ProtocolScope> PROTOCOL_SCOPES=List.of(
        new ProtocolScope("openid","OIDC"),new ProtocolScope("profile","OIDC"),new ProtocolScope("email","OIDC"),
        new ProtocolScope("offline_access","OIDC"),new ProtocolScope("mcp:flows:read","MCP"),
        new ProtocolScope("mcp:executions:write","MCP"),new ProtocolScope("kestra:application-group-scopes","SELECTOR"));

    public ApplicationGroupDirectoryService(ApplicationGroupDirectoryStore store,ApplicationGroupPolicyStore policies,
        OidcProtocolStateV1 protocol,@Value("${kestra.oidc.issuer}") String issuer,
        @Value("${micronaut.security.oauth2.clients.kestra.client-id}") String loginClientId) {
        this.store=store;this.policies=policies;this.protocol=protocol;this.issuer=issuer;this.loginClientId=loginClientId;
    }

    public <Target> Target transaction(ApplicationGroupDirectoryStore.Work<Target> work) { return store.transaction(work); }
    public List<Snapshot> snapshots(Connection connection)throws SQLException { return store.readAll(connection); }
    public Snapshot find(Connection connection,String groupId)throws SQLException {
        return snapshots(connection).stream().filter(value->value.group().id().equals(groupId)).findFirst()
            .orElseThrow(ApplicationGroupChanges.NotFound::new);
    }
    public GroupView view(Connection connection,Snapshot snapshot)throws SQLException {
        var group=snapshot.group();return new GroupView(group.id(),"main",issuer,group.displayName(),group.active(),group.revision(),group.authorizationVersion(),group.id().equals(store.builtinGroupId(connection)));
    }
    public String builtinGroupId(Connection connection)throws SQLException { return store.builtinGroupId(connection); }
    public long catalogRevision(Connection connection)throws SQLException { return store.catalogRevision(connection); }

    @Override public Result current(Connection connection,Request request)throws SQLException {
        try { return readCurrent(connection, request); }
        catch(SQLException failure) { throw ApplicationGroupDirectoryStore.reclassified(failure); }
    }
    private Result readCurrent(Connection connection,Request request)throws SQLException {
        if(!issuer.equals(request.issuer()) || !"main".equals(request.tenant()))throw new IllegalArgumentException("Protocol target mismatch");
        if(store.builtinGroupId(connection)==null)return new Result(1,false,Optional.empty());
        ApplicationGroupProtocolProjectionV1.Projection result=null;
        for(var snapshot:snapshots(connection))for(var application:snapshot.applications()) {
            if(!application.clientId().equals(request.clientId()))continue;
            if(result!=null)throw new IllegalStateException("Application belongs to multiple groups");
            var group=snapshot.group();var grant=snapshot.userGrants().stream()
                .filter(value->value.issuer().equals(request.issuer())&&value.subject().equals(request.subject())).findFirst();
            result=new ApplicationGroupProtocolProjectionV1.Projection(group.id(),"main",issuer,application.clientId(),request.subject(),
                group.active(),application.approved(),group.revision(),group.authorizationVersion(),application.audiences(),
                snapshot.scopes().stream().map(scope->new ApplicationGroupProtocolProjectionV1.Scope(scope.key(),scope.name(),scope.description())).toList(),
                application.supportedScopeKeys(),grant.map(UserGrant::scopeKeys).orElse(Set.of()));
            result.eligibleScopeKeys(request);
        }
        return new Result(1,true,Optional.ofNullable(result));
    }

    @Override public List<Boundary> boundaries() { return transaction(this::boundaries); }
    @Override public void validatePolicy(Connection connection,ApplicationGroupPolicy policy)throws SQLException {
        requireAdministrators(connection,snapshots(connection),policy,null,null);
    }
    @Override public void verifyIdentity(Connection connection,io.kestra.webserver.oidc.TrustedIdentityV1.Identity identity)throws SQLException {
        if(identity==null || identity.contractVersion()!=1 || !issuer.equals(identity.issuer()) || !"main".equals(identity.tenant()))
            throw new IdentityLifecycleGuardV1.Rejected(IdentityLifecycleGuardV1.Failure.FORBIDDEN);
        var account=protocol.account(connection,issuer,identity.subject());var client=protocol.client(connection,issuer,identity.clientId());
        var snapshot=find(connection,identity.applicationGroupId());
        var application=snapshot.applications().stream().filter(value->value.clientId().equals(identity.clientId())).findFirst();
        if(account.isEmpty() || !account.get().active() || !account.get().canLogin()
            || account.get().authenticationVersion()!=identity.authenticationVersion() || client.isEmpty() || !client.get().active()
            || !snapshot.group().active() || snapshot.group().revision()!=identity.directoryRevision()
            || snapshot.group().authorizationVersion()!=identity.authorizationVersion() || application.isEmpty() || !application.get().approved()
            || identity.audiences().isEmpty() || !application.get().audiences().containsAll(identity.audiences())
            || !snapshot.eligibleScopeKeys(identity.clientId(),issuer,identity.subject()).containsAll(identity.applicationGroupScopes()))
            throw new IdentityLifecycleGuardV1.Rejected(IdentityLifecycleGuardV1.Failure.FORBIDDEN);
    }
    @Override public List<Boundary> boundaries(Connection connection)throws SQLException {
        var result=new ArrayList<Boundary>();
        for(var snapshot:snapshots(connection)) {
            var audiences=new HashSet<String>();var reachable=new HashSet<String>();
            for(var application:snapshot.applications()) {
                if(!application.approved())continue;
                audiences.addAll(application.audiences());
                var client=protocol.client(connection,issuer,application.clientId());
                if(!application.clientId().equals(loginClientId) || client.isEmpty() || !client.get().active()
                    || !client.get().configuredLoginClient() || !snapshot.group().active())continue;
                for(var grant:snapshot.userGrants()) {
                    if(!issuer.equals(grant.issuer()))throw new IllegalStateException("Foreign group grant issuer");
                    var account=protocol.account(connection,issuer,grant.subject());
                    if(account.isPresent() && account.get().active() && account.get().canLogin())
                        reachable.addAll(snapshot.eligibleScopeKeys(application.clientId(),issuer,grant.subject()));
                }
            }
            result.add(new Boundary(view(connection,snapshot),snapshot.scopes().stream()
                .map(scope->new ScopeView(snapshot.group().id(),scope.key(),scope.name(),scope.description(),scope.revision())).toList(),
                audiences,reachable,PROTOCOL_SCOPES));
        }
        return List.copyOf(result);
    }

    public Snapshot create(Connection connection,Snapshot initial)throws SQLException { return store.create(connection,initial); }
    public Snapshot update(Connection connection,String groupId,long revision,java.util.function.UnaryOperator<Snapshot> change)throws SQLException {
        return store.update(connection,groupId,revision,current->{
            var next=change.apply(current);var candidates=new ArrayList<Snapshot>();
            try {
                for(var snapshot:snapshots(connection))candidates.add(snapshot.group().id().equals(groupId)?next:snapshot);
                requireAdministrators(connection,candidates,policies.readCurrent(connection).policy(),null,null);
                validateApplications(connection,next);
            }catch(SQLException failure){throw new ApplicationGroupDirectoryStore.Unavailable(failure);}
            return next;
        });
    }
    public void delete(Connection connection,String groupId,long revision)throws SQLException { store.delete(connection,groupId,revision); }

    public void validateApplications(Connection connection,Snapshot snapshot)throws SQLException {
        for(var application:snapshot.applications()) {
            var client=protocol.client(connection,issuer,application.clientId());
            if(client.isEmpty() || !client.get().active()) {
                var pending=protocol.pendingClient(connection,issuer,application.clientId());
                if(pending.isEmpty())throw new IllegalArgumentException("Active or pending protocol client registration required");
            }
        }
        for(var grant:snapshot.userGrants()) {
            if(!issuer.equals(grant.issuer()) || protocol.account(connection,issuer,grant.subject()).isEmpty())
                throw new IllegalArgumentException("Registered account in the installation issuer required");
        }
    }

    @Override public void beforeChange(Connection connection,Change change)throws SQLException {
        if(!issuer.equals(change.issuer()))throw new IdentityLifecycleGuardV1.Rejected(IdentityLifecycleGuardV1.Failure.FORBIDDEN);
        var actor=ApplicationGroupMutationContext.current();
        if(actor!=null) {
            verifyIdentity(connection,actor);
            var snapshot=find(connection,actor.applicationGroupId());var policy=policies.readCurrent(connection).policy();
            var application=policy.applicationGroups().get(actor.applicationGroupId());
            if(application==null || !application.audiences().containsAll(actor.audiences()))throw new IdentityLifecycleGuardV1.Rejected(IdentityLifecycleGuardV1.Failure.FORBIDDEN);
            var scopes=new java.util.TreeMap<String,ApplicationGroupAuthorization.DirectoryScope>();
            snapshot.scopes().forEach(scope->scopes.put(scope.key(),new ApplicationGroupAuthorization.DirectoryScope(scope.name(),scope.description())));
            var registeredAudiences=snapshot.applications().stream().filter(Application::approved).flatMap(member->member.audiences().stream()).collect(java.util.stream.Collectors.toSet());
            var boundary=new ApplicationGroupAuthorization.Directory(new ApplicationGroupAuthorization.ScopeCatalog(issuer,registeredAudiences,snapshot.group().active(),snapshot.group().revision(),scopes),
                snapshot.group().id(),PROTOCOL_SCOPES.stream().map(ProtocolScope::scope).collect(java.util.stream.Collectors.toSet()));
            var identity=new ApplicationGroupAuthorization.Identity(actor.subject(),issuer,actor.audiences().stream().sorted().findFirst().orElseThrow(),
                actor.applicationGroupId(),actor.directoryRevision(),actor.applicationGroupScopes(),actor.protocolScopes(),"main");
            if(!new ApplicationGroupAuthorization(policy).authorizeManagement(identity,boundary,IDENTITY,MANAGE,actor.applicationGroupId()))
                throw new IdentityLifecycleGuardV1.Rejected(IdentityLifecycleGuardV1.Failure.FORBIDDEN);
        }
        if(change.kind()==Kind.ACCOUNT_ACTIVATE || change.kind()==Kind.PASSWORD_RESET)return;
        String excludedSubject=change.kind()==Kind.ACCOUNT_DEACTIVATE?change.subject():null;
        String excludedClient=change.kind()==Kind.CLIENT_REVOKE || change.kind()==Kind.CLIENT_DELETE?change.clientId():null;
        requireAdministrators(connection,snapshots(connection),policies.readCurrent(connection).policy(),excludedSubject,excludedClient);
    }

    public void requireAdministrators(Connection connection,List<Snapshot> groups,ApplicationGroupPolicy policy,
        String excludedSubject,String excludedClient)throws SQLException {
        var targets=new HashSet<String>();
        var existing=groups.stream().map(snapshot->snapshot.group().id()).collect(java.util.stream.Collectors.toSet());
        for(var entry:policy.applicationGroups().entrySet())if(entry.getValue().enabled() && existing.contains(entry.getKey()))targets.add(entry.getKey());
        String builtin=store.builtinGroupId(connection);
        if(builtin==null)throw new IllegalStateException("Application group installation required");
        targets.add(builtin);
        for(String target:targets)for(var domain:List.of(IDENTITY,LOCAL_POLICY)) {
            boolean reachable=false;
            for(var snapshot:groups) {
                var configured=policy.applicationGroups().get(snapshot.group().id());
                if(!snapshot.group().active() || configured==null || !configured.enabled() || !issuer.equals(configured.issuer()))continue;
                for(var application:snapshot.applications()) {
                    if(!application.clientId().equals(loginClientId) || application.clientId().equals(excludedClient)
                        || !application.approved() || !configured.audiences().containsAll(application.audiences()))continue;
                    var client=protocol.client(connection,issuer,application.clientId());
                    if(client.isEmpty() || !client.get().active() || !client.get().configuredLoginClient())continue;
                    for(var grant:snapshot.userGrants()) {
                        if(grant.subject().equals(excludedSubject) || !issuer.equals(grant.issuer()))continue;
                        var account=protocol.account(connection,issuer,grant.subject());
                        if(account.isEmpty() || !account.get().active() || !account.get().canLogin())continue;
                        for(String key:snapshot.eligibleScopeKeys(application.clientId(),issuer,grant.subject())) {
                            var scope=configured.scopes().get(key);
                            if(scope!=null && scope.enabled() && scope.managementGrants().stream().anyMatch(permission->
                                permission.domain()==domain && permission.actions().contains(MANAGE)
                                && (domain==IDENTITY || ApplicationGroupAuthorization.resolvedTargets(permission,snapshot.group().id()).contains(target))))reachable=true;
                        }
                    }
                }
            }
            if(!reachable)throw new IdentityLifecycleGuardV1.Rejected(IdentityLifecycleGuardV1.Failure.LAST_ADMIN);
        }
    }
}
