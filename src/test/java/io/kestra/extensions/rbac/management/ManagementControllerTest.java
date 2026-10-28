package io.kestra.extensions.rbac.management;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.extensions.rbac.ApplicationGroupRuntimePolicies;
import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicyParser;
import io.kestra.extensions.rbac.store.ApplicationGroupPolicyStore;
import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupDirectoryReader;
import io.kestra.webserver.oidc.TrustedIdentityV1;
import io.micronaut.context.BeanProvider;
import io.micronaut.security.authentication.Authentication;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Controller wire tests use the real schema 2 runtime and real policy persistence.
 * Host authentication/CSRF and transport filters remain a separate real HTTP acceptance gate. */
class ManagementControllerTest {
    private static final String ISSUER="https://identity.example.invalid";
    private static final String MANAGER="console-manage-v1";
    private static final ObjectMapper MAPPER=new ObjectMapper();
    private static byte[] json(String value) {return value.getBytes(StandardCharsets.UTF_8);}
    private static Authentication owner() {return Authentication.build("owner",List.of("ignored-legacy-role"));}
    private static <T> BeanProvider<T> provider(T value) {
        return new BeanProvider<>() {
            public T get(){return value;}public boolean isUnique(){return value!=null;}public boolean isPresent(){return value!=null;}
        };
    }
    private static final String POLICY="""
        {"schemaVersion":2,"applicationGroups":{"console":{"issuer":"https://identity.example.invalid",
        "audiences":["kestra-ui"],"enabled":true,"scopes":{
        "console-manage-v1":{"description":"Manager","enabled":true,"grants":[],"managementGrants":[
        {"domain":"IDENTITY","actions":["READ","MANAGE"],"applicationGroupIds":[]},
        {"domain":"LOCAL_POLICY","actions":["READ","MANAGE"],"applicationGroupIds":[]}]}}}}}
        """;
    private record Setup(ManagementController controller,ApplicationGroupPolicyStore store,ApplicationGroupDirectoryReader directory,
                         UserDirectoryAccess users,ApplicationDirectoryAccess applications,TrustedIdentityV1 identities) {}
    private Setup setup(long directoryRevision,Set<String> scopes) {
        var data=new JdbcDataSource();data.setURL("jdbc:h2:mem:http-"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        var store=spy(new ApplicationGroupPolicyStore(data,new ApplicationGroupPolicyParser()));store.initializeSchema();store.initialize(json(POLICY),"installer");
        var directory=mock(ApplicationGroupDirectoryReader.class);var identities=mock(TrustedIdentityV1.class);
        when(identities.current()).thenReturn(new TrustedIdentityV1.Identity(1,ISSUER,"owner","kestra-ui",Set.of("kestra-ui"),"main","console",Set.of("openid"),scopes,1,97,directoryRevision));
        var shared=new ApplicationGroupDirectoryReader.GroupView("console","main",ISSUER,"Console",true,directoryRevision,97);
        var manager=mock(ApplicationGroupDirectoryReader.ScopeView.class);
        when(manager.applicationGroupId()).thenReturn("console");when(manager.key()).thenReturn(MANAGER);
        when(manager.name()).thenReturn("Manager");when(manager.description()).thenReturn("");when(manager.revision()).thenReturn(2L);
        when(directory.boundaries()).thenReturn(List.of(new ApplicationGroupDirectoryReader.Boundary(shared,List.of(manager),Set.of("kestra-ui"),Set.of(MANAGER),List.of(new ApplicationGroupDirectoryReader.ProtocolScope("openid","OIDC")))));
        try {when(directory.boundaries(any(java.sql.Connection.class))).thenAnswer(call->directory.boundaries());}
        catch(java.sql.SQLException failure){throw new AssertionError(failure);}
        var users=mock(UserDirectoryAccess.class);var applications=mock(ApplicationDirectoryAccess.class);
        when(applications.invoke(any())).thenAnswer(invocation->{
            ApplicationDirectoryAccess.Request request=invocation.getArgument(0);
            if(request==null)return null;
            if(request.resource()==ApplicationDirectoryAccess.Resource.CLIENT && request.operation()==ApplicationDirectoryAccess.Operation.FIND
                && "console".equals(request.applicationGroupId()) && "kestra-ui".equals(request.key()))
                return new ApplicationGroupApplication("console","kestra-ui","",Set.of("kestra-ui"),Set.of(MANAGER),1);
            return null;
        });
        when(directory.runtimePolicy()).thenAnswer(call->store.readCurrent());
        try {when(directory.runtimePolicy(any(java.sql.Connection.class))).thenAnswer(call->store.readCurrent(call.getArgument(0)));}
        catch(java.sql.SQLException failure){throw new AssertionError(failure);}
        var runtime=new ApplicationGroupRuntimePolicies(directory,identities);
        var controller=new ManagementController(runtime,store,provider(users),provider(applications));
        try {
            var enabled=ManagementController.class.getDeclaredField("applicationGroups");enabled.setAccessible(true);enabled.set(controller,true);
            var application=ManagementController.class.getDeclaredField("runtimeApplicationClientId");application.setAccessible(true);application.set(controller,"kestra-ui");
        } catch(ReflectiveOperationException failure) {throw new AssertionError(failure);}
        clearInvocations(store,directory);
        return new Setup(controller,store,directory,users,applications,identities);
    }
    @Test void authorizedMissingDirectoryTargetsReturn404WithoutChangingCapabilityDenial() {
        var setup=setup(3,Set.of(MANAGER));
        var before=setup.store.readCurrent().canonicalJson();
        assertEquals(404,setup.controller.applicationGroup("main","missing",owner()).code());
        assertEquals(404,setup.controller.applicationGroupScopes("main","missing",owner(),1,25).code());
        assertEquals(404,setup.controller.clients("main","missing",owner(),1,25).code());
        assertEquals(404,setup.controller.assignments("main","missing",owner(),1,25).code());
        assertEquals(404,setup.controller.applicationGroupScope("main","missing","reader",owner()).code());
        assertEquals(404,setup.controller.createApplicationGroupScope("main","missing",owner(),"\"1\"",json("{\"key\":\"reader\",\"name\":\"Reader\",\"description\":\"\"}")).code());
        assertEquals(404,setup.controller.updateApplicationGroupScope("main","missing","reader",owner(),"\"1\"",json("{\"key\":\"reader\",\"name\":\"Reader\",\"description\":\"\"}")).code());
        assertEquals(404,setup.controller.deleteApplicationGroupScope("main","missing","reader",owner(),"\"1\"").code());
        assertEquals(404,setup.controller.clientsDetail("main","missing",clientKey(),owner()).code());
        assertEquals(404,setup.controller.clientsCreate("main","missing",owner(),"\"1\"",json("{\"clientId\":\"new-client\",\"description\":\"\",\"audiences\":[]}")).code());
        assertEquals(404,setup.controller.clientsUpdate("main","missing",clientKey(),owner(),"\"1\"",json("{\"clientId\":\"kestra-ui\",\"description\":\"\",\"audiences\":[]}")).code());
        assertEquals(404,setup.controller.clientsDelete("main","missing",clientKey(),owner(),"\"1\"").code());
        assertEquals(404,setup.controller.assignmentsDetail("main","missing","target",owner()).code());
        assertEquals(404,setup.controller.assignmentsCreate("main","missing",owner(),"\"1\"",json("{\"issuer\":\""+ISSUER+"\",\"subject\":\"target\",\"scopeKeys\":[]}")).code());
        assertEquals(404,setup.controller.assignmentsUpdate("main","missing","target",owner(),"\"1\"",json("{\"issuer\":\""+ISSUER+"\",\"subject\":\"target\",\"scopeKeys\":[]}")).code());
        assertEquals(404,setup.controller.assignmentsDelete("main","missing","target",owner(),"\"1\"").code());
        assertEquals(404,setup.controller.updateApplicationGroup("main","missing",owner(),"\"1\"",json("{\"displayName\":\"Missing\",\"description\":\"\",\"active\":true}")).code());
        assertEquals(404,setup.controller.deleteApplicationGroup("main","missing",owner(),"\"1\"").code());
        assertEquals(403,setup.controller.capabilities("main",owner(),"missing").code());
        assertEquals(403,setup.controller.runtimePolicy("main","missing",clientKey(),owner()).code());
        assertEquals(before,setup.store.readCurrent().canonicalJson());verifyNoInteractions(setup.applications);
    }

    @Test void unprivilegedMissingAndExistingDirectoryTargetsBothDenyWithoutEnumeration() {
        var setup=setup(3,Set.of());
        for(String target:List.of("console","missing")) {
            assertEquals(403,setup.controller.applicationGroup("main",target,owner()).code());
            assertEquals(403,setup.controller.deleteApplicationGroup("main",target,owner(),"\"1\"").code());
        }
        verifyNoInteractions(setup.applications);
    }

    @Test void directoryReadAndManageRemainIndependentOfLocalPolicyAndRejectForeignIssuer() {
        for(var action:List.of("READ","MANAGE")) {
            var setup=setup(3,Set.of(MANAGER));
            var policy=responsePolicyForIdentityOnly(action);
            setup.store.replace(setup.store.readCurrent().revision(),policy,"owner");
            assertEquals(action.equals("READ")?404:403,setup.controller.applicationGroup("main","missing",owner()).code());
            assertEquals(action.equals("MANAGE")?404:403,setup.controller.deleteApplicationGroup("main","missing",owner(),"\"1\"").code());
            var current=setup.directory.boundaries();
            var foreign=new ApplicationGroupDirectoryReader.GroupView("foreign","main","https://foreign.invalid","Foreign",true,1,1);
            var values=new java.util.ArrayList<>(current);values.add(new ApplicationGroupDirectoryReader.Boundary(foreign,List.of(),Set.of(),Set.of(),List.of()));
            when(setup.directory.boundaries()).thenReturn(values);
            assertEquals(403,setup.controller.applicationGroup("main","foreign",owner()).code());
            assertEquals(403,setup.controller.deleteApplicationGroup("main","foreign",owner(),"\"1\"").code());
            verifyNoInteractions(setup.applications);
        }
    }

    private byte[] responsePolicyForIdentityOnly(String action) {
        try {
            var policy=(com.fasterxml.jackson.databind.node.ObjectNode)MAPPER.readTree(POLICY);
            var grants=(com.fasterxml.jackson.databind.node.ArrayNode)policy.path("applicationGroups").path("console").path("scopes").path(MANAGER).path("managementGrants");
            grants.remove(1);((com.fasterxml.jackson.databind.node.ObjectNode)grants.get(0)).putArray("actions").add(action);
            return json(policy.toString());
        } catch(java.io.IOException failure) {throw new AssertionError(failure);}
    }

    @Test void unavailableDirectoryIs503RatherThanMissingTarget404() {
        var setup=setup(3,Set.of(MANAGER));
        when(setup.directory.boundaries()).thenThrow(new IllegalStateException("Directory unavailable"));
        assertEquals(503,setup.controller.applicationGroup("main","missing",owner()).code());
        assertEquals(503,setup.controller.deleteApplicationGroup("main","missing",owner(),"\"1\"").code());
        verifyNoInteractions(setup.applications);
    }

    private JsonNode response(io.micronaut.http.HttpResponse<?> response) {return MAPPER.valueToTree(response.body());}
    private void onlyRuntimeMembershipReads(Setup setup) {
        verifyNoInteractions(setup.users);
        for(var invocation:mockingDetails(setup.applications).getInvocations()) {
            assertEquals("invoke",invocation.getMethod().getName());
            var request=(ApplicationDirectoryAccess.Request)invocation.getArgument(0);
            assertEquals(ApplicationDirectoryAccess.Resource.CLIENT,request.resource());
            assertEquals(ApplicationDirectoryAccess.Operation.FIND,request.operation());
            assertEquals("console",request.applicationGroupId());assertEquals("kestra-ui",request.key());
            assertEquals(ISSUER,request.identity().issuer());assertNull(request.body());assertNull(request.revision());
        }
    }
    private String scopeBody() throws Exception {
        var value=(com.fasterxml.jackson.databind.node.ObjectNode)MAPPER.readTree(POLICY).path("applicationGroups").path("console").path("scopes").path(MANAGER).deepCopy();
        value.put("scopeKey",MANAGER);value.put("description","Updated manager");return value.toString();
    }
    private String scopeKey() {return MANAGER;}
    private String clientKey() {return "~"+Base64.getUrlEncoder().withoutPadding().encodeToString(json("kestra-ui"));}
    @Test void directoryChangesKeepOrdinaryManagementAvailableWithoutRecovery()throws Exception {
        var s=setup(4,Set.of(MANAGER));
        var capabilities=response(s.controller.capabilities("main",owner(),"console"));
        assertTrue(capabilities.path("allowed").booleanValue());assertTrue(capabilities.path("localPolicyManagement").booleanValue());
        assertTrue(capabilities.path("localPolicyManage").booleanValue());assertTrue(capabilities.path("identityManagement").booleanValue());
        assertFalse(capabilities.has("reconciliationAllowed"));assertFalse(capabilities.has("needsReconciliation"));
        String before=s.store.readCurrent().canonicalJson();
        assertEquals(200,s.controller.runtimePolicy("main","console",clientKey(),owner()).code());
        assertEquals(400,s.controller.createRuntimeScope("main","console",clientKey(),owner(),"\"1\"",json("{\"scopeKey\":\"new\",\"description\":\"New\",\"enabled\":true,\"grants\":[]}")).code());
        assertEquals(before,s.store.readCurrent().canonicalJson());onlyRuntimeMembershipReads(s);
        assertEquals(200,s.controller.updateRuntimeScope("main","console",clientKey(),scopeKey(),owner(),"\"1\"",json(scopeBody())).code());
        assertEquals("Updated manager",s.store.readCurrent().policy().applicationGroups().get("console").scopes().get(MANAGER).description());
        var after=response(s.controller.capabilities("main",owner(),"console"));
        assertTrue(after.path("localPolicyManage").booleanValue());assertTrue(after.path("identityManagement").booleanValue());assertFalse(after.path("needsReconciliation").booleanValue());
    }
    @Test void mixedUnknownScopeCannotUseKnownSubsetAndHasNoSideEffects()throws Exception {
        var s=setup(4,Set.of(MANAGER,"unknown-scope"));String before=s.store.readCurrent().canonicalJson();
        assertFalse(response(s.controller.capabilities("main",owner(),"console")).path("allowed").booleanValue());
        assertEquals(403,s.controller.updateRuntimeScope("main","console",clientKey(),scopeKey(),owner(),"\"1\"",json(scopeBody())).code());
        assertEquals(403,s.controller.runtimePolicy("main","console",clientKey(),owner()).code());
        assertEquals(before,s.store.readCurrent().canonicalJson());verifyNoInteractions(s.users,s.applications);
    }
    @Test void oneRequestReadsOnePolicyAndDirectoryAndProjectionDoesNotLeakReachabilityOrEpoch() {
        var s=setup(3,Set.of(MANAGER));var result=s.controller.runtimePolicy("main","console",clientKey(),owner());assertEquals(200,result.code());
        verify(s.store,times(1)).readCurrent();verify(s.directory,times(1)).boundaries();
        var value=response(result);assertEquals(1,value.path("revision").longValue());
        assertEquals("console",value.path("applicationGroupId").textValue());assertEquals("kestra-ui",value.path("clientId").textValue());
        assertEquals("Manager",value.path("policy").path("scopes").path(MANAGER).path("description").textValue());
        String body=value.toString();for(String field:List.of("directoryRevision","authorizationVersion","reachableManagementScopes","subject","assignments","applicationGroups"))assertFalse(body.contains("\""+field+"\""));
        onlyRuntimeMembershipReads(s);
    }
    @Test void strongRevisionMalformedJsonAndImmutablePathRejectWithoutWriting()throws Exception {
        var s=setup(3,Set.of(MANAGER));String before=s.store.readCurrent().canonicalJson();
        String body=scopeBody();
        assertEquals(428,s.controller.updateRuntimeScope("main","console",clientKey(),scopeKey(),owner(),null,json(body)).code());
        assertEquals(400,s.controller.updateRuntimeScope("main","console",clientKey(),scopeKey(),owner(),"W/\"1\"",json(body)).code());
        assertEquals(400,s.controller.updateRuntimeScope("main","console",clientKey(),scopeKey(),owner(),"\"1\"",json(body+" {} ")).code());
        assertEquals(400,s.controller.updateRuntimeScope("main","console",clientKey(),scopeKey(),owner(),"\"1\"",json(body.replace(MANAGER,"other"))).code());
        assertEquals(400,s.controller.updateRuntimeScope("main","console",clientKey(),"~YR",owner(),"\"1\"",json("{}")).code());
        assertEquals(before,s.store.readCurrent().canonicalJson());
    }
    @Test void permanentSubjectAndRolesCannotSupplyTrustedIdentity() {
        var s=setup(3,Set.of(MANAGER));when(s.identities.current()).thenReturn(null);
        assertEquals(403,s.controller.runtimePolicy("main","console",clientKey(),owner()).code());
        assertEquals(401,s.controller.runtimePolicy("main","console",clientKey(),null).code());verifyNoInteractions(s.users,s.applications);
    }
    @Test void legacyPolicyRoutesAreNotRegisteredAndRuntimeRoutesAlwaysCarryApplicationContext() throws Exception {
        var routes=new java.util.ArrayList<String>();
        for(var method:ManagementController.class.getDeclaredMethods())for(var annotation:method.getDeclaredAnnotations()) {
            if(!Set.of("Get","Post","Put","Delete").contains(annotation.annotationType().getSimpleName()))continue;
            routes.add((String)annotation.annotationType().getMethod("value").invoke(annotation));
        }
        for(var route:routes) {
            assertFalse(route.matches("^/(?:policy|projects|roles|bindings|role-mappings|identity)(?:[/{?].*)?$"),route);
            assertFalse(route.contains("reconciliation") || route.contains("binding-options"),route);
        }
        var runtime=routes.stream().filter(route->route.contains("runtime-permissions")).toList();
        assertEquals(0,runtime.size());
        assertTrue(runtime.stream().allMatch(route->route.startsWith("/application-groups/{id}/applications/{clientKey}/runtime-permissions")));
    }
    @Test void directoryTargetAndBodyCannotChangeIssuerOrOpaqueScope() {
        var s=setup(3,Set.of(MANAGER));
        String key="~"+Base64.getUrlEncoder().withoutPadding().encodeToString(json("a/read"));
        assertEquals(400,s.controller.updateApplicationGroupScope("main","console",key,owner(),"\"2\"",json("{\"scope\":\"different\",\"name\":\"Different\",\"description\":\"x\",\"active\":true,\"resourceIntents\":[],\"managementIntents\":[]}")).code());
        assertEquals(403,s.controller.assignmentsCreate("main","console",owner(),"\"3\"",json("{\"issuer\":\"https://other.invalid\",\"subject\":\"user\",\"scopeKeys\":[]}")).code());
        verifyNoInteractions(s.applications);
    }
    @Test void temporaryAccountCreateRejectsCallerPasswordAndReturnsExactNoStoreReceipt() {
        var s=setup(3,Set.of(MANAGER));
        assertEquals(400,s.controller.createUser("main",owner(),json("{\"username\":\"target\",\"password\":\"caller-password\"}")).code());
        verifyNoInteractions(s.users);
        when(s.users.createTemporary(new TemporaryAccountCommands.Create("target","","")))
            .thenReturn(new TemporaryAccountCommands.Created("target",0,"temporary-test-only"));
        var created=s.controller.createUser("main",owner(),json("{\"username\":\"target\"}"));
        assertEquals(201,created.code());assertEquals("no-store",created.header("Cache-Control"));assertEquals("\"0\"",created.header("ETag"));
        assertEquals(Set.of("subject","revision","temporaryPassword"),MAPPER.convertValue(created.body(),java.util.Map.class).keySet());
        verify(s.users,never()).create(any(),any(),any(),any());
    }

    @Test void accountDeleteRequiresStrongRevisionRejectsSelfAndReturns204WithoutRead() {
        var s=setup(3,Set.of(MANAGER));
        assertEquals(428,s.controller.deleteUser("main",owner(),"target",null).code());
        assertEquals(400,s.controller.deleteUser("main",owner(),"target","W/\"0\"").code());
        assertEquals(403,s.controller.deleteUser("main",owner(),"owner","\"0\"").code());
        verifyNoInteractions(s.users);
        var deleted=s.controller.deleteUser("main",owner(),"target","\"0\"");
        assertEquals(204,deleted.code());assertNull(deleted.body());
        verify(s.users).delete("target",0);verifyNoMoreInteractions(s.users);
    }

    @Test void missingPublishedAccountV2FailsClosedWithoutCallingOldCreate() {
        var s=setup(3,Set.of(MANAGER));
        doCallRealMethod().when(s.users).createTemporary(any());doCallRealMethod().when(s.users).delete(any(),anyLong());
        assertEquals(503,s.controller.createUser("main",owner(),json("{\"username\":\"target\"}")).code());
        assertEquals(503,s.controller.deleteUser("main",owner(),"target","\"0\"").code());
        verify(s.users,never()).create(any(),any(),any(),any());
    }

    @Test void manageOnlyAccountCommandsDoNotReadDirectoryAndConflictsStayExact()throws Exception {
        var s=setup(3,Set.of(MANAGER));
        var policy=(com.fasterxml.jackson.databind.node.ObjectNode)MAPPER.readTree(POLICY);
        var grants=(com.fasterxml.jackson.databind.node.ArrayNode)policy.path("applicationGroups").path("console").path("scopes").path(MANAGER).path("managementGrants");
        for(var grant:grants)((com.fasterxml.jackson.databind.node.ObjectNode)grant).putArray("actions").add("MANAGE");
        s.store.replace(1,json(policy.toString()),"installer");
        assertEquals(403,s.controller.users("main",owner(),1,25).code());
        when(s.users.createTemporary(any())).thenReturn(new TemporaryAccountCommands.Created("target",1,"temporary-test-only"));
        assertEquals(201,s.controller.createUser("main",owner(),json("{\"username\":\"target\"}")).code());
        doThrow(new UserDirectoryAccess.DirectoryFailure(UserDirectoryAccess.Failure.CONFLICT)).when(s.users).delete("target",1);
        assertEquals(412,s.controller.deleteUser("main",owner(),"target","\"1\"").code());
        verify(s.users,never()).list(anyInt(),anyInt());verify(s.users,never()).find(any());
    }

    @Test void readOnlyAccountCommandsNeverReachDirectoryMutation()throws Exception {
        var s=setup(3,Set.of(MANAGER));
        var policy=(com.fasterxml.jackson.databind.node.ObjectNode)MAPPER.readTree(POLICY);
        var grants=(com.fasterxml.jackson.databind.node.ArrayNode)policy.path("applicationGroups").path("console").path("scopes").path(MANAGER).path("managementGrants");
        for(var grant:grants)((com.fasterxml.jackson.databind.node.ObjectNode)grant).putArray("actions").add("READ");
        s.store.replace(1,json(policy.toString()),"installer");
        assertEquals(403,s.controller.createUser("main",owner(),json("{\"username\":\"target\"}")).code());
        assertEquals(403,s.controller.deleteUser("main",owner(),"target","\"0\"").code());
        verifyNoInteractions(s.users);
    }

    @Test void missingParticipantAndAbortedDeleteReturnNoReceiptOrInternalDetail() {
        var s=setup(3,Set.of(MANAGER));
        doThrow(new UserDirectoryAccess.DirectoryFailure(UserDirectoryAccess.Failure.UNAVAILABLE)).when(s.users).delete("target",0);
        var missing=s.controller.deleteUser("main",owner(),"target","\"0\"");
        assertEquals(503,missing.code());assertEquals("directory_unavailable",response(missing).path("code").textValue());
        assertEquals("no-store",missing.header("Cache-Control"));
        doThrow(new IllegalStateException("private database detail")).when(s.users).delete("target",0);
        var aborted=s.controller.deleteUser("main",owner(),"target","\"0\"");
        assertEquals(500,aborted.code());assertFalse(response(aborted).toString().contains("private database detail"));
        assertEquals(1,response(aborted).size());verify(s.users,times(2)).delete("target",0);
        verify(s.users,never()).find(any());verify(s.users,never()).list(anyInt(),anyInt());
    }

    @Test void profileStateAndPasswordReturnOnlySubjectRevisionAndEtag() {
        var s=setup(3,Set.of(MANAGER));
        var user=new UserDirectoryAccess.User("target","Private name","private@example.invalid",true,8);
        when(s.users.update("target",7,"Name","mail")).thenReturn(user);
        when(s.users.setActive("target",7,false)).thenReturn(user);
        when(s.users.resetPassword("target",7,"test-password-only")).thenReturn(user);
        var responses=List.of(s.controller.profile("main",owner(),"\"7\"",json("{\"subject\":\"target\",\"name\":\"Name\",\"email\":\"mail\"}")),
            s.controller.state("main",owner(),"\"7\"",json("{\"subject\":\"target\",\"active\":false}")),
            s.controller.password("main",owner(),"\"7\"",json("{\"subject\":\"target\",\"password\":\"test-password-only\"}")));
        for(var saved:responses) {
            assertEquals(200,saved.code());assertEquals("\"8\"",saved.header("ETag"));assertEquals("no-store",saved.header("Cache-Control"));
            assertEquals(2,response(saved).size());assertEquals("target",response(saved).path("subject").textValue());
            assertEquals(8,response(saved).path("revision").longValue());
        }
    }

    @Test void lastAdministratorUserGuardPreserves409AndAccountConflictPreserves412() {
        var s=setup(3,Set.of(MANAGER));
        when(s.users.setActive("last",0,false)).thenThrow(new UserDirectoryAccess.DirectoryFailure(UserDirectoryAccess.Failure.LAST_ADMIN));
        assertEquals(409,s.controller.state("main",owner(),"\"0\"",json("{\"subject\":\"last\",\"active\":false}")).code());
        when(s.users.update("user",0,"Name","mail")).thenThrow(new UserDirectoryAccess.DirectoryFailure(UserDirectoryAccess.Failure.CONFLICT));
        assertEquals(412,s.controller.profile("main",owner(),"\"0\"",json("{\"subject\":\"user\",\"name\":\"Name\",\"email\":\"mail\"}")).code());
    }
    @Test void manageWithoutReadCanWriteButReceivesNoPolicyData() throws Exception {
        var s=setup(3,Set.of(MANAGER));
        var policy=(com.fasterxml.jackson.databind.node.ObjectNode)MAPPER.readTree(POLICY);
        var grants=(com.fasterxml.jackson.databind.node.ArrayNode)policy.path("applicationGroups").path("console").path("scopes").path(MANAGER).path("managementGrants");
        grants.remove(0);((com.fasterxml.jackson.databind.node.ObjectNode)grants.get(0)).putArray("actions").add("MANAGE");
        s.store.replace(1,json(policy.toString()),"installer");
        assertEquals(403,s.controller.runtimePolicy("main","console",clientKey(),owner()).code());
        assertEquals(403,s.controller.runtimePolicy("main","different",clientKey(),owner()).code());
        assertEquals(403,s.controller.runtimeScopes("main","console",clientKey(),owner()).code());
        assertEquals(403,s.controller.users("main",owner(),1,20).code());
        assertEquals(403,s.controller.applicationGroups("main",owner(),1,20).code());
        var capabilities=response(s.controller.capabilities("main",owner(),"console"));
        assertFalse(capabilities.path("localPolicyRead").booleanValue());assertTrue(capabilities.path("localPolicyManage").booleanValue());
        assertEquals(2,capabilities.path("runtimePolicyRevision").longValue());
        var input=(com.fasterxml.jackson.databind.node.ObjectNode)policy.path("applicationGroups").path("console").path("scopes").path(MANAGER).deepCopy();
        input.put("scopeKey",MANAGER);input.put("description","Write without read");
        var saved=response(s.controller.updateRuntimeScope("main","console",clientKey(),MANAGER,owner(),"\"2\"",json(input.toString())));
        assertEquals(1,saved.size());assertEquals(3,saved.path("revision").longValue());assertFalse(saved.has("policy"));
        assertEquals("Write without read",s.store.readCurrent().policy().applicationGroups().get("console").scopes().get(MANAGER).description());
        assertEquals(403,s.controller.runtimePolicy("main","console",clientKey(),owner()).code());
        onlyRuntimeMembershipReads(s);
    }
    @Test void capabilityProjectionUsesTheExactTargetAndConfiguredApplicationWithoutChangingAuthorization() throws Exception {
        var setup=setup(3,Set.of(MANAGER));
        var configuration=ManagementController.class.getDeclaredField("runtimeApplicationClientId");configuration.setAccessible(true);
        configuration.set(setup.controller,"");
        var initial=response(setup.controller.capabilities("main",owner(),"console"));
        assertEquals("",initial.path("runtimeApplicationClientId").textValue());
        configuration.set(setup.controller,"kestra-ui");
        var console=setup.directory.boundaries().getFirst();
        var target=new ApplicationGroupDirectoryReader.GroupView("target","main",ISSUER,"Target",true,1,1);
        when(setup.directory.boundaries()).thenReturn(List.of(console,new ApplicationGroupDirectoryReader.Boundary(target,List.of(),Set.of("api"),Set.of(),List.of())));
        String before=setup.store.readCurrent().canonicalJson();
        var own=response(setup.controller.capabilities("main",owner(),"console"));
        assertEquals("kestra-ui",own.path("runtimeApplicationClientId").textValue());
        assertEquals("console",own.path("applicationGroupId").textValue());
        assertTrue(own.path("localPolicyRead").booleanValue());assertTrue(own.path("localPolicyManage").booleanValue());
        var outside=response(setup.controller.capabilities("main",owner(),"target"));
        assertEquals("target",outside.path("applicationGroupId").textValue());
        assertFalse(outside.path("localPolicyRead").booleanValue());assertFalse(outside.path("localPolicyManage").booleanValue());
        assertEquals(403,setup.controller.capabilities("main",owner(),"unknown").code());
        assertEquals(before,setup.store.readCurrent().canonicalJson());
        verifyNoInteractions(setup.users,setup.applications);
    }
    @Test void groupScopeUsesKeyMetadataAndRejectsOldPayloadsAndMismatchedHostOutput() {
        var setup=setup(3,Set.of(MANAGER));
        var scope=mock(ApplicationGroupScope.class);
        when(scope.applicationGroupId()).thenReturn("console");when(scope.key()).thenReturn("reader");when(scope.name()).thenReturn("Reader");
        when(scope.description()).thenReturn("Read workflows");when(scope.revision()).thenReturn(1L);
        when(setup.applications.invoke(any())).thenReturn(scope);
        var detail=setup.controller.applicationGroupScope("main","console","reader",owner());
        assertEquals(200,detail.code());var value=response(detail);
        assertEquals(5,value.size());assertEquals("console",value.path("applicationGroupId").textValue());
        assertEquals("reader",value.path("key").textValue());assertEquals("Reader",value.path("name").textValue());
        assertEquals("Read workflows",value.path("description").textValue());assertEquals(1,value.path("revision").longValue());
        for(String field:List.of("projectId","scope","active","resourceIntents","managementIntents"))assertFalse(value.has(field));
        when(setup.applications.invoke(any())).thenReturn(new ApplicationGroupDirectoryManagement.Page<>(List.of(scope),1,1,20,3));
        var list=setup.controller.applicationGroupScopes("main","console",owner(),1,20);
        assertEquals(200,list.code());assertEquals(value,response(list).path("items").get(0));
        assertEquals(3,response(list).path("directoryRevision").longValue());
        clearInvocations(setup.applications);
        assertEquals(400,setup.controller.createApplicationGroupScope("main","console",owner(),"\"3\"",json("{\"scope\":\"reader\",\"name\":\"Reader\",\"description\":\"\"}")).code());
        assertEquals(400,setup.controller.updateApplicationGroupScope("main","console","reader",owner(),"\"1\"",json("{\"key\":\"renamed\",\"name\":\"Reader\",\"description\":\"\"}")).code());
        assertEquals(400,setup.controller.applicationGroupScope("main","console","~cmVhZGVy",owner()).code());
        verifyNoInteractions(setup.applications);
        when(setup.applications.invoke(any())).thenReturn(scope);
        when(scope.applicationGroupId()).thenReturn("other");
        assertEquals(503,setup.controller.applicationGroupScope("main","console","reader",owner()).code());
        when(scope.applicationGroupId()).thenReturn("console");when(scope.key()).thenReturn("other");
        assertEquals(503,setup.controller.applicationGroupScope("main","console","reader",owner()).code());
        when(scope.key()).thenReturn("BAD");
        assertEquals(503,setup.controller.applicationGroupScope("main","console","reader",owner()).code());
        assertEquals(1,setup.store.readCurrent().revision());
    }
    @Test void deletedGroupMappingsDoNotBlockOtherGroupsOrKeepManagementAuthority() throws Exception {
        var setup=setup(3,Set.of(MANAGER));
        var document=(com.fasterxml.jackson.databind.node.ObjectNode)MAPPER.readTree(POLICY);
        var groups=(com.fasterxml.jackson.databind.node.ObjectNode)document.path("applicationGroups");
        var current=(com.fasterxml.jackson.databind.node.ObjectNode)groups.path("console");
        groups.set("deleted",current.deepCopy());
        var scope=(com.fasterxml.jackson.databind.node.ObjectNode)current.path("scopes").path(MANAGER);
        var management=(com.fasterxml.jackson.databind.node.ObjectNode)scope.path("managementGrants").get(1);
        management.putArray("applicationGroupIds").add("console").add("deleted");
        setup.store.replace(1,json(document.toString()),"installer");
        var retained=setup.store.readCurrent().policy().applicationGroups().get("deleted");
        assertEquals(200,setup.controller.runtimePolicy("main","console",clientKey(),owner()).code());
        assertEquals(403,setup.controller.runtimePolicy("main","deleted",clientKey(),owner()).code());
        assertEquals(403,setup.controller.capabilities("main",owner(),"deleted").code());
        scope.put("scopeKey",MANAGER);scope.put("description","Updated without recovering the deleted group");
        var saved=setup.controller.updateRuntimeScope("main","console",clientKey(),MANAGER,owner(),"\"2\"",json(scope.toString()));
        assertEquals(200,saved.code());assertEquals(3,setup.store.readCurrent().revision());
        assertEquals(retained,setup.store.readCurrent().policy().applicationGroups().get("deleted"));
        assertEquals("Updated without recovering the deleted group",setup.store.readCurrent().policy().applicationGroups().get("console").scopes().get(MANAGER).description());
        var changed=(com.fasterxml.jackson.databind.node.ObjectNode)scope.path("managementGrants").get(1);
        changed.putArray("applicationGroupIds").add("console").add("new-missing");
        String before=setup.store.readCurrent().canonicalJson();
        assertEquals(400,setup.controller.updateRuntimeScope("main","console",clientKey(),MANAGER,owner(),"\"3\"",json(scope.toString())).code());
        assertEquals(before,setup.store.readCurrent().canonicalJson());
        onlyRuntimeMembershipReads(setup);
    }
    @Test void memberAndUserGrantProjectionsHaveOnlyApplicationGroupFieldsAndExactTargets() {
        var setup=setup(3,Set.of(MANAGER));
        var member=new ApplicationGroupApplication("console","api-client","",Set.of("api"),Set.of("reader"),4);
        String applicationKey="~"+Base64.getUrlEncoder().withoutPadding().encodeToString(json("api-client"));
        when(setup.applications.invoke(any())).thenReturn(member);
        var detail=setup.controller.clientsDetail("main","console",applicationKey,owner());
        assertEquals(200,detail.code());var application=response(detail);
        assertEquals(7,application.size());assertEquals("console",application.path("applicationGroupId").textValue());
        assertEquals("api-client",application.path("clientId").textValue());assertEquals("reader",application.path("supportedScopeKeys").get(0).textValue());
        for(String field:List.of("projectId","allowedProjectScopes","firstPartyUi"))assertFalse(application.has(field));
        when(setup.applications.invoke(any())).thenReturn(new ApplicationGroupDirectoryManagement.Page<>(List.of(member),1,1,20,3));
        var applications=setup.controller.clients("main","console",owner(),1,20);
        assertEquals(200,applications.code());assertEquals(application,response(applications).path("items").get(0));
        var grant=new ApplicationGroupScopeGrant("console",ISSUER,"user",Set.of("reader"),4,97);
        when(setup.applications.invoke(any())).thenReturn(grant);
        var grantDetail=setup.controller.assignmentsDetail("main","console","user",owner());
        assertEquals(200,grantDetail.code());var assignment=response(grantDetail);
        assertEquals(6,assignment.size());assertEquals("console",assignment.path("applicationGroupId").textValue());
        assertEquals("reader",assignment.path("scopeKeys").get(0).textValue());
        assertFalse(assignment.has("projectId"));assertFalse(assignment.has("projectScopes"));
        when(setup.applications.invoke(any())).thenReturn(new ApplicationGroupDirectoryManagement.Page<>(List.of(grant),1,1,20,3));
        assertEquals(assignment,response(setup.controller.assignments("main","console",owner(),1,20)).path("items").get(0));
        for(var wrong:List.of(
            new ApplicationGroupScopeGrant("other",ISSUER,"user",Set.of("reader"),4,97),
            new ApplicationGroupScopeGrant("console","https://other.invalid","user",Set.of("reader"),4,97),
            new ApplicationGroupScopeGrant("console",ISSUER,"other-user",Set.of("reader"),4,97))) {
            when(setup.applications.invoke(any())).thenReturn(wrong);
            assertEquals(503,setup.controller.assignmentsDetail("main","console","user",owner()).code());
        }
        when(setup.applications.invoke(any())).thenReturn(new ApplicationGroupApplication("other","api-client","",Set.of("api"),Set.of("reader"),4));
        assertEquals(503,setup.controller.clientsDetail("main","console",applicationKey,owner()).code());
        assertEquals(1,setup.store.readCurrent().revision());
    }
    @Test void memberAndGrantWritesRejectLegacyFieldsBeforeReachingTheHost() {
        var setup=setup(3,Set.of(MANAGER));
        assertEquals(400,setup.controller.clientsCreate("main","console",owner(),"\"3\"",json("{\"clientId\":\"api\",\"approved\":true,\"firstPartyUi\":true,\"audiences\":[\"api\"],\"supportedScopeKeys\":[\"reader\"]}")).code());
        assertEquals(400,setup.controller.clientsCreate("main","console",owner(),"\"3\"",json("{\"clientId\":\"api\",\"approved\":true,\"audiences\":[\"api\"],\"allowedProjectScopes\":[\"reader\"]}")).code());
        assertEquals(400,setup.controller.assignmentsCreate("main","console",owner(),"\"3\"",json("{\"issuer\":\"https://identity.example.invalid\",\"subject\":\"user\",\"projectScopes\":[\"reader\"]}")).code());
        verifyNoInteractions(setup.applications);assertEquals(1,setup.store.readCurrent().revision());
    }
    @Test void runtimePolicyRequiresTheActualConfiguredApplicationMembershipWithoutHostUiPrivileges() throws Exception {
        var setup=setup(3,Set.of(MANAGER));String before=setup.store.readCurrent().canonicalJson();
        assertEquals(200,setup.controller.runtimePolicy("main","console",clientKey(),owner()).code());
        var lookup=org.mockito.ArgumentCaptor.forClass(ApplicationDirectoryAccess.Request.class);
        verify(setup.applications).invoke(lookup.capture());
        assertEquals(3,lookup.getValue().directoryRevision());assertEquals("kestra-ui",lookup.getValue().key());
        when(setup.applications.invoke(any())).thenThrow(new ApplicationDirectoryAccess.DirectoryFailure(404,"not_found"));
        assertEquals(404,setup.controller.runtimePolicy("main","console",clientKey(),owner()).code());
        assertEquals(404,setup.controller.updateRuntimeScope("main","console",clientKey(),MANAGER,owner(),"\"1\"",json(scopeBody())).code());
        var invalidMember=mock(ApplicationGroupApplication.class);
        when(invalidMember.applicationGroupId()).thenReturn("console");when(invalidMember.clientId()).thenReturn("kestra-ui");
        when(invalidMember.revision()).thenReturn(0L);
        for(var invalid:List.of(
            new ApplicationGroupApplication("other","kestra-ui","",Set.of("kestra-ui"),Set.of(MANAGER),1),
            new ApplicationGroupApplication("console","other-application","",Set.of("kestra-ui"),Set.of(MANAGER),1),
            invalidMember)) {
            doReturn(invalid).when(setup.applications).invoke(any());
            assertEquals(503,setup.controller.runtimePolicy("main","console",clientKey(),owner()).code());
            assertEquals(503,setup.controller.updateRuntimeScope("main","console",clientKey(),MANAGER,owner(),"\"1\"",json(scopeBody())).code());
        }
        doReturn(null).when(setup.applications).invoke(any());
        assertEquals(503,setup.controller.runtimePolicy("main","console",clientKey(),owner()).code());
        assertEquals(before,setup.store.readCurrent().canonicalJson());assertEquals(1,setup.store.readCurrent().revision());
        onlyRuntimeMembershipReads(setup);
    }
    @Test void runtimeReadDoesNotGrantWriteAndRejectedWritesPreserveTheStoredPolicy() throws Exception {
        var setup=setup(3,Set.of(MANAGER));
        var policy=(com.fasterxml.jackson.databind.node.ObjectNode)MAPPER.readTree(POLICY);
        var scope=(com.fasterxml.jackson.databind.node.ObjectNode)policy.path("applicationGroups").path("console").path("scopes").path(MANAGER);
        var grants=(com.fasterxml.jackson.databind.node.ArrayNode)scope.path("managementGrants");
        for(var grant:grants)((com.fasterxml.jackson.databind.node.ObjectNode)grant).putArray("actions").add("READ");
        setup.store.replace(1,json(policy.toString()),"installer");
        String before=setup.store.readCurrent().canonicalJson();
        assertEquals(200,setup.controller.runtimePolicy("main","console",clientKey(),owner()).code());
        assertEquals(200,setup.controller.runtimeScope("main","console",clientKey(),MANAGER,owner()).code());
        var capabilities=response(setup.controller.capabilities("main",owner(),"console"));
        assertTrue(capabilities.path("localPolicyRead").booleanValue());assertFalse(capabilities.path("localPolicyManage").booleanValue());
        String settings="{\"issuer\":\"https://identity.example.invalid\",\"audiences\":[\"kestra-ui\"],\"enabled\":false}";
        assertEquals(403,setup.controller.createRuntimePolicy("main","console",clientKey(),owner(),"\"2\"",json(settings)).code());
        assertEquals(403,setup.controller.updateRuntimePolicy("main","console",clientKey(),owner(),"\"2\"",json(settings)).code());
        assertEquals(403,setup.controller.createRuntimeScope("main","console",clientKey(),owner(),"\"2\"",json(scopeBody())).code());
        assertEquals(403,setup.controller.updateRuntimeScope("main","console",clientKey(),MANAGER,owner(),"\"2\"",json(scopeBody())).code());
        assertEquals(403,setup.controller.deleteRuntimeScope("main","console",clientKey(),MANAGER,owner(),"\"2\"").code());
        assertEquals(before,setup.store.readCurrent().canonicalJson());assertEquals(2,setup.store.readCurrent().revision());
        onlyRuntimeMembershipReads(setup);
    }
    @Test void runtimeApiRejectsOtherApplicationsOldPayloadsAndDisabledOrMissingConfigurationWithoutWrites() throws Exception {
        var setup=setup(3,Set.of(MANAGER));
        String before=setup.store.readCurrent().canonicalJson();
        String other="~"+Base64.getUrlEncoder().withoutPadding().encodeToString(json("other-application"));
        assertEquals(404,setup.controller.runtimePolicy("main","console",other,owner()).code());
        assertEquals(404,setup.controller.updateRuntimeScope("main","console",other,MANAGER,owner(),"\"1\"",json(scopeBody())).code());
        assertEquals(400,setup.controller.updateRuntimeScope("main","console",clientKey(),MANAGER,owner(),"\"1\"",json(scopeBody().replace("\"scopeKey\"","\"name\""))).code());
        String settings="{\"id\":\"console\",\"issuer\":\"https://identity.example.invalid\",\"audiences\":[\"kestra-ui\"],\"enabled\":true}";
        assertEquals(400,setup.controller.updateRuntimePolicy("main","console",clientKey(),owner(),"\"1\"",json(settings)).code());
        var application=ManagementController.class.getDeclaredField("runtimeApplicationClientId");application.setAccessible(true);application.set(setup.controller,"");
        assertEquals(503,setup.controller.runtimePolicy("main","console",clientKey(),owner()).code());
        application.set(setup.controller,"kestra-ui");
        var enabled=ManagementController.class.getDeclaredField("applicationGroups");enabled.setAccessible(true);enabled.set(setup.controller,false);
        assertEquals(404,setup.controller.runtimePolicy("main","console",clientKey(),owner()).code());
        assertEquals(404,setup.controller.updateRuntimeScope("main","console",clientKey(),MANAGER,owner(),"\"1\"",json(scopeBody())).code());
        assertEquals(before,setup.store.readCurrent().canonicalJson());assertEquals(1,setup.store.readCurrent().revision());
        onlyRuntimeMembershipReads(setup);
    }
    @Test void successfulWriteRechecksWithinTransactionAndStaleRetryCannotOverwriteIt() throws Exception {
        var s=setup(3,Set.of(MANAGER));String body=scopeBody();
        var result=s.controller.updateRuntimeScope("main","console",clientKey(),scopeKey(),owner(),"\"1\"",json(body));
        assertEquals(200,result.code());verify(s.store,times(1)).readCurrent();verify(s.directory,times(1)).boundaries(any(java.sql.Connection.class));
        var expectedIdentity=s.identities.current();
        verify(s.directory,times(1)).verifyIdentity(any(java.sql.Connection.class),eq(expectedIdentity));
        assertEquals(2,response(result).path("revision").longValue());
        assertEquals(1,response(result).size());assertFalse(response(result).has("policy"));
        String before=s.store.readCurrent().canonicalJson();
        assertEquals(412,s.controller.updateRuntimeScope("main","console",clientKey(),scopeKey(),owner(),"\"1\"",json(body)).code());
        assertEquals(before,s.store.readCurrent().canonicalJson());assertEquals(2,s.store.readCurrent().revision());
    }

}
