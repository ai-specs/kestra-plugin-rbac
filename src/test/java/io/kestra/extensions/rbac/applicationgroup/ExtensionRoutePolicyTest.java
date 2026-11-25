package io.kestra.extensions.rbac.applicationgroup;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExtensionRoutePolicyTest {
    @Test void acceptsOnlyNamespaceExecuteAndExactId() {
        var grant = new ApplicationGroupPolicy.Grant("NAMESPACE", "main", "company", false,
            "EXTENSION_ROUTE", Set.of("EXECUTE"), Set.of("amis-apps"), false);
        assertEquals(Set.of("amis-apps"), grant.ids());
        var multiple = new ApplicationGroupPolicy.Grant("NAMESPACE", "main", "company", true,
            "EXTENSION_ROUTE", Set.of("EXECUTE"), Set.of("amis-apps", "other-capability"), false);
        assertEquals(2, multiple.ids().size());
        assertThrows(IllegalArgumentException.class, () -> new ApplicationGroupPolicy.Grant("TENANT", "main", null, false,
            "EXTENSION_ROUTE", Set.of("EXECUTE"), Set.of("amis-apps"), false));
        assertThrows(IllegalArgumentException.class, () -> new ApplicationGroupPolicy.Grant("NAMESPACE", "main", "company", false,
            "EXTENSION_ROUTE", Set.of("READ"), Set.of("amis-apps"), false));
        assertThrows(IllegalArgumentException.class, () -> new ApplicationGroupPolicy.Grant("NAMESPACE", "main", "company", false,
            "EXTENSION_ROUTE", Set.of("EXECUTE"), Set.of("bad.id"), false));
    }

    @Test void doesNotEnterInitialAdministratorPlan() {
        var plan = InitialApplicationGroupPlan.create("https://issuer", "00000000-0000-0000-0000-000000000001", "client", "user");
        assertTrue(plan.applicationGroups().stream().flatMap(entry -> entry.applications().stream())
            .flatMap(application -> application.permissions().stream())
            .flatMap(permission -> permission.grants().stream())
            .noneMatch(grant -> grant.resource().equals("EXTENSION_ROUTE")));
    }
}
