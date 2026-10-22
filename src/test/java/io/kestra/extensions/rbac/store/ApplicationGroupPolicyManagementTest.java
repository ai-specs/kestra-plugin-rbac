package io.kestra.extensions.rbac.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.extensions.rbac.management.ApplicationGroupPolicyManagement;
import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy;
import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicyParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.kestra.extensions.rbac.store.ProjectMigrationReviewTest.*;

class ApplicationGroupPolicyManagementTest {
    @Test void ordinarySettingsUpdatePreservesEveryScopeAndRejectsWholeGroupReplacement()throws Exception {
        var service=service(true);
        var input=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(TARGET).path("applicationGroups").get("kestra").deepCopy();
        input.put("id","kestra");input.remove("scopes");
        var before=store.readCurrent().policy().applicationGroups().get("kestra").scopes();
        input.put("enabled",false);
        assertEquals(2,service.updateProject(1,"kestra",input,"editor").revision());
        assertEquals(before,store.readCurrent().policy().applicationGroups().get("kestra").scopes());
        input.remove("scopes");input.put("directoryRevision",2);
        assertThrows(IllegalArgumentException.class,()->service.updateProject(2,"kestra",input,"editor"));
        input.remove("directoryRevision");input.putObject("scopes");
        assertThrows(IllegalArgumentException.class,()->service.updateProject(2,"kestra",input,"editor"));
        assertEquals(before,store.readCurrent().policy().applicationGroups().get("kestra").scopes());
    }
    private final ApplicationGroupPolicyStore store=new ApplicationGroupPolicyStore(JdbcPolicyStoreTest.database(),new ApplicationGroupPolicyParser());
    private final ObjectMapper mapper=new ObjectMapper();
    private static final String SCOPE="""
        {"name":"a-read","description":"A read","enabled":true,"grants":[{"scope":"NAMESPACE","tenant":"main","namespace":"a","descendants":false,"resource":"FLOW","actions":["READ","LIST"],"ids":[],"allIds":true}]}
        """;
    private ApplicationGroupPolicyManagement service(boolean allowed) {
        store.initializeSchema();store.initialize(bytes(TARGET),"operator");
        return new ApplicationGroupPolicyManagement(store,new ApplicationGroupPolicyManagement.DirectoryBoundary() {
            public void validate(java.sql.Connection connection,ApplicationGroupPolicy previous,ApplicationGroupPolicy candidate){if(!allowed)throw new IllegalArgumentException("Directory boundary refused");}
        });
    }
    @Test void scopeCrudRetainsItsOwnNamespaceAndActions()throws Exception {
        var service=service(true);
        var created=service.createScope(1,"kestra",mapper.readTree(SCOPE),"editor");
        assertEquals(2,created.revision());
        service.createScope(2,"kestra",mapper.readTree(SCOPE.replace("a-read","b-write").replace("\"namespace\":\"a\"","\"namespace\":\"b\"").replace("\"READ\",\"LIST\"","\"UPDATE\"")),"editor");
        var edited=service.replaceScope(3,"kestra","a-read",mapper.readTree(SCOPE.replace("A read","Updated A description")),"editor");
        var scopes=edited.policy().applicationGroups().get("kestra").scopes();
        assertEquals("a",scopes.get("a-read").grants().getFirst().namespace());
        assertEquals(java.util.Set.of("READ","LIST"),scopes.get("a-read").grants().getFirst().actions());
        assertEquals("b",scopes.get("b-write").grants().getFirst().namespace());
        assertEquals(java.util.Set.of("UPDATE"),scopes.get("b-write").grants().getFirst().actions());
        assertEquals(1,service.deleteScope(4,"kestra","a-read","editor").policy().applicationGroups().get("kestra").scopes().size());
    }
    @Test void directoryRefusalAndInvalidGrantHaveNoPolicySideEffect()throws Exception {
        var service=service(false);String before=store.readCurrent().canonicalJson();
        assertThrows(IllegalArgumentException.class,()->service.createScope(1,"kestra",mapper.readTree(SCOPE),"editor"));
        assertEquals(1,store.readCurrent().revision());assertEquals(before,store.readCurrent().canonicalJson());
        var allowed=service(true);
        assertThrows(IllegalArgumentException.class,()->allowed.createScope(1,"kestra",mapper.readTree(SCOPE.replace("\"namespace\":\"a\"","\"namespace\":\"*\"")),"editor"));
        assertEquals(before,store.readCurrent().canonicalJson());
    }
    @Test void staleWritesAndScopeRenamesCannotOverwriteNewPolicy()throws Exception {
        var service=service(true);service.createScope(1,"kestra",mapper.readTree(SCOPE),"editor");
        String before=store.readCurrent().canonicalJson();
        assertThrows(ApplicationGroupPolicyStore.Conflict.class,()->service.replaceScope(1,"kestra","a-read",mapper.readTree(SCOPE),"stale"));
        assertThrows(IllegalArgumentException.class,()->service.replaceScope(2,"kestra","a-read",mapper.readTree(SCOPE.replace("a-read","new-name")),"editor"));
        assertEquals(before,store.readCurrent().canonicalJson());
    }
    @Test void managementSafetyFailurePreventsDeletion()throws Exception {
        var service=service(true);service.createScope(1,"kestra",mapper.readTree(SCOPE),"editor");
        var protectedService=new ApplicationGroupPolicyManagement(store,new ApplicationGroupPolicyManagement.DirectoryBoundary() {
            public void validate(java.sql.Connection connection,ApplicationGroupPolicy previous,ApplicationGroupPolicy candidate){throw new IllegalArgumentException("Last administrator");}
        });
        assertThrows(IllegalArgumentException.class,()->protectedService.deleteScope(2,"kestra","a-read","editor"));
        assertTrue(store.readCurrent().policy().applicationGroups().get("kestra").scopes().containsKey("a-read"));
    }
    @Test void settingsUpdatePreservesScopesAndUsesPolicyCompareAndSwap()throws Exception {
        var service=service(true);
        var candidate=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(TARGET).path("applicationGroups").get("kestra").deepCopy();
        candidate.put("id","kestra");candidate.put("enabled",false);candidate.remove("scopes");
        var original=store.readCurrent();
        var checked=new ApplicationGroupPolicyManagement(store,new ApplicationGroupPolicyManagement.DirectoryBoundary() {
            public void validate(java.sql.Connection connection,ApplicationGroupPolicy previous,ApplicationGroupPolicy candidate){assertFalse(candidate.applicationGroups().get("kestra").enabled());assertTrue(previous.applicationGroups().get("kestra").enabled());}
        });
        var result=checked.updateProject(1,"kestra",candidate,"editor");
        assertEquals(2,result.revision());assertFalse(result.policy().applicationGroups().get("kestra").enabled());
        assertEquals(original.policy().applicationGroups().get("kestra").scopes(),result.policy().applicationGroups().get("kestra").scopes());
        String saved=store.readCurrent().canonicalJson();
        assertThrows(ApplicationGroupPolicyStore.Conflict.class,()->checked.updateProject(1,"kestra",candidate,"stale"));
        candidate.put("id","another");
        assertThrows(IllegalArgumentException.class,()->checked.updateProject(2,"kestra",candidate,"editor"));
        assertEquals(saved,store.readCurrent().canonicalJson());
    }
    @Test void settingsUpdateCannotBypassValidationOrLastAdministratorProtection()throws Exception {
        service(true);
        var candidate=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(TARGET).path("applicationGroups").get("kestra").deepCopy();
        candidate.put("id","kestra");candidate.put("enabled",false);candidate.remove("scopes");
        String saved=store.readCurrent().canonicalJson();
        var refused=new ApplicationGroupPolicyManagement(store,new ApplicationGroupPolicyManagement.DirectoryBoundary() {
            public void validate(java.sql.Connection connection,ApplicationGroupPolicy previous,ApplicationGroupPolicy candidate){throw new IllegalArgumentException("Scope exceeds current directory");}
        });
        assertThrows(IllegalArgumentException.class,()->refused.updateProject(1,"kestra",candidate,"editor"));
        var protectedService=new ApplicationGroupPolicyManagement(store,new ApplicationGroupPolicyManagement.DirectoryBoundary() {
            public void validate(java.sql.Connection connection,ApplicationGroupPolicy previous,ApplicationGroupPolicy candidate){throw new IllegalArgumentException("Last local administrator");}
        });
        assertThrows(IllegalArgumentException.class,()->protectedService.updateProject(1,"kestra",candidate,"editor"));
        assertEquals(1,store.readCurrent().revision());assertEquals(saved,store.readCurrent().canonicalJson());
        candidate.putObject("scopes");
        assertThrows(IllegalArgumentException.class,()->protectedService.updateProject(1,"kestra",candidate,"editor"));
        assertEquals(saved,store.readCurrent().canonicalJson());
    }

}
