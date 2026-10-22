package io.kestra.extensions.rbac.applicationgroup;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ApplicationGroupAuthorizationTest {
    private final ApplicationGroupPolicy policy = new ApplicationGroupPolicyParser().parse(("""
        {"schemaVersion":2,"applicationGroups":{"p":{"issuer":"https://iss.example","audiences":["ui","mcp"],"enabled":true,"scopes":{
        "read":{"description":"r","enabled":true,"grants":[{"scope":"NAMESPACE","tenant":"main","namespace":"a","descendants":true,"resource":"FLOW","actions":["READ","LIST"],"ids":[],"allIds":true}]},
        "write":{"description":"w","enabled":true,"grants":[{"scope":"NAMESPACE","tenant":"main","namespace":"b","descendants":false,"resource":"FLOW","actions":["READ","LIST","UPDATE"],"ids":[],"allIds":true}]},
        "read-only":{"description":"r","enabled":true,"grants":[{"scope":"NAMESPACE","tenant":"main","namespace":"a","descendants":false,"resource":"FLOW","actions":["READ"],"ids":[],"allIds":true}]},
        "list-only":{"description":"l","enabled":true,"grants":[{"scope":"NAMESPACE","tenant":"main","namespace":"a","descendants":false,"resource":"FLOW","actions":["LIST"],"ids":[],"allIds":true}]}
        }}}}
        """).getBytes());
    private final ApplicationGroupAuthorization auth = new ApplicationGroupAuthorization(policy);
    private final ApplicationGroupAuthorization.Directory directory = directory(policy.applicationGroups().get("p"), "p", Set.of("openid"));
    private ApplicationGroupAuthorization.Identity id(String audience, String... scopes) { return new ApplicationGroupAuthorization.Identity("s","https://iss.example",audience,"p",3,Set.of(scopes),Set.of()); }
    @Test void completeScopeAndIdentityBoundaries() {
        assertTrue(auth.authorize(id("ui","read"), new ApplicationGroupAuthorization.Request("main","a.child","FLOW","READ","x",false), directory).allowed());
        assertFalse(auth.authorize(id("bad","read"), new ApplicationGroupAuthorization.Request("main","a","FLOW","READ","x",false), directory).allowed());
        assertFalse(auth.authorize(new ApplicationGroupAuthorization.Identity("s","https://other","ui","p",3,Set.of("read"),Set.of()), new ApplicationGroupAuthorization.Request("main","a","FLOW","READ","x",false), directory).allowed());
    }
    @Test void crossScopeReadListCannotBeComposed() {
        assertFalse(auth.authorize(id("ui","read-only","list-only"), new ApplicationGroupAuthorization.Request("main","a","FLOW","READ",null,true), directory).allowed());
        assertFalse(auth.authorize(id("ui","read-only"), new ApplicationGroupAuthorization.Request("main","a","FLOW","READ",null,true), directory).allowed());
        assertFalse(auth.authorize(id("ui","list-only"), new ApplicationGroupAuthorization.Request("main","a","FLOW","READ",null,true), directory).allowed());
        assertTrue(auth.authorize(id("ui","read"), new ApplicationGroupAuthorization.Request("main","a","FLOW","READ",null,true), directory).allowed());
        assertFalse(auth.authorize(id("ui","read"), new ApplicationGroupAuthorization.Request("main","a2","FLOW","READ",null,true), directory).allowed());
    }
    @Test void writeAndUnknownScopeFailClosed() {
        assertTrue(auth.authorize(id("mcp","write"), new ApplicationGroupAuthorization.Request("main","b","FLOW","UPDATE","f",false), directory).allowed());
        assertFalse(auth.authorize(id("ui","write","unknown"), new ApplicationGroupAuthorization.Request("main","b","FLOW","UPDATE","f",false), directory).allowed());
        assertFalse(auth.authorize(id("ui","read"), new ApplicationGroupAuthorization.Request("main","a","FLOW","LIST",null,true), directory).domain().contains("a.child"));
    }
    @Test void directoryRevisionAndUpperBoundAreRequired() {
        var request = new ApplicationGroupAuthorization.Request("main","a","FLOW","READ","f",false);
        assertFalse(auth.authorize(id("ui","read"), request, null).allowed());
        var upper = policy.applicationGroups().get("p");
        var narrower = new ApplicationGroupPolicy.ApplicationGroup(upper.issuer(), upper.audiences(), true, java.util.Map.of("write", upper.scopes().get("write")));
        assertFalse(auth.authorize(id("ui","read"), request, directory(narrower,"p",Set.of())).allowed());
        assertFalse(auth.authorize(new ApplicationGroupAuthorization.Identity("s",upper.issuer(),"ui","p",4,Set.of("read"),Set.of()),request,directory).allowed());
    }
    @Test void directoryRevisionCannotBeReplacedByIndependentAuthorizationEpoch() {
        long directoryRevision=3, authorizationVersion=97;
        assertNotEquals(directoryRevision,authorizationVersion);
        var request=new ApplicationGroupAuthorization.Request("main","a","FLOW","READ","f",false);
        var trusted=new ApplicationGroupAuthorization.Identity("s","https://iss.example","ui","p",directoryRevision,Set.of("read"),Set.of());
        assertTrue(auth.authorize(trusted,request,directory).allowed());
        var confusedIdentity=new ApplicationGroupAuthorization.Identity("s","https://iss.example","ui","p",authorizationVersion,Set.of("read"),Set.of());
        assertFalse(auth.authorize(confusedIdentity,request,directory).allowed());
        var upper=directory.upperBound();
        var confusedUpper=new ApplicationGroupAuthorization.ScopeCatalog(upper.issuer(),upper.audiences(),true,authorizationVersion,upper.scopes());
        assertFalse(auth.authorize(trusted,request,directory(confusedUpper,"p",Set.of("openid"))).allowed());
        var local=policy.applicationGroups().get("p");
        var confusedPolicy=new ApplicationGroupPolicy.ApplicationGroup(local.issuer(),local.audiences(),true,local.scopes());
        var confusedLocal=new ApplicationGroupAuthorization(new ApplicationGroupPolicy(2,java.util.Map.of("p",confusedPolicy)));
        assertTrue(confusedLocal.authorize(trusted,request,directory).allowed());
    }
    @Test void unrelatedRemovedBindingDoesNotSupplyOrInvalidateTokenPermissions() {
        var upper=policy.applicationGroups().get("p");
        var readOnlyDirectory=new ApplicationGroupPolicy.ApplicationGroup(upper.issuer(),upper.audiences(),true,java.util.Map.of("read",upper.scopes().get("read")));
        var directory=directory(readOnlyDirectory,"p",Set.of("openid"));
        var read=new ApplicationGroupAuthorization.Request("main","a","FLOW","READ","f",false);
        assertTrue(auth.authorize(id("ui","read"),read,directory).allowed());
        assertFalse(auth.authorize(id("ui","read","write"),read,directory).allowed());
        assertFalse(auth.authorize(id("ui","read"),new ApplicationGroupAuthorization.Request("main","b","FLOW","UPDATE","f",false),directory).allowed());
    }
    @Test void structuredDomainsIntersectParentSubtreeAndExactChild() {
        var parent = new ApplicationGroupAuthorization.Domain(Set.of(),Set.of("a"));
        var exact = new ApplicationGroupAuthorization.Domain(Set.of("a.child","a2"),Set.of());
        var intersection = parent.intersect(exact);
        assertTrue(intersection.contains("a.child"));
        assertFalse(intersection.contains("a.child.next"));
        assertFalse(intersection.contains("a2"));
        var child = parent.intersect(new ApplicationGroupAuthorization.Domain(Set.of(),Set.of("a.child")));
        assertTrue(child.contains("a.child.next"));
        assertFalse(child.contains("a.other"));
    }
    @Test void allActionsAndRestrictedIdsRemainIndependent() {
        for (String action : java.util.List.of("CREATE","UPDATE","DELETE","EXECUTE","ACCESS_OUTPUTS"))
            assertFalse(auth.authorize(id("ui","read"),new ApplicationGroupAuthorization.Request("main","a","FLOW",action,"f",false),directory).allowed());
        assertTrue(auth.authorize(id("ui","read-only"),new ApplicationGroupAuthorization.Request("main","a","FLOW","READ","f",false),directory).allowed());
        assertTrue(auth.authorize(id("ui","read-only"),new ApplicationGroupAuthorization.Request("main","a","FLOW","READ","other",false),directory).allowed());
        assertFalse(auth.authorize(id("ui","read-only"),new ApplicationGroupAuthorization.Request("main","a","FLOW","LIST",null,true),directory).allowed());
    }
    @Test void mcpServerConfigurationUsesItsOwnTenantResourceAndActions() {
        var read=new ApplicationGroupPolicy.Grant("TENANT","main",null,false,"MCP_SERVER",Set.of("READ","LIST"),Set.of(),true);
        var server=project(java.util.Map.of("servers",scope(read)));
        var bounds=directory(server,"p",Set.of("openid"));
        var evaluator=new ApplicationGroupAuthorization(new ApplicationGroupPolicy(2,java.util.Map.of("p",server)));
        var identity=id("ui","servers");
        assertTrue(evaluator.authorize(identity,new ApplicationGroupAuthorization.Request("main",null,"MCP_SERVER","READ","default",false),bounds).allowed());
        assertTrue(evaluator.authorize(identity,new ApplicationGroupAuthorization.Request("main",null,"MCP_SERVER","LIST",null,true),bounds).allowed());
        assertFalse(evaluator.authorize(identity,new ApplicationGroupAuthorization.Request("main",null,"MCP_SERVER","UPDATE","default",false),bounds).allowed());
        assertFalse(evaluator.authorize(identity,new ApplicationGroupAuthorization.Request("main","a","FLOW","READ","default",false),bounds).allowed());
        assertFalse(evaluator.authorizeManagement(identity,bounds,ApplicationGroupPolicy.ManagementDomain.IDENTITY,ApplicationGroupPolicy.ManagementAction.MANAGE));
    }
    @Test void mcpConfigurationIdsAndListActionsCannotBeBorrowedAcrossScopes() {
        var update=new ApplicationGroupPolicy.Grant("TENANT","main",null,false,"MCP_SERVER",Set.of("READ","UPDATE"),Set.of("default"),false);
        var read=new ApplicationGroupPolicy.Grant("TENANT","main",null,false,"MCP_SERVER",Set.of("READ"),Set.of(),true);
        var list=new ApplicationGroupPolicy.Grant("TENANT","main",null,false,"MCP_SERVER",Set.of("LIST"),Set.of(),true);
        var server=project(java.util.Map.of("one",scope(update),"r",scope(read),"l",scope(list)));
        var bounds=directory(server,"p",Set.of("openid"));
        var evaluator=new ApplicationGroupAuthorization(new ApplicationGroupPolicy(2,java.util.Map.of("p",server)));
        assertTrue(evaluator.authorize(id("ui","one"),new ApplicationGroupAuthorization.Request("main",null,"MCP_SERVER","UPDATE","default",false),bounds).allowed());
        assertFalse(evaluator.authorize(id("ui","one"),new ApplicationGroupAuthorization.Request("main",null,"MCP_SERVER","UPDATE","another",false),bounds).allowed());
        assertFalse(evaluator.authorize(id("ui","r","l"),new ApplicationGroupAuthorization.Request("main",null,"MCP_SERVER","LIST",null,true),bounds).allowed());
    }
    @Test void parsingIsStrictAndCanonicalRoundTripIsStable() {
        var parser = new ApplicationGroupPolicyParser(); String json=parser.canonicalJson(policy);
        var management=new ApplicationGroupPolicy.ManagementGrant(ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,Set.of(ApplicationGroupPolicy.ManagementAction.MANAGE),Set.of("target"));
        var managed=new ApplicationGroupPolicy(2,java.util.Map.of("p",project(java.util.Map.of("manager",new ApplicationGroupPolicy.ScopeDefinition("",true,java.util.List.of(),java.util.List.of(management))))));
        var canonical=parser.canonicalJson(managed);
        assertTrue(canonical.contains("\"applicationGroupIds\""));
        assertThrows(IllegalArgumentException.class,()->parser.parse(canonical.replace("\"applicationGroupIds\"","\"projectIds\"").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(json,parser.canonicalJson(parser.parse(json.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        assertThrows(IllegalArgumentException.class,()->parser.parse((json+" {}").getBytes()));
        assertThrows(IllegalArgumentException.class,()->parser.parse(json.replace("\"schemaVersion\":2","\"schemaVersion\":2.5").getBytes()));
        assertThrows(IllegalArgumentException.class,()->parser.parse(new byte[]{(byte)0xc3,0x28}));
        assertThrows(IllegalArgumentException.class,()->parser.parse(json.replace("https://iss.example","not-uri").getBytes()));
        assertThrows(IllegalArgumentException.class,()->parser.parse(json.replace("\"actions\":[\"LIST\",\"READ\"]","\"actions\":[]").getBytes()));
    }
    private ApplicationGroupPolicy.Grant grant(String namespace, boolean tree, Set<String> actions, Set<String> ids) {
        return new ApplicationGroupPolicy.Grant("NAMESPACE","main",namespace,tree,"FLOW",actions,ids,ids.isEmpty());
    }
    private ApplicationGroupPolicy.ApplicationGroup project(java.util.Map<String,ApplicationGroupPolicy.ScopeDefinition> scopes) {
        return new ApplicationGroupPolicy.ApplicationGroup("https://iss.example",Set.of("ui","mcp"),true,scopes);
    }
    private ApplicationGroupPolicy.ScopeDefinition scope(ApplicationGroupPolicy.Grant grant) {
        return new ApplicationGroupPolicy.ScopeDefinition("",true,java.util.List.of(grant));
    }
    private boolean evaluate(ApplicationGroupPolicy.ApplicationGroup local, ApplicationGroupPolicy.ApplicationGroup upper, Set<String> scopes, String namespace, String action, String resourceId, boolean list) {
        return new ApplicationGroupAuthorization(new ApplicationGroupPolicy(2,java.util.Map.of("p",local)))
            .authorize(new ApplicationGroupAuthorization.Identity("s",local.issuer(),"ui","p",3,scopes,Set.of()),
                new ApplicationGroupAuthorization.Request("main",namespace,"FLOW",action,resourceId,list),
                directory(upper,"p",Set.of("openid"))).allowed();
    }
    @Test void crossScopeReadAndListWithAllIdsCannotCombineInSameOrDifferentNamespace() {
        for (String namespace : java.util.List.of("a","b")) {
            var local=project(java.util.Map.of("r",scope(grant("a",false,Set.of("READ"),Set.of())),"l",scope(grant(namespace,false,Set.of("LIST"),Set.of()))));
            assertFalse(evaluate(local,local,Set.of("r","l"),"a","LIST",null,true));
            assertFalse(evaluate(local,local,Set.of("r","l"),"b","LIST",null,true));
        }
    }
    @Test void completeScopeUnionKeepsAReadAndBWriteSeparate() {
        assertTrue(auth.authorize(id("ui","read","write"),new ApplicationGroupAuthorization.Request("main","a","FLOW","READ","f",false),directory).allowed());
        assertFalse(auth.authorize(id("ui","read","write"),new ApplicationGroupAuthorization.Request("main","a","FLOW","UPDATE","f",false),directory).allowed());
        assertTrue(auth.authorize(id("ui","read","write"),new ApplicationGroupAuthorization.Request("main","b","FLOW","UPDATE","f",false),directory).allowed());
        assertFalse(auth.authorize(id("ui","read","write"),new ApplicationGroupAuthorization.Request("main","outside","FLOW","UPDATE","f",false),directory).allowed());
    }
    @Test void runtimeWriteComesFromTheApplicationPolicyAndRespectsItsNamespace() {
        var upper=project(java.util.Map.of("r",scope(grant("a",false,Set.of("READ"),Set.of()))));
        var local=project(java.util.Map.of("r",scope(grant("a",false,Set.of("READ","UPDATE"),Set.of()))));
        assertTrue(evaluate(local,upper,Set.of("r"),"a","UPDATE","f",false));
        assertFalse(evaluate(local,upper,Set.of("r"),"outside","UPDATE","f",false));
        assertFalse(evaluate(upper,local,Set.of("r"),"a","UPDATE","f",false));
        assertTrue(evaluate(upper,upper,Set.of("r"),"a","READ","f",false));
    }
    @Test void eachActionHasIndependentAllowAndDenyIncludingRestrictedIds() {
        for(String action:java.util.List.of("READ","LIST","CREATE","UPDATE","DELETE","EXECUTE","ACCESS_OUTPUTS")) {
            var local=project(java.util.Map.of("r",scope(grant("a",false,Set.of(action),Set.of("f")))));
            assertTrue(evaluate(local,local,Set.of("r"),"a",action,"f",false),action);
            assertFalse(evaluate(local,local,Set.of("r"),"a",action,"other",false),action);
            assertFalse(evaluate(local,local,Set.of("r"),"a2",action,"f",false),action);
            assertFalse(evaluate(local,local,Set.of("r"),"a","LIST",null,true),action);
        }
    }
    @Test void disabledAndUnknownMixturesAndProtocolClassificationFailClosed() {
        var base=policy.applicationGroups().get("p");
        var disabled=new ApplicationGroupPolicy.ScopeDefinition("",false,base.scopes().get("read").grants());
        var local=project(java.util.Map.of("read",base.scopes().get("read"),"disabled",disabled));
        var upper=project(java.util.Map.of("read",base.scopes().get("read"),"disabled",scope(grant("a",true,Set.of("READ","LIST"),Set.of()))));
        assertFalse(evaluate(local,upper,Set.of("read","disabled"),"a","READ","f",false));
        assertFalse(evaluate(local,upper,Set.of("read","unknown"),"a","READ","f",false));
        var request=new ApplicationGroupAuthorization.Request("main","a","FLOW","READ","f",false);
        assertTrue(auth.authorize(new ApplicationGroupAuthorization.Identity("s",base.issuer(),"ui","p",3,Set.of("read"),Set.of("openid")),request,directory).allowed());
        assertFalse(auth.authorize(new ApplicationGroupAuthorization.Identity("s",base.issuer(),"ui","p",3,Set.of("read"),Set.of("openidx")),request,directory).allowed());
        assertFalse(auth.authorize(new ApplicationGroupAuthorization.Identity("s",base.issuer(),"ui","p",0,Set.of("read"),Set.of()),request,directory).allowed());
        assertFalse(auth.authorize(new ApplicationGroupAuthorization.Identity("s",base.issuer(),"ui","other",3,Set.of("read"),Set.of()),request,directory).allowed());
        assertFalse(auth.authorize(id("ui","read"),new ApplicationGroupAuthorization.Request("other","a","FLOW","READ","f",false),directory).allowed());
    }
    @Test void managementDomainsRequireExplicitApplicationPolicyGrantsAndRegisteredScopes() {
        for (var domain : ApplicationGroupPolicy.ManagementDomain.values()) {
            var grant = new ApplicationGroupPolicy.ManagementGrant(domain,Set.of(ApplicationGroupPolicy.ManagementAction.READ,ApplicationGroupPolicy.ManagementAction.MANAGE));
            var scope = new ApplicationGroupPolicy.ScopeDefinition("",true,java.util.List.of(),java.util.List.of(grant));
            var local=project(java.util.Map.of("opaque-admin-name",scope));
            var evaluator=new ApplicationGroupAuthorization(new ApplicationGroupPolicy(2,java.util.Map.of("p",local)));
            var identity=id("ui","opaque-admin-name");
            var bounds=directory(local,"p",Set.of("openid"));
            assertTrue(evaluator.authorizeManagement(identity,bounds,domain,ApplicationGroupPolicy.ManagementAction.READ));
            assertTrue(evaluator.authorizeManagement(identity,bounds,domain,ApplicationGroupPolicy.ManagementAction.MANAGE));
            for(var other:ApplicationGroupPolicy.ManagementDomain.values()) if(other!=domain)
                assertFalse(evaluator.authorizeManagement(identity,bounds,other,ApplicationGroupPolicy.ManagementAction.READ));
            assertFalse(evaluator.authorizeManagement(id("ui","opaque-admin-name","unknown"),bounds,domain,ApplicationGroupPolicy.ManagementAction.READ));
            assertFalse(evaluator.authorize(identity,new ApplicationGroupAuthorization.Request("main","a","FLOW","UPDATE","f",false),bounds).allowed());
            var readOnly=project(java.util.Map.of("opaque-admin-name",scope(grant("a",false,Set.of("READ"),Set.of()))));
            assertTrue(evaluator.authorizeManagement(identity,directory(readOnly,"p",Set.of()),domain,ApplicationGroupPolicy.ManagementAction.READ));
            var unregistered=project(java.util.Map.of());
            assertFalse(evaluator.authorizeManagement(identity,directory(unregistered,"p",Set.of()),domain,ApplicationGroupPolicy.ManagementAction.READ));
        }
        assertFalse(auth.authorizeManagement(id("ui","read"),directory,ApplicationGroupPolicy.ManagementDomain.IDENTITY,ApplicationGroupPolicy.ManagementAction.MANAGE));
    }
    @Test void emptyGrantsDuplicateKeysAndOpaqueScopeSyntaxAreValidated() {
        var parser=new ApplicationGroupPolicyParser();
        String json=parser.canonicalJson(policy);
        assertThrows(IllegalArgumentException.class,()->parser.parse(json.replace("\"schemaVersion\":2","\"schemaVersion\":2,\"schemaVersion\":2").getBytes()));
        assertThrows(IllegalArgumentException.class,()->new ApplicationGroupPolicy.ScopeDefinition("",true,java.util.List.of()));
        assertThrows(IllegalArgumentException.class,()->parser.parse(json.replace("\"read-only\":", "\"has space\":").getBytes()));
        assertThrows(IllegalArgumentException.class,()->parser.parse(json.replace("\"p\":", "\"bad.project\":").getBytes()));
        assertThrows(IllegalArgumentException.class,()->parser.parse(new byte[1024*1024+1]));
        var empty=project(java.util.Map.of());
        assertFalse(evaluate(empty,empty,Set.of(),"a","READ","f",false));
    }
    @Test void metadataNeedsAnExplicitCompleteApplicationGrantAndRegisteredScope() {
        var request = new ApplicationGroupAuthorization.Request("main",null,"METADATA","READ",null,false);
        assertFalse(auth.authorize(id("ui","read"),request,directory).allowed());
        var metadata = new ApplicationGroupPolicy.Grant("TENANT","main",null,false,"METADATA",Set.of("READ","LIST"),Set.of(),true);
        var local=project(java.util.Map.of("read",new ApplicationGroupPolicy.ScopeDefinition("",true,java.util.List.of(grant("a",false,Set.of("READ"),Set.of()),metadata))));
        var evaluator=new ApplicationGroupAuthorization(new ApplicationGroupPolicy(2,java.util.Map.of("p",local)));
        var bounds=directory(local,"p",Set.of());
        assertTrue(evaluator.authorize(id("ui","read"),request,bounds).allowed());
        assertTrue(evaluator.authorize(id("ui","read"),new ApplicationGroupAuthorization.Request("main",null,"METADATA","LIST",null,true),bounds).allowed());
        assertFalse(evaluator.authorize(id("ui","read"),new ApplicationGroupAuthorization.Request("main","outside","FLOW","READ","f",false),bounds).allowed());
        assertFalse(evaluator.authorizeManagement(id("ui","read"),bounds,ApplicationGroupPolicy.ManagementDomain.IDENTITY,ApplicationGroupPolicy.ManagementAction.READ));
        var upper=project(java.util.Map.of("read",scope(grant("a",false,Set.of("READ"),Set.of()))));
        assertTrue(evaluator.authorize(id("ui","read"),request,directory(upper,"p",Set.of())).allowed());
        assertFalse(evaluator.authorize(id("ui","read"),request,directory(project(java.util.Map.of()),"p",Set.of())).allowed());
        assertThrows(IllegalArgumentException.class,()->new ApplicationGroupPolicy.Grant("TENANT","main",null,false,"FLOW",Set.of("READ"),Set.of(),true));
        assertThrows(IllegalArgumentException.class,()->new ApplicationGroupPolicy.Grant("TENANT","main",null,false,"METADATA",Set.of("UPDATE"),Set.of(),true));
        assertThrows(IllegalArgumentException.class,()->new ApplicationGroupPolicy.Grant("NAMESPACE","main","a",false,"UNKNOWN",Set.of("READ"),Set.of(),true));
    }
    @Test void correlatedListsKeepNamespaceAndIdPairsAndIntersectIdsWithinScope() {
        var a=grant("a",true,Set.of("READ","LIST"),Set.of("one"));
        var b=grant("b",false,Set.of("READ","LIST"),Set.of("two"));
        var local=project(java.util.Map.of("a",scope(a),"b",scope(b)));
        var evaluator=new ApplicationGroupAuthorization(new ApplicationGroupPolicy(2,java.util.Map.of("p",local)));
        var request=new ApplicationGroupAuthorization.Request("main",null,"FLOW","LIST",null,true,
            new ApplicationGroupAuthorization.Domain(Set.of("b"),Set.of("a")));
        var bounds=directory(local,"p",Set.of());
        var decision=evaluator.authorizeList(id("ui","a","b"),request,bounds);
        assertTrue(decision.allowed());
        assertTrue(decision.contains("a.child","one"));
        assertTrue(decision.contains("b","two"));
        assertFalse(decision.contains("a","two"));
        assertFalse(decision.contains("b","one"));
        assertFalse(decision.contains("b.child","two"));
        assertFalse(decision.contains("outside","one"));
        assertFalse(evaluator.authorize(id("ui","a","b"),request,bounds).allowed(),"Legacy domain cannot carry ID constraints");
        assertFalse(evaluator.authorizeList(id("ui","a","unknown"),request,bounds).allowed());
        var intersected=new ApplicationGroupPolicy.ScopeDefinition("",true,java.util.List.of(
            grant("a",true,Set.of("READ"),Set.of("one","two")),
            grant("a.child",false,Set.of("LIST"),Set.of("two","three"))));
        var narrowed=project(java.util.Map.of("r",intersected));
        var narrowedEvaluator=new ApplicationGroupAuthorization(new ApplicationGroupPolicy(2,java.util.Map.of("p",narrowed)));
        var result=narrowedEvaluator.authorizeList(id("ui","r"),request,directory(narrowed,"p",Set.of()));
        assertTrue(result.contains("a.child","two"));
        assertFalse(result.contains("a","two"));
        assertFalse(result.contains("a.child","one"));
        assertFalse(result.contains("a.child","three"));
        var explicitId = new ApplicationGroupAuthorization.Request("main","a.child","FLOW","LIST","two",true);
        var explicitResult = narrowedEvaluator.authorizeList(id("ui","r"),explicitId,directory(narrowed,"p",Set.of()));
        assertTrue(explicitResult.contains("a.child","two"));
        assertFalse(explicitResult.contains("a.child","one"));
        var excludedId = new ApplicationGroupAuthorization.Request("main","a.child","FLOW","LIST","one",true);
        assertFalse(narrowedEvaluator.authorizeList(id("ui","r"),excludedId,directory(narrowed,"p",Set.of())).allowed());
    }
    @Test void correlatedListsNeverBorrowReadListOrIdsAcrossScopes() {
        var local=project(java.util.Map.of("r",scope(grant("a",false,Set.of("READ"),Set.of("one"))),
            "l",scope(grant("a",false,Set.of("LIST"),Set.of("one")))));
        var evaluator=new ApplicationGroupAuthorization(new ApplicationGroupPolicy(2,java.util.Map.of("p",local)));
        var request=new ApplicationGroupAuthorization.Request("main","a","FLOW","LIST",null,true);
        assertFalse(evaluator.authorizeList(id("ui","r","l"),request,directory(local,"p",Set.of())).allowed());
        var disjoint=new ApplicationGroupPolicy.ScopeDefinition("",true,java.util.List.of(
            grant("a",false,Set.of("READ"),Set.of("one")),grant("a",false,Set.of("LIST"),Set.of("two"))));
        local=project(java.util.Map.of("r",disjoint));
        evaluator=new ApplicationGroupAuthorization(new ApplicationGroupPolicy(2,java.util.Map.of("p",local)));
        assertFalse(evaluator.authorizeList(id("ui","r"),request,directory(local,"p",Set.of())).allowed());
    }
    @Test void correlatedTenantListsRetainIdLimitsAndCannotBecomeNamespacePermissions() {
        var grant=new ApplicationGroupPolicy.Grant("TENANT","main",null,false,"MCP_SERVER",Set.of("READ","LIST"),Set.of("default"),false);
        var local=project(java.util.Map.of("r",scope(grant)));
        var evaluator=new ApplicationGroupAuthorization(new ApplicationGroupPolicy(2,java.util.Map.of("p",local)));
        var bounds=directory(local,"p",Set.of());
        var result=evaluator.authorizeList(id("ui","r"),new ApplicationGroupAuthorization.Request("main",null,"MCP_SERVER","LIST",null,true),bounds);
        assertTrue(result.allowed());
        assertTrue(result.contains(null,"default"));
        assertFalse(result.contains(null,"other"));
        assertFalse(result.contains("a","default"));
        assertFalse(evaluator.authorizeList(id("ui","r"),new ApplicationGroupAuthorization.Request("main","a","MCP_SERVER","LIST",null,true),bounds).allowed());
        assertFalse(evaluator.authorizeList(id("ui","r"),new ApplicationGroupAuthorization.Request("main","a","FLOW","LIST",null,true),bounds).allowed());
        assertFalse(evaluator.authorizeList(null,null,bounds).allowed());
    }

    @Test void directoryRevisionChangesDoNotRequireApplicationPolicyRecovery() {
        var management = new ApplicationGroupPolicy.ManagementGrant(ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,
            Set.of(ApplicationGroupPolicy.ManagementAction.MANAGE));
        var definition = new ApplicationGroupPolicy.ScopeDefinition("",true,java.util.List.of(),java.util.List.of(management));
        var local = project(java.util.Map.of("manager",definition,"unrelated",scope(grant("a",false,Set.of("READ"),Set.of()))));
        var current = DirectoryCatalogTestData.catalog(new ApplicationGroupPolicy.ApplicationGroup(local.issuer(),local.audiences(),true,java.util.Map.of("manager",definition)),4);
        var evaluator = new ApplicationGroupAuthorization(new ApplicationGroupPolicy(2,java.util.Map.of("p",local)));
        var identity = new ApplicationGroupAuthorization.Identity("s",local.issuer(),"ui","p",4,Set.of("manager"),Set.of());
        var directory = directory(current,"p",Set.of());
        assertTrue(canManage(evaluator,identity,directory,"p"));
        assertTrue(evaluator.authorizeManagement(identity,directory,ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,ApplicationGroupPolicy.ManagementAction.MANAGE));
        assertFalse(evaluator.authorize(identity,new ApplicationGroupAuthorization.Request("main","a","FLOW","READ","f",false),directory).allowed());
        assertFalse(canManage(evaluator,new ApplicationGroupAuthorization.Identity("s",local.issuer(),"ui","p",4,Set.of("manager","unknown"),Set.of()),directory,"p"));
        assertFalse(canManage(evaluator,identity,directory(new ApplicationGroupPolicy.ApplicationGroup(local.issuer(),local.audiences(),true,java.util.Map.of()),"p",Set.of()),"p"));
        var disabled = new ApplicationGroupPolicy.ScopeDefinition("",false,java.util.List.of(),java.util.List.of(management));
        assertTrue(canManage(evaluator,identity,directory(DirectoryCatalogTestData.catalog(new ApplicationGroupPolicy.ApplicationGroup(local.issuer(),local.audiences(),true,java.util.Map.of("manager",disabled)),4),"p",Set.of()),"p"));
        var disabledPolicy=new ApplicationGroupAuthorization(new ApplicationGroupPolicy(2,java.util.Map.of("p",project(java.util.Map.of("manager",disabled)))));
        assertFalse(canManage(disabledPolicy,identity,directory,"p"));
        var readOnly = new ApplicationGroupPolicy.ScopeDefinition("",true,java.util.List.of(),java.util.List.of(new ApplicationGroupPolicy.ManagementGrant(ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,Set.of(ApplicationGroupPolicy.ManagementAction.READ))));
        assertTrue(canManage(evaluator,identity,directory(DirectoryCatalogTestData.catalog(new ApplicationGroupPolicy.ApplicationGroup(local.issuer(),local.audiences(),true,java.util.Map.of("manager",readOnly)),4),"p",Set.of()),"p"));
        var readOnlyPolicy=new ApplicationGroupAuthorization(new ApplicationGroupPolicy(2,java.util.Map.of("p",project(java.util.Map.of("manager",readOnly)))));
        assertFalse(canManage(readOnlyPolicy,identity,directory,"p"));
        assertFalse(canManage(auth,id("ui","read"),this.directory,"p"));
    }
    @Test void explicitLocalManagementTargetsDoNotIncludeOwnerOrOtherProjects() {
        var grant=new ApplicationGroupPolicy.ManagementGrant(ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,
            Set.of(ApplicationGroupPolicy.ManagementAction.READ,ApplicationGroupPolicy.ManagementAction.MANAGE),Set.of("target"));
        var definition=new ApplicationGroupPolicy.ScopeDefinition("",true,java.util.List.of(),java.util.List.of(grant));
        var local=project(java.util.Map.of("manager",definition));
        var evaluator=new ApplicationGroupAuthorization(new ApplicationGroupPolicy(2,java.util.Map.of("p",local)));
        var bounds=directory(local,"p",Set.of());
        assertTrue(evaluator.authorizeManagement(id("ui","manager"),bounds,ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,ApplicationGroupPolicy.ManagementAction.MANAGE,"target"));
        assertFalse(evaluator.authorizeManagement(id("ui","manager"),bounds,ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,ApplicationGroupPolicy.ManagementAction.MANAGE));
        assertFalse(evaluator.authorizeManagement(id("ui","manager"),bounds,ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,ApplicationGroupPolicy.ManagementAction.MANAGE,"other"));
        assertTrue(canManage(evaluator,id("ui","manager"),bounds,"target"));
        assertFalse(canManage(evaluator,id("ui","manager"),bounds,"p"));
        assertFalse(evaluator.authorizeManagement(id("ui","manager"),bounds,ApplicationGroupPolicy.ManagementDomain.IDENTITY,ApplicationGroupPolicy.ManagementAction.MANAGE,"target"));
        assertFalse(evaluator.authorizeManagement(id("ui","manager"),bounds,ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,ApplicationGroupPolicy.ManagementAction.MANAGE,"*"));
        var parser=new ApplicationGroupPolicyParser();
        var policy=new ApplicationGroupPolicy(2,java.util.Map.of("p",local));
        assertEquals(policy,parser.parse(parser.canonicalJson(policy).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    @Test void managementActionCannotBorrowAnotherScopesProjectTarget() {
        var ownManage=new ApplicationGroupPolicy.ManagementGrant(ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,Set.of(ApplicationGroupPolicy.ManagementAction.MANAGE));
        var targetRead=new ApplicationGroupPolicy.ManagementGrant(ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,Set.of(ApplicationGroupPolicy.ManagementAction.READ),Set.of("target"));
        var local=project(java.util.Map.of(
            "manage",new ApplicationGroupPolicy.ScopeDefinition("",true,java.util.List.of(),java.util.List.of(ownManage)),
            "target",new ApplicationGroupPolicy.ScopeDefinition("",true,java.util.List.of(),java.util.List.of(targetRead))));
        var evaluator=new ApplicationGroupAuthorization(new ApplicationGroupPolicy(2,java.util.Map.of("p",local)));
        var bounds=directory(local,"p",Set.of());
        var identity=id("ui","manage","target");
        assertTrue(evaluator.authorizeManagement(identity,bounds,ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,ApplicationGroupPolicy.ManagementAction.MANAGE,"p"));
        assertTrue(evaluator.authorizeManagement(identity,bounds,ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,ApplicationGroupPolicy.ManagementAction.READ,"target"));
        assertFalse(evaluator.authorizeManagement(identity,bounds,ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,ApplicationGroupPolicy.ManagementAction.MANAGE,"target"));
        assertFalse(canManage(evaluator,identity,bounds,"target"));
    }
    @Test void managementTargetsComeFromTheApplicationPolicyWithoutBorrowingResourcePermissions() {
        var bounded=new ApplicationGroupPolicy.ManagementGrant(ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,Set.of(ApplicationGroupPolicy.ManagementAction.MANAGE),Set.of("target"));
        var expanded=new ApplicationGroupPolicy.ManagementGrant(ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,Set.of(ApplicationGroupPolicy.ManagementAction.MANAGE),Set.of("target","other"));
        var local=project(java.util.Map.of("manager",new ApplicationGroupPolicy.ScopeDefinition("",true,java.util.List.of(),java.util.List.of(expanded))));
        var upper=project(java.util.Map.of("manager",new ApplicationGroupPolicy.ScopeDefinition("",true,java.util.List.of(),java.util.List.of(bounded))));
        var evaluator=new ApplicationGroupAuthorization(new ApplicationGroupPolicy(2,java.util.Map.of("p",local)));
        var bounds=directory(upper,"p",Set.of());
        assertTrue(evaluator.authorizeManagement(id("ui","manager"),bounds,ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,ApplicationGroupPolicy.ManagementAction.MANAGE,"target"));
        assertTrue(canManage(evaluator,id("ui","manager"),bounds,"target"));
        assertTrue(canManage(evaluator,id("ui","manager"),bounds,"other"));
        assertFalse(evaluator.authorizeManagement(id("ui","manager"),bounds,ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,ApplicationGroupPolicy.ManagementAction.MANAGE,"outside"));
        assertThrows(IllegalArgumentException.class,()->new ApplicationGroupPolicy.ManagementGrant(ApplicationGroupPolicy.ManagementDomain.IDENTITY,Set.of(ApplicationGroupPolicy.ManagementAction.MANAGE),Set.of("target")));
        assertThrows(IllegalArgumentException.class,()->new ApplicationGroupPolicy.ManagementGrant(ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,Set.of(ApplicationGroupPolicy.ManagementAction.MANAGE),Set.of("*")));
        assertThrows(IllegalArgumentException.class,()->new ApplicationGroupPolicy.ManagementGrant(ApplicationGroupPolicy.ManagementDomain.BUSINESS_INSTANCE,Set.of(ApplicationGroupPolicy.ManagementAction.MANAGE),Set.of("target")));
    }

    private ApplicationGroupAuthorization.Directory directory(ApplicationGroupPolicy.ApplicationGroup policy, String group, Set<String> protocols) {
        return directory(DirectoryCatalogTestData.catalog(policy,3),group,protocols);
    }
    private ApplicationGroupAuthorization.Directory directory(ApplicationGroupAuthorization.ScopeCatalog catalog, String group, Set<String> protocols) {
        return new ApplicationGroupAuthorization.Directory(catalog,group,protocols);
    }
    private boolean canManage(ApplicationGroupAuthorization authorization, ApplicationGroupAuthorization.Identity identity,
                              ApplicationGroupAuthorization.Directory directory, String target) {
        return authorization.authorizeManagement(identity,directory,ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,
            ApplicationGroupPolicy.ManagementAction.MANAGE,target);
    }
}
