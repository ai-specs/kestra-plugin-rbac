package io.kestra.extensions.rbac.store;

import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ApplicationScopeDeclarationCommandTest {
    @Test
    void validatesExplicitScopeAndPermissionKeys() throws Exception {
        var file = Files.createTempFile("application-declarations", ".json");
        try {
            Files.writeString(file, """
                {"applicationGroupId":"kestra","clientId":"kestra-ui","scopes":[
                {"key":"reader","permissions":[{"key":"view-flows","name":"View flows"}]}]}
                """);
            assertDoesNotThrow(() -> ApplicationScopeDeclarationCommand.run(new String[]{"validate", file.toString()}));
            Files.writeString(file, """
                {"applicationGroupId":"kestra","clientId":"kestra-ui","scopes":[
                {"key":"reader","permissions":[{"key":"view-flows","name":"View flows"},
                {"key":"view-flows","name":"Duplicate"}]}]}
                """);
            assertThrows(IllegalArgumentException.class,
                () -> ApplicationScopeDeclarationCommand.run(new String[]{"validate", file.toString()}));
        } finally { Files.deleteIfExists(file); }
    }

    @Test
    void rejectsDuplicateJsonFieldsAndInvalidUtf8() throws Exception {
        var file = Files.createTempFile("application-declarations", ".json");
        try {
            Files.writeString(file, """
                {"applicationGroupId":"kestra","applicationGroupId":"other","clientId":"kestra-ui","scopes":[]}
                """);
            assertThrows(Exception.class,
                () -> ApplicationScopeDeclarationCommand.run(new String[]{"validate", file.toString()}));
            Files.write(file, new byte[]{(byte) 0xc3, (byte) 0x28});
            assertThrows(Exception.class,
                () -> ApplicationScopeDeclarationCommand.run(new String[]{"validate", file.toString()}));
        } finally { Files.deleteIfExists(file); }
    }
}
