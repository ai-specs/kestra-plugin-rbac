package io.kestra.extensions.rbac.applicationgroup;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.*;
import static org.junit.jupiter.api.Assertions.*;

class ApplicationPermissionTest {
    private static final Grant READ=new Grant("NAMESPACE","main","allowed",false,"FLOW",Set.of("READ"),Set.of(),true);
    private static final Grant LIST=new Grant("NAMESPACE","main","allowed",false,"FLOW",Set.of("LIST"),Set.of(),true);
    private ApplicationPermission permission(String key,Set<String> scopes,List<Grant> grants,boolean enabled) {
        return new ApplicationPermission(key,key,"",scopes,grants,List.of(),"",enabled,1);
    }

    @Test void manyToManySupportIsUnionAndEligibilityIsIntersection() {
        var first=permission("first",Set.of("one","two"),List.of(),true);
        var second=permission("second",Set.of("two"),List.of(),false);
        assertEquals(Set.of("one","two"),ApplicationPermission.supportedScopes(List.of(first,second)));
        assertEquals(Set.of("two"),ApplicationPermission.eligibleScopes(List.of(first,second),Set.of("two","outside")));
        var unlinked=permission("first",Set.of("one"),List.of(),true);
        assertFalse(ApplicationPermission.supportChanged(List.of(first,second),List.of(unlinked,second)));
        assertTrue(ApplicationPermission.supportChanged(List.of(unlinked,second),List.of(unlinked)));
        assertTrue(ApplicationPermission.supportedScopes(List.of(permission("empty",Set.of(),List.of(READ),true))).isEmpty());
    }

    @Test void projectionCombinesOnlyWithinEachScopeAndSkipsEmptyAndDisabled() {
        var reader=permission("reader",Set.of("one"),List.of(READ),true);
        var lister=permission("lister",Set.of("one","two"),List.of(LIST),true);
        var disabled=permission("disabled",Set.of("two"),List.of(READ),false);
        var empty=permission("empty",Set.of("empty"),List.of(),true);
        var projected=ApplicationPermission.project(List.of(reader,lister,disabled,empty),ApplicationPermission.Kind.KESTRA_POLICY);
        assertEquals(Set.of(READ,LIST),Set.copyOf(projected.get("one").grants()));
        assertEquals(List.of(LIST),projected.get("two").grants());assertFalse(projected.containsKey("empty"));
        assertTrue(ApplicationPermission.supportedScopes(List.of(disabled)).contains("two"));
    }

    @Test void metadataAndPolicyVersionsAreSeparateFromSupportEpoch() {
        var original=permission("reader",Set.of("one"),List.of(READ),true);
        var metadata=original.replace("renamed","description",original.scopeKeys(),original.grants(),List.of(),"",true);
        assertFalse(ApplicationPermission.supportChanged(List.of(original),List.of(metadata)));
        assertFalse(ApplicationPermission.policyChanged(List.of(original),List.of(metadata),ApplicationPermission.Kind.KESTRA_POLICY));
        var grants=original.replace(original.name(),"",original.scopeKeys(),List.of(LIST),List.of(),"",true);
        assertFalse(ApplicationPermission.supportChanged(List.of(original),List.of(grants)));
        assertTrue(ApplicationPermission.policyChanged(List.of(original),List.of(grants),ApplicationPermission.Kind.KESTRA_POLICY));
        var disabled=original.replace(original.name(),"",original.scopeKeys(),original.grants(),List.of(),"",false);
        assertTrue(ApplicationPermission.policyChanged(List.of(original),List.of(disabled),ApplicationPermission.Kind.KESTRA_POLICY));
    }

    @Test void externalAdminTextCannotGrantAndUnknownScopeFailsClosed() {
        var external=new ApplicationPermission("external","Administrator","",Set.of("one"),List.of(),List.of(),"all resources admin",true,1);
        assertDoesNotThrow(()->external.validate(ApplicationPermission.Kind.EXTERNAL_DECLARATION,Set.of("one")));
        assertTrue(ApplicationPermission.project(List.of(external),ApplicationPermission.Kind.EXTERNAL_DECLARATION).isEmpty());
        assertThrows(IllegalArgumentException.class,()->external.validate(ApplicationPermission.Kind.KESTRA_POLICY,Set.of("one")));
        assertThrows(IllegalArgumentException.class,()->permission("typed",Set.of("one"),List.of(READ),true).validate(ApplicationPermission.Kind.EXTERNAL_DECLARATION,Set.of("one")));
        assertThrows(IllegalArgumentException.class,()->external.validate(ApplicationPermission.Kind.EXTERNAL_DECLARATION,Set.of("other")));
    }

    @Test void unicodeLimitsCountCodePointsAndPermissionKeysHave128Characters() {
        String emoji="😀";
        assertDoesNotThrow(()->new ApplicationGroupDirectory.Group("main",emoji.repeat(256),emoji.repeat(4096),true,1,1));
        assertThrows(IllegalArgumentException.class,()->new ApplicationGroupDirectory.Group("main",emoji.repeat(257),"",true,1,1));
        assertThrows(IllegalArgumentException.class,()->new ApplicationGroupDirectory.Scope("scope","name",emoji.repeat(4097),1));
        assertDoesNotThrow(()->permission("a".repeat(128),Set.of(),List.of(),true));
        assertThrows(IllegalArgumentException.class,()->permission("a".repeat(129),Set.of(),List.of(),true));
        assertThrows(IllegalArgumentException.class,()->permission("valid",Set.of("s".repeat(65)),List.of(),true));
        assertTrue(ApplicationGroupDirectory.compareCodePoints("\uE000","\uD800\uDC00")<0);
    }
}
