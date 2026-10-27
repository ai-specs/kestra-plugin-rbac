package io.kestra.extensions.rbac.management;

import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupDirectory.*;
import io.kestra.extensions.rbac.store.ApplicationGroupDirectoryStore;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountGrantCleanupTest {
    private static final String ISSUER="https://issuer.example";
    private final javax.sql.DataSource source;
    private final ApplicationGroupDirectoryStore store;
    private final AccountGrantCleanup.Actor actor=new AccountGrantCleanup.Actor(ISSUER,"administrator");
    private final AccountGrantCleanup.Guard guard=mock(AccountGrantCleanup.Guard.class);

    AccountGrantCleanupTest()throws SQLException {
        var postgres=System.getenv("RBAC_STORE_TEST_JDBC");
        if(postgres!=null && !postgres.isBlank()) {
            var database=new org.postgresql.ds.PGSimpleDataSource();database.setURL(postgres);
            database.setUser("postgres");database.setPassword("policy-test-only");
            String schema="account_cleanup_"+UUID.randomUUID().toString().replace("-","");
            try(var connection=database.getConnection();var statement=connection.createStatement()) {statement.execute("CREATE SCHEMA "+schema);}
            database.setCurrentSchema(schema);source=database;
        }else {
            var database=new JdbcDataSource();
            database.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");source=database;
        }
        try(var connection=source.getConnection()) {
            var backend=connection.getMetaData().getDatabaseProductName();
            assertEquals(postgres!=null && !postgres.isBlank()?"PostgreSQL":"H2",backend);
            System.out.println("Account grant cleanup backend: "+backend);
        }
        store=new ApplicationGroupDirectoryStore(source);store.initializeSchema();
        store.initializeBuiltin(group("first",Set.of("read")));store.create(group("second",Set.of()));
        try(var connection=source.getConnection();var statement=connection.createStatement()) {
            statement.execute("CREATE TABLE test_standard_account (subject VARCHAR PRIMARY KEY)");
            statement.execute("INSERT INTO test_standard_account VALUES ('target')");
        }
    }

    private static Snapshot group(String id,Set<String> scopes) {
        return new Snapshot(new Group(id,id,true,1,1),List.of(new Scope("read","Reader","",1)),List.of(),
            List.of(new UserGrant(ISSUER,"target",scopes,1),new UserGrant(ISSUER,"keep",Set.of(),1),
                new UserGrant("https://other.example","target",Set.of(),1)));
    }

    @Test void allGroupsIncludingZeroScopesShareBorrowedAccountTransaction()throws Exception {
        try(var raw=source.getConnection()) {
            raw.setAutoCommit(false);raw.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            var connection=spy(raw);
            var cleanup=new AccountGrantCleanup(store,guard,ISSUER);
            assertEquals(2,cleanup.beforeAccountDelete(connection,actor,"target"));
            verify(guard).authorizeDeletion(connection,actor,"target");
            verify(guard).requireAdministrators(connection,"target");
            for(var snapshot:store.readAll(connection)) {
                assertEquals(2,snapshot.userGrants().size());
                assertTrue(snapshot.userGrants().stream().anyMatch(grant->grant.issuer().equals("https://other.example")));
                assertEquals(2,snapshot.group().revision());
                assertEquals(snapshot.group().id().equals("first")?2:1,snapshot.group().authorizationVersion());
            }
            try(var statement=connection.createStatement()) {assertEquals(1,statement.executeUpdate("DELETE FROM test_standard_account WHERE subject='target'"));}
            verify(connection,never()).commit();verify(connection,never()).rollback();verify(connection,never()).close();
            raw.rollback();
        }
        assertOriginal();
    }

    @Test void ownerCommitPersistsPhysicalDeletionAndEveryGroupCleanupTogether() {
        int changed=store.transaction(connection->{
            int affected=new AccountGrantCleanup(store,guard,ISSUER).beforeAccountDelete(connection,actor,"target");
            try(var statement=connection.createStatement()) {assertEquals(1,statement.executeUpdate("DELETE FROM test_standard_account WHERE subject='target'"));}
            return affected;
        });
        assertEquals(2,changed);
        assertTrue(store.readAll().stream().allMatch(snapshot->snapshot.userGrants().stream()
            .noneMatch(grant->ISSUER.equals(grant.issuer()) && "target".equals(grant.subject()))));
        try(var connection=source.getConnection();var statement=connection.createStatement();var result=statement.executeQuery("SELECT count(*) FROM test_standard_account")) {
            assertTrue(result.next());assertEquals(0,result.getInt(1));
        }catch(SQLException failure) {throw new AssertionError(failure);}
    }

    @Test void participantFailureRollsBackAllGroupsAndPhysicalAccountDeletion()throws Exception {
        var failure=new SQLException("test participant failure");
        doThrow(failure).when(guard).requireAdministrators(any(),eq("target"));
        assertSame(failure,assertThrows(SQLException.class,()->{
            try(var connection=source.getConnection()) {
                connection.setAutoCommit(false);connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                try {
                    try(var statement=connection.createStatement()) {statement.executeUpdate("DELETE FROM test_standard_account WHERE subject='target'");}
                    new AccountGrantCleanup(store,guard,ISSUER).beforeAccountDelete(connection,actor,"target");
                    connection.commit();
                }catch(Exception rejected) {connection.rollback();throw rejected;}
            }
        }));
        assertOriginal();
    }

    @Test void hostFailureAfterCleanupRollsBackBothStores() {
        assertThrows(IllegalStateException.class,()->store.transaction(connection->{
            new AccountGrantCleanup(store,guard,ISSUER).beforeAccountDelete(connection,actor,"target");
            try(var statement=connection.createStatement()) {statement.executeUpdate("DELETE FROM test_standard_account WHERE subject='target'");}
            throw new IllegalStateException("test host failure");
        }));
        assertOriginal();
    }

    @Test void selfCrossIssuerAndStaleActorFailBeforeGrantChanges()throws Exception {
        var cleanup=new AccountGrantCleanup(store,guard,ISSUER);
        store.transaction(connection->{
            assertThrows(UserDirectoryAccess.DirectoryFailure.class,()->cleanup.beforeAccountDelete(connection,actor,"administrator"));
            assertThrows(UserDirectoryAccess.DirectoryFailure.class,()->cleanup.beforeAccountDelete(connection,new AccountGrantCleanup.Actor("other","admin"),"target"));
            return null;
        });
        verifyNoInteractions(guard);
        doThrow(new UserDirectoryAccess.DirectoryFailure(UserDirectoryAccess.Failure.FORBIDDEN))
            .when(guard).authorizeDeletion(any(),eq(actor),eq("target"));
        assertThrows(UserDirectoryAccess.DirectoryFailure.class,()->store.transaction(connection->cleanup.beforeAccountDelete(connection,actor,"target")));
        verify(guard,never()).requireAdministrators(any(),any());
        assertOriginal();
    }

    @Test void invalidTransactionAndMissingGuardFailClosed()throws Exception {
        assertThrows(NullPointerException.class,()->new AccountGrantCleanup(store,null,ISSUER));
        try(var connection=source.getConnection()) {
            assertThrows(IllegalStateException.class,()->new AccountGrantCleanup(store,guard,ISSUER).beforeAccountDelete(connection,actor,"target"));
            connection.setAutoCommit(false);connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            assertThrows(IllegalStateException.class,()->new AccountGrantCleanup(store,guard,ISSUER).beforeAccountDelete(connection,actor,"target"));
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            var readOnly=spy(connection);doReturn(true).when(readOnly).isReadOnly();
            assertThrows(IllegalStateException.class,()->new AccountGrantCleanup(store,guard,ISSUER).beforeAccountDelete(readOnly,actor,"target"));
            connection.rollback();
        }
        verifyNoInteractions(guard);assertOriginal();
    }

    private void assertOriginal() {
        assertEquals(List.of(group("first",Set.of("read")),group("second",Set.of())),store.readAll());
        try(var connection=source.getConnection();var statement=connection.createStatement();var result=statement.executeQuery("SELECT count(*) FROM test_standard_account")) {
            assertTrue(result.next());assertEquals(1,result.getInt(1));
        }catch(SQLException failure) {throw new AssertionError(failure);}
    }
}
