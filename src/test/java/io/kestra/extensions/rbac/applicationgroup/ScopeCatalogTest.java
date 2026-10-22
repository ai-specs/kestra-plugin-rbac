package io.kestra.extensions.rbac.applicationgroup;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ScopeCatalogTest {
    private final ApplicationGroupPolicy.ApplicationGroup policy = new ApplicationGroupPolicy.ApplicationGroup("https://issuer.example", Set.of("ui"), true,
        Map.of("reader", new ApplicationGroupPolicy.ScopeDefinition("Runtime policy", true, List.of(
            new ApplicationGroupPolicy.Grant("NAMESPACE", "main", "allowed", false, "FLOW", Set.of("READ"), Set.of(), true)))));

    private ApplicationGroupAuthorization.Directory directory(String name, String description) {
        return new ApplicationGroupAuthorization.Directory(new ApplicationGroupAuthorization.ScopeCatalog("https://issuer.example",
            Set.of("ui"), true, 1, Map.of("reader", new ApplicationGroupAuthorization.DirectoryScope(name, description))),
            "group", Set.of("openid"));
    }

    private boolean allowed(ApplicationGroupAuthorization.Directory directory, String namespace, String action, Set<String> scopes) {
        var authorization = new ApplicationGroupAuthorization(new ApplicationGroupPolicy(2, Map.of("group", policy)));
        var identity = new ApplicationGroupAuthorization.Identity("subject", "https://issuer.example", "ui", "group", 1, scopes, Set.of());
        return authorization.authorize(identity,
            new ApplicationGroupAuthorization.Request("main", namespace, "FLOW", action, "flow", false), directory).allowed();
    }

    @Test void catalogNamesAndDescriptionsDoNotGrantResourcePermissions() {
        var original = directory("Reader", "Read workflows");
        var renamed = directory("Administrator", "May update everything");
        for (var catalog : List.of(original, renamed)) {
            assertTrue(allowed(catalog, "allowed", "READ", Set.of("reader")));
            assertFalse(allowed(catalog, "allowed", "UPDATE", Set.of("reader")));
            assertFalse(allowed(catalog, "outside", "READ", Set.of("reader")));
        }
    }

    @Test void unknownScopeRejectsTheWholeRequest() {
        assertFalse(allowed(directory("Reader", ""), "allowed", "READ", Set.of("reader", "unknown")));
    }

    @Test void unusedApplicationMappingsDoNotBlockPolicyEditsOrAuthorizeUnknownScopes() {
        var definitions = new java.util.HashMap<>(policy.scopes());
        definitions.put("removed",new ApplicationGroupPolicy.ScopeDefinition("Retained application mapping",true,List.of(
            new ApplicationGroupPolicy.Grant("NAMESPACE","main","outside",false,"FLOW",Set.of("UPDATE"),Set.of(),true))));
        var retained = new ApplicationGroupPolicy.ApplicationGroup(policy.issuer(),policy.audiences(),policy.enabled(),definitions);
        var candidate = new ApplicationGroupPolicy(2,Map.of("group",retained));
        var catalog = directory("Reader","");
        assertDoesNotThrow(() -> ApplicationGroupPolicyBoundary.validate(candidate,Map.of("group",catalog)));
        var authorization = new ApplicationGroupAuthorization(candidate);
        var known = new ApplicationGroupAuthorization.Identity("subject",policy.issuer(),"ui","group",1,Set.of("reader"),Set.of());
        var unknown = new ApplicationGroupAuthorization.Identity("subject",policy.issuer(),"ui","group",1,Set.of("removed"),Set.of());
        var mixed = new ApplicationGroupAuthorization.Identity("subject",policy.issuer(),"ui","group",1,Set.of("reader","removed"),Set.of());
        var read = new ApplicationGroupAuthorization.Request("main","allowed","FLOW","READ","flow",false);
        var update = new ApplicationGroupAuthorization.Request("main","outside","FLOW","UPDATE","flow",false);
        assertTrue(authorization.authorize(known,read,catalog).allowed());
        assertFalse(authorization.authorize(unknown,update,catalog).allowed());
        assertFalse(authorization.authorize(mixed,read,catalog).allowed());
    }

    @Test void freshIdentityUsesExistingPolicyAfterDirectoryMetadataChanges() {
        var parser = new ApplicationGroupPolicyParser();
        var document = parser.canonicalJson(new ApplicationGroupPolicy(2, Map.of("group", policy)));
        assertFalse(document.contains("directoryRevision"));
        assertTrue(document.contains("\"applicationGroups\""));
        assertThrows(IllegalArgumentException.class,()->parser.parse(document.replace("\"applicationGroups\"","\"applicationProjects\"").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(document.replace("\"scopes\":",
            "\"directoryRevision\":1,\"scopes\":").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var authorization = new ApplicationGroupAuthorization(new ApplicationGroupPolicy(2, Map.of("group", policy)));
        var catalog = new ApplicationGroupAuthorization.Directory(new ApplicationGroupAuthorization.ScopeCatalog("https://issuer.example",
            Set.of("ui"), true, 2, Map.of("reader", new ApplicationGroupAuthorization.DirectoryScope("Renamed reader", "Changed"))),
            "group", Set.of("openid"));
        var freshIdentity = new ApplicationGroupAuthorization.Identity("subject", "https://issuer.example", "ui", "group", 2,
            Set.of("reader"), Set.of());
        var staleIdentity = new ApplicationGroupAuthorization.Identity("subject", "https://issuer.example", "ui", "group", 1,
            Set.of("reader"), Set.of());
        var request = new ApplicationGroupAuthorization.Request("main", "allowed", "FLOW", "READ", "flow", false);
        assertTrue(authorization.authorize(freshIdentity, request, catalog).allowed());
        assertFalse(authorization.authorize(staleIdentity, request, catalog).allowed());
        assertFalse(authorization.authorize(freshIdentity,
            new ApplicationGroupAuthorization.Request("main", "outside", "FLOW", "READ", "flow", false), catalog).allowed());
    }

    @Test void catalogAcceptsRolesWithoutResourceGrantsWhileRuntimePolicyRejectsEmptyGrants() {
        assertNotNull(directory("Reader", ""));
        assertThrows(IllegalArgumentException.class, () -> new ApplicationGroupPolicy.ScopeDefinition("", true, List.of(), List.of()));
    }

    @Test void catalogValidatesKeysAndRetainsAnImmutableSnapshot() {
        var scopes = new java.util.HashMap<String, ApplicationGroupAuthorization.DirectoryScope>();
        scopes.put("reader", new ApplicationGroupAuthorization.DirectoryScope("Reader", ""));
        var catalog = new ApplicationGroupAuthorization.ScopeCatalog("https://issuer.example", Set.of("ui"), true, 1, scopes);
        scopes.clear();
        assertEquals(Set.of("reader"), catalog.scopes().keySet());
        assertThrows(UnsupportedOperationException.class, () -> catalog.scopes().clear());
        assertThrows(IllegalArgumentException.class, () -> new ApplicationGroupAuthorization.ScopeCatalog("https://issuer.example",
            Set.of("ui"), true, 1, Map.of("BAD", new ApplicationGroupAuthorization.DirectoryScope("Reader", ""))));
        assertThrows(IllegalArgumentException.class, () -> new ApplicationGroupAuthorization.DirectoryScope(" ", ""));
    }
}
