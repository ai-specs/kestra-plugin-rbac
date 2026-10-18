package io.kestra.extensions.rbac.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.extensions.rbac.management.ProjectPolicyManagement;
import io.kestra.extensions.rbac.project.ProjectPolicy;
import io.kestra.extensions.rbac.project.ProjectPolicyParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.kestra.extensions.rbac.store.ProjectMigrationReviewTest.*;

class ProjectPolicyManagementTest {
    @Test void ordinaryProjectUpdateCannotConfirmDirectoryRevision()throws Exception {
        var service=service(true);
        var input=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(TARGET).path("applicationProjects").get("kestra").deepCopy();
        input.put("id","kestra");input.remove("scopes");
        String before=store.readCurrent().canonicalJson();
        input.put("directoryRevision",2);
        assertThrows(IllegalArgumentException.class,()->service.updateProject(1,"kestra",input,"editor"));
        assertEquals(1,store.readCurrent().revision());
        assertEquals(before,store.readCurrent().canonicalJson());
        input.put("directoryRevision",1);
        assertEquals(2,service.updateProject(1,"kestra",input,"editor").revision());
    }
    private final ProjectPolicyStore store=new ProjectPolicyStore(JdbcPolicyStoreTest.database(),new ProjectPolicyParser());
    private final ObjectMapper mapper=new ObjectMapper();
    private static final String SCOPE="""
        {"name":"a:read","description":"A read","enabled":true,"grants":[{"scope":"NAMESPACE","tenant":"main","namespace":"a","descendants":false,"resource":"FLOW","actions":["READ","LIST"],"ids":[],"allIds":true}]}
        """;
    private ProjectPolicyManagement service(boolean allowed) {
        store.initializeSchema();store.initialize(bytes(TARGET),"operator");
        return new ProjectPolicyManagement(store,new ProjectPolicyManagement.DirectoryBoundary() {
            public void validate(ProjectPolicy policy){if(!allowed)throw new IllegalArgumentException("Directory boundary refused");}
            public void validateManagementChange(ProjectPolicy previous,ProjectPolicy candidate){}
        });
    }
    @Test void scopeCrudRetainsItsOwnNamespaceAndActions()throws Exception {
        var service=service(true);
        var created=service.createScope(1,"kestra",mapper.readTree(SCOPE),"editor");
        assertEquals(2,created.revision());
        service.createScope(2,"kestra",mapper.readTree(SCOPE.replace("a:read","b:write").replace("\"namespace\":\"a\"","\"namespace\":\"b\"").replace("\"READ\",\"LIST\"","\"UPDATE\"")),"editor");
        var edited=service.replaceScope(3,"kestra","a:read",mapper.readTree(SCOPE.replace("A read","Updated A description")),"editor");
        var scopes=edited.policy().applicationProjects().get("kestra").scopes();
        assertEquals("a",scopes.get("a:read").grants().getFirst().namespace());
        assertEquals(java.util.Set.of("READ","LIST"),scopes.get("a:read").grants().getFirst().actions());
        assertEquals("b",scopes.get("b:write").grants().getFirst().namespace());
        assertEquals(java.util.Set.of("UPDATE"),scopes.get("b:write").grants().getFirst().actions());
        assertEquals(1,service.deleteScope(4,"kestra","a:read","editor").policy().applicationProjects().get("kestra").scopes().size());
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
        assertThrows(ProjectPolicyStore.Conflict.class,()->service.replaceScope(1,"kestra","a:read",mapper.readTree(SCOPE),"stale"));
        assertThrows(IllegalArgumentException.class,()->service.replaceScope(2,"kestra","a:read",mapper.readTree(SCOPE.replace("a:read","new-name")),"editor"));
        assertEquals(before,store.readCurrent().canonicalJson());
    }
    @Test void managementSafetyFailurePreventsDeletion()throws Exception {
        var service=service(true);service.createScope(1,"kestra",mapper.readTree(SCOPE),"editor");
        var protectedService=new ProjectPolicyManagement(store,new ProjectPolicyManagement.DirectoryBoundary() {
            public void validate(ProjectPolicy policy){}
            public void validateManagementChange(ProjectPolicy old,ProjectPolicy next){throw new IllegalArgumentException("Last administrator");}
        });
        assertThrows(IllegalArgumentException.class,()->protectedService.deleteScope(2,"kestra","a:read","editor"));
        assertTrue(store.readCurrent().policy().applicationProjects().get("kestra").scopes().containsKey("a:read"));
    }
    @Test void reconciliationReplacesAllReviewedScopesAndRevisionAtomically()throws Exception {
        var service=service(true);
        var candidate=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(TARGET).path("applicationProjects").get("kestra").deepCopy();
        candidate.put("id","kestra");candidate.put("directoryRevision",2);
        var original=store.readCurrent();
        var checked=new ProjectPolicyManagement(store,new ProjectPolicyManagement.DirectoryBoundary() {
            public void validate(ProjectPolicy policy){assertEquals(2,policy.applicationProjects().get("kestra").directoryRevision());}
            public void validateManagementChange(ProjectPolicy before,ProjectPolicy after){assertEquals(1,before.applicationProjects().get("kestra").directoryRevision());}
        });
        var result=checked.reconcileProject(1,"kestra",candidate,"editor");
        assertEquals(2,result.revision());assertEquals(2,result.policy().applicationProjects().get("kestra").directoryRevision());
        assertEquals(original.policy().applicationProjects().get("kestra").scopes(),result.policy().applicationProjects().get("kestra").scopes());
        String saved=store.readCurrent().canonicalJson();
        assertThrows(ProjectPolicyStore.Conflict.class,()->checked.reconcileProject(1,"kestra",candidate,"stale"));
        candidate.put("id","another");
        assertThrows(IllegalArgumentException.class,()->checked.reconcileProject(2,"kestra",candidate,"editor"));
        assertEquals(saved,store.readCurrent().canonicalJson());
    }
    @Test void reconciliationCannotPartiallyConfirmInvalidBindingsOrRemoveLastAdministrator()throws Exception {
        service(true);
        var candidate=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(TARGET).path("applicationProjects").get("kestra").deepCopy();
        candidate.put("id","kestra");candidate.put("directoryRevision",2);
        String saved=store.readCurrent().canonicalJson();
        var refused=new ProjectPolicyManagement(store,new ProjectPolicyManagement.DirectoryBoundary() {
            public void validate(ProjectPolicy policy){throw new IllegalArgumentException("Scope exceeds current directory");}
            public void validateManagementChange(ProjectPolicy before,ProjectPolicy after){fail("Invalid candidate reached management safety");}
        });
        assertThrows(IllegalArgumentException.class,()->refused.reconcileProject(1,"kestra",candidate,"editor"));
        var protectedService=new ProjectPolicyManagement(store,new ProjectPolicyManagement.DirectoryBoundary() {
            public void validate(ProjectPolicy policy){}
            public void validateManagementChange(ProjectPolicy before,ProjectPolicy after){throw new IllegalArgumentException("Last local administrator");}
        });
        assertThrows(IllegalArgumentException.class,()->protectedService.reconcileProject(1,"kestra",candidate,"editor"));
        assertEquals(1,store.readCurrent().revision());assertEquals(saved,store.readCurrent().canonicalJson());
        candidate.remove("scopes");
        assertThrows(IllegalArgumentException.class,()->protectedService.reconcileProject(1,"kestra",candidate,"editor"));
        assertEquals(saved,store.readCurrent().canonicalJson());
    }

}
