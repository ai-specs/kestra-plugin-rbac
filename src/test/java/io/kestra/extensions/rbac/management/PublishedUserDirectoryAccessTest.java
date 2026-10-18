package io.kestra.extensions.rbac.management;

import io.kestra.webserver.oidc.UserDirectoryV1;
import io.kestra.webserver.oidc.ApplicationDirectoryV1;
import io.micronaut.context.BeanProvider;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublishedUserDirectoryAccessTest {
    @SuppressWarnings("unchecked")
    private final BeanProvider<UserDirectoryV1> provider = mock(BeanProvider.class);
    private final UserDirectoryV1 directory = mock(UserDirectoryV1.class);
    private final PublishedUserDirectoryAccess access = new PublishedUserDirectoryAccess(provider);
    private final UserDirectoryV1.User hostUser = new UserDirectoryV1.User("exact/用户", "Name", "user@example.test", false, 37);
    private final UserDirectoryAccess.User expected = new UserDirectoryAccess.User("exact/用户", "Name", "user@example.test", false, 37);

    private void unique() {
        when(provider.isUnique()).thenReturn(true);
        when(provider.get()).thenReturn(directory);
    }

    @Test void copiesEveryUserAndPageFieldAndPreservesMissingIdentity() {
        unique();
        when(directory.list(3, 7)).thenReturn(new UserDirectoryV1.Page(List.of(hostUser), 95, 3, 7));
        when(directory.find(hostUser.subject())).thenReturn(Optional.of(hostUser));
        when(directory.find("missing")).thenReturn(Optional.empty());
        var page = access.list(3, 7);
        assertEquals(List.of(expected), page.items());
        assertEquals(95, page.total());
        assertEquals(3, page.page());
        assertEquals(7, page.size());
        assertThrows(UnsupportedOperationException.class, () -> page.items().clear());
        assertEquals(Optional.of(expected), access.find(hostUser.subject()));
        assertTrue(access.find("missing").isEmpty());
    }

    @Test void forwardsMutationArgumentsAndCopiesResults() {
        unique();
        when(directory.create("login", "Name", "mail", "test-password")).thenReturn(hostUser);
        when(directory.update("subject", 11, "Changed", "new-mail")).thenReturn(hostUser);
        when(directory.setActive("subject", 12, false)).thenReturn(hostUser);
        when(directory.resetPassword("subject", 13, "new-test-password")).thenReturn(hostUser);
        assertEquals(expected, access.create("login", "Name", "mail", "test-password"));
        assertEquals(expected, access.update("subject", 11, "Changed", "new-mail"));
        assertEquals(expected, access.setActive("subject", 12, false));
        assertEquals(expected, access.resetPassword("subject", 13, "new-test-password"));
        verify(directory).create("login", "Name", "mail", "test-password");
        verify(directory).update("subject", 11, "Changed", "new-mail");
        verify(directory).setActive("subject", 12, false);
        verify(directory).resetPassword("subject", 13, "new-test-password");
    }

    @Test void mapsEveryPublishedFailureWithoutRetainingCause() {
        unique();
        for (var failure : UserDirectoryV1.Failure.values()) {
            doThrow(new UserDirectoryV1.DirectoryException(failure)).when(directory).find("subject");
            var result = assertThrows(UserDirectoryAccess.DirectoryFailure.class, () -> access.find("subject"));
            assertEquals(UserDirectoryAccess.Failure.valueOf(failure.name()), result.failure());
            assertNull(result.getCause());
        }
    }

    @Test void missingOrAmbiguousProviderFailsClosedWithoutCallingDirectory() {
        when(provider.isUnique()).thenReturn(false);
        var result = assertThrows(UserDirectoryAccess.DirectoryFailure.class, () -> access.list(1, 20));
        assertEquals(UserDirectoryAccess.Failure.UNAVAILABLE, result.failure());
        verify(provider, never()).get();
        verifyNoInteractions(directory);
    }

    @Test void unexpectedProviderAndDirectoryExceptionsDoNotExposeCredentials() {
        unique();
        when(directory.resetPassword("subject", 1, "secret")).thenThrow(new IllegalStateException("secret credential SQL"));
        var result = assertThrows(UserDirectoryAccess.DirectoryFailure.class, () -> access.resetPassword("subject", 1, "secret"));
        assertEquals(UserDirectoryAccess.Failure.UNAVAILABLE, result.failure());
        assertEquals("Directory operation failed", result.getMessage());
        assertNull(result.getCause());
        when(provider.get()).thenThrow(new IllegalStateException("secret"));
        assertEquals(UserDirectoryAccess.Failure.UNAVAILABLE,
            assertThrows(UserDirectoryAccess.DirectoryFailure.class, () -> access.find("subject")).failure());
    }
    @Test void hostLastAdministratorGuardRemainsAConflictInsteadOfUnavailable() {
        unique();
        when(directory.setActive("last-admin", 3, false)).thenThrow(
            new ApplicationDirectoryV1.DirectoryException(ApplicationDirectoryV1.Failure.LAST_ADMIN));
        assertEquals(UserDirectoryAccess.Failure.LAST_ADMIN,
            assertThrows(UserDirectoryAccess.DirectoryFailure.class,()->access.setActive("last-admin",3,false)).failure());
        when(directory.resetPassword("denied", 4, "password")).thenThrow(
            new ApplicationDirectoryV1.DirectoryException(ApplicationDirectoryV1.Failure.FORBIDDEN));
        assertEquals(UserDirectoryAccess.Failure.FORBIDDEN,
            assertThrows(UserDirectoryAccess.DirectoryFailure.class,()->access.resetPassword("denied",4,"password")).failure());
    }

}
