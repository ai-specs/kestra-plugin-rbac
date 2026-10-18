package io.kestra.extensions.rbac.management;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ManagementInputTest {
    private final ManagementInput input=new ManagementInput();
    private byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    @Test void strongRevisionRejectsWeakListsWhitespaceOverflowAndWildcards() {
        assertEquals(0,ManagementInput.revision("\"0\""));
        assertEquals(Long.MAX_VALUE,ManagementInput.revision("\"9223372036854775807\""));
        assertEquals(428,assertThrows(ManagementInput.Failure.class,()->ManagementInput.revision(null)).status);
        for (String value:new String[]{"*","W/\"1\"","\"1\",\"2\""," \"1\"","\"01\"","\"-1\"","\"9223372036854775808\""})
            assertEquals(400,assertThrows(ManagementInput.Failure.class,()->ManagementInput.revision(value)).status);
    }
    @Test void strictObjectRejectsDuplicateUnknownMissingNullTrailingAndBadUtf8() {
        assertEquals("x",ManagementInput.text(input.body(bytes("{\"id\":\"x\"}"),Set.of("id")),"id"));
        for (String value:new String[]{"{}","[]","null","{\"id\":null}","{\"id\":\"x\",\"extra\":1}","{\"id\":1,\"id\":2}","{\"id\":1} {}"})
            assertThrows(ManagementInput.Failure.class,()->input.body(bytes(value),Set.of("id")));
        assertThrows(ManagementInput.Failure.class,()->input.body(new byte[]{(byte)0xc3,0x28},Set.of("id")));
        assertThrows(ManagementInput.Failure.class,()->input.body(new byte[ManagementInput.MAX_BYTES+1],Set.of()));
    }
    @Test void opaquePathRoundTripsSlashUnicodeAndRejectsAliasesAndInvalidUtf8() {
        for (String value:new String[]{"project:read/a","客户端/one","a+?%#"}) {
            String key="~"+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes(value));
            assertEquals(value,ManagementInput.opaque(key));
        }
        for (String value:new String[]{"plain","~","~YQ=","~YR","~_w","~AA","~YQ/","~YQ%3D"})
            assertThrows(ManagementInput.Failure.class,()->ManagementInput.opaque(value));
    }
    @Test void pathsAndPrimitiveFieldsCannotBeCoerced() {
        var body=input.body(bytes("{\"id\":\"p\",\"active\":true}"),Set.of("id","active"));
        ManagementInput.same("p",body,"id");assertTrue(ManagementInput.bool(body,"active"));
        assertThrows(ManagementInput.Failure.class,()->ManagementInput.same("q",body,"id"));
        assertThrows(ManagementInput.Failure.class,()->ManagementInput.text(body,"active"));
        for (String id:new String[]{"../a","a/b","a%2fb","","a.b"}) assertThrows(ManagementInput.Failure.class,()->ManagementInput.project(id));
        assertThrows(ManagementInput.Failure.class,()->ManagementInput.page(0,100));
        assertThrows(ManagementInput.Failure.class,()->ManagementInput.page(1,101));
    }
}
