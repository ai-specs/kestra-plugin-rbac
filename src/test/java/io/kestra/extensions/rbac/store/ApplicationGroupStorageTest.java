package io.kestra.extensions.rbac.store;

import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicyParser;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ApplicationGroupStorageTest {
    private static final String DOCUMENT="""
        {"schemaVersion":2,"applicationGroups":{"group":{"issuer":"https://issuer.example",
        "audiences":["ui"],"enabled":true,"scopes":{"reader":{"description":"Read workflows",
        "enabled":true,"grants":[{"scope":"NAMESPACE","tenant":"main","namespace":"allowed",
        "descendants":false,"resource":"FLOW","actions":["READ"],"ids":[],"allIds":true}],"managementGrants":[]}}}}}
        """;

    @Test void legacyCanonicalSnapshotReadsWithoutRewritingAndNextSaveUsesExpressions() throws Exception {
        var source=JdbcPolicyStoreTest.database();
        var parser=new ApplicationGroupPolicyParser();var store=new ApplicationGroupPolicyStore(source,parser);
        store.initializeSchema();store.initialize(DOCUMENT.getBytes(StandardCharsets.UTF_8),"installer");
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var legacy=mapper.readTree(parser.canonicalJson(parser.parse(DOCUMENT.getBytes(StandardCharsets.UTF_8))));
        var grant=(com.fasterxml.jackson.databind.node.ObjectNode)legacy.path("applicationGroups").path("group").path("scopes").path("reader").path("grants").get(0);
        grant.put("descendants",false);
        var sorted=new java.util.TreeMap<String,com.fasterxml.jackson.databind.JsonNode>();grant.fields().forEachRemaining(entry->sorted.put(entry.getKey(),entry.getValue()));
        grant.removeAll();sorted.forEach(grant::set);
        String old=mapper.writeValueAsString(legacy);
        try(var connection=source.getConnection();var statement=connection.prepareStatement("UPDATE rbac_application_group_policy_snapshot SET document_json=? WHERE revision=1")) {
            statement.setString(1,old);assertEquals(1,statement.executeUpdate());
        }
        var loaded=store.readCurrent();
        assertFalse(loaded.canonicalJson().contains("descendants"));
        assertEquals(parser.parse(DOCUMENT.getBytes(StandardCharsets.UTF_8)),loaded.policy());
        assertFalse(store.replace(1,loaded.canonicalJson().getBytes(StandardCharsets.UTF_8),"editor").canonicalJson().contains("descendants"));
        try(var connection=source.getConnection();var statement=connection.createStatement();var rows=statement.executeQuery("SELECT document_json FROM rbac_application_group_policy_snapshot WHERE revision=1")) {
            assertTrue(rows.next());assertEquals(old,rows.getString(1));
        }
    }

    @Test void newInstallCreatesOnlyApplicationGroupPolicyTablesAndPersistsNewJson() throws Exception {
        var source=JdbcPolicyStoreTest.database();
        var parser=new ApplicationGroupPolicyParser();var store=new ApplicationGroupPolicyStore(source,parser);
        store.initializeSchema();store.initializeSchema();
        var tables=new HashSet<String>();
        try(var connection=source.getConnection();var result=connection.getMetaData().getTables(null,connection.getSchema(),"%",new String[]{"TABLE"})) {
            while(result.next())tables.add(result.getString("TABLE_NAME").toLowerCase(java.util.Locale.ROOT));
        }
        assertTrue(tables.contains("rbac_application_group_policy_head"));
        assertTrue(tables.contains("rbac_application_group_policy_snapshot"));
        assertFalse(tables.stream().anyMatch(table->table.startsWith("rbac_project_policy_")));
        var created=store.initialize(DOCUMENT.getBytes(StandardCharsets.UTF_8),"installer");
        assertEquals(1,created.revision());assertTrue(created.policy().applicationGroups().containsKey("group"));
        var saved=store.replace(1,DOCUMENT.replace("Read workflows","Reviewed workflows").getBytes(StandardCharsets.UTF_8),"editor");
        assertEquals(2,saved.revision());
        var reloaded=new ApplicationGroupPolicyStore(source,new ApplicationGroupPolicyParser()).readCurrent();
        assertEquals(saved.canonicalJson(),reloaded.canonicalJson());
        assertEquals("Reviewed workflows",reloaded.policy().applicationGroups().get("group").scopes().get("reader").description());
        try(var connection=source.getConnection();var statement=connection.createStatement();
            var rows=statement.executeQuery("SELECT document_json FROM rbac_application_group_policy_snapshot WHERE revision=2")) {
            assertTrue(rows.next());assertEquals(saved.canonicalJson(),rows.getString(1));
            assertFalse(rows.getString(1).contains("applicationProjects"));
        }
    }
}
