package io.kestra.extensions.rbac.store;

import io.kestra.extensions.rbac.management.ManagementLifecycleAudit;
import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupDirectory.*;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ManagementAuditStoreTest {
    private static final String ISSUER="https://issuer.example";
    final DataSource source;
    final ApplicationGroupDirectoryStore directory;
    private final ManagementAuditStore audit=new ManagementAuditStore();
    private final ManagementLifecycleAudit lifecycle=new ManagementLifecycleAudit(audit);
    ManagementAuditStoreTest()throws Exception {
        var jdbc=System.getenv("RBAC_STORE_TEST_JDBC");
        if(jdbc!=null && !jdbc.isBlank()) {
            var database=new org.postgresql.ds.PGSimpleDataSource();database.setURL(jdbc);database.setUser("postgres");database.setPassword("policy-test-only");
            String schema="management_audit_"+UUID.randomUUID().toString().replace("-","");
            try(var connection=database.getConnection();var statement=connection.createStatement()) {statement.execute("CREATE SCHEMA "+schema);}
            database.setCurrentSchema(schema);source=database;
        }else {
            var database=new org.h2.jdbcx.JdbcDataSource();database.setURL("jdbc:h2:mem:audit_"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");source=database;
        }
        try(var connection=source.getConnection()) {
            String backend=connection.getMetaData().getDatabaseProductName();assertEquals(jdbc!=null && !jdbc.isBlank()?"PostgreSQL":"H2",backend);
            System.out.println("Management audit backend: "+backend);
        }
        directory=new ApplicationGroupDirectoryStore(source);directory.initializeSchema();
        directory.initializeBuiltin(new Snapshot(new Group("main","Main",true,1,1),List.of(),List.of(),List.of()));
    }

    @Test void appendOnlyWriterPreservesOriginalSubjectsAndFiltersItemsAndTotalsBeforePaging() {
        directory.transaction(connection->{
            lifecycle.append(connection,ISSUER,"original-actor",ManagementAuditStore.Operation.ACCOUNT_DELETE,"old-target",null,Set.of("main","other"));
            lifecycle.append(connection,"https://foreign.example","foreign",ManagementAuditStore.Operation.ACCOUNT_DELETE,"foreign-target",null,Set.of("main"));
            var page=audit.read(connection,ISSUER,Set.of("main"),1,1);
            assertEquals(1,page.total());assertEquals(1,page.items().size());
            var event=page.items().getFirst();assertEquals("original-actor",event.actorSubject());assertEquals("old-target",event.targetSubject());
            assertEquals("main",event.groupId());assertNotNull(event.time());assertNotNull(event.id());
            assertEquals(1,audit.read(connection,ISSUER,Set.of("other"),1,25).total());
            assertEquals(0,audit.read(connection,ISSUER,Set.of(),1,25).total());
            assertEquals(2,audit.readIssuer(connection,ISSUER,1,25).total());
            assertEquals(1,audit.readIssuer(connection,"https://foreign.example",1,25).total());
            return null;
        });
        assertEquals(2,directory.transaction(connection->audit.read(connection,ISSUER,Set.of("main","other"),1,25)).total());
        directory.create(new Snapshot(new Group("other","Other",true,1,1),List.of(),List.of(),List.of()));directory.delete("other",1);
        assertEquals(2,directory.transaction(connection->audit.readIssuer(connection,ISSUER,1,25)).total());
    }

    @Test void participantAndHostFailureRollbackLeaveNoAuditRows() {
        for(String failure:List.of("participant","host"))assertThrows(IllegalStateException.class,()->directory.transaction(connection->{
            lifecycle.append(connection,ISSUER,"actor",ManagementAuditStore.Operation.PASSWORD_RESET,"target",null,Set.of("main"));
            throw new IllegalStateException(failure);
        }));
        assertEquals(0,directory.transaction(connection->audit.read(connection,ISSUER,Set.of("main"),1,25)).total());
    }

    @Test void credentialAuditHasNoSecretFieldsAndMissingTableFailsClosed() {
        directory.transaction(connection->{
            lifecycle.append(connection,ISSUER,"actor",ManagementAuditStore.Operation.CLIENT_SECRET_ROTATE,null,"client",Set.of("main"));
            var event=audit.read(connection,ISSUER,Set.of("main"),1,25).items().getFirst();
            assertNull(event.targetSubject());assertEquals("client",event.clientId());
            assertEquals(Set.of("id","time","issuer","actorSubject","operation","targetSubject","clientId","groupId"),
                java.util.Arrays.stream(event.getClass().getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).collect(java.util.stream.Collectors.toSet()));
            try(var statement=connection.createStatement()) {statement.execute("DROP TABLE rbac_management_audit");}
            assertThrows(java.sql.SQLException.class,()->lifecycle.append(connection,ISSUER,"actor",ManagementAuditStore.Operation.ACCOUNT_DELETE,"target",null,Set.of("main")));
            return null;
        });
    }

    @Test void targetSnapshotUsesAllMembershipsAndGrouplessAccountsUseOnlyActorGroup() {
        var grant=new UserGrant(ISSUER,"target",Set.of(),1);
        var snapshots=List.of(new Snapshot(new Group("main","Main",true,1,1),List.of(),List.of(),List.of(grant)),
            new Snapshot(new Group("other","Other",true,1,1),List.of(),List.of(),List.of(grant)));
        assertEquals(Set.of("main","other"),lifecycle.targetGroups(snapshots,ISSUER,"main",ManagementAuditStore.Operation.ACCOUNT_DELETE,"target",null));
        assertEquals(Set.of("main"),lifecycle.targetGroups(snapshots,ISSUER,"main",ManagementAuditStore.Operation.ACCOUNT_DELETE,"ungrouped",null));
        assertThrows(IllegalArgumentException.class,()->lifecycle.targetGroups(snapshots,ISSUER,"main",ManagementAuditStore.Operation.CLIENT_SECRET_ROTATE,null,"unknown-client"));
    }
}
