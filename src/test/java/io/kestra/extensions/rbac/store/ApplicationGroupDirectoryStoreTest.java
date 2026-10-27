package io.kestra.extensions.rbac.store;

import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupDirectory.*;
import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupChanges;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ApplicationGroupDirectoryStoreTest {
    private static DataSource database() {
        var postgres=System.getenv("RBAC_STORE_TEST_JDBC");
        DataSource source;
        if(postgres!=null && !postgres.isBlank()) {
            var database=new org.postgresql.ds.PGSimpleDataSource();
            database.setURL(postgres);database.setUser("postgres");database.setPassword("policy-test-only");
            String schema="group_directory_"+UUID.randomUUID().toString().replace("-","");
            try(var connection=database.getConnection();var statement=connection.createStatement()) {
                statement.execute("CREATE SCHEMA "+schema);
            }catch(java.sql.SQLException failure){throw new IllegalStateException("Cannot prepare application group test schema",failure);}
            database.setCurrentSchema(schema);source=database;
        }else {
            var database=new JdbcDataSource();
            database.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
            source=database;
        }
        try(var connection=source.getConnection()) {
            String backend=connection.getMetaData().getDatabaseProductName();
            assertEquals(postgres!=null && !postgres.isBlank()?"PostgreSQL":"H2",backend);
            System.out.println("Application group directory store backend: "+backend);
        }catch(java.sql.SQLException failure){throw new IllegalStateException("Cannot verify application group test database",failure);}
        return source;
    }

    private static Snapshot empty(String id) {
        return new Snapshot(new Group(id,id,true,1,1),List.of(),List.of(),List.of());
    }

    @Test void emptySupportApplicationRemovalAndReentryAdvanceEpochButMetadataDoesNot() {
        var store=new ApplicationGroupDirectoryStore(database());store.initializeSchema();store.initializeBuiltin(empty("first"));
        var added=store.update("first",1,current->ApplicationGroupChanges.registerApplication(current,"client","",Set.of("resource")));
        assertEquals(2,added.group().authorizationVersion());
        var renamed=store.update("first",2,current->ApplicationGroupChanges.applicationMetadata(current,"client",1,"Changed",Set.of("resource")));
        assertEquals(2,renamed.group().authorizationVersion());
        var removed=store.update("first",3,current->ApplicationGroupChanges.removeApplication(current,"client",2));
        assertEquals(3,removed.group().authorizationVersion());
        var reentered=store.update("first",4,current->ApplicationGroupChanges.registerApplication(current,"client","",Set.of("resource")));
        assertEquals(4,reentered.group().authorizationVersion());
        assertTrue(reentered.applications().getFirst().permissions().isEmpty());
        assertTrue(reentered.applications().getFirst().supportedScopeKeys().isEmpty());
        assertEquals(5,reentered.group().revision());
    }

    @Test void builtinPersistsAndRetiredGroupsCannotReappear() {
        var source=database();var store=new ApplicationGroupDirectoryStore(source);
        store.initializeSchema();store.initializeSchema();
        assertThrows(IllegalStateException.class,()->store.create(empty("early")));
        store.initializeBuiltin(empty("first"));
        assertEquals("first",new ApplicationGroupDirectoryStore(source).builtinGroupId());
        assertThrows(ApplicationGroupDirectoryStore.Conflict.class,()->store.initializeBuiltin(empty("replacement")));
        assertThrows(IllegalArgumentException.class,()->store.delete("first",1));
        store.create(empty("temporary"));
        assertThrows(ApplicationGroupDirectoryStore.Conflict.class,()->store.delete("temporary",2));
        store.delete("temporary",1);
        assertThrows(ApplicationGroupDirectoryStore.Conflict.class,()->store.create(empty("temporary")));
        assertEquals(List.of(empty("first")),store.readAll());
    }

    @Test void referencesAndClientUniquenessAreTransactional() {
        var source=database();var store=new ApplicationGroupDirectoryStore(source);store.initializeSchema();
        var scope=new Scope("read","Reader","Read access",1);
        var app=io.kestra.extensions.rbac.applicationgroup.TestApplicationFactory.application("client",true,Set.of("resource"),Set.of("read"),1);
        var grant=new UserGrant("https://issuer.example","subject",Set.of("read"),1);
        var initial=new Snapshot(empty("first").group(),List.of(scope),List.of(app),List.of(grant));
        store.initializeBuiltin(initial);store.create(empty("other"));
        assertThrows(IllegalArgumentException.class,()->store.update("first",1,current->
            new Snapshot(current.group(),List.of(),current.applications(),current.userGrants())));
        assertThrows(IllegalArgumentException.class,()->store.update("other",1,current->
            new Snapshot(current.group(),List.of(scope),List.of(app),List.of())));
        var occupied=store.update("other",1,current->new Snapshot(current.group(),List.of(scope),List.of(),List.of()));
        assertThrows(IllegalArgumentException.class,()->store.delete("other",occupied.group().revision()));
        assertEquals(initial,store.readAll().stream().filter(value->value.group().id().equals("first")).findFirst().orElseThrow());
    }

    @Test void updatesAdvanceEpochAndStaleWritersLeaveNoChanges() {
        var store=new ApplicationGroupDirectoryStore(database());store.initializeSchema();store.initializeBuiltin(empty("first"));
        var updated=store.update("first",1,current->new Snapshot(
            new Group("first","Renamed",false,1,1),current.scopes(),current.applications(),current.userGrants()));
        assertEquals(2,updated.group().revision());assertEquals(2,updated.group().authorizationVersion());
        assertThrows(ApplicationGroupDirectoryStore.Conflict.class,()->store.update("first",1,current->current));
        assertThrows(IllegalArgumentException.class,()->store.update("first",2,current->empty("renamed-id")));
        assertEquals(List.of(updated),store.readAll());
    }

    @Test void parallelEditorsHaveExactlyOneWinner() throws Exception {
        var store=new ApplicationGroupDirectoryStore(database());store.initializeSchema();store.initializeBuiltin(empty("first"));
        var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var work=(java.util.concurrent.Callable<Boolean>)()->{
                assertTrue(start.await(5,TimeUnit.SECONDS));
                try{store.update("first",1,current->current);return true;}
                catch(ApplicationGroupDirectoryStore.Conflict expected){return false;}
            };
            var first=executor.submit(work);var second=executor.submit(work);start.countDown();
            assertNotEquals(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));
        }
        assertEquals(2,store.readAll().getFirst().group().revision());
    }

    @Test void catalogOverflowRollsBackDeletionAndRetirement() throws Exception {
        var source=database();var store=new ApplicationGroupDirectoryStore(source);store.initializeSchema();
        store.initializeBuiltin(empty("first"));store.create(empty("other"));
        try(var connection=source.getConnection();var statement=connection.createStatement()) {
            statement.executeUpdate("UPDATE rbac_application_group_bootstrap SET catalog_revision=9223372036854775807");
        }
        assertThrows(IllegalStateException.class,()->store.delete("other",1));
        assertEquals(2,store.readAll().size());
        try(var connection=source.getConnection();var statement=connection.createStatement();
            var result=statement.executeQuery("SELECT COUNT(*) FROM rbac_application_group_retired")) {
            assertTrue(result.next());assertEquals(0,result.getInt(1));
        }
    }

    @Test void storedUnknownFieldsFailClosed() throws Exception {
        var source=database();var store=new ApplicationGroupDirectoryStore(source);store.initializeSchema();store.initializeBuiltin(empty("first"));
        try(var connection=source.getConnection();var statement=connection.createStatement()) {
            statement.executeUpdate("UPDATE rbac_application_group_state SET document_json=REPLACE(document_json,'\"scopes\":','\"legacy\":true,\"scopes\":')");
        }
        assertThrows(IllegalStateException.class,store::readAll);
    }

    @Test void missingDirectoryTablesReportNotInitializedWithGuidance() {
        var store=new ApplicationGroupDirectoryStore(database());
        var failure=assertThrows(ApplicationGroupDirectoryStore.NotInitialized.class,store::readAll);
        assertTrue(failure.getMessage().contains("ApplicationGroupDirectoryCommand"));
    }

    @Test void databaseVersionMismatchAndMissingStorageFailClosed() throws Exception {
        var source=database();var store=new ApplicationGroupDirectoryStore(source);store.initializeSchema();store.initializeBuiltin(empty("first"));
        try(var connection=source.getConnection();var statement=connection.createStatement()) {
            statement.executeUpdate("UPDATE rbac_application_group_state SET revision=2");
        }
        assertThrows(IllegalStateException.class,store::readAll);
        try(var connection=source.getConnection();var statement=connection.createStatement()) {
            statement.execute("ALTER TABLE rbac_application_group_state DROP COLUMN document_json");
        }
        assertThrows(ApplicationGroupDirectoryStore.Unavailable.class,store::readAll);
    }

    @Test void parallelRegistrationCannotAttachClientToTwoGroups() throws Exception {
        var store=new ApplicationGroupDirectoryStore(database());store.initializeSchema();store.initializeBuiltin(empty("first"));store.create(empty("second"));
        var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var futures=new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for(var groupId:List.of("first","second"))futures.add(executor.submit(()->{
                assertTrue(start.await(5,TimeUnit.SECONDS));
                try {
                    store.update(groupId,1,current->new Snapshot(current.group(),List.of(),
                        List.of(io.kestra.extensions.rbac.applicationgroup.TestApplicationFactory.application("client",true,Set.of("resource"),Set.of(),1)),List.of()));
                    return true;
                }catch(IllegalArgumentException expected){return false;}
            }));
            start.countDown();
            assertNotEquals(futures.get(0).get(10,TimeUnit.SECONDS),futures.get(1).get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,store.readAll().stream().mapToInt(value->value.applications().size()).sum());
    }

    @Test void businessChangesArePersistedTogetherWithRevocationAndRejectStaleDeletion() throws Exception {
        var source=database();var store=new ApplicationGroupDirectoryStore(source);store.initializeSchema();store.initializeBuiltin(empty("first"));
        var scoped=store.update("first",1,current->ApplicationGroupChanges.createScope(current,"read","Reader","Read access"));
        var supported=store.update("first",scoped.group().revision(),current->io.kestra.extensions.rbac.applicationgroup.TestApplicationFactory.registerApplication(current,"client",true,Set.of("resource"),Set.of("read")));
        var assigned=store.update("first",supported.group().revision(),current->ApplicationGroupChanges.grantUser(current,"https://issuer.example","subject",Set.of("read")));
        assertEquals(Set.of("read"),assigned.eligibleScopeKeys("client","https://issuer.example","subject"));
        assertThrows(ApplicationGroupChanges.ScopeInUse.class,()->store.update("first",assigned.group().revision(),
            current->ApplicationGroupChanges.deleteScope(current,"read",1)));
        assertEquals(List.of(assigned),store.readAll());
        var revoked=store.update("first",assigned.group().revision(),current->ApplicationGroupChanges.revokeUser(current,"https://issuer.example","subject",1));
        assertEquals(Set.of(),revoked.eligibleScopeKeys("client","https://issuer.example","subject"));
        assertThrows(ApplicationGroupDirectoryStore.Conflict.class,()->store.update("first",assigned.group().revision(),current->current));
        try(var connection=source.getConnection();var statement=connection.createStatement();
            var result=statement.executeQuery("SELECT authorization_version FROM rbac_application_group_invalidation WHERE group_id='first'")) {
            assertTrue(result.next());assertEquals(revoked.group().authorizationVersion(),result.getLong(1));
        }
        assertEquals(List.of(revoked),new ApplicationGroupDirectoryStore(source).readAll());
    }

    @Test void missingOrMismatchedInvalidationCannotYieldAUsableSnapshot() throws Exception {
        var source=database();var store=new ApplicationGroupDirectoryStore(source);store.initializeSchema();store.initializeBuiltin(empty("first"));
        try(var connection=source.getConnection();var statement=connection.createStatement()) {
            statement.executeUpdate("UPDATE rbac_application_group_invalidation SET authorization_version=2");
        }
        assertThrows(IllegalStateException.class,store::readAll);
        try(var connection=source.getConnection();var statement=connection.createStatement()) {
            statement.executeUpdate("DELETE FROM rbac_application_group_invalidation");
        }
        assertThrows(IllegalStateException.class,store::readAll);
        assertThrows(IllegalStateException.class,()->store.update("first",1,current->current));
    }

    @Test void builtinForeignKeyRejectsDirectDeletionWithoutRetiringTheGroup() throws Exception {
        var source=database();var store=new ApplicationGroupDirectoryStore(source);store.initializeSchema();store.initializeBuiltin(empty("first"));
        try(var connection=source.getConnection();var statement=connection.createStatement()) {
            assertThrows(java.sql.SQLException.class,()->statement.executeUpdate("DELETE FROM rbac_application_group_state WHERE group_id='first'"));
        }
        assertEquals("first",store.builtinGroupId());assertEquals(List.of(empty("first")),store.readAll());
        try(var connection=source.getConnection();var statement=connection.createStatement();
            var result=statement.executeQuery("SELECT count(*) FROM rbac_application_group_retired")) {
            assertTrue(result.next());assertEquals(0,result.getInt(1));
        }
    }

    @Test void callerTransactionOwnsCommitAndRollbackOfGroupChanges() throws Exception {
        var source=database();var store=new ApplicationGroupDirectoryStore(source);store.initializeSchema();
        try(var connection=source.getConnection()) {
            connection.setAutoCommit(false);connection.setTransactionIsolation(java.sql.Connection.TRANSACTION_READ_COMMITTED);
            store.initializeBuiltin(connection,empty("first"));
            store.create(connection,empty("temporary"));
            store.update(connection,"temporary",1,current->new Snapshot(
                new Group("temporary","Updated",true,1,1),List.of(),List.of(),List.of()));
            assertEquals(2,store.readAll(connection).size());
            assertEquals(List.of(),store.readAll());
            connection.rollback();assertEquals(List.of(),store.readAll(connection));
            assertNull(store.builtinGroupId(connection));assertFalse(connection.isClosed());
            store.initializeBuiltin(connection,empty("first"));connection.commit();
            assertEquals(List.of(empty("first")),store.readAll());
        }
    }

    @Test void securityGuardRunsBeforeWorkAndFailureRollsBackItsOwnWrite() throws Exception {
        var source=database();var ordinary=new ApplicationGroupDirectoryStore(source);ordinary.initializeSchema();ordinary.initializeBuiltin(empty("first"));
        var guarded=new ApplicationGroupDirectoryStore(source,connection->{
            assertFalse(connection.getAutoCommit());
            assertEquals(java.sql.Connection.TRANSACTION_READ_COMMITTED,connection.getTransactionIsolation());
            try(var statement=connection.createStatement()) {
                statement.executeUpdate("UPDATE rbac_application_group_bootstrap SET catalog_revision=99 WHERE singleton_id=1");
            }
            throw new IllegalStateException("Rejected test guard");
        });
        var invoked=new java.util.concurrent.atomic.AtomicBoolean();
        assertThrows(IllegalStateException.class,()->guarded.transaction(connection->{invoked.set(true);return null;}));
        assertFalse(invoked.get());assertEquals(List.of(empty("first")),ordinary.readAll());
        try(var connection=source.getConnection();var statement=connection.createStatement();
            var result=statement.executeQuery("SELECT catalog_revision FROM rbac_application_group_bootstrap")) {
            assertTrue(result.next());assertEquals(1,result.getLong(1));
        }
    }

    @Test void borrowedConnectionsRejectAutocommitAndStaleSnapshotIsolation() throws Exception {
        var source=database();var store=new ApplicationGroupDirectoryStore(source);store.initializeSchema();store.initializeBuiltin(empty("first"));
        try(var connection=source.getConnection()) {
            assertThrows(IllegalStateException.class,()->store.readAll(connection));
            assertThrows(IllegalStateException.class,()->store.create(connection,empty("other")));
            connection.setAutoCommit(false);connection.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
            assertThrows(IllegalStateException.class,()->store.delete(connection,"first",1));
            assertThrows(IllegalStateException.class,()->store.builtinGroupId(connection));
            connection.rollback();assertFalse(connection.isClosed());
        }
        assertEquals(List.of(empty("first")),store.readAll());
    }

    @Test void concurrentDeletionAndCreationCannotResetAnExistingGroupIdentity() throws Exception {
        var store=new ApplicationGroupDirectoryStore(database());store.initializeSchema();store.initializeBuiltin(empty("first"));store.create(empty("temporary"));
        var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var deletion=executor.submit(()->{
                assertTrue(start.await(5,TimeUnit.SECONDS));store.delete("temporary",1);return true;
            });
            var creation=executor.submit(()->{
                assertTrue(start.await(5,TimeUnit.SECONDS));
                try{store.create(empty("temporary"));return true;}
                catch(ApplicationGroupDirectoryStore.Conflict expected){return false;}
            });
            start.countDown();assertTrue(deletion.get(10,TimeUnit.SECONDS));assertFalse(creation.get(10,TimeUnit.SECONDS));
        }
        assertEquals(List.of(empty("first")),store.readAll());
        assertThrows(ApplicationGroupDirectoryStore.Conflict.class,()->store.create(empty("temporary")));
    }
}
