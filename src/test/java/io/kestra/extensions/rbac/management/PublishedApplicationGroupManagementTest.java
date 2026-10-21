package io.kestra.extensions.rbac.management;

import io.kestra.extensions.rbac.project.ProjectPolicy;
import io.kestra.extensions.rbac.store.ProjectPolicyStore;
import io.kestra.webserver.oidc.ApplicationGroupManagementV1;
import io.kestra.webserver.oidc.TrustedIdentityV1;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublishedApplicationGroupManagementTest {
    private final ProjectPolicyStore store = mock(ProjectPolicyStore.class);
    private final PublishedApplicationGroupManagement management = new PublishedApplicationGroupManagement(store);

    private void policy() {
        var manager = new ProjectPolicy.ScopeDefinition("Manager", true, List.of(), List.of(
            new ProjectPolicy.ManagementGrant(ProjectPolicy.ManagementDomain.IDENTITY,
                Set.of(ProjectPolicy.ManagementAction.READ, ProjectPolicy.ManagementAction.MANAGE))));
        var reader = new ProjectPolicy.ScopeDefinition("Reader", true, List.of(), List.of(
            new ProjectPolicy.ManagementGrant(ProjectPolicy.ManagementDomain.IDENTITY,
                Set.of(ProjectPolicy.ManagementAction.READ))));
        var document = new ProjectPolicy(2, Map.of("group", new ProjectPolicy.Project(
            "https://issuer.example", Set.of("ui"), true, 3, Map.of("manager", manager, "reader", reader))));
        when(store.readCurrent()).thenReturn(new ProjectPolicyStore.Snapshot(1, 0, "operator", Instant.EPOCH, document, "{}"));
    }

    private TrustedIdentityV1.Identity identity(Set<String> scopes, String issuer, String audience) {
        return new TrustedIdentityV1.Identity(1, issuer, "operator", "ui-client", Set.of(audience), "main",
            "group", Set.of("openid"), scopes, 1, 97, 42);
    }

    @Test
    void completeScopeProvidesReadAndManageWithoutSharedIntentRevision() {
        policy();
        assertTrue(management.authorize(identity(Set.of("manager"), "https://issuer.example", "ui"),
            "target", ApplicationGroupManagementV1.Action.MANAGE));
        assertTrue(management.authorize(identity(Set.of("reader"), "https://issuer.example", "ui"),
            "target", ApplicationGroupManagementV1.Action.READ));
        assertFalse(management.authorize(identity(Set.of("reader"), "https://issuer.example", "ui"),
            "target", ApplicationGroupManagementV1.Action.MANAGE));
        assertTrue(management.authorize(identity(Set.of("manager", "reader"), "https://issuer.example", "ui"),
            "target", ApplicationGroupManagementV1.Action.MANAGE));
    }

    @Test
    void unknownScopeWrongIssuerAndAudienceNeverGrantManagement() {
        policy();
        for (var scopes : List.of(Set.of("unknown"), Set.of("manager", "unknown")))
            assertFalse(management.authorize(identity(scopes, "https://issuer.example", "ui"),
                "target", ApplicationGroupManagementV1.Action.MANAGE));
        assertFalse(management.authorize(identity(Set.of("manager"), "https://other.example", "ui"),
            "target", ApplicationGroupManagementV1.Action.MANAGE));
        assertFalse(management.authorize(identity(Set.of("manager"), "https://issuer.example", "other"),
            "target", ApplicationGroupManagementV1.Action.MANAGE));
    }

    @Test
    void candidateReachabilityIsSeparateFromAuthenticatedDecision() {
        policy();
        var candidate = new ApplicationGroupManagementV1.Candidate("group", "https://issuer.example",
            "operator", "ui-client", Set.of("ui"), Set.of("manager"));
        assertTrue(management.reachable(candidate, "group", ApplicationGroupManagementV1.Action.MANAGE));
        assertFalse(management.reachable(new ApplicationGroupManagementV1.Candidate("group", "https://issuer.example",
            "operator", "ui-client", Set.of("ui"), Set.of("reader")), "group", ApplicationGroupManagementV1.Action.MANAGE));
    }

    @Test
    void unavailableStrategyDoesNotReturnAllow() {
        when(store.readCurrent()).thenThrow(new ProjectPolicyStore.Unavailable(new IllegalStateException("database")));
        assertThrows(ProjectPolicyStore.Unavailable.class, () -> management.authorize(
            identity(Set.of("manager"), "https://issuer.example", "ui"), "group", ApplicationGroupManagementV1.Action.MANAGE));
    }
}
