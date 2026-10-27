package io.kestra.extensions.rbac.management;

import io.kestra.extensions.rbac.ApplicationGroupRuntimePolicies;
import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupDirectoryService;
import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy;
import io.kestra.extensions.rbac.store.ApplicationGroupDirectoryStore;
import io.kestra.extensions.rbac.store.ManagementAuditStore;
import io.kestra.webserver.oidc.IdentityLifecycleParticipantV1;
import io.kestra.webserver.oidc.IdentityLifecycleGuardV1;
import io.kestra.webserver.oidc.TrustedIdentityV2;
import io.micronaut.context.BeanProvider;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.SQLException;

@Singleton
@Requires(property="kestra.rbac.enabled",value="true")
@Requires(property="kestra.rbac.application-groups.enabled",value="true")
public final class PublishedIdentityLifecycleParticipant implements IdentityLifecycleParticipantV1 {
    private final ApplicationGroupDirectoryService directory;
    private final ApplicationGroupDirectoryStore store;
    private final ApplicationGroupRuntimePolicies policies;
    private final BeanProvider<TrustedIdentityV2> identities;
    private final ManagementLifecycleAudit audit;
    public PublishedIdentityLifecycleParticipant(ApplicationGroupDirectoryService directory,ApplicationGroupDirectoryStore store,
        ApplicationGroupRuntimePolicies policies,BeanProvider<TrustedIdentityV2> identities,ManagementAuditStore audit) {
        this.directory=directory;this.store=store;this.policies=policies;this.identities=identities;this.audit=new ManagementLifecycleAudit(audit);
    }
    @Override public void beforeChange(Connection connection,Change change)throws SQLException {
        if(connection.getAutoCommit() || connection.isReadOnly() || connection.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)
            throw new SQLException("Borrowed lifecycle transaction required");
        if(change==null || change.contractVersion()!=1 || change.actor()==null || !identities.isUnique())
            throw new IdentityLifecycleGuardV1.Rejected(IdentityLifecycleGuardV1.Failure.UNAVAILABLE);
        var actor=identities.get().current(connection);
        if(actor==null || !actor.equals(change.actor()) || !change.issuer().equals(actor.issuer()))
            throw new IdentityLifecycleGuardV1.Rejected(IdentityLifecycleGuardV1.Failure.FORBIDDEN);
        var operation=ManagementAuditStore.Operation.valueOf(change.kind().name());
        var snapshots=directory.snapshots(connection);
        var groups=audit.targetGroups(snapshots,actor.issuer(),actor.applicationGroupId(),operation,change.subject(),change.clientId());
        try(var context=policies.open(connection,actor,"main")) {
            for(String group:groups)if(!context.authorizeManagement(ApplicationGroupPolicy.ManagementDomain.IDENTITY,ApplicationGroupPolicy.ManagementAction.MANAGE,group))
                throw new IdentityLifecycleGuardV1.Rejected(IdentityLifecycleGuardV1.Failure.FORBIDDEN);
            if(change.kind()==Kind.ACCOUNT_DELETE) {
                new AccountGrantCleanup(store,new AccountGrantCleanup.Guard() {
                    public void authorizeDeletion(Connection borrowed,AccountGrantCleanup.Actor identity,String subject)throws SQLException {
                        if(borrowed!=connection || !identity.subject().equals(actor.subject()))throw new SQLException("Lifecycle connection mismatch");
                        directory.verifyIdentity(borrowed,actor);
                    }
                    public void requireAdministrators(Connection borrowed,String excluded)throws SQLException {
                        directory.requireAdministrators(borrowed,directory.snapshots(borrowed),directory.runtimePolicy(borrowed).policy(),excluded,null);
                    }
                },actor.issuer()).beforeAccountDelete(connection,new AccountGrantCleanup.Actor(actor.issuer(),actor.subject()),change.subject());
            }else if(change.kind()==Kind.ACCOUNT_DEACTIVATE) {
                if(actor.subject().equals(change.subject()))throw new IdentityLifecycleGuardV1.Rejected(IdentityLifecycleGuardV1.Failure.FORBIDDEN);
                directory.requireAdministrators(connection,snapshots,directory.runtimePolicy(connection).policy(),change.subject(),null);
            }
            audit.append(connection,actor.issuer(),actor.subject(),operation,change.subject(),change.clientId(),groups);
        }catch(UserDirectoryAccess.DirectoryFailure failure) {
            throw new IdentityLifecycleGuardV1.Rejected(IdentityLifecycleGuardV1.Failure.FORBIDDEN);
        }catch(ApplicationGroupRuntimePolicies.Rejected failure) {
            throw new IdentityLifecycleGuardV1.Rejected(IdentityLifecycleGuardV1.Failure.FORBIDDEN);
        }
    }
}
