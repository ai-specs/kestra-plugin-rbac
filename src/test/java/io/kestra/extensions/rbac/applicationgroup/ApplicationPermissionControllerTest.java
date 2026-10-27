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
