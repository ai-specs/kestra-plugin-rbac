package io.kestra.extensions.rbac.management;

import org.junit.jupiter.api.Test;
import static io.kestra.extensions.rbac.management.ClientCredentialManagement.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClientCredentialManagementTest {
    private final Host host=mock(Host.class);
    private final Access access=mock(Access.class);
    private final Target target=new Target("group","ordinary");
    private final ClientCredentialManagement service=new ClientCredentialManagement(host,access);

    @Test void readAndManageDoNotImplyEachOtherAndRotationReturnsOnlyReceipt() {
        var state=new State("ordinary",ClientType.CONFIDENTIAL,true,"",4,RotationMode.ONLINE);
        when(host.state(target)).thenReturn(state);
        doThrow(new SecurityException()).when(access).authorize(target,Action.READ);
        assertThrows(SecurityException.class,()->service.state(target));verifyNoInteractions(host);
        var receipt=new Rotation("ordinary",5,"test-secret-only");
        when(host.rotate(target,4)).thenReturn(receipt);
        assertSame(receipt,service.rotate(target,4));
        assertFalse(receipt.toString().contains("test-secret-only"));
        verify(access).authorize(target,Action.MANAGE);
    }

    @Test void readOnlyAndLostManagementNeverReachHostMutation() {
        when(host.state(target)).thenReturn(new State("ordinary",ClientType.CONFIDENTIAL,true,"",4,RotationMode.ONLINE));
        assertEquals(4,service.state(target).credentialRevision());
        doThrow(new SecurityException()).when(access).authorize(target,Action.MANAGE);
        assertThrows(SecurityException.class,()->service.rotate(target,4));
        verify(host,never()).rotate(any(),anyLong());
    }

    @Test void publicMaintenanceAndStaleRevisionRejectWithoutRotation() {
        when(host.state(target)).thenReturn(new State("ordinary",ClientType.PUBLIC,false,"",4,RotationMode.NONE));
        rejected(400,"public_client_no_secret",4);
        when(host.state(target)).thenReturn(new State("ordinary",ClientType.CONFIDENTIAL,true,"abc••xyz",4,RotationMode.DEPLOYMENT_MAINTENANCE));
        rejected(409,"maintenance_required",4);
        when(host.state(target)).thenReturn(new State("ordinary",ClientType.CONFIDENTIAL,true,"",4,RotationMode.ONLINE));
        rejected(412,"revision_conflict",3);
        assertThrows(IllegalArgumentException.class,()->service.rotate(target,-1));
        verify(host,never()).rotate(any(),anyLong());
    }

    @Test void mismatchedHostTargetAndReceiptAreNeverReturned() {
        when(host.state(target)).thenReturn(new State("other",ClientType.PUBLIC,false,"",4,RotationMode.NONE));
        assertThrows(IllegalStateException.class,()->service.state(target));
        when(host.state(target)).thenReturn(new State("ordinary",ClientType.CONFIDENTIAL,true,"",4,RotationMode.ONLINE));
        when(host.rotate(target,4)).thenReturn(new Rotation("other",5,"test-secret-only"));
        assertThrows(IllegalStateException.class,()->service.rotate(target,4));
        when(host.rotate(target,4)).thenReturn(new Rotation("ordinary",4,"test-secret-only"));
        assertThrows(IllegalStateException.class,()->service.rotate(target,4));
    }

    @Test void historicalCredentialWithoutHintRemainsReadableWithoutInventedHint() {
        when(host.state(target)).thenReturn(new State("ordinary",ClientType.CONFIDENTIAL,true,null,4,RotationMode.ONLINE));
        assertNull(service.state(target).maskedHint());
        verify(host,never()).rotate(any(),anyLong());
    }

    @Test void unavailableParticipantAndTransactionalFailurePropagateWithoutRetryOrReceipt() {
        when(host.state(target)).thenReturn(new State("ordinary",ClientType.CONFIDENTIAL,true,null,4,RotationMode.ONLINE));
        var missing=new ApplicationDirectoryAccess.DirectoryFailure(503,"lifecycle_unavailable");
        when(host.rotate(target,4)).thenThrow(missing);
        assertSame(missing,assertThrows(ApplicationDirectoryAccess.DirectoryFailure.class,()->service.rotate(target,4)));
        verify(host,times(1)).rotate(target,4);
        var aborted=new IllegalStateException("internal transaction aborted");
        doThrow(aborted).when(host).rotate(target,4);
        assertSame(aborted,assertThrows(IllegalStateException.class,()->service.rotate(target,4)));
        verify(host,times(2)).rotate(target,4);
    }

    @Test void wireReceiptsContainOnlyStableIdentityRevisionAndOneTimeSecret()throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var receipt=new Rotation("ordinary",5,"test-secret-only");
        var node=mapper.valueToTree(receipt);
        var fields=new java.util.HashSet<String>();node.fieldNames().forEachRemaining(fields::add);
        assertEquals(java.util.Set.of("clientId","credentialRevision","clientSecret"),fields);
        assertFalse(mapper.valueToTree(new State("ordinary",ClientType.CONFIDENTIAL,true,null,4,RotationMode.ONLINE)).has("clientSecret"));
        assertFalse(receipt.toString().contains(receipt.clientSecret()));
    }

    private void rejected(int status,String code,long revision) {
        var failure=assertThrows(ApplicationDirectoryAccess.DirectoryFailure.class,()->service.rotate(target,revision));
        assertEquals(status,failure.status());assertEquals(code,failure.code());
    }
}
