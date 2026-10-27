package io.kestra.extensions.rbac.management;

import org.junit.jupiter.api.Test;
import static io.kestra.extensions.rbac.management.TemporaryAccountCommands.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TemporaryAccountCommandsTest {
    private final Host host=mock(Host.class);
    private final Access access=mock(Access.class);
    private final TemporaryAccountCommands service=new TemporaryAccountCommands(host,access);

    @Test void manageOnlyCreationUsesNoReadAndOnlyReturnsTemporaryReceipt() {
        var input=new Create("target",null,null);
        var receipt=new Created("target",0,"temporary-test-only");
        when(host.createTemporary(input)).thenReturn(receipt);
        assertSame(receipt,service.create(input));
        assertEquals("",input.name());assertEquals("",input.email());
        verify(access).requireIdentityManage();
        verify(host).createTemporary(input);verifyNoMoreInteractions(host);
        assertFalse(receipt.toString().contains("temporary-test-only"));
    }

    @Test void deniedCreateAndDeleteNeverCallHost() {
        doThrow(new SecurityException()).when(access).requireIdentityManage();
        assertThrows(SecurityException.class,()->service.create(new Create("target","","")));
        assertThrows(SecurityException.class,()->service.delete("target",0));
        verifyNoInteractions(host);
    }

    @Test void deletePassesExactRevisionAndNeverRetriesFailure() {
        doThrow(new UserDirectoryAccess.DirectoryFailure(UserDirectoryAccess.Failure.CONFLICT)).when(host).delete("target",7);
        assertThrows(UserDirectoryAccess.DirectoryFailure.class,()->service.delete("target",7));
        verify(host).delete("target",7);verifyNoMoreInteractions(host);
        assertThrows(IllegalArgumentException.class,()->service.delete("target",-1));
    }

    @Test void failedCreateIsNotRetriedAndMismatchedSubjectIsRejected() {
        var input=new Create("target","","");
        when(host.createTemporary(input)).thenThrow(new IllegalStateException("test host failure"));
        assertThrows(IllegalStateException.class,()->service.create(input));
        verify(host).createTemporary(input);
        doReturn(new Created("other",1,"temporary-test-only")).when(host).createTemporary(input);
        assertThrows(IllegalStateException.class,()->service.create(input));
    }
}
