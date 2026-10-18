package io.kestra.extensions.rbac.store;

import io.kestra.extensions.rbac.project.ProjectPolicyParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.kestra.extensions.rbac.store.ProjectMigrationReviewTest.*;

class ProjectPolicyStoreTest {
    @Test void concurrentWritersCannotMixSnapshotsOrOverwriteWinner()throws Exception {
        var store=new ProjectPolicyStore(JdbcPolicyStoreTest.database(),new ProjectPolicyParser());
        store.initializeSchema();var original=store.initialize(bytes(TARGET),"operator");
        var barrier=new java.util.concurrent.CyclicBarrier(2);
        var wins=new java.util.concurrent.atomic.AtomicInteger();
        var conflicts=new java.util.concurrent.atomic.AtomicInteger();
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var futures=java.util.stream.IntStream.range(0,2).mapToObj(index->executor.submit(()->{
                barrier.await(5,java.util.concurrent.TimeUnit.SECONDS);
                try {store.replace(1,bytes(TARGET),"writer-"+index);wins.incrementAndGet();}
                catch(ProjectPolicyStore.Conflict expected){conflicts.incrementAndGet();}
                return null;
            })).toList();
            for(var future:futures)future.get(10,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals(1,wins.get());assertEquals(1,conflicts.get());
        var current=store.readCurrent();assertEquals(2,current.revision());assertEquals(1,current.previousRevision());
        assertTrue(current.actor().startsWith("writer-"));
        assertEquals(1,original.revision());assertEquals("operator",original.actor());
        assertEquals(original.policy(),current.policy());
    }
    @Test void initializesOnlyEmptyDatabaseAndUsesCompareAndSwap() {
        var source=JdbcPolicyStoreTest.database();var store=new ProjectPolicyStore(source,new ProjectPolicyParser());
        store.initializeSchema();store.initializeSchema();
        assertThrows(IllegalStateException.class,store::readCurrent);
        var first=store.initialize(bytes(TARGET),"operator");assertEquals(1,first.revision());
        assertEquals(first.canonicalJson(),store.initialize(bytes(TARGET),"other").canonicalJson());
        var next=store.replace(1,bytes(TARGET.replace("\"enabled\":true","\"enabled\":false")),"editor");
        assertEquals(2,next.revision());assertEquals(1,next.previousRevision());
        assertThrows(ProjectPolicyStore.Conflict.class,()->store.replace(1,bytes(TARGET),"stale"));
        assertEquals(next.canonicalJson(),new ProjectPolicyStore(source,new ProjectPolicyParser()).readCurrent().canonicalJson());
        assertTrue(first.policy().applicationProjects().get("kestra").enabled(),"An old snapshot is immutable");
    }
    @Test void legacyPolicyCannotBeSilentlyInitializedOrChangedByMigration() {
        var source=JdbcPolicyStoreTest.database();var legacy=JdbcPolicyStoreTest.store(source);legacy.initializeSchema();
        var old=legacy.importInitial(bytes(LEGACY),"old-operator");
        var store=new ProjectPolicyStore(source,new ProjectPolicyParser());store.initializeSchema();
        assertThrows(IllegalStateException.class,()->store.initialize(bytes(TARGET),"operator"));
        var review=new ProjectMigrationReview().preview(old.revision(),bytes(LEGACY),bytes(TARGET),bytes(DROP),"operator");
        assertThrows(IllegalArgumentException.class,()->store.migrateApproved(review,"0".repeat(64),bytes(TARGET),"operator"));
        assertThrows(IllegalStateException.class,store::readCurrent);
        assertThrows(ProjectPolicyStore.Conflict.class,()->store.migrateApproved(review,review.approvalHash(),bytes(TARGET.replace("kestra-ui","another-audience")),"operator"));
        assertThrows(IllegalStateException.class,store::readCurrent);
        var migrated=store.migrateApproved(review,review.approvalHash(),bytes(TARGET),"operator");
        assertEquals(2,migrated.policy().schemaVersion());assertEquals(1,migrated.revision());
        assertEquals(old.canonicalJson(),legacy.readCurrent().canonicalJson());
        assertEquals(old.revision(),legacy.readCurrent().revision(),"Legacy backup is not rewritten or pruned");
        assertThrows(ProjectPolicyStore.Conflict.class,()->store.migrateApproved(review,review.approvalHash(),bytes(TARGET),"operator"));
    }
    @Test void staleReviewDoesNotLeavePartialTargetPolicy() {
        var source=JdbcPolicyStoreTest.database();var legacy=JdbcPolicyStoreTest.store(source);legacy.initializeSchema();
        legacy.importInitial(bytes(LEGACY),"old-operator");
        var review=new ProjectMigrationReview().preview(1,bytes(LEGACY),bytes(TARGET),bytes(DROP),"operator");
        legacy.replace(1,bytes(LEGACY.replace("alice","bob")),"concurrent-editor");
        var store=new ProjectPolicyStore(source,new ProjectPolicyParser());store.initializeSchema();
        assertThrows(IllegalArgumentException.class,()->store.migrateApproved(review,review.approvalHash(),bytes(TARGET),"operator"));
        assertThrows(IllegalStateException.class,store::readCurrent);
        assertEquals(2,legacy.readCurrent().revision());
    }
    @Test void corruptedStoredDocumentAndDatabaseFailureDoNotReuseOldAllows() throws Exception {
        var source=JdbcPolicyStoreTest.database();var store=new ProjectPolicyStore(source,new ProjectPolicyParser());store.initializeSchema();
        store.initialize(bytes(TARGET),"operator");
        try(var connection=source.getConnection();var statement=connection.createStatement()) {
            statement.executeUpdate("UPDATE rbac_project_policy_snapshot SET document_json='{}'");
        }
        assertThrows(IllegalArgumentException.class,store::readCurrent);
        try(var connection=source.getConnection();var statement=connection.createStatement()) {
            statement.execute("DROP TABLE rbac_project_policy_snapshot");
        }
        assertThrows(ProjectPolicyStore.Unavailable.class,store::readCurrent);
    }
}
