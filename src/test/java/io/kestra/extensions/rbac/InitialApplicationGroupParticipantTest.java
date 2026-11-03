package io.kestra.extensions.rbac;

import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupDirectory.Snapshot;
import io.kestra.extensions.rbac.store.ApplicationGroupDirectoryStore;
import io.kestra.webserver.oidc.OidcInitialAccountParticipantV1.InitialAccount;
import io.kestra.webserver.oidc.OidcProtocolStateV1;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InitialApplicationGroupParticipantTest {
    private static final String ISSUER="https://issuer.example";
    private static InitialAccount account(String issuer,String client) {
        return new InitialAccount(issuer,"28e9352d-ea66-4a24-888f-e76fda93fef0",client,"first-user");
    }
    private static Connection empty()throws SQLException {
        var connection=mock(Connection.class);var metadata=mock(DatabaseMetaData.class);var tables=mock(ResultSet.class);
        when(connection.getMetaData()).thenReturn(metadata);when(connection.getSchema()).thenReturn("public");
        when(metadata.getTables(isNull(),eq("public"),isNull(),any())).thenReturn(tables);
        when(tables.next()).thenReturn(false);return connection;
    }

    @Test void initializesOnlyThroughBorrowedConnectionAndNeverCommitsOrCloses()throws SQLException {
        var connection=empty();var store=mock(ApplicationGroupDirectoryStore.class);var protocol=mock(OidcProtocolStateV1.class);
        new InitialApplicationGroupParticipant(store,protocol,ISSUER,"ui").initialize(connection,account(ISSUER,"ui"));
        var captured=ArgumentCaptor.forClass(Snapshot.class);var order=inOrder(protocol,store);
        order.verify(protocol).lockSecurityState(connection);order.verify(store).initializeSchema(connection);
        order.verify(store).initializeBuiltin(eq(connection),captured.capture());
        assertEquals("first-user",captured.getValue().userGrants().getFirst().subject());
        assertEquals("ui",captured.getValue().applications().getFirst().clientId());
        verify(protocol,never()).verifyInstallation(any(),any(),any());
        verify(connection,never()).commit();verify(connection,never()).rollback();verify(connection,never()).close();
    }

    @Test void rejectsIssuerMismatchBeforeAnyMutation()throws SQLException {
        var store=mock(ApplicationGroupDirectoryStore.class);var protocol=mock(OidcProtocolStateV1.class);var connection=empty();
        var participant=new InitialApplicationGroupParticipant(store,protocol,ISSUER,"ui");
        assertThrows(IllegalArgumentException.class,()->participant.initialize(connection,account("https://another.example","ui")));
        verifyNoInteractions(store,protocol);verify(connection,never()).getMetaData();
    }

    @Test void rejectsLoginClientMismatchBeforeAnyMutation()throws SQLException {
        var store=mock(ApplicationGroupDirectoryStore.class);var protocol=mock(OidcProtocolStateV1.class);var connection=empty();
        var participant=new InitialApplicationGroupParticipant(store,protocol,ISSUER,"ui");
        assertThrows(IllegalArgumentException.class,()->participant.initialize(connection,account(ISSUER,"another-ui")));
        verifyNoInteractions(store,protocol);verify(connection,never()).getMetaData();
    }

    @Test void rejectsAnyExistingRbacTableIncludingLegacyOrPartialStorage()throws SQLException {
        for(var table:java.util.List.of("rbac_application_group_state","rbac_policy_head","RBAC_MANAGEMENT_AUDIT")) {
            var connection=empty();var tables=connection.getMetaData().getTables(null,"public",null,new String[]{"TABLE"});
            when(tables.next()).thenReturn(true,false);when(tables.getString("TABLE_NAME")).thenReturn(table);
            var store=mock(ApplicationGroupDirectoryStore.class);var protocol=mock(OidcProtocolStateV1.class);
            var participant=new InitialApplicationGroupParticipant(store,protocol,ISSUER,"ui");
            assertThrows(IllegalStateException.class,()->participant.initialize(connection,account(ISSUER,"ui")));
            verifyNoInteractions(store);verify(connection,never()).commit();verify(connection,never()).rollback();
        }
    }

    @Test void propagatesStoreFailureToHostTransactionOwnerWithoutCompensation()throws SQLException {
        var connection=empty();var store=mock(ApplicationGroupDirectoryStore.class);var protocol=mock(OidcProtocolStateV1.class);
        var failure=new SQLException("isolated test failure");doThrow(failure).when(store).initializeSchema(connection);
        var participant=new InitialApplicationGroupParticipant(store,protocol,ISSUER,"ui");
        assertSame(failure,assertThrows(SQLException.class,()->participant.initialize(connection,account(ISSUER,"ui"))));
        verify(store,never()).initializeBuiltin(any(),any());
        verify(connection,never()).commit();verify(connection,never()).rollback();verify(connection,never()).close();
    }

    @Test void propagatesMetadataFailureWithoutCreatingTables()throws SQLException {
        var connection=empty();var store=mock(ApplicationGroupDirectoryStore.class);var protocol=mock(OidcProtocolStateV1.class);
        when(connection.getMetaData()).thenThrow(new SQLException("isolated metadata failure"));
        var participant=new InitialApplicationGroupParticipant(store,protocol,ISSUER,"ui");
        assertThrows(SQLException.class,()->participant.initialize(connection,account(ISSUER,"ui")));
        verifyNoInteractions(store);verify(connection,never()).commit();verify(connection,never()).rollback();verify(connection,never()).close();
    }
}
