package io.kestra.extensions.rbac.store;

import java.sql.Connection;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ManagementAuditSchemaCommandTest {
    @Test void previewDoesNotCreateAndApplyRejectsWrongOrDriftedHash()throws Exception {
        var fixture=new ManagementAuditStoreTest();
        fixture.directory.transaction(connection->{try(var statement=connection.createStatement()){statement.execute("DROP TABLE rbac_management_audit");}return null;});
        String approved=fixture.directory.transaction(connection->ManagementAuditSchemaCommand.approval(connection,fixture.directory));
        assertFalse(fixture.directory.transaction(ManagementAuditSchemaCommandTest::present));
        assertThrows(IllegalArgumentException.class,()->fixture.directory.transaction(connection->{ManagementAuditSchemaCommand.apply(connection,fixture.directory,"0".repeat(64));return null;}));
        fixture.directory.update("main",1,current->io.kestra.extensions.rbac.applicationgroup.ApplicationGroupChanges.groupMetadata(current,"Changed",true));
        assertThrows(IllegalArgumentException.class,()->fixture.directory.transaction(connection->{ManagementAuditSchemaCommand.apply(connection,fixture.directory,approved);return null;}));
        assertFalse(fixture.directory.transaction(ManagementAuditSchemaCommandTest::present));
        String latest=fixture.directory.transaction(connection->ManagementAuditSchemaCommand.approval(connection,fixture.directory));
        fixture.directory.transaction(connection->{ManagementAuditSchemaCommand.apply(connection,fixture.directory,latest);return null;});
        assertTrue(fixture.directory.transaction(ManagementAuditSchemaCommandTest::present));
    }

    @Test void repeatedSchemaApplyPreservesExistingAuditWithoutBackfilling()throws Exception {
        var fixture=new ManagementAuditStoreTest();var audit=new ManagementAuditStore();
        fixture.directory.transaction(connection->{audit.append(connection,"https://issuer.example","original",ManagementAuditStore.Operation.PASSWORD_RESET,"target",null,Set.of("main"));return null;});
        var before=fixture.directory.transaction(connection->audit.readIssuer(connection,"https://issuer.example",1,25));
        String hash=fixture.directory.transaction(connection->ManagementAuditSchemaCommand.approval(connection,fixture.directory));
        for(int i=0;i<2;i++)fixture.directory.transaction(connection->{ManagementAuditSchemaCommand.apply(connection,fixture.directory,hash);return null;});
        assertEquals(before,fixture.directory.transaction(connection->audit.readIssuer(connection,"https://issuer.example",1,25)));
    }
    private static boolean present(Connection connection)throws java.sql.SQLException {
        try(var tables=connection.getMetaData().getTables(null,connection.getSchema(),null,new String[]{"TABLE"})) {
            while(tables.next())if("rbac_management_audit".equalsIgnoreCase(tables.getString("TABLE_NAME")))return true;
        }
        return false;
    }
}
