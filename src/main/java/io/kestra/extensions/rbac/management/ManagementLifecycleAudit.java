package io.kestra.extensions.rbac.management;

import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupDirectory;
import io.kestra.extensions.rbac.store.ManagementAuditStore;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

public final class ManagementLifecycleAudit {
    private final ManagementAuditStore store;
    public ManagementLifecycleAudit(ManagementAuditStore store) {this.store=java.util.Objects.requireNonNull(store);}

    public Set<String> targetGroups(List<ApplicationGroupDirectory.Snapshot> snapshots,String issuer,String actorGroup,
        ManagementAuditStore.Operation operation,String subject,String client) {
        var groups=snapshots.stream().filter(snapshot->operation==ManagementAuditStore.Operation.CLIENT_SECRET_ROTATE?
            snapshot.applications().stream().anyMatch(application->application.clientId().equals(client)):
            snapshot.userGrants().stream().anyMatch(grant->grant.issuer().equals(issuer) && grant.subject().equals(subject)))
            .map(snapshot->snapshot.group().id()).collect(java.util.stream.Collectors.toSet());
        if(groups.isEmpty()) {
            if(operation==ManagementAuditStore.Operation.CLIENT_SECRET_ROTATE || snapshots.stream().noneMatch(snapshot->snapshot.group().id().equals(actorGroup)))
                throw new IllegalArgumentException("Audit target scope required");
            return Set.of(actorGroup);
        }
        return Set.copyOf(groups);
    }

    public void append(Connection connection,String issuer,String actor,ManagementAuditStore.Operation operation,
        String subject,String client,Set<String> groups)throws SQLException {
        store.append(connection,issuer,actor,operation,subject,client,groups);
    }
}
