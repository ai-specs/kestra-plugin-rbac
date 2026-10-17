package io.kestra.extensions.rbac.store;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import io.kestra.extensions.rbac.RbacConfiguration;
import io.kestra.extensions.rbac.Principal;
import io.kestra.extensions.rbac.AuthorizationRequest;
import io.kestra.webserver.rbac.RbacAuthorizer.Action;
import io.kestra.webserver.rbac.RbacAuthorizer.Kind;
import io.kestra.webserver.rbac.RbacAuthorizer.Scope;
import org.junit.jupiter.api.Test;

import static io.kestra.extensions.rbac.store.PolicyDocument.*;
import static org.junit.jupiter.api.Assertions.*;

class PolicyDocumentValidatorTest {
    final PolicyDocumentValidator validator = new PolicyDocumentValidator();

    static Grant grant() {
        return new Grant(Scope.NAMESPACE, "main", "team.a", false, Kind.FLOW, List.of(Action.READ), List.of(), true);
    }

    static PolicyDocument policy(String description) {
        return new PolicyDocument(1, List.of(new Role(new RoleId("role-1"), "读者", description, List.of(grant()))),
            Map.of("alice", List.of(new RoleId("role-1"))), Map.of("trusted-reader", List.of(new RoleId("role-1"))));
    }

    static byte[] bytes(String json) { return json.getBytes(StandardCharsets.UTF_8); }

    static byte[] json(String description) {
        return bytes(new PolicyDocumentValidator().validate(policy(description)).canonicalJson());
    }

    @Test
    void shouldCanonicalizeAndCompileStableRoleIdentifiersWithoutAdminAuthority() {
        var validated = validator.validate(policy("中文说明"));
        var reparsed = validator.parse(bytes(validated.canonicalJson()));
        assertEquals(validated.canonicalJson(), reparsed.canonicalJson());
        var request = new AuthorizationRequest("main", "team.a", Kind.FLOW, Action.READ, "flow", Scope.NAMESPACE);
        assertTrue(reparsed.engine().authorize(new Principal("alice", Set.of()), request));
        assertTrue(reparsed.engine().authorize(new Principal("other", Set.of("trusted-reader")), request));
        assertFalse(reparsed.engine().authorize(new Principal("other", Set.of("role-1")), request));
        assertFalse(reparsed.engine().isInstanceAdmin(new Principal("alice", Set.of("admin"))));
        var renamed = new PolicyDocument(1, List.of(new Role(new RoleId("role-1"), "新名称", "", List.of(grant()))), policy("").subjects(), Map.of());
        assertTrue(validator.validate(renamed).engine().authorize(new Principal("alice", Set.of()), request));
        assertThrows(UnsupportedOperationException.class, () -> reparsed.document().roles().clear());
        assertThrows(UnsupportedOperationException.class, () -> reparsed.document().subjects().get("alice").clear());
    }

    @Test
    void shouldRejectAdministratorFieldsUnknownFieldsAndPrivilegedGrants() {
        String ordinary = validator.validate(policy("")).canonicalJson();
        for (String field : List.of("instance-admin-roles", "instanceAdminRoles", "managementAdminSubjects", "administrator", "unknown")) {
            assertThrows(IllegalArgumentException.class, () -> validator.parse(bytes(ordinary.replaceFirst("\\{", "{\"" + field + "\":[],"))));
        }
        for (Grant privileged : List.of(
            new Grant(Scope.INSTANCE, null, null, false, Kind.SYSTEM, List.of(Action.EXECUTE), List.of(), true),
            new Grant(Scope.TENANT, "main", null, false, Kind.SYSTEM, List.of(Action.READ), List.of(), true))) {
            var document = new PolicyDocument(1, List.of(new Role(new RoleId("admin"), "admin", "", List.of(privileged))), Map.of(), Map.of());
            assertThrows(IllegalArgumentException.class, () -> validator.validate(document));
        }
    }

    @Test
    void shouldRejectDuplicateFieldsCoercionsUnknownVersionsAndMalformedUtf8() {
        for (String json : List.of(
            "{\"schemaVersion\":1,\"schemaVersion\":1,\"roles\":[],\"subjects\":{},\"roleMapping\":{}}",
            "{\"schemaVersion\":\"1\",\"roles\":[],\"subjects\":{},\"roleMapping\":{}}",
            "{\"schemaVersion\":2,\"roles\":[],\"subjects\":{},\"roleMapping\":{}}",
            "{\"schemaVersion\":1,\"roles\":[],\"subjects\":{},\"roleMapping\":{}} {}",
            "{\"schemaVersion\":1,\"roles\":[],\"subjects\":{\"alice\":[true]},\"roleMapping\":{}}")) {
            assertThrows(IllegalArgumentException.class, () -> validator.parse(bytes(json)));
        }
        assertThrows(IllegalArgumentException.class, () -> validator.parse(new byte[]{(byte) 0xc3, 0x28}));
        assertThrows(IllegalArgumentException.class, () -> validator.parse(bytes(validator.validate(policy("")).canonicalJson().replace("\"allIds\":true", "\"allIds\":\"true\""))));
    }

    @Test
    void shouldRejectInvalidReferencesNamesAndGrantCombinationsUsingExistingCompiler() {
        assertThrows(IllegalArgumentException.class, () -> validator.validate(new PolicyDocument(1, policy("").roles(), Map.of("alice", List.of(new RoleId("missing"))), Map.of())));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(new PolicyDocument(1, List.of(policy("").roles().getFirst(), policy("").roles().getFirst()), Map.of(), Map.of())));
        var invalid = new Grant(Scope.NAMESPACE, "main", "team.*", false, Kind.FLOW, List.of(Action.READ), List.of("flow"), true);
        assertThrows(IllegalArgumentException.class, () -> validator.validate(new PolicyDocument(1, List.of(new Role(new RoleId("reader"), "reader", "", List.of(invalid))), Map.of(), Map.of())));
    }

    @Test
    void shouldEnforceUtf8SizeAndEveryCollectionLimit() {
        assertThrows(IllegalArgumentException.class, () -> validator.parse(new byte[PolicyDocumentValidator.MAX_BYTES + 1]));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(policy("中".repeat(PolicyDocumentValidator.MAX_BYTES / 3 + 1))));
        var roles = IntStream.range(0, 1001).mapToObj(index -> new Role(new RoleId("role-" + index), "角色" + index, "", List.<Grant>of())).toList();
        assertThrows(IllegalArgumentException.class, () -> validator.validate(new PolicyDocument(1, roles, Map.of(), Map.of())));
        var tooManyGrants = new Role(new RoleId("reader"), "reader", "", java.util.Collections.nCopies(10001, grant()));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(new PolicyDocument(1, List.of(tooManyGrants), Map.of(), Map.of())));
        var bindings = new java.util.HashMap<String, List<RoleId>>();
        IntStream.range(0, 10001).forEach(index -> bindings.put("subject-" + index, List.of(new RoleId("role-1"))));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(new PolicyDocument(1, policy("").roles(), bindings, Map.of())));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(new PolicyDocument(1, policy("").roles(), Map.of(), bindings)));
        var identifiers = IntStream.range(0, 1001).mapToObj(index -> "flow-" + index).toList();
        var invalid = new Grant(Scope.NAMESPACE, "main", "team.a", false, Kind.FLOW, List.of(Action.READ), identifiers, false);
        assertThrows(IllegalArgumentException.class, () -> validator.validate(new PolicyDocument(1, List.of(new Role(new RoleId("reader"), "reader", "", List.of(invalid))), Map.of(), Map.of())));
    }

    @Test
    void shouldKeepDeploymentTrustRootSeparateAndRejectMixedPolicySources() {
        var database = new PolicySourceSelection(PolicySourceSelection.Source.DATABASE, Set.of("deployment-owner"));
        var configuration = new RbacConfiguration();
        database.validateConfiguration(configuration);
        configuration.setSubjects(Map.of("alice", List.of("reader")));
        assertThrows(IllegalArgumentException.class, () -> database.validateConfiguration(configuration));
        assertThrows(IllegalArgumentException.class, () -> new PolicySourceSelection(PolicySourceSelection.Source.DATABASE, Set.of()));
        assertThrows(UnsupportedOperationException.class, () -> database.managementAdminSubjects().clear());
        var json = validator.validate(policy("")).canonicalJson();
        assertFalse(json.contains("deployment-owner"));
        assertThrows(IllegalArgumentException.class, () -> database.validateConfiguration(configuration));
    }
}
