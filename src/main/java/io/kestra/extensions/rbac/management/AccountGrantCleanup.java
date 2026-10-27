package io.kestra.extensions.rbac.management;

import io.kestra.extensions.rbac.store.ApplicationGroupDirectoryStore;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

public final class AccountGrantCleanup {
    public record Actor(String issuer,String subject) {
        public Actor {
            if(issuer==null || issuer.isBlank() || subject==null || subject.isBlank())
                throw new IllegalArgumentException("Trusted actor required");
        }
    }
    public interface Guard {
        void authorizeDeletion(Connection connection,Actor actor,String subject)throws SQLException;
        void requireAdministrators(Connection connection,String excludedSubject)throws SQLException;
    }
    private final ApplicationGroupDirectoryStore store;
    private final Guard guard;
    private final String issuer;

    public AccountGrantCleanup(ApplicationGroupDirectoryStore store,Guard guard,String issuer) {
        this.store=Objects.requireNonNull(store);this.guard=Objects.requireNonNull(guard);
        if(issuer==null || issuer.isBlank())throw new IllegalArgumentException("Issuer required");
        this.issuer=issuer;
    }

    public int beforeAccountDelete(Connection connection,Actor actor,String subject)throws SQLException {
        Objects.requireNonNull(connection);Objects.requireNonNull(actor);
        if(connection.getAutoCommit() || connection.isReadOnly()
            || connection.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)
            throw new IllegalStateException("Writable READ_COMMITTED transaction required");
        if(subject==null || subject.isBlank())throw new IllegalArgumentException("Subject required");
        if(!issuer.equals(actor.issuer()) || actor.subject().equals(subject))
            throw new UserDirectoryAccess.DirectoryFailure(UserDirectoryAccess.Failure.FORBIDDEN);
        guard.authorizeDeletion(connection,actor,subject);
        int affected=store.removeUserGrants(connection,issuer,subject);
        guard.requireAdministrators(connection,subject);
        return affected;
    }
}
