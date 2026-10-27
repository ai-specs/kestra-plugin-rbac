package io.kestra.extensions.rbac.applicationgroup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.kestra.extensions.rbac.declaration.ApplicationScopeDeclarationStore;
import io.kestra.extensions.rbac.store.ApplicationPermissionUpgrade;
import io.kestra.webserver.oidc.ApplicationScopeDeclarationsV1;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static io.kestra.extensions.rbac.applicationgroup.ApplicationGroupDirectory.*;
import static io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.*;
import static org.junit.jupiter.api.Assertions.*;

class ApplicationPermissionUpgradeTest {
    private static final ObjectMapper MAPPER=new ObjectMapper();
    private static final class Fixture {
        final ApplicationGroupDirectoryServiceTest.Fixture fixture=new ApplicationGroupDirectoryServiceTest.Fixture();
        final ApplicationScopeDeclarationStore declarations=new ApplicationScopeDeclarationStore(fixture.source,MAPPER);
        final ApplicationPermissionUpgrade upgrade=new ApplicationPermissionUpgrade(fixture.store,fixture.policies,declarations,fixture.service);
        Snapshot target;
        Fixture()throws Exception {
            try(var connection=fixture.source.getConnection()) {
                System.out.println("Declaration upgrade backend: "+connection.getMetaData().getDatabaseProductName());
                String configured=System.getenv("RBAC_STORE_TEST_JDBC");
                assertEquals(configured!=null&&!configured.isBlank()?"PostgreSQL":"H2",connection.getMetaData().getDatabaseProductName());
            }
            var original=fixture.current();var group=original.group();
            var applications=original.applications().stream().map(value->new Application(value.clientId(),value.description(),value.audiences(),value.permissions(),value.revision()+1)).toList();
            target=new Snapshot(new Group(group.id(),group.displayName(),"",group.active(),group.revision()+1,group.authorizationVersion()+1),original.scopes(),applications,original.userGrants());
            var legacy=(ObjectNode)MAPPER.valueToTree(original);((ObjectNode)legacy.get("group")).remove("description");
            for(var application:legacy.get("applications")) {
                var node=(ObjectNode)application;node.remove("description");node.remove("permissions");node.put("approved",true);
                node.set("supportedScopeKeys",MAPPER.valueToTree(original.applications().getFirst().supportedScopeKeys()));
            }
            fixture.store.transaction(connection->{
                try(var statement=connection.prepareStatement("UPDATE rbac_application_group_state SET document_json=? WHERE group_id='group'")) {
                    statement.setString(1,legacy.toString());statement.executeUpdate();
                }return null;
            });
            declarations.initializeSchema();
            declarations.create("group","ui","manager",List.of(new ApplicationScopeDeclarationsV1.Permission("identity","manager")));
            declarations.create("group","ui","policy-manager",List.of(new ApplicationScopeDeclarationsV1.Permission("policy","policy-manager")));
        }
        ApplicationPermissionUpgrade.Plan plan() {
            return new ApplicationPermissionUpgrade.Plan(1,upgrade.source().hash(),List.of(target),List.of(
                new ApplicationPermissionUpgrade.DeclarationMapping("group","ui","manager","identity","allow-manager"),
                new ApplicationPermissionUpgrade.DeclarationMapping("group","ui","policy-manager","policy","allow-policy-manager")),List.of(
                new ApplicationPermissionUpgrade.RuntimeMapping("group","manager","allow-manager"),
                new ApplicationPermissionUpgrade.RuntimeMapping("group","policy-manager","allow-policy-manager")));
        }
    }

    @Test void upgradePreservesPolicySupportAndSubjectGrants()throws Exception {
        var fixture=new Fixture();var before=fixture.upgrade.source();var plan=fixture.plan();var review=fixture.upgrade.preview(plan);
        var audit=new io.kestra.extensions.rbac.store.ManagementAuditStore();
        fixture.fixture.store.transaction(connection->{audit.append(connection,"https://issuer.example","first",io.kestra.extensions.rbac.store.ManagementAuditStore.Operation.PASSWORD_RESET,"second",null,Set.of("group"));return null;});
        assertEquals(List.of(),review.conflicts());assertEquals(before.hash(),fixture.upgrade.source().hash());
        assertTrue(fixture.upgrade.apply(plan,review.approvalHash()));
        var actual=fixture.fixture.current();assertEquals(fixture.target,actual);
        assertEquals(Set.of("manager","policy-manager","reader"),actual.eligibleScopeKeys("ui","https://issuer.example","first"));
        assertEquals(before.policy().applicationGroups().get("group").scopes().keySet(),fixture.fixture.service.runtimePolicy().policy().applicationGroups().get("group").scopes().keySet());
        assertTrue(fixture.declarations.list("group","ui").isEmpty());assertEquals(2,actual.group().authorizationVersion());
        assertEquals(1,fixture.fixture.store.transaction(connection->audit.readIssuer(connection,"https://issuer.example",1,25)).total());
    }

    @Test void conflictingMappingIsRejectedWithoutSideEffects()throws Exception {
        var fixture=new Fixture();var plan=fixture.plan();var before=fixture.upgrade.source().hash();
        var conflicting=new ApplicationPermissionUpgrade.Plan(1,plan.sourceHash(),plan.applicationGroups(),List.of(
            plan.declarationMappings().getFirst(),new ApplicationPermissionUpgrade.DeclarationMapping("group","ui","policy-manager","policy","allow-manager")),plan.runtimeMappings());
        var review=fixture.upgrade.preview(conflicting);assertTrue(review.conflicts().stream().anyMatch(value->value.startsWith("declaration_mapping_conflict")));
        assertThrows(IllegalArgumentException.class,()->fixture.upgrade.apply(conflicting,review.approvalHash()));
        assertEquals(before,fixture.upgrade.source().hash());
    }

    @Test void failureAfterWritesRollsBackDirectoryDeclarationsEpochAndReceipt()throws Exception {
        var fixture=new Fixture();var plan=fixture.plan();var review=fixture.upgrade.preview(plan);var before=fixture.upgrade.source().hash();
        assertThrows(IllegalStateException.class,()->fixture.fixture.store.transaction(connection->fixture.upgrade.apply(connection,plan,review.approvalHash(),()->{throw new IllegalStateException("injected failure");})));
        assertEquals(before,fixture.upgrade.source().hash());
        assertTrue(fixture.upgrade.apply(plan,review.approvalHash()));
        assertEquals(0,fixture.fixture.store.transaction(connection->new io.kestra.extensions.rbac.store.ManagementAuditStore().readIssuer(connection,"https://issuer.example",1,25)).total());
    }

    @Test void reviewedUpgradeIsIdempotentButCannotOverwriteLaterWrites()throws Exception {
        var fixture=new Fixture();var plan=fixture.plan();var review=fixture.upgrade.preview(plan);
        assertTrue(fixture.upgrade.apply(plan,review.approvalHash()));assertFalse(fixture.upgrade.apply(plan,review.approvalHash()));
        fixture.fixture.update(current->ApplicationGroupChanges.groupMetadata(current,"Changed",true));
        assertThrows(io.kestra.extensions.rbac.store.ApplicationGroupDirectoryStore.Conflict.class,()->fixture.upgrade.apply(plan,review.approvalHash()));
    }

    @Test void disabledRuntimePolicyRemainsDisabledWithoutChangingSupport()throws Exception {
        var fixture=new Fixture();var stored=fixture.fixture.policies.readCurrent();var group=stored.policy().applicationGroups().get("group");
        var grants=List.of(new Grant("NAMESPACE","main","allowed",false,"FLOW",Set.of("READ"),Set.of(),true));
        var scopes=new java.util.TreeMap<>(group.scopes());scopes.put("reader",new ScopeDefinition("Disabled",false,grants));
        var policy=new ApplicationGroupPolicy(2,Map.of("group",new ApplicationGroup( group.issuer(),group.audiences(),group.enabled(),scopes)));
        fixture.fixture.policies.replace(stored.revision(),new ApplicationGroupPolicyParser().canonicalJson(policy).getBytes(java.nio.charset.StandardCharsets.UTF_8),"test");
        var target=fixture.target;var app=target.applications().getFirst();
        var permissions=app.permissions().stream().map(value->value.key().equals("allow-reader")?
            new ApplicationPermission(value.key(),value.name(),"",value.scopeKeys(),grants,List.of(),"",false,1):value).toList();
        fixture.target=new Snapshot(target.group(),target.scopes(),List.of(new Application("ui","",app.audiences(),permissions,app.revision())),target.userGrants());
        var original=fixture.plan();var runtime=new ArrayList<>(original.runtimeMappings());runtime.add(new ApplicationPermissionUpgrade.RuntimeMapping("group","reader","allow-reader"));
        var plan=new ApplicationPermissionUpgrade.Plan(1,original.sourceHash(),original.applicationGroups(),original.declarationMappings(),runtime);
        var review=fixture.upgrade.preview(plan);assertEquals(List.of(),review.conflicts());assertTrue(fixture.upgrade.apply(plan,review.approvalHash()));
        assertTrue(fixture.fixture.current().applications().getFirst().supportedScopeKeys().contains("reader"));
        assertFalse(fixture.fixture.service.runtimePolicy().policy().applicationGroups().get("group").scopes().containsKey("reader"));
    }

    @Test void unknownReferencesAndUnknownMappingsAreRejected()throws Exception {
        var fixture=new Fixture();var original=fixture.plan();
        var mappings=new ArrayList<>(original.runtimeMappings());mappings.add(new ApplicationPermissionUpgrade.RuntimeMapping("group","unknown","allow-reader"));
        var plan=new ApplicationPermissionUpgrade.Plan(1,original.sourceHash(),original.applicationGroups(),original.declarationMappings(),mappings);
        var review=fixture.upgrade.preview(plan);assertTrue(review.conflicts().contains("unknown_or_duplicate_runtime_mapping"));
        assertThrows(IllegalArgumentException.class,()->fixture.upgrade.apply(plan,review.approvalHash()));
        assertThrows(IllegalArgumentException.class,()->new Snapshot(fixture.target.group(),fixture.target.scopes(),List.of(
            new Application("ui","",Set.of("ui"),TestApplicationFactory.declarations(Set.of("unknown")),2)),fixture.target.userGrants()));
    }

    @Test void approvedFalseCannotGainSupportDuringUpgrade()throws Exception {
        var fixture=new Fixture();fixture.fixture.store.transaction(connection->{
            try(var statement=connection.prepareStatement("SELECT document_json FROM rbac_application_group_state WHERE group_id='group'");var rows=statement.executeQuery()) {
                assertTrue(rows.next());var legacy=(ObjectNode)MAPPER.readTree(rows.getString(1));
                ((ObjectNode)legacy.at("/applications/0")).put("approved",false);
                try(var update=connection.prepareStatement("UPDATE rbac_application_group_state SET document_json=? WHERE group_id='group'")) {
                    update.setString(1,legacy.toString());update.executeUpdate();
                }
            }catch(java.io.IOException failure){throw new IllegalArgumentException(failure);}return null;
        });
        var plan=fixture.plan();var review=fixture.upgrade.preview(plan);
        assertTrue(review.conflicts().stream().anyMatch(value->value.startsWith("support_or_application_changed")));
        assertThrows(IllegalArgumentException.class,()->fixture.upgrade.apply(plan,review.approvalHash()));
    }

    @Test void sourceDriftAndWrongApprovalCannotApply()throws Exception {
        var fixture=new Fixture();var plan=fixture.plan();
        fixture.declarations.replace("group","ui","manager",1,List.of(new ApplicationScopeDeclarationsV1.Permission("identity","Changed")));
        var review=fixture.upgrade.preview(plan);assertTrue(review.conflicts().contains("source_changed"));
        assertThrows(IllegalArgumentException.class,()->fixture.upgrade.apply(plan,review.approvalHash()));
        assertThrows(IllegalArgumentException.class,()->fixture.upgrade.apply(plan,"0".repeat(64)));
    }
}
