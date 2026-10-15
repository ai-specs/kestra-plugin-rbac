package io.kestra.extensions.rbac.store;

import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import static io.kestra.extensions.rbac.store.PolicyDocumentValidatorTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JdbcPolicyStoreTest {
    static DataSource database() {
        String postgres = System.getenv("RBAC_STORE_TEST_JDBC");
        if (postgres != null && !postgres.isBlank()) {
            var dataSource = new org.postgresql.ds.PGSimpleDataSource();
            dataSource.setURL(postgres);
            dataSource.setUser("postgres");
            dataSource.setPassword("policy-test-only");
            String schema = "policy_" + UUID.randomUUID().toString().replace("-", "");
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA " + schema);
            } catch (java.sql.SQLException failure) { throw new IllegalStateException("Cannot prepare isolated policy schema", failure); }
            dataSource.setCurrentSchema(schema);
            return dataSource;
        }
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:policy-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        return dataSource;
    }

    static JdbcPolicyStore store(DataSource source) {
        return new JdbcPolicyStore(source, new PolicyDocumentValidator());
    }

    @Test
    void shouldInitializeIdempotentlyAndImportOnlyMatchingCanonicalPolicy() {
        var store = store(database());
        store.initializeSchema();
        store.initializeSchema();
        assertThrows(IllegalStateException.class, store::readCurrent);
        var first = store.importInitial(json("初始"), "owner");
        var again = store.importInitial(bytes(" \n" + first.canonicalJson()), "other-operator");
        assertEquals(1, first.revision());
        assertEquals(first.canonicalJson(), again.canonicalJson());
        assertEquals("owner", again.actorSubject());
        assertThrows(JdbcPolicyStore.RevisionConflictException.class, () -> store.importInitial(json("不同"), "owner"));
        assertEquals(1, store.history().size());
    }

    @Test
    void shouldSerializeConcurrentSchemaInitialization() throws Exception {
        var source = database();
        var barrier = new CyclicBarrier(2);
        var tasks = List.of(1, 2).stream().map(index -> CompletableFuture.runAsync(() -> {
            try { barrier.await(2, TimeUnit.SECONDS); store(source).initializeSchema(); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
        })).toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(tasks).get(10, TimeUnit.SECONDS);
        assertEquals(1, store(source).importInitial(json("concurrent schema"), "owner").revision());
    }

    @Test
    void shouldCommitWholePolicyWithExpectedRevisionAndReadFromAnotherStore() {
        var database = database();
        var first = store(database);
        var second = store(database);
        first.initializeSchema();
        var initial = first.importInitial(json("旧策略"), "owner");
        assertEquals(initial.canonicalJson(), second.readCurrent().canonicalJson());
        var updated = first.replace(1, json("新策略"), "editor");
        assertEquals(2, updated.revision());
        assertEquals(1, updated.previousRevision());
        assertEquals("editor", second.readCurrent().actorSubject());
        assertEquals("旧策略", initial.document().roles().getFirst().description());
        assertEquals("新策略", second.readCurrent().document().roles().getFirst().description());
        assertSame(second.readCurrent(), second.readCurrent());
        assertThrows(JdbcPolicyStore.RevisionConflictException.class, () -> first.replace(1, json("过时"), "editor"));
        assertEquals(List.of(2L, 1L), first.history().stream().map(JdbcPolicyStore.Snapshot::revision).toList());
    }

    @Test
    void shouldAllowExactlyOneConcurrentUpdateAndConflictTheOther() throws Exception {
        var database = database();
        var store = store(database);
        store.initializeSchema();
        store.importInitial(json("initial"), "owner");
        var barrier = new CyclicBarrier(2);
        var successes = new AtomicInteger();
        var conflicts = new AtomicInteger();
        var tasks = List.of("first", "second").stream().map(description -> CompletableFuture.runAsync(() -> {
            try {
                barrier.await(2, TimeUnit.SECONDS);
                store(database).replace(1, json(description), description);
                successes.incrementAndGet();
            } catch (JdbcPolicyStore.RevisionConflictException expected) { conflicts.incrementAndGet(); }
            catch (Exception failure) { throw new RuntimeException(failure); }
        })).toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(tasks).get(10, TimeUnit.SECONDS);
        assertEquals(1, successes.get());
        assertEquals(1, conflicts.get());
        assertEquals(2, store.readCurrent().revision());
        assertEquals(2, store.history().size());
    }

    @Test
    void shouldRollbackInvalidReferenceAndOversizedDocumentWithoutChangingHead() {
        var store = store(database());
        store.initializeSchema();
        store.importInitial(json("initial"), "owner");
        String invalid = new PolicyDocumentValidator().validate(policy("")).canonicalJson().replace("\"alice\":[\"role-1\"]", "\"alice\":[\"missing\"]");
        assertThrows(IllegalArgumentException.class, () -> store.replace(1, bytes(invalid), "editor"));
        assertThrows(IllegalArgumentException.class, () -> store.replace(1, new byte[PolicyDocumentValidator.MAX_BYTES + 1], "editor"));
        assertEquals(1, store.readCurrent().revision());
        assertEquals(1, store.history().size());
        assertEquals(2, store.replace(1, json("valid"), "editor").revision());
    }

    @Test
    void shouldRetainCurrentAndOneHundredHistoricalSnapshots() {
        var store = store(database());
        store.initializeSchema();
        store.importInitial(json("initial"), "owner");
        for (long revision = 1; revision < 105; revision++) store.replace(revision, json("revision-" + revision), "editor");
        var history = store.history();
        assertEquals(JdbcPolicyStore.HISTORY_LIMIT + 1, history.size());
        assertEquals(105, history.getFirst().revision());
        assertEquals(5, history.getLast().revision());
    }

    @Test
    void shouldFailOnDatabaseOutageEvenAfterCacheWasPopulated() throws Exception {
        var real = database();
        var source = mock(DataSource.class);
        var outage = new AtomicBoolean();
        when(source.getConnection()).thenAnswer(invocation -> {
            if (outage.get()) throw new java.sql.SQLException("simulated connection failure");
            return real.getConnection();
        });
        var store = store(source);
        store.initializeSchema();
        store.importInitial(json("initial"), "owner");
        store.readCurrent();
        outage.set(true);
        assertThrows(JdbcPolicyStore.StorageException.class, store::readCurrent);
        assertThrows(JdbcPolicyStore.StorageException.class, store::history);
        assertThrows(JdbcPolicyStore.StorageException.class, () -> store.replace(1, json("uncommitted"), "editor"));
        outage.set(false);
        assertEquals(1, store.readCurrent().revision());
        assertEquals(1, store.history().size());
    }

    @Test
    void shouldRollbackInsertedSnapshotWhenHeadUpdateFails() throws Exception {
        var real = database();
        var store = store(real);
        store.initializeSchema();
        store.importInitial(json("initial"), "owner");
        var source = mock(DataSource.class);
        when(source.getConnection()).thenAnswer(invocation -> {
            var connection = spy(real.getConnection());
            doThrow(new java.sql.SQLException("simulated update failure")).when(connection).prepareStatement(startsWith("UPDATE rbac_policy_head"));
            return connection;
        });
        assertThrows(JdbcPolicyStore.StorageException.class, () -> store(source).replace(1, json("must rollback"), "editor"));
        assertEquals(1, store.readCurrent().revision());
        assertEquals(1, store.history().size());
    }

    @Test
    void shouldRejectCorruptedSnapshotAndIncompatibleSchemaInsteadOfOldCache() throws Exception {
        var source = database();
        var store = store(source);
        store.initializeSchema();
        store.importInitial(json("initial"), "owner");
        store.readCurrent();
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE rbac_policy_head SET schema_version = 2");
            assertThrows(IllegalStateException.class, store::readCurrent);
            statement.executeUpdate("UPDATE rbac_policy_head SET schema_version = 1");
            statement.executeUpdate("UPDATE rbac_policy_snapshot SET document_json = '{}' WHERE revision = 1");
        }
        assertThrows(IllegalArgumentException.class, () -> store(source).readCurrent());
        assertThrows(IllegalArgumentException.class, store::history);
    }
}
