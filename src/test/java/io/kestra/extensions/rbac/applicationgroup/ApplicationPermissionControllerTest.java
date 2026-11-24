package io.kestra.extensions.rbac.applicationgroup;

import io.kestra.extensions.rbac.ApplicationGroupRuntimePolicies;
import io.kestra.extensions.rbac.management.ApplicationPermissionController;
import io.kestra.webserver.oidc.TrustedIdentityV1;
import io.micronaut.security.authentication.Authentication;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApplicationPermissionControllerTest {
    private static final String CLIENT="~dWk";
    private static final Authentication ACTOR=Authentication.build("first");
    private static final Set<String> ALL=Set.of("manager","policy-manager");
    private record Setup(ApplicationGroupDirectoryServiceTest.Fixture fixture,ApplicationPermissionController controller,TrustedIdentityV1 identities) {}
    private Setup setup(Set<String> scopes)throws Exception {
        var fixture=new ApplicationGroupDirectoryServiceTest.Fixture();var identities=mock(TrustedIdentityV1.class);
        when(identities.current()).thenAnswer(call->fixture.identity(scopes));
        var runtime=new ApplicationGroupRuntimePolicies(fixture.service,identities);
        return new Setup(fixture,new ApplicationPermissionController(fixture.service,runtime),identities);
    }
    private byte[] json(String key,String scopes,String grants,String extra) {
        return ("{\"key\":\""+key+"\",\"name\":\"Permission\",\"description\":\"\",\"scopeKeys\":"+scopes+
            ",\"grants\":"+grants+",\"managementGrants\":[]"+extra+"}").getBytes(StandardCharsets.UTF_8);
    }

    private static final String AI_TENANT="[{\"scope\":\"TENANT\",\"tenant\":\"main\",\"namespace\":null,\"descendants\":false,\"resource\":\"AI\",\"actions\":[\"READ\",\"EXECUTE\"],\"ids\":[],\"allIds\":true}]";
    private static final String AI_LEGACY="[{\"scope\":\"NAMESPACE\",\"tenant\":\"main\",\"namespace\":\"company\",\"descendants\":true,\"resource\":\"AI\",\"actions\":[\"READ\",\"LIST\",\"UPDATE\",\"EXECUTE\"],\"ids\":[\"legacy-ai\"],\"allIds\":false}]";

    private ApplicationPermission persistedPermission(Setup setup,String key)throws Exception {
        try(var connection=setup.fixture().source.getConnection();var statement=connection.prepareStatement(
            "SELECT document_json FROM rbac_application_group_state WHERE group_id=?")) {
            statement.setString(1,"group");
            try(var rows=statement.executeQuery()) {
                assertTrue(rows.next());
                var snapshot=new com.fasterxml.jackson.databind.ObjectMapper().readValue(rows.getString(1),ApplicationGroupDirectory.Snapshot.class);
                return snapshot.applications().stream().filter(application->application.clientId().equals("ui"))
                    .flatMap(application->application.permissions().stream()).filter(permission->permission.key().equals(key)).findFirst().orElseThrow();
            }
        }
    }

    private java.util.Map<String,List<String>> storedRows(Setup setup)throws Exception {
        var result=new java.util.TreeMap<String,List<String>>();
        try(var connection=setup.fixture().source.getConnection();var tables=connection.getMetaData().getTables(
            connection.getCatalog(),connection.getSchema(),"%",new String[]{"TABLE"})) {
            while(tables.next()) {
                String table=tables.getString("TABLE_NAME");
                if(!table.toLowerCase(java.util.Locale.ROOT).startsWith("rbac_"))continue;
                assertTrue(table.matches("[A-Za-z0-9_]+"));
                var values=new java.util.ArrayList<String>();
                try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT * FROM "+table)) {
                    int columns=rows.getMetaData().getColumnCount();
                    while(rows.next()) {
                        var row=new java.util.ArrayList<String>();
                        for(int column=1;column<=columns;column++)row.add(rows.getString(column));
                        values.add(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(row));
                    }
                }
                values.sort(String::compareTo);result.put(table,List.copyOf(values));
            }
        }
        assertFalse(result.isEmpty());return result;
    }

    private io.kestra.webserver.rbac.AuthorizationEvaluationV1 evaluation(Setup setup) {
        var runtime=new ApplicationGroupRuntimePolicies(setup.fixture().service,setup.identities());
        return new io.kestra.extensions.rbac.ConfiguredEvaluationProvider(runtime,Runnable::run)
            .begin(setup.fixture().identity(Set.of("reader")),"main").toCompletableFuture().join();
    }

    private boolean aiAllowed(io.kestra.webserver.rbac.AuthorizationEvaluationV1 evaluation,
        io.kestra.webserver.rbac.TypedAuthorization.Action action) {
        var operation=new io.kestra.webserver.rbac.TypedAuthorization.Operation(
            io.kestra.webserver.rbac.TypedAuthorization.CONTRACT_VERSION,
            io.kestra.webserver.rbac.TypedAuthorization.Source.REST,
            io.kestra.webserver.rbac.TypedAuthorization.Resource.AI,action,"main",null,null,
            io.kestra.webserver.rbac.TypedAuthorization.QueryDomain.unrestricted(),
            action==io.kestra.webserver.rbac.TypedAuthorization.Action.EXECUTE
                ?io.kestra.webserver.rbac.TypedAuthorization.Coverage.COMPLETE_BATCH:io.kestra.webserver.rbac.TypedAuthorization.Coverage.SINGLE,
            io.kestra.webserver.rbac.TypedAuthorization.Aggregate.NONE,
            action==io.kestra.webserver.rbac.TypedAuthorization.Action.EXECUTE
                ?io.kestra.webserver.rbac.TypedAuthorization.SideEffect.EXTERNAL:io.kestra.webserver.rbac.TypedAuthorization.SideEffect.READ_ONLY);
        return evaluation.authorize(operation).toCompletableFuture().join().allowed();
    }

    @Test void namespaceExpressionsPersistAndManagementResponsesRoundTripWithoutLegacyFlags() throws Exception {
        var setup=setup(ALL); var controller=setup.controller();
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        String template="[{\"scope\":\"NAMESPACE\",\"tenant\":\"main\",\"namespace\":\"%s\",\"resource\":\"FLOW\",\"actions\":[\"READ\",\"LIST\"],\"ids\":[],\"allIds\":true}]";
        int revision=1;
        for(String expression:List.of("company","company.*","!company","!company.*")) {
            String key="expression-"+revision;
            assertEquals(200,controller.create("main","group",CLIENT,ACTOR,"\""+revision+"\"",json(key,"[\"reader\"]",template.formatted(expression),"")).code());
            var found=controller.find("main","group",CLIENT,key,ACTOR);
            assertEquals(200,found.code());
            var tree=mapper.valueToTree(found.body());
            assertEquals(expression,tree.path("grants").get(0).path("namespace").asText());
            assertFalse(tree.path("grants").get(0).has("descendants"));
            String output=mapper.writeValueAsString(tree.path("grants"));
            assertEquals(200,controller.update("main","group",CLIENT,key,ACTOR,"\"1\"",json(key,"[\"reader\"]",output,",\"enabled\":true")).code());
            assertEquals(NamespaceExpression.parse(expression).namespace(),persistedPermission(setup,key).grants().getFirst().namespace());
            assertEquals(NamespaceExpression.parse(expression).descendants(),persistedPermission(setup,key).grants().getFirst().descendants());
            var before=storedRows(setup);
            if(expression.endsWith(".*")) for(boolean flag:List.of(false,true)) {
                String conflict=output.replace("\"scope\"", "\"descendants\":"+flag+",\"scope\"");
                assertEquals(400,controller.update("main","group",CLIENT,key,ACTOR,"\"2\"",json(key,"[\"reader\"]",conflict,",\"enabled\":true")).code());
                assertEquals(before,storedRows(setup));
            }
            revision+=2;
        }
        try(var connection=setup.fixture().source.getConnection();var statement=connection.createStatement();var rows=statement.executeQuery("SELECT document_json FROM rbac_application_group_state")) {
            while(rows.next())assertFalse(rows.getString(1).contains("descendants"));
        }
    }

    @Test void signedNamespaceGrantsRoundTripRejectInvalidAndObserveExplicitRevocation()throws Exception {
        var setup=setup(ALL);var controller=setup.controller();
        String grants="[{\"scope\":\"NAMESPACE\",\"tenant\":\"main\",\"namespace\":\"!company\",\"descendants\":true,\"resource\":\"FLOW\",\"actions\":[\"READ\",\"LIST\"],\"ids\":[\"a\"],\"allIds\":false},{\"scope\":\"NAMESPACE\",\"tenant\":\"main\",\"namespace\":\"company\",\"descendants\":true,\"resource\":\"FLOW\",\"actions\":[\"READ\",\"LIST\"],\"ids\":[\"b\"],\"allIds\":false}]";
        assertEquals(200,controller.create("main","group",CLIENT,ACTOR,"\"1\"",json("signed","[\"reader\"]",grants,"")).code());
        assertEquals("!company",persistedPermission(setup,"signed").grants().getFirst().namespace());
        assertEquals(200,controller.find("main","group",CLIENT,"signed",ACTOR).code());
        var before=storedRows(setup);
        assertEquals(400,controller.update("main","group",CLIENT,"signed",ACTOR,"\"1\"",json("signed","[\"reader\"]",grants.replace("!company","!company."),",\"enabled\":true")).code());
        assertEquals(before,storedRows(setup));
        var e=evaluation(setup);try {
            var operation=new io.kestra.webserver.rbac.TypedAuthorization.Operation(io.kestra.webserver.rbac.TypedAuthorization.CONTRACT_VERSION,io.kestra.webserver.rbac.TypedAuthorization.Source.REST,io.kestra.webserver.rbac.TypedAuthorization.Resource.FLOW,io.kestra.webserver.rbac.TypedAuthorization.Action.LIST,"main",null,null,io.kestra.webserver.rbac.TypedAuthorization.QueryDomain.unrestricted(),io.kestra.webserver.rbac.TypedAuthorization.Coverage.COMPLETE_QUERY,io.kestra.webserver.rbac.TypedAuthorization.Aggregate.NONE,io.kestra.webserver.rbac.TypedAuthorization.SideEffect.READ_ONLY);
            var decision=e.authorize(operation).toCompletableFuture().join();assertTrue(decision.allowed());
            assertTrue(decision.scope().resourceAllowed("outside","a"));assertTrue(decision.scope().resourceAllowed("company.sub","b"));
            assertFalse(decision.scope().resourceAllowed("outside","b"));assertFalse(decision.scope().resourceAllowed("company","a"));
        }
        finally {e.close().toCompletableFuture().join();}
        assertEquals(200,controller.update("main","group",CLIENT,"signed",ACTOR,"\"1\"",json("signed","[\"reader\"]","[]",",\"enabled\":true")).code());
        assertTrue(persistedPermission(setup,"signed").grants().isEmpty());
        assertEquals(200,controller.delete("main","group",CLIENT,"signed",ACTOR,"\"2\"").code());
    }

    @Test void aiTenantCreateFindUpdateDeletePersistAndNewEvaluationsObserveRevocation()throws Exception {
        var setup=setup(ALL);var controller=setup.controller();
        assertEquals(200,controller.create("main","group",CLIENT,ACTOR,"\"1\"",json("ai-entry","[\"reader\"]",AI_TENANT,"")).code());
        var original=persistedPermission(setup,"ai-entry");
        var expected=new ApplicationGroupPolicy.Grant("TENANT","main",null,false,"AI",Set.of("READ","EXECUTE"),Set.of(),true);
        assertEquals(List.of(expected),original.grants());
        var found=controller.find("main","group",CLIENT,"ai-entry",ACTOR);
        assertEquals(200,found.code());
        var body=new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(found.body());
        assertEquals("AI",body.path("grants").get(0).path("resource").asText());
        assertEquals("TENANT",body.path("grants").get(0).path("scope").asText());
        var allowed=evaluation(setup);
        try {
            assertTrue(aiAllowed(allowed,io.kestra.webserver.rbac.TypedAuthorization.Action.READ));
            assertTrue(aiAllowed(allowed,io.kestra.webserver.rbac.TypedAuthorization.Action.EXECUTE));
        } finally {allowed.close().toCompletableFuture().join();}
        String readOnly=AI_TENANT.replace("[\"READ\",\"EXECUTE\"]","[\"READ\"]");
        assertEquals(200,controller.update("main","group",CLIENT,"ai-entry",ACTOR,"\"1\"",json("ai-entry","[\"reader\"]",readOnly,",\"enabled\":true")).code());
        assertEquals(Set.of("READ"),persistedPermission(setup,"ai-entry").grants().getFirst().actions());
        var revoked=evaluation(setup);
        try {
            assertTrue(aiAllowed(revoked,io.kestra.webserver.rbac.TypedAuthorization.Action.READ));
            assertFalse(aiAllowed(revoked,io.kestra.webserver.rbac.TypedAuthorization.Action.EXECUTE));
        } finally {revoked.close().toCompletableFuture().join();}
        assertEquals(200,controller.delete("main","group",CLIENT,"ai-entry",ACTOR,"\"2\"").code());
        assertEquals(404,controller.find("main","group",CLIENT,"ai-entry",ACTOR).code());
        var deleted=evaluation(setup);
        try {
            assertFalse(aiAllowed(deleted,io.kestra.webserver.rbac.TypedAuthorization.Action.READ));
            assertFalse(aiAllowed(deleted,io.kestra.webserver.rbac.TypedAuthorization.Action.EXECUTE));
        } finally {deleted.close().toCompletableFuture().join();}
    }

    @Test void legacyAiGrantSurvivesPublicControllerMetadataEditWithoutTenantPromotion()throws Exception {
        var setup=setup(ALL);var controller=setup.controller();
        assertEquals(200,controller.create("main","group",CLIENT,ACTOR,"\"1\"",json("legacy-ai","[\"reader\"]",AI_LEGACY,"")).code());
        var before=persistedPermission(setup,"legacy-ai").grants();
        var edited=new String(json("legacy-ai","[\"reader\"]",AI_LEGACY,",\"enabled\":true"),StandardCharsets.UTF_8)
            .replace("\"name\":\"Permission\"","\"name\":\"Renamed legacy permission\"").getBytes(StandardCharsets.UTF_8);
        assertEquals(200,controller.update("main","group",CLIENT,"legacy-ai",ACTOR,"\"1\"",
            edited).code());
        assertEquals("Renamed legacy permission",persistedPermission(setup,"legacy-ai").name());
        assertEquals(before,persistedPermission(setup,"legacy-ai").grants());
        var found=controller.find("main","group",CLIENT,"legacy-ai",ACTOR);
        assertEquals(200,found.code());
        assertEquals("NAMESPACE",new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(found.body()).path("grants").get(0).path("scope").asText());
        var evaluation=evaluation(setup);
        try {
            assertFalse(aiAllowed(evaluation,io.kestra.webserver.rbac.TypedAuthorization.Action.READ));
            assertFalse(aiAllowed(evaluation,io.kestra.webserver.rbac.TypedAuthorization.Action.EXECUTE));
        } finally {evaluation.close().toCompletableFuture().join();}
    }

    @Test void deniedAndInvalidAiMutationsLeaveAllPersistedRbacRowsUnchanged()throws Exception {
        var setup=setup(ALL);var controller=setup.controller();
        assertEquals(200,controller.create("main","group",CLIENT,ACTOR,"\"1\"",json("ai-entry","[\"reader\"]",AI_TENANT,"")).code());
        var before=storedRows(setup);
        when(setup.identities().current()).thenAnswer(call->setup.fixture().identity(Set.of("manager")));
        assertEquals(403,controller.create("main","group",CLIENT,ACTOR,"\"2\"",json("denied","[\"reader\"]",AI_TENANT,"")).code());
        assertEquals(before,storedRows(setup));
        assertEquals(403,controller.update("main","group",CLIENT,"ai-entry",ACTOR,"\"1\"",json("ai-entry","[\"reader\"]","[]",",\"enabled\":true")).code());
        assertEquals(before,storedRows(setup));
        assertEquals(403,controller.delete("main","group",CLIENT,"ai-entry",ACTOR,"\"1\"").code());
        assertEquals(before,storedRows(setup));
        when(setup.identities().current()).thenAnswer(call->setup.fixture().identity(ALL));
        assertEquals(400,controller.update("main","group",CLIENT,"ai-entry",ACTOR,"\"1\"",
            json("ai-entry","[\"reader\"]",AI_TENANT.replace("[\"READ\",\"EXECUTE\"]","[\"LIST\"]"),",\"enabled\":true")).code());
        assertEquals(before,storedRows(setup));
        assertEquals(412,controller.update("main","group",CLIENT,"ai-entry",ACTOR,"\"99\"",json("ai-entry","[\"reader\"]",AI_TENANT,",\"enabled\":true")).code());
        assertEquals(before,storedRows(setup));
    }

    @Test void createUpdateDeleteUseObjectRevisionAndReceiptWithoutPolicyDisclosure()throws Exception {
        var setup=setup(ALL);var controller=setup.controller();var before=setup.fixture().current();
        var created=controller.create("main","group",CLIENT,ACTOR,"\"1\"",json("new","[\"reader\"]","[]",""));
        assertEquals(200,created.code());assertInstanceOf(ApplicationPermission.Receipt.class,created.body());
        var receipt=(ApplicationPermission.Receipt)created.body();assertEquals(2,receipt.parentRevision());
        assertEquals(before.group().authorizationVersion(),setup.fixture().current().group().authorizationVersion());
        assertEquals(412,controller.update("main","group",CLIENT,"new",ACTOR,"\"2\"",json("new","[]","[]",",\"enabled\":true")).code());
        assertEquals(200,controller.update("main","group",CLIENT,"new",ACTOR,"\"1\"",json("new","[]","[]",",\"enabled\":true")).code());
        assertEquals(200,controller.delete("main","group",CLIENT,"new",ACTOR,"\"2\"").code());
        assertEquals(404,controller.find("main","group",CLIENT,"new",ACTOR).code());
    }

    @Test void policyOnlyWriterCannotChangeSupportButCanChangeGrantsAndCannotRead()throws Exception {
        var setup=setup(Set.of("policy-manager"));
        assertEquals(200,setup.controller().create("main","group",CLIENT,ACTOR,"\"1\"",json("new","[\"reader\"]","[]","" )).code());
        setup.fixture().update(current->ApplicationGroupChanges.createScope(current,"unsupported","Unsupported",""));
        var before=setup.fixture().current();
        assertEquals(403,setup.controller().create("main","group",CLIENT,ACTOR,"\"2\"",json("denied","[\"unsupported\"]","[]","" )).code());
        assertEquals(before,setup.fixture().current());
        var app=before.applications().getFirst();var permissions=app.permissions().stream().map(value->value.key().equals("allow-policy-manager")?
            value.replace(value.name(),value.description(),value.scopeKeys(),value.grants(),List.of(new ApplicationGroupPolicy.ManagementGrant(
                ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,Set.of(ApplicationGroupPolicy.ManagementAction.MANAGE))),"",true):value).toList();
        setup.fixture().update(current->ApplicationGroupChanges.permissions(current,"ui",app.revision(),permissions));
        assertEquals(403,setup.controller().find("main","group",CLIENT,"new",ACTOR).code());
        assertEquals(200,setup.controller().metadata("main","group",CLIENT,"new",ACTOR).code());
    }

    @Test void identityOnlyCannotAccessKestraPolicyAndLastAdminMutationRollsBack()throws Exception {
        var setup=setup(Set.of("manager"));var before=setup.fixture().current();
        assertEquals(403,setup.controller().list("main","group",CLIENT,ACTOR,1,20).code());
        assertEquals(403,setup.controller().create("main","group",CLIENT,ACTOR,"\"1\"",json("new","[]","[]","" )).code());
        assertEquals(before,setup.fixture().current());
        var admin=setup(ALL);var original=admin.fixture().current();
        assertEquals(409,admin.controller().delete("main","group",CLIENT,"allow-manager",ACTOR,"\"1\"").code());
        assertEquals(original,admin.fixture().current());
    }

    @Test void strictInputsMissingRevisionAndUndefinedScopeHaveNoSideEffects()throws Exception {
        var setup=setup(ALL);var controller=setup.controller();var before=setup.fixture().current();
        assertEquals(428,controller.create("main","group",CLIENT,ACTOR,null,json("new","[]","[]","" )).code());
        assertEquals(400,controller.create("main","group",CLIENT,ACTOR,"W/\"1\"",json("new","[]","[]","" )).code());
        assertEquals(400,controller.create("main","group",CLIENT,ACTOR,"\"1\"",json("new","[\"unknown\"]","[]","" )).code());
        assertEquals(400,controller.create("main","group",CLIENT,ACTOR,"\"1\"",json("new","[]","[]",",\"implementationKind\":\"external-declaration\"" )).code());
        assertEquals(before,setup.fixture().current());
        byte[] minimum="{\"key\":\"minimum\",\"name\":\"Minimal\",\"scopeKeys\":[],\"grants\":[],\"managementGrants\":[]}".getBytes(StandardCharsets.UTF_8);
        assertEquals(200,controller.create("main","group",CLIENT,ACTOR,"\"1\"",minimum).code());
        assertEquals(400,controller.update("main","group",CLIENT,"minimum",ACTOR,"\"1\"",minimum).code());
        assertEquals(400,controller.create("main","group",CLIENT,ACTOR,"\"2\"","{\"key\":\"missing\",\"name\":\"Missing\",\"scopeKeys\":[],\"grants\":[]}".getBytes(StandardCharsets.UTF_8)).code());
        when(setup.fixture().protocol.client(any(),eq("https://issuer.example"),eq("external"))).thenReturn(java.util.Optional.of(
            new io.kestra.webserver.oidc.OidcProtocolStateV1.Client("https://issuer.example","external",true,Set.of("openid"),false)));
        setup.fixture().update(current->ApplicationGroupChanges.registerApplication(current,"external","",Set.of("external")));
        byte[] external="{\"key\":\"text\",\"name\":\"Administrator\",\"scopeKeys\":[\"reader\"],\"details\":\"all admin\"}".getBytes(StandardCharsets.UTF_8);
        assertEquals(200,controller.create("main","group","~ZXh0ZXJuYWw",ACTOR,"\"1\"",external).code());
        assertEquals(200,controller.update("main","group","~ZXh0ZXJuYWw","text",ACTOR,"\"1\"",external).code());
    }

    @Test void legalMutationMetadataKeyKeepsReadManageSeparationThroughCreateUpdateAndDelete()throws Exception {
        var setup=setup(ALL);var controller=setup.controller();String key="mutation-metadata";
        assertEquals(200,controller.create("main","group",CLIENT,ACTOR,"\"1\"",json(key,"[\"reader\"]","[]","")).code());
        var initial=setup.fixture().current();var application=initial.applications().getFirst();
        var permissions=new java.util.ArrayList<>(application.permissions());
        permissions.add(new ApplicationPermission("read-only","Reader","",Set.of("reader"),List.of(),List.of(new ApplicationGroupPolicy.ManagementGrant(
            ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,Set.of(ApplicationGroupPolicy.ManagementAction.READ))),"",true,1));
        permissions= new java.util.ArrayList<>(permissions.stream().map(value->value.key().equals("allow-policy-manager")?
            value.replace(value.name(),value.description(),value.scopeKeys(),value.grants(),List.of(new ApplicationGroupPolicy.ManagementGrant(
                ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,Set.of(ApplicationGroupPolicy.ManagementAction.MANAGE))),"",true):value).toList());
        var updatedPermissions=List.copyOf(permissions);
        setup.fixture().update(current->ApplicationGroupChanges.permissions(current,"ui",application.revision(),updatedPermissions));
        when(setup.identities().current()).thenAnswer(call->setup.fixture().identity(Set.of("reader")));
        var read=controller.find("main","group",CLIENT,key,ACTOR);assertEquals(200,read.code());
        assertEquals(key,new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(read.body()).path("key").textValue());
        assertEquals(403,controller.metadata("main","group",CLIENT,key,ACTOR).code());
        assertEquals(403,controller.applicationMetadata("main","group",CLIENT,ACTOR).code());
        assertEquals(403,controller.update("main","group",CLIENT,key,ACTOR,"\"1\"",json(key,"[\"reader\"]","[]",",\"enabled\":true")).code());
        when(setup.identities().current()).thenAnswer(call->setup.fixture().identity(Set.of("policy-manager")));
        assertEquals(403,controller.find("main","group",CLIENT,key,ACTOR).code());
        assertEquals(200,controller.metadata("main","group",CLIENT,key,ACTOR).code());
        assertEquals(200,controller.applicationMetadata("main","group",CLIENT,ACTOR).code());
        assertEquals(200,controller.update("main","group",CLIENT,key,ACTOR,"\"1\"",json(key,"[\"reader\"]","[]",",\"enabled\":true")).code());
        assertEquals(200,controller.delete("main","group",CLIENT,key,ACTOR,"\"2\"").code());
        when(setup.identities().current()).thenAnswer(call->setup.fixture().identity(Set.of("reader")));
        assertEquals(404,controller.find("main","group",CLIENT,key,ACTOR).code());
    }

    @Test void metadataChangesKeepTrustedIdentityUsableAndConsentUsesUnifiedEntities()throws Exception {
        var setup=setup(ALL);var identity=setup.fixture().identity(ALL);
        setup.fixture().update(current->ApplicationGroupChanges.groupMetadata(current,"Renamed","Description",true));
        var runtime=new ApplicationGroupRuntimePolicies(setup.fixture().service,setup.identities());
        try(var context=runtime.open(identity,"main")) {
            assertTrue(context.authorizeManagement(ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,ApplicationGroupPolicy.ManagementAction.MANAGE,"group"));
        }
        var provider=new io.kestra.extensions.rbac.declaration.ApplicationScopeDeclarationProvider(setup.fixture().service);
        var projection=provider.current("group","ui");
        assertEquals(Set.of("manager","policy-manager","reader"),Set.copyOf(projection.supportedScopeKeys()));
        assertEquals("allow-manager",projection.scopes().stream().filter(value->value.key().equals("manager")).findFirst().orElseThrow().permissions().getFirst().key());
    }

    @Test void permissionPagesSortByStableKeyBeforeSlicingUnorderedStorage()throws Exception {
        var setup=setup(ALL);var current=setup.fixture().current();var application=current.applications().getFirst();
        var permissions=new java.util.ArrayList<>(application.permissions());
        for(int index=11;index>=0;index--)permissions.add(new ApplicationPermission("p"+String.format("%02d",index),"Permission","",Set.of("reader"),List.of(),List.of(),"",true,1));
        setup.fixture().update(snapshot->ApplicationGroupChanges.permissions(snapshot,"ui",application.revision(),permissions));
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var first=mapper.valueToTree(setup.controller().list("main","group",CLIENT,ACTOR,1,10).body());
        var second=mapper.valueToTree(setup.controller().list("main","group",CLIENT,ACTOR,2,10).body());
        var keys=new java.util.ArrayList<String>();first.path("items").forEach(value->keys.add(value.path("key").asText()));
        second.path("items").forEach(value->keys.add(value.path("key").asText()));
        assertEquals(15,first.path("total").asInt());assertEquals(15,second.path("total").asInt());
        assertEquals(permissions.stream().map(ApplicationPermission::key).sorted().toList(),keys);
        assertEquals(15,Set.copyOf(keys).size());
        assertEquals(first,mapper.valueToTree(setup.controller().list("main","group",CLIENT,ACTOR,1,10).body()));
    }
}
