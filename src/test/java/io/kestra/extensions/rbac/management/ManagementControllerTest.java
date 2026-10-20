package io.kestra.extensions.rbac.management;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.extensions.rbac.ProjectRuntimePolicies;
import io.kestra.extensions.rbac.project.ProjectPolicyParser;
import io.kestra.extensions.rbac.store.ProjectPolicyStore;
import io.kestra.webserver.oidc.ApplicationDirectoryV1;
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
        {"schemaVersion":2,"applicationProjects":{"console":{"issuer":"https://identity.example.invalid",
        "audiences":["kestra-ui"],"enabled":true,"directoryRevision":3,"scopes":{
        "console-manage-v1":{"description":"Manager","enabled":true,"grants":[],"managementGrants":[
        {"domain":"IDENTITY","actions":["READ","MANAGE"],"projectIds":[]},
        {"domain":"LOCAL_POLICY","actions":["READ","MANAGE"],"projectIds":[]}]}}}}}
        """;
    private record Setup(ManagementController controller,ProjectPolicyStore store,ApplicationDirectoryV1 directory,
                         UserDirectoryAccess users,ApplicationDirectoryAccess applications,TrustedIdentityV1 identities) {}
    private Setup setup(long directoryRevision,Set<String> scopes) {
        var data=new JdbcDataSource();data.setURL("jdbc:h2:mem:http-"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        var store=spy(new ProjectPolicyStore(data,new ProjectPolicyParser()));store.initializeSchema();store.initialize(json(POLICY),"installer");
        var directory=mock(ApplicationDirectoryV1.class);var identities=mock(TrustedIdentityV1.class);
        when(identities.current()).thenReturn(new TrustedIdentityV1.Identity(1,ISSUER,"owner","kestra-ui",Set.of("kestra-ui"),"main","console",Set.of("openid"),scopes,1,97,directoryRevision));
        var shared=new ApplicationDirectoryV1.Project("console","main",ISSUER,"Console",true,directoryRevision,97);
        var manager=new ApplicationDirectoryV1.ScopeDefinition("console",MANAGER,"Manager","",true,List.of(),List.of(
            new ApplicationDirectoryV1.ManagementIntent(ApplicationDirectoryV1.ManagementDomain.IDENTITY,Set.of("READ","MANAGE"),Set.of()),
            new ApplicationDirectoryV1.ManagementIntent(ApplicationDirectoryV1.ManagementDomain.LOCAL_POLICY,Set.of("READ","MANAGE"),Set.of())),2);
        when(directory.policyBoundaries()).thenReturn(List.of(new ApplicationDirectoryV1.PolicyBoundary(shared,List.of(manager),Set.of("kestra-ui"),Set.of(MANAGER),List.of(new ApplicationDirectoryV1.ProtocolScope("openid","OIDC")))));
        var users=mock(UserDirectoryAccess.class);var applications=mock(ApplicationDirectoryAccess.class);
        var runtime=new ProjectRuntimePolicies(store,directory,identities);
        var controller=new ManagementController(runtime,store,provider(users),provider(applications));
        clearInvocations(store,directory);
        return new Setup(controller,store,directory,users,applications,identities);
    }
    private JsonNode response(io.micronaut.http.HttpResponse<?> response) {return MAPPER.valueToTree(response.body());}
    private String reconciliationBody(long revision) throws Exception {
        var value=(com.fasterxml.jackson.databind.node.ObjectNode)MAPPER.readTree(POLICY).path("applicationProjects").path("console").deepCopy();
        value.put("id","console");value.put("directoryRevision",revision);return value.toString();
    }
    @Test void staleBindingEnablesOnlyRecoveryAndCompleteRecoveryRestoresOrdinaryManagement()throws Exception {
        var s=setup(4,Set.of(MANAGER));
        var capabilities=response(s.controller.capabilities("main",owner()));
        assertTrue(capabilities.path("allowed").booleanValue());assertTrue(capabilities.path("localPolicyManagement").booleanValue());
        assertFalse(capabilities.path("localPolicyManage").booleanValue());assertTrue(capabilities.path("reconciliationAllowed").booleanValue());
        assertTrue(capabilities.path("needsReconciliation").booleanValue());assertFalse(capabilities.path("identityManagement").booleanValue());
        String before=s.store.readCurrent().canonicalJson();
        assertEquals(403,s.controller.bindingOptions("main","console",owner()).code());
        assertEquals(403,s.controller.createScope("main","console",owner(),"\"1\"",json("{\"name\":\"new\",\"description\":\"New\",\"enabled\":true,\"grants\":[]}")).code());
        assertEquals(403,s.controller.users("main",owner(),1,20).code());
        assertEquals(before,s.store.readCurrent().canonicalJson());verifyNoInteractions(s.users,s.applications);
        var recovery=s.controller.reconciliation("main","console",owner());assertEquals(200,recovery.code());
        assertEquals(4,response(recovery).path("directoryRevision").longValue());
        assertEquals(200,s.controller.reconcileProject("main","console",owner(),"\"1\"",json(reconciliationBody(4))).code());
        assertEquals(4,s.store.readCurrent().policy().applicationProjects().get("console").directoryRevision());
        var after=response(s.controller.capabilities("main",owner()));
        assertTrue(after.path("localPolicyManage").booleanValue());assertTrue(after.path("identityManagement").booleanValue());assertFalse(after.path("needsReconciliation").booleanValue());
    }
    @Test void mixedUnknownScopeCannotUseRecoveryOrKnownSubsetAndHasNoSideEffects()throws Exception {
        var s=setup(4,Set.of(MANAGER,"unknown-scope"));String before=s.store.readCurrent().canonicalJson();
        assertFalse(response(s.controller.capabilities("main",owner())).path("allowed").booleanValue());
        assertEquals(403,s.controller.reconciliation("main","console",owner()).code());
        assertEquals(403,s.controller.reconcileProject("main","console",owner(),"\"1\"",json(reconciliationBody(4))).code());
        assertEquals(403,s.controller.bindingOptions("main","console",owner()).code());
        assertEquals(before,s.store.readCurrent().canonicalJson());verifyNoInteractions(s.users,s.applications);
    }
    @Test void oneRequestReadsOnePolicyAndDirectoryAndProjectionDoesNotLeakReachabilityOrEpoch() {
        var s=setup(3,Set.of(MANAGER));var result=s.controller.bindingOptions("main","console",owner());assertEquals(200,result.code());
        verify(s.store,times(1)).readCurrent();verify(s.directory,times(1)).policyBoundaries();
        var value=response(result);assertEquals(3,value.path("directoryRevision").longValue());
        assertEquals(3,value.path("upperBound").path("project").path("revision").longValue());
        String body=value.toString();for(String field:List.of("authorizationVersion","reachableManagementScopes","clientId","subject","assignments"))assertFalse(body.contains("\""+field+"\""));
        verifyNoInteractions(s.users,s.applications);
    }
    @Test void strongRevisionMalformedJsonAndImmutablePathRejectWithoutWriting()throws Exception {
        var s=setup(3,Set.of(MANAGER));String before=s.store.readCurrent().canonicalJson();
        String body=reconciliationBody(3);
        assertEquals(428,s.controller.reconcileProject("main","console",owner(),null,json(body)).code());
        assertEquals(400,s.controller.reconcileProject("main","console",owner(),"W/\"1\"",json(body)).code());
        assertEquals(400,s.controller.reconcileProject("main","console",owner(),"\"1\"",json(body+" {} ")).code());
        assertEquals(400,s.controller.reconcileProject("main","console",owner(),"\"1\"",json(body.replace("\"id\":\"console\"","\"id\":\"other\""))).code());
        assertEquals(400,s.controller.updateScope("main","console","~YR",owner(),"\"1\"",json("{}")).code());
        assertEquals(before,s.store.readCurrent().canonicalJson());
    }
    @Test void permanentSubjectAndRolesCannotSupplyTrustedIdentity() {
        var s=setup(3,Set.of(MANAGER));when(s.identities.current()).thenReturn(null);
        assertEquals(403,s.controller.policy("main",owner()).code());
        assertEquals(401,s.controller.policy("main",null).code());verifyNoInteractions(s.users,s.applications);
    }
    @Test void legacyWritesAreExplicitlyGoneAndNeverReadOrWriteEitherStoreOrDirectory() {
        var s=setup(3,Set.of(MANAGER));
        assertEquals(410,s.controller.createRole("main",owner(),"\"1\"",json("{}")).code());
        assertEquals(410,s.controller.updateRole("main","old",owner(),"\"1\"",json("{}")).code());
        assertEquals(410,s.controller.deleteRole("main","old",owner(),"\"1\"").code());
        assertEquals(410,s.controller.binding("main",owner(),"\"1\"",json("{}")).code());
        assertEquals(410,s.controller.mapping("main",owner(),"\"1\"",json("{}")).code());
        verifyNoInteractions(s.store,s.directory,s.users,s.applications,s.identities);
    }
    @Test void directoryTargetAndBodyCannotChangeIssuerOrOpaqueScope() {
        var s=setup(3,Set.of(MANAGER));
        String key="~"+Base64.getUrlEncoder().withoutPadding().encodeToString(json("a/read"));
        assertEquals(400,s.controller.identityScopesUpdate("main","console",key,owner(),"\"2\"",json("{\"scope\":\"different\",\"name\":\"Different\",\"description\":\"x\",\"active\":true,\"resourceIntents\":[],\"managementIntents\":[]}")).code());
        assertEquals(403,s.controller.assignmentsCreate("main","console",owner(),"\"3\"",json("{\"issuer\":\"https://other.invalid\",\"subject\":\"user\",\"projectScopes\":[]}")).code());
        verifyNoInteractions(s.applications);
    }
    @Test void lastAdministratorUserGuardPreserves409AndAccountConflictPreserves412() {
        var s=setup(3,Set.of(MANAGER));
        when(s.users.setActive("last",0,false)).thenThrow(new UserDirectoryAccess.DirectoryFailure(UserDirectoryAccess.Failure.LAST_ADMIN));
        assertEquals(409,s.controller.state("main",owner(),"\"0\"",json("{\"subject\":\"last\",\"active\":false}")).code());
        when(s.users.update("user",0,"Name","mail")).thenThrow(new UserDirectoryAccess.DirectoryFailure(UserDirectoryAccess.Failure.CONFLICT));
        assertEquals(412,s.controller.profile("main",owner(),"\"0\"",json("{\"subject\":\"user\",\"name\":\"Name\",\"email\":\"mail\"}")).code());
    }
    @Test void exactLocalManageCanReadBindingOptionsWithoutIdentityOrGeneralRead() throws Exception {
        var s=setup(3,Set.of(MANAGER));
        var policy=(com.fasterxml.jackson.databind.node.ObjectNode)MAPPER.readTree(POLICY);
        var grants=(com.fasterxml.jackson.databind.node.ArrayNode)policy.path("applicationProjects").path("console").path("scopes").path(MANAGER).path("managementGrants");
        grants.remove(0);((com.fasterxml.jackson.databind.node.ObjectNode)grants.get(0)).putArray("actions").add("MANAGE");
        s.store.replace(1,json(policy.toString()),"installer");
        assertEquals(200,s.controller.bindingOptions("main","console",owner()).code());
        assertEquals(403,s.controller.bindingOptions("main","different",owner()).code());
        assertEquals(403,s.controller.scopes("main","console",owner()).code());
        assertEquals(403,s.controller.users("main",owner(),1,20).code());
        assertEquals(403,s.controller.identityProjects("main",owner(),1,20).code());
        verifyNoInteractions(s.users,s.applications);
    }
    @Test void successfulWriteUsesSingleSnapshotAndStaleRetryCannotOverwriteIt() throws Exception {
        var s=setup(3,Set.of(MANAGER));String body=reconciliationBody(3);
        var result=s.controller.reconcileProject("main","console",owner(),"\"1\"",json(body));
        assertEquals(200,result.code());verify(s.store,times(1)).readCurrent();verify(s.directory,times(1)).policyBoundaries();
        assertEquals(2,response(result).path("revision").longValue());
        assertEquals(2,response(result).path("policy").path("schemaVersion").intValue());
        assertEquals(1,response(result).path("policy").path("applicationProjects").size());
        String before=s.store.readCurrent().canonicalJson();
        assertEquals(412,s.controller.reconcileProject("main","console",owner(),"\"1\"",json(body)).code());
        assertEquals(before,s.store.readCurrent().canonicalJson());assertEquals(2,s.store.readCurrent().revision());
    }

}
