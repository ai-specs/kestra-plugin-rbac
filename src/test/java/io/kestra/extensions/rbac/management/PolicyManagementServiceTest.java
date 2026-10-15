package io.kestra.extensions.rbac.management;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import io.kestra.extensions.rbac.store.JdbcPolicyStore;
import io.kestra.extensions.rbac.store.PolicyDocument;
import io.kestra.extensions.rbac.store.PolicyDocumentValidator;
import io.kestra.webserver.rbac.RbacAuthorizer;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PolicyManagementServiceTest {
    private static RbacAuthorizer.Principal principal(String subject) {
        return new RbacAuthorizer.Principal(subject, List.of("admin"), null, null, List.of());
    }
    private record Setup(PolicyManagementService service, JdbcPolicyStore store) {}
    private Setup setup() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:management-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        var validator = new PolicyDocumentValidator();
        var store = new JdbcPolicyStore(source, validator);
        store.initializeSchema();
        store.importInitial("{\"schemaVersion\":1,\"roles\":[],\"subjects\":{},\"roleMapping\":{}}".getBytes(StandardCharsets.UTF_8), "owner");
        return new Setup(new PolicyManagementService(store, validator, Set.of("owner")), store);
    }
    private PolicyDocument.Role role(String description) {
        return new PolicyDocument.Role(new PolicyDocument.RoleId("reader"), "Reader", description, List.of());
    }
    @Test void presentedAdminRoleDoesNotGrantManagementAndDenialHasNoWrites() {
        var setup = setup();
        assertThrows(PolicyManagementService.Denied.class, () -> setup.service().read(principal("other")));
        assertThrows(PolicyManagementService.Denied.class, () -> setup.service().createRole(principal("other"), 1, role("unauthorized")));
        assertEquals(1, setup.store().readCurrent().revision());
        assertTrue(setup.store().readCurrent().document().roles().isEmpty());
        assertTrue(setup.service().protectedSubject("owner"));
    }
    @Test void referencedRolesCannotBeDeletedAndBindingsAreSavedTogether() {
        var setup = setup();
        var service = setup.service();
        var owner = principal("owner");
        assertEquals(2, service.createRole(owner, 1, role("initial")).revision());
        assertEquals(3, service.bindSubject(owner, 2, "user", List.of("reader")).revision());
        assertThrows(PolicyManagementService.Conflict.class, () -> service.deleteRole(owner, 3, "reader"));
        assertEquals(3, setup.store().readCurrent().revision());
        assertThrows(IllegalArgumentException.class, () -> service.mapTrustedRole(owner, 3, "trusted", List.of("missing")));
        assertEquals(3, setup.store().readCurrent().revision());
        service.bindSubject(owner, 3, "user", List.of());
        assertEquals(5, service.deleteRole(owner, 4, "reader").revision());
        assertTrue(service.read(owner).document().roles().isEmpty());
    }
    @Test void roleEditsPersistExplicitFieldsAndStaleSavesCannotOverwrite() {
        var setup = setup();
        var owner = principal("owner");
        setup.service().createRole(owner, 1, role("initial"));
        var updated = setup.service().replaceRole(owner, 2, "reader", role("explicitly changed"));
        assertEquals("explicitly changed", updated.document().roles().getFirst().description());
        assertThrows(JdbcPolicyStore.RevisionConflictException.class, () -> setup.service().replaceRole(owner, 2, "reader", role("stale")));
        assertEquals("explicitly changed", setup.store().readCurrent().document().roles().getFirst().description());
        assertThrows(PolicyManagementService.Conflict.class, () -> setup.service().createRole(owner, 3, role("duplicate")));
        assertEquals(3, setup.store().readCurrent().revision());
    }
}
