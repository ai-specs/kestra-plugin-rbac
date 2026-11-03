package io.kestra.extensions.rbac.applicationgroup;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.extensions.rbac.InitialApplicationGroupParticipant;
import io.kestra.extensions.rbac.store.ApplicationGroupDirectoryStore;
import io.kestra.webserver.oidc.OidcInitialAccountParticipantV1.InitialAccount;
import io.kestra.webserver.oidc.OidcProtocolState;
import io.kestra.webserver.oidc.services.OidcInstallationReceipt;
import java.sql.Connection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@Tag("postgres-lifecycle")
class InitialApplicationGroupPostgresTest {
    private static final String ISSUER="https://issuer.example";
    private final org.postgresql.ds.PGSimpleDataSource source=new org.postgresql.ds.PGSimpleDataSource();
    private final org.postgresql.ds.PGSimpleDataSource owner=new org.postgresql.ds.PGSimpleDataSource();
    private ApplicationGroupDirectoryStore store;
    private InitialApplicationGroupParticipant participant;
    private String schema;
    @TempDir java.nio.file.Path temporary;

    @BeforeEach void prepare()throws Exception {
        String jdbc=System.getenv("RBAC_STORE_TEST_JDBC");assertNotNull(jdbc,"PostgreSQL input is required");
        String user=System.getenv().getOrDefault("RBAC_STORE_TEST_USER","postgres");
        String password=System.getenv().getOrDefault("RBAC_STORE_TEST_PASSWORD","policy-test-only");
        for(var database:List.of(source,owner)) {database.setURL(jdbc);database.setUser(user);database.setPassword(password);}
        schema="initial_account_"+UUID.randomUUID().toString().replace("-","");
        try(var connection=owner.getConnection();var statement=connection.createStatement()) {
            assertEquals("PostgreSQL",connection.getMetaData().getDatabaseProductName());
            statement.execute("CREATE SCHEMA "+schema);
        }
        source.setCurrentSchema(schema);
        var protocol=new OidcProtocolState(ISSUER,"ui",new OidcInstallationReceipt(temporary.resolve("receipt")),new ObjectMapper());
        store=new ApplicationGroupDirectoryStore(source,protocol::lockSecurityState);
        participant=new InitialApplicationGroupParticipant(store,protocol,ISSUER,"ui");
        var provenance=new ObjectMapper().readTree(java.nio.file.Files.readString(java.nio.file.Path.of(".integration/host-provenance.json")));
        System.out.println("Initial-account backend: PostgreSQL; host: published-initial-account-participant; executable: "+provenance.path("sha256").asText()+"; image: "+provenance.path("image").asText());
    }

    @AfterEach void clean()throws Exception {
        if(schema!=null)try(var connection=owner.getConnection();var statement=connection.createStatement()) {statement.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");}
    }
    private InitialAccount account() {return new InitialAccount(ISSUER,"28e9352d-ea66-4a24-888f-e76fda93fef0","ui","first-user");}
    private Connection transaction()throws Exception {
        var connection=source.getConnection();connection.setAutoCommit(false);connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);return connection;
    }
    private Set<String> tables()throws Exception {
        var names=new java.util.TreeSet<String>();
        try(var connection=source.getConnection();var tables=connection.getMetaData().getTables(null,schema,null,new String[]{"TABLE"})) {
            while(tables.next())names.add(tables.getString("TABLE_NAME"));
        }
        return names;
    }
    private void install()throws Exception {
        try(var connection=transaction()){participant.initialize(connection,account());connection.commit();}
    }

    @Test void borrowedConnectionRollbackRemovesEveryDirectoryAndAuditTableAndAllowsRetry()throws Exception {
        try(var connection=transaction()) {participant.initialize(connection,account());assertEquals(1,store.readAll(connection).size());connection.rollback();}
        assertTrue(tables().isEmpty());install();assertEquals("main",store.builtinGroupId());
    }

    @Test void committedInstallationCreatesOnlyCurrentSchemaAndProtectsBuiltinGroup()throws Exception {
        install();assertEquals(Set.of("rbac_application_group_state","rbac_application_group_bootstrap","rbac_application_group_invalidation","rbac_application_group_retired","rbac_management_audit"),tables());
        var group=store.readAll().getFirst();assertEquals(1,group.group().revision());assertEquals(1,group.group().authorizationVersion());
        assertEquals(1,group.applications().getFirst().permissions().size());
        var failure=assertThrows(ApplicationGroupDirectoryStore.DeletionRejected.class,()->store.delete("main",1));
        assertEquals(ApplicationGroupDirectoryStore.DeletionFailure.PROTECTED_GROUP,failure.failure());
    }

    @Test void repeatedInitializationRejectsWithoutChangingAnyDirectoryOrGrant()throws Exception {
        install();var before=store.readAll();
        try(var connection=transaction()) {
            assertThrows(IllegalStateException.class,()->participant.initialize(connection,account()));connection.rollback();
        }
        assertEquals(before,store.readAll());
        assertEquals(1L,store.transaction(store::catalogRevision).longValue());
    }

    @Test void partialLegacyStorageIsRejectedWithoutImplicitUpgradeOrNewTables()throws Exception {
        try(var connection=source.getConnection();var statement=connection.createStatement()) {statement.execute("CREATE TABLE rbac_policy_head (revision BIGINT)");}
        try(var connection=transaction()) {
            assertThrows(IllegalStateException.class,()->participant.initialize(connection,account()));connection.rollback();
        }
        assertEquals(Set.of("rbac_policy_head"),tables());
    }

    @Test void concurrentInitializationCommitsExactlyOneInitialDirectory()throws Exception {
        var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> operation=()->{
                assertTrue(start.await(10,TimeUnit.SECONDS));
                try(var connection=transaction()) {
                    try {participant.initialize(connection,account());connection.commit();return true;}
                    catch(IllegalStateException failure){connection.rollback();return false;}
                }
            };
            var first=executor.submit(operation);var second=executor.submit(operation);start.countDown();
            assertNotEquals(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS));
        }
        assertEquals(1,store.readAll().size());assertEquals(1L,store.transaction(store::catalogRevision).longValue());
    }

    @Test void firstSubjectAloneReceivesEditableTypedScopeAndNoAdministratorNameBypass()throws Exception {
        install();var group=store.readAll().getFirst();
        assertEquals(Set.of("admin"),group.eligibleScopeKeys("ui",ISSUER,"first-user"));
        assertTrue(group.eligibleScopeKeys("ui",ISSUER,"admin").isEmpty());
        assertTrue(group.eligibleScopeKeys("external",ISSUER,"first-user").isEmpty());
        assertEquals(List.of("ui"),group.applications().stream().map(ApplicationGroupDirectory.Application::clientId).toList());
    }
}
