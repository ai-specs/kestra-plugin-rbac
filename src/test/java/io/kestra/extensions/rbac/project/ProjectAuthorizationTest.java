package io.kestra.extensions.rbac.project;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ProjectAuthorizationTest {
    private final ProjectPolicy policy = new ProjectPolicyParser().parse(("""
        {"schemaVersion":2,"applicationProjects":{"p":{"issuer":"https://iss.example","audiences":["ui","mcp"],"enabled":true,"directoryRevision":3,"scopes":{
        "read":{"description":"r","enabled":true,"grants":[{"scope":"NAMESPACE","tenant":"main","namespace":"a","descendants":true,"resource":"FLOW","actions":["READ","LIST"],"ids":[],"allIds":true}]},
        "write":{"description":"w","enabled":true,"grants":[{"scope":"NAMESPACE","tenant":"main","namespace":"b","descendants":false,"resource":"FLOW","actions":["READ","LIST","UPDATE"],"ids":[],"allIds":true}]},
        "read-only":{"description":"r","enabled":true,"grants":[{"scope":"NAMESPACE","tenant":"main","namespace":"a","descendants":false,"resource":"FLOW","actions":["READ"],"ids":[],"allIds":true}]},
        "list-only":{"description":"l","enabled":true,"grants":[{"scope":"NAMESPACE","tenant":"main","namespace":"a","descendants":false,"resource":"FLOW","actions":["LIST"],"ids":[],"allIds":true}]}
        }}}}
        """).getBytes());
    private final ProjectAuthorization auth = new ProjectAuthorization(policy);
    private final ProjectAuthorization.Directory directory = new ProjectAuthorization.Directory(policy.applicationProjects().get("p"), "p", Set.of("openid"));
    private ProjectAuthorization.Identity id(String audience, String... scopes) { return new ProjectAuthorization.Identity("s","https://iss.example",audience,"p",3,Set.of(scopes),Set.of()); }
    @Test void completeScopeAndIdentityBoundaries() {
        assertTrue(auth.authorize(id("ui","read"), new ProjectAuthorization.Request("main","a.child","FLOW","READ","x",false), directory).allowed());
        assertFalse(auth.authorize(id("bad","read"), new ProjectAuthorization.Request("main","a","FLOW","READ","x",false), directory).allowed());
        assertFalse(auth.authorize(new ProjectAuthorization.Identity("s","https://other","ui","p",3,Set.of("read"),Set.of()), new ProjectAuthorization.Request("main","a","FLOW","READ","x",false), directory).allowed());
    }
    @Test void crossScopeReadListCannotBeComposed() {
        assertFalse(auth.authorize(id("ui","read-only","list-only"), new ProjectAuthorization.Request("main","a","FLOW","READ",null,true), directory).allowed());
        assertFalse(auth.authorize(id("ui","read-only"), new ProjectAuthorization.Request("main","a","FLOW","READ",null,true), directory).allowed());
        assertFalse(auth.authorize(id("ui","list-only"), new ProjectAuthorization.Request("main","a","FLOW","READ",null,true), directory).allowed());
        assertTrue(auth.authorize(id("ui","read"), new ProjectAuthorization.Request("main","a","FLOW","READ",null,true), directory).allowed());
        assertFalse(auth.authorize(id("ui","read"), new ProjectAuthorization.Request("main","a2","FLOW","READ",null,true), directory).allowed());
    }
    @Test void writeAndUnknownScopeFailClosed() {
        assertTrue(auth.authorize(id("mcp","write"), new ProjectAuthorization.Request("main","b","FLOW","UPDATE","f",false), directory).allowed());
        assertFalse(auth.authorize(id("ui","write","unknown"), new ProjectAuthorization.Request("main","b","FLOW","UPDATE","f",false), directory).allowed());
        assertFalse(auth.authorize(id("ui","read"), new ProjectAuthorization.Request("main","a","FLOW","LIST",null,true), directory).domain().contains("a.child"));
    }
    @Test void directoryRevisionAndUpperBoundAreRequired() {
        var request = new ProjectAuthorization.Request("main","a","FLOW","READ","f",false);
        assertFalse(auth.authorize(id("ui","read"), request, null).allowed());
        var upper = policy.applicationProjects().get("p");
        var narrower = new ProjectPolicy.Project(upper.issuer(), upper.audiences(), true, 3, java.util.Map.of("write", upper.scopes().get("write")));
        assertFalse(auth.authorize(id("ui","read"), request, new ProjectAuthorization.Directory(narrower,"p",Set.of())).allowed());
        assertFalse(auth.authorize(new ProjectAuthorization.Identity("s",upper.issuer(),"ui","p",4,Set.of("read"),Set.of()),request,directory).allowed());
    }
    @Test void directoryRevisionCannotBeReplacedByIndependentAuthorizationEpoch() {
        long directoryRevision=3, authorizationVersion=97;
        assertNotEquals(directoryRevision,authorizationVersion);
        var request=new ProjectAuthorization.Request("main","a","FLOW","READ","f",false);
        var trusted=new ProjectAuthorization.Identity("s","https://iss.example","ui","p",directoryRevision,Set.of("read"),Set.of());
        assertTrue(auth.authorize(trusted,request,directory).allowed());
        var confusedIdentity=new ProjectAuthorization.Identity("s","https://iss.example","ui","p",authorizationVersion,Set.of("read"),Set.of());
        assertFalse(auth.authorize(confusedIdentity,request,directory).allowed());
        var upper=directory.upperBound();
        var confusedUpper=new ProjectPolicy.Project(upper.issuer(),upper.audiences(),true,authorizationVersion,upper.scopes());
        assertFalse(auth.authorize(trusted,request,new ProjectAuthorization.Directory(confusedUpper,"p",Set.of("openid"))).allowed());
        var confusedLocal=new ProjectAuthorization(new ProjectPolicy(2,java.util.Map.of("p",confusedUpper)));
        assertFalse(confusedLocal.authorize(trusted,request,directory).allowed());
    }
    @Test void unrelatedRemovedBindingDoesNotSupplyOrInvalidateTokenPermissions() {
        var upper=policy.applicationProjects().get("p");
        var readOnlyDirectory=new ProjectPolicy.Project(upper.issuer(),upper.audiences(),true,3,java.util.Map.of("read",upper.scopes().get("read")));
        var directory=new ProjectAuthorization.Directory(readOnlyDirectory,"p",Set.of("openid"));
        var read=new ProjectAuthorization.Request("main","a","FLOW","READ","f",false);
        assertTrue(auth.authorize(id("ui","read"),read,directory).allowed());
        assertFalse(auth.authorize(id("ui","read","write"),read,directory).allowed());
        assertFalse(auth.authorize(id("ui","read"),new ProjectAuthorization.Request("main","b","FLOW","UPDATE","f",false),directory).allowed());
    }
    @Test void structuredDomainsIntersectParentSubtreeAndExactChild() {
        var parent = new ProjectAuthorization.Domain(Set.of(),Set.of("a"));
        var exact = new ProjectAuthorization.Domain(Set.of("a.child","a2"),Set.of());
        var intersection = parent.intersect(exact);
        assertTrue(intersection.contains("a.child"));
        assertFalse(intersection.contains("a.child.next"));
        assertFalse(intersection.contains("a2"));
        var child = parent.intersect(new ProjectAuthorization.Domain(Set.of(),Set.of("a.child")));
        assertTrue(child.contains("a.child.next"));
        assertFalse(child.contains("a.other"));
    }
    @Test void allActionsAndRestrictedIdsRemainIndependent() {
        for (String action : java.util.List.of("CREATE","UPDATE","DELETE","EXECUTE","ACCESS_OUTPUTS"))
            assertFalse(auth.authorize(id("ui","read"),new ProjectAuthorization.Request("main","a","FLOW",action,"f",false),directory).allowed());
        assertTrue(auth.authorize(id("ui","read-only"),new ProjectAuthorization.Request("main","a","FLOW","READ","f",false),directory).allowed());
        assertTrue(auth.authorize(id("ui","read-only"),new ProjectAuthorization.Request("main","a","FLOW","READ","other",false),directory).allowed());
        assertFalse(auth.authorize(id("ui","read-only"),new ProjectAuthorization.Request("main","a","FLOW","LIST",null,true),directory).allowed());
    }
    @Test void mcpServerConfigurationUsesItsOwnTenantResourceAndActions() {
        var read=new ProjectPolicy.Grant("TENANT","main",null,false,"MCP_SERVER",Set.of("READ","LIST"),Set.of(),true);
        var server=project(java.util.Map.of("servers",scope(read)));
        var bounds=new ProjectAuthorization.Directory(server,"p",Set.of("openid"));
        var evaluator=new ProjectAuthorization(new ProjectPolicy(2,java.util.Map.of("p",server)));
        var identity=id("ui","servers");
        assertTrue(evaluator.authorize(identity,new ProjectAuthorization.Request("main",null,"MCP_SERVER","READ","default",false),bounds).allowed());
        assertTrue(evaluator.authorize(identity,new ProjectAuthorization.Request("main",null,"MCP_SERVER","LIST",null,true),bounds).allowed());
        assertFalse(evaluator.authorize(identity,new ProjectAuthorization.Request("main",null,"MCP_SERVER","UPDATE","default",false),bounds).allowed());
        assertFalse(evaluator.authorize(identity,new ProjectAuthorization.Request("main","a","FLOW","READ","default",false),bounds).allowed());
        assertFalse(evaluator.authorizeManagement(identity,bounds,ProjectPolicy.ManagementDomain.IDENTITY,ProjectPolicy.ManagementAction.MANAGE));
    }
    @Test void mcpConfigurationIdsAndListActionsCannotBeBorrowedAcrossScopes() {
        var update=new ProjectPolicy.Grant("TENANT","main",null,false,"MCP_SERVER",Set.of("READ","UPDATE"),Set.of("default"),false);
        var read=new ProjectPolicy.Grant("TENANT","main",null,false,"MCP_SERVER",Set.of("READ"),Set.of(),true);
        var list=new ProjectPolicy.Grant("TENANT","main",null,false,"MCP_SERVER",Set.of("LIST"),Set.of(),true);
        var server=project(java.util.Map.of("one",scope(update),"r",scope(read),"l",scope(list)));
        var bounds=new ProjectAuthorization.Directory(server,"p",Set.of("openid"));
        var evaluator=new ProjectAuthorization(new ProjectPolicy(2,java.util.Map.of("p",server)));
        assertTrue(evaluator.authorize(id("ui","one"),new ProjectAuthorization.Request("main",null,"MCP_SERVER","UPDATE","default",false),bounds).allowed());
        assertFalse(evaluator.authorize(id("ui","one"),new ProjectAuthorization.Request("main",null,"MCP_SERVER","UPDATE","another",false),bounds).allowed());
        assertFalse(evaluator.authorize(id("ui","r","l"),new ProjectAuthorization.Request("main",null,"MCP_SERVER","LIST",null,true),bounds).allowed());
    }
    @Test void parsingIsStrictAndCanonicalRoundTripIsStable() {
        var parser = new ProjectPolicyParser(); String json=parser.canonicalJson(policy);
        assertEquals(json,parser.canonicalJson(parser.parse(json.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        assertThrows(IllegalArgumentException.class,()->parser.parse((json+" {}").getBytes()));
        assertThrows(IllegalArgumentException.class,()->parser.parse(json.replace("\"schemaVersion\":2","\"schemaVersion\":2.5").getBytes()));
        assertThrows(IllegalArgumentException.class,()->parser.parse(new byte[]{(byte)0xc3,0x28}));
        assertThrows(IllegalArgumentException.class,()->parser.parse(json.replace("https://iss.example","not-uri").getBytes()));
        assertThrows(IllegalArgumentException.class,()->parser.parse(json.replace("\"actions\":[\"LIST\",\"READ\"]","\"actions\":[]").getBytes()));
    }
    private ProjectPolicy.Grant grant(String namespace, boolean tree, Set<String> actions, Set<String> ids) {
        return new ProjectPolicy.Grant("NAMESPACE","main",namespace,tree,"FLOW",actions,ids,ids.isEmpty());
    }
    private ProjectPolicy.Project project(java.util.Map<String,ProjectPolicy.ScopeDefinition> scopes) {
        return new ProjectPolicy.Project("https://iss.example",Set.of("ui","mcp"),true,3,scopes);
    }
    private ProjectPolicy.ScopeDefinition scope(ProjectPolicy.Grant grant) {
        return new ProjectPolicy.ScopeDefinition("",true,java.util.List.of(grant));
    }
    private boolean evaluate(ProjectPolicy.Project local, ProjectPolicy.Project upper, Set<String> scopes, String namespace, String action, String resourceId, boolean list) {
        return new ProjectAuthorization(new ProjectPolicy(2,java.util.Map.of("p",local)))
            .authorize(new ProjectAuthorization.Identity("s",local.issuer(),"ui","p",3,scopes,Set.of()),
                new ProjectAuthorization.Request("main",namespace,"FLOW",action,resourceId,list),
                new ProjectAuthorization.Directory(upper,"p",Set.of("openid"))).allowed();
    }
    @Test void crossScopeReadAndListWithAllIdsCannotCombineInSameOrDifferentNamespace() {
        for (String namespace : java.util.List.of("a","b")) {
            var local=project(java.util.Map.of("r",scope(grant("a",false,Set.of("READ"),Set.of())),"l",scope(grant(namespace,false,Set.of("LIST"),Set.of()))));
            assertFalse(evaluate(local,local,Set.of("r","l"),"a","LIST",null,true));
            assertFalse(evaluate(local,local,Set.of("r","l"),"b","LIST",null,true));
        }
    }
    @Test void completeScopeUnionKeepsAReadAndBWriteSeparate() {
        assertTrue(auth.authorize(id("ui","read","write"),new ProjectAuthorization.Request("main","a","FLOW","READ","f",false),directory).allowed());
        assertFalse(auth.authorize(id("ui","read","write"),new ProjectAuthorization.Request("main","a","FLOW","UPDATE","f",false),directory).allowed());
        assertTrue(auth.authorize(id("ui","read","write"),new ProjectAuthorization.Request("main","b","FLOW","UPDATE","f",false),directory).allowed());
        assertFalse(auth.authorize(id("ui","read","write"),new ProjectAuthorization.Request("main","outside","FLOW","UPDATE","f",false),directory).allowed());
    }
    @Test void localWriteCannotExceedReadOnlyDirectoryUpperBound() {
        var upper=project(java.util.Map.of("r",scope(grant("a",false,Set.of("READ"),Set.of()))));
        var local=project(java.util.Map.of("r",scope(grant("a",false,Set.of("READ","UPDATE"),Set.of()))));
        assertFalse(evaluate(local,upper,Set.of("r"),"a","UPDATE","f",false));
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
        var base=policy.applicationProjects().get("p");
        var disabled=new ProjectPolicy.ScopeDefinition("",false,base.scopes().get("read").grants());
        var local=project(java.util.Map.of("read",base.scopes().get("read"),"disabled",disabled));
        var upper=project(java.util.Map.of("read",base.scopes().get("read"),"disabled",scope(grant("a",true,Set.of("READ","LIST"),Set.of()))));
        assertFalse(evaluate(local,upper,Set.of("read","disabled"),"a","READ","f",false));
        assertFalse(evaluate(local,upper,Set.of("read","unknown"),"a","READ","f",false));
        var request=new ProjectAuthorization.Request("main","a","FLOW","READ","f",false);
        assertTrue(auth.authorize(new ProjectAuthorization.Identity("s",base.issuer(),"ui","p",3,Set.of("read"),Set.of("openid")),request,directory).allowed());
        assertFalse(auth.authorize(new ProjectAuthorization.Identity("s",base.issuer(),"ui","p",3,Set.of("read"),Set.of("openidx")),request,directory).allowed());
        assertFalse(auth.authorize(new ProjectAuthorization.Identity("s",base.issuer(),"ui","p",0,Set.of("read"),Set.of()),request,directory).allowed());
        assertFalse(auth.authorize(new ProjectAuthorization.Identity("s",base.issuer(),"ui","other",3,Set.of("read"),Set.of()),request,directory).allowed());
        assertFalse(auth.authorize(id("ui","read"),new ProjectAuthorization.Request("other","a","FLOW","READ","f",false),directory).allowed());
    }
    @Test void managementDomainsRequireExplicitLocalAndDirectoryGrants() {
        for (var domain : ProjectPolicy.ManagementDomain.values()) {
            var grant = new ProjectPolicy.ManagementGrant(domain,Set.of(ProjectPolicy.ManagementAction.READ,ProjectPolicy.ManagementAction.MANAGE));
            var scope = new ProjectPolicy.ScopeDefinition("",true,java.util.List.of(),java.util.List.of(grant));
            var local=project(java.util.Map.of("opaque-admin-name",scope));
            var evaluator=new ProjectAuthorization(new ProjectPolicy(2,java.util.Map.of("p",local)));
            var identity=id("ui","opaque-admin-name");
            var bounds=new ProjectAuthorization.Directory(local,"p",Set.of("openid"));
            assertTrue(evaluator.authorizeManagement(identity,bounds,domain,ProjectPolicy.ManagementAction.READ));
            assertTrue(evaluator.authorizeManagement(identity,bounds,domain,ProjectPolicy.ManagementAction.MANAGE));
            for(var other:ProjectPolicy.ManagementDomain.values()) if(other!=domain)
                assertFalse(evaluator.authorizeManagement(identity,bounds,other,ProjectPolicy.ManagementAction.READ));
            assertFalse(evaluator.authorizeManagement(id("ui","opaque-admin-name","unknown"),bounds,domain,ProjectPolicy.ManagementAction.READ));
            assertFalse(evaluator.authorize(identity,new ProjectAuthorization.Request("main","a","FLOW","UPDATE","f",false),bounds).allowed());
            var readOnly=project(java.util.Map.of("opaque-admin-name",scope(grant("a",false,Set.of("READ"),Set.of()))));
            assertFalse(evaluator.authorizeManagement(identity,new ProjectAuthorization.Directory(readOnly,"p",Set.of()),domain,ProjectPolicy.ManagementAction.READ));
        }
        assertFalse(auth.authorizeManagement(id("ui","read"),directory,ProjectPolicy.ManagementDomain.IDENTITY,ProjectPolicy.ManagementAction.MANAGE));
    }
    @Test void emptyGrantsDuplicateKeysAndOpaqueScopeSyntaxAreValidated() {
        var parser=new ProjectPolicyParser();
        String json=parser.canonicalJson(policy);
        assertThrows(IllegalArgumentException.class,()->parser.parse(json.replace("\"schemaVersion\":2","\"schemaVersion\":2,\"schemaVersion\":2").getBytes()));
        assertThrows(IllegalArgumentException.class,()->new ProjectPolicy.ScopeDefinition("",true,java.util.List.of()));
        assertThrows(IllegalArgumentException.class,()->parser.parse(json.replace("\"read-only\":", "\"has space\":").getBytes()));
        assertThrows(IllegalArgumentException.class,()->parser.parse(json.replace("\"p\":", "\"bad.project\":").getBytes()));
        assertThrows(IllegalArgumentException.class,()->parser.parse(new byte[1024*1024+1]));
        var empty=project(java.util.Map.of());
        assertFalse(evaluate(empty,empty,Set.of(),"a","READ","f",false));
    }
    @Test void metadataNeedsExplicitCompleteLocalAndDirectoryGrant() {
        var request = new ProjectAuthorization.Request("main",null,"METADATA","READ",null,false);
        assertFalse(auth.authorize(id("ui","read"),request,directory).allowed());
        var metadata = new ProjectPolicy.Grant("TENANT","main",null,false,"METADATA",Set.of("READ","LIST"),Set.of(),true);
        var local=project(java.util.Map.of("read",new ProjectPolicy.ScopeDefinition("",true,java.util.List.of(grant("a",false,Set.of("READ"),Set.of()),metadata))));
        var evaluator=new ProjectAuthorization(new ProjectPolicy(2,java.util.Map.of("p",local)));
        var bounds=new ProjectAuthorization.Directory(local,"p",Set.of());
        assertTrue(evaluator.authorize(id("ui","read"),request,bounds).allowed());
        assertTrue(evaluator.authorize(id("ui","read"),new ProjectAuthorization.Request("main",null,"METADATA","LIST",null,true),bounds).allowed());
        assertFalse(evaluator.authorize(id("ui","read"),new ProjectAuthorization.Request("main","outside","FLOW","READ","f",false),bounds).allowed());
        assertFalse(evaluator.authorizeManagement(id("ui","read"),bounds,ProjectPolicy.ManagementDomain.IDENTITY,ProjectPolicy.ManagementAction.READ));
        var upper=project(java.util.Map.of("read",scope(grant("a",false,Set.of("READ"),Set.of()))));
        assertFalse(evaluator.authorize(id("ui","read"),request,new ProjectAuthorization.Directory(upper,"p",Set.of())).allowed());
        assertThrows(IllegalArgumentException.class,()->new ProjectPolicy.Grant("TENANT","main",null,false,"FLOW",Set.of("READ"),Set.of(),true));
        assertThrows(IllegalArgumentException.class,()->new ProjectPolicy.Grant("TENANT","main",null,false,"METADATA",Set.of("UPDATE"),Set.of(),true));
        assertThrows(IllegalArgumentException.class,()->new ProjectPolicy.Grant("NAMESPACE","main","a",false,"UNKNOWN",Set.of("READ"),Set.of(),true));
    }
    @Test void correlatedListsKeepNamespaceAndIdPairsAndIntersectIdsWithinScope() {
        var a=grant("a",true,Set.of("READ","LIST"),Set.of("one"));
        var b=grant("b",false,Set.of("READ","LIST"),Set.of("two"));
        var local=project(java.util.Map.of("a",scope(a),"b",scope(b)));
        var evaluator=new ProjectAuthorization(new ProjectPolicy(2,java.util.Map.of("p",local)));
        var request=new ProjectAuthorization.Request("main",null,"FLOW","LIST",null,true,
            new ProjectAuthorization.Domain(Set.of("b"),Set.of("a")));
        var bounds=new ProjectAuthorization.Directory(local,"p",Set.of());
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
        var intersected=new ProjectPolicy.ScopeDefinition("",true,java.util.List.of(
            grant("a",true,Set.of("READ"),Set.of("one","two")),
            grant("a.child",false,Set.of("LIST"),Set.of("two","three"))));
        var narrowed=project(java.util.Map.of("r",intersected));
        var narrowedEvaluator=new ProjectAuthorization(new ProjectPolicy(2,java.util.Map.of("p",narrowed)));
        var result=narrowedEvaluator.authorizeList(id("ui","r"),request,new ProjectAuthorization.Directory(narrowed,"p",Set.of()));
        assertTrue(result.contains("a.child","two"));
        assertFalse(result.contains("a","two"));
        assertFalse(result.contains("a.child","one"));
        assertFalse(result.contains("a.child","three"));
        var explicitId = new ProjectAuthorization.Request("main","a.child","FLOW","LIST","two",true);
        var explicitResult = narrowedEvaluator.authorizeList(id("ui","r"),explicitId,new ProjectAuthorization.Directory(narrowed,"p",Set.of()));
        assertTrue(explicitResult.contains("a.child","two"));
        assertFalse(explicitResult.contains("a.child","one"));
        var excludedId = new ProjectAuthorization.Request("main","a.child","FLOW","LIST","one",true);
        assertFalse(narrowedEvaluator.authorizeList(id("ui","r"),excludedId,new ProjectAuthorization.Directory(narrowed,"p",Set.of())).allowed());
    }
    @Test void correlatedListsNeverBorrowReadListOrIdsAcrossScopes() {
        var local=project(java.util.Map.of("r",scope(grant("a",false,Set.of("READ"),Set.of("one"))),
            "l",scope(grant("a",false,Set.of("LIST"),Set.of("one")))));
        var evaluator=new ProjectAuthorization(new ProjectPolicy(2,java.util.Map.of("p",local)));
        var request=new ProjectAuthorization.Request("main","a","FLOW","LIST",null,true);
        assertFalse(evaluator.authorizeList(id("ui","r","l"),request,new ProjectAuthorization.Directory(local,"p",Set.of())).allowed());
        var disjoint=new ProjectPolicy.ScopeDefinition("",true,java.util.List.of(
            grant("a",false,Set.of("READ"),Set.of("one")),grant("a",false,Set.of("LIST"),Set.of("two"))));
        local=project(java.util.Map.of("r",disjoint));
        evaluator=new ProjectAuthorization(new ProjectPolicy(2,java.util.Map.of("p",local)));
        assertFalse(evaluator.authorizeList(id("ui","r"),request,new ProjectAuthorization.Directory(local,"p",Set.of())).allowed());
    }
    @Test void correlatedTenantListsRetainIdLimitsAndCannotBecomeNamespacePermissions() {
        var grant=new ProjectPolicy.Grant("TENANT","main",null,false,"MCP_SERVER",Set.of("READ","LIST"),Set.of("default"),false);
        var local=project(java.util.Map.of("r",scope(grant)));
        var evaluator=new ProjectAuthorization(new ProjectPolicy(2,java.util.Map.of("p",local)));
        var bounds=new ProjectAuthorization.Directory(local,"p",Set.of());
        var result=evaluator.authorizeList(id("ui","r"),new ProjectAuthorization.Request("main",null,"MCP_SERVER","LIST",null,true),bounds);
        assertTrue(result.allowed());
        assertTrue(result.contains(null,"default"));
        assertFalse(result.contains(null,"other"));
        assertFalse(result.contains("a","default"));
        assertFalse(evaluator.authorizeList(id("ui","r"),new ProjectAuthorization.Request("main","a","MCP_SERVER","LIST",null,true),bounds).allowed());
        assertFalse(evaluator.authorizeList(id("ui","r"),new ProjectAuthorization.Request("main","a","FLOW","LIST",null,true),bounds).allowed());
        assertFalse(evaluator.authorizeList(null,null,bounds).allowed());
    }

    @Test void reconciliationOnlyRepairsStaleLocalPolicyWithCurrentManagementAuthority() {
        var management = new ProjectPolicy.ManagementGrant(ProjectPolicy.ManagementDomain.LOCAL_POLICY,
            Set.of(ProjectPolicy.ManagementAction.MANAGE));
        var definition = new ProjectPolicy.ScopeDefinition("",true,java.util.List.of(),java.util.List.of(management));
        var local = project(java.util.Map.of("manager",definition,"unrelated",scope(grant("a",false,Set.of("READ"),Set.of()))));
        var current = new ProjectPolicy.Project(local.issuer(),local.audiences(),true,4,java.util.Map.of("manager",definition));
        var evaluator = new ProjectAuthorization(new ProjectPolicy(2,java.util.Map.of("p",local)));
        var identity = new ProjectAuthorization.Identity("s",local.issuer(),"ui","p",4,Set.of("manager"),Set.of());
        var directory = new ProjectAuthorization.Directory(current,"p",Set.of());
        assertTrue(evaluator.authorizeReconciliation(identity,directory));
        assertFalse(evaluator.authorizeManagement(identity,directory,ProjectPolicy.ManagementDomain.LOCAL_POLICY,ProjectPolicy.ManagementAction.MANAGE));
        assertFalse(evaluator.authorize(identity,new ProjectAuthorization.Request("main","a","FLOW","READ","f",false),directory).allowed());
        assertFalse(evaluator.authorizeReconciliation(new ProjectAuthorization.Identity("s",local.issuer(),"ui","p",4,Set.of("manager","unknown"),Set.of()),directory));
        assertFalse(evaluator.authorizeReconciliation(identity,new ProjectAuthorization.Directory(new ProjectPolicy.Project(local.issuer(),local.audiences(),true,4,java.util.Map.of()),"p",Set.of())));
        var disabled = new ProjectPolicy.ScopeDefinition("",false,java.util.List.of(),java.util.List.of(management));
        assertFalse(evaluator.authorizeReconciliation(identity,new ProjectAuthorization.Directory(new ProjectPolicy.Project(local.issuer(),local.audiences(),true,4,java.util.Map.of("manager",disabled)),"p",Set.of())));
        var readOnly = new ProjectPolicy.ScopeDefinition("",true,java.util.List.of(),java.util.List.of(new ProjectPolicy.ManagementGrant(ProjectPolicy.ManagementDomain.LOCAL_POLICY,Set.of(ProjectPolicy.ManagementAction.READ))));
        assertFalse(evaluator.authorizeReconciliation(identity,new ProjectAuthorization.Directory(new ProjectPolicy.Project(local.issuer(),local.audiences(),true,4,java.util.Map.of("manager",readOnly)),"p",Set.of())));
        assertFalse(auth.authorizeReconciliation(id("ui","read"),this.directory));
    }
    @Test void explicitLocalManagementTargetsDoNotIncludeOwnerOrOtherProjects() {
        var grant=new ProjectPolicy.ManagementGrant(ProjectPolicy.ManagementDomain.LOCAL_POLICY,
            Set.of(ProjectPolicy.ManagementAction.READ,ProjectPolicy.ManagementAction.MANAGE),Set.of("target"));
        var definition=new ProjectPolicy.ScopeDefinition("",true,java.util.List.of(),java.util.List.of(grant));
        var local=project(java.util.Map.of("manager",definition));
        var evaluator=new ProjectAuthorization(new ProjectPolicy(2,java.util.Map.of("p",local)));
        var bounds=new ProjectAuthorization.Directory(local,"p",Set.of());
        assertTrue(evaluator.authorizeManagement(id("ui","manager"),bounds,ProjectPolicy.ManagementDomain.LOCAL_POLICY,ProjectPolicy.ManagementAction.MANAGE,"target"));
        assertFalse(evaluator.authorizeManagement(id("ui","manager"),bounds,ProjectPolicy.ManagementDomain.LOCAL_POLICY,ProjectPolicy.ManagementAction.MANAGE));
        assertFalse(evaluator.authorizeManagement(id("ui","manager"),bounds,ProjectPolicy.ManagementDomain.LOCAL_POLICY,ProjectPolicy.ManagementAction.MANAGE,"other"));
        assertTrue(evaluator.authorizeReconciliation(id("ui","manager"),bounds,"target"));
        assertFalse(evaluator.authorizeReconciliation(id("ui","manager"),bounds,"p"));
        assertFalse(evaluator.authorizeManagement(id("ui","manager"),bounds,ProjectPolicy.ManagementDomain.IDENTITY,ProjectPolicy.ManagementAction.MANAGE,"target"));
        assertFalse(evaluator.authorizeManagement(id("ui","manager"),bounds,ProjectPolicy.ManagementDomain.LOCAL_POLICY,ProjectPolicy.ManagementAction.MANAGE,"*"));
        var parser=new ProjectPolicyParser();
        var policy=new ProjectPolicy(2,java.util.Map.of("p",local));
        assertEquals(policy,parser.parse(parser.canonicalJson(policy).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    @Test void managementActionCannotBorrowAnotherScopesProjectTarget() {
        var ownManage=new ProjectPolicy.ManagementGrant(ProjectPolicy.ManagementDomain.LOCAL_POLICY,Set.of(ProjectPolicy.ManagementAction.MANAGE));
        var targetRead=new ProjectPolicy.ManagementGrant(ProjectPolicy.ManagementDomain.LOCAL_POLICY,Set.of(ProjectPolicy.ManagementAction.READ),Set.of("target"));
        var local=project(java.util.Map.of(
            "manage",new ProjectPolicy.ScopeDefinition("",true,java.util.List.of(),java.util.List.of(ownManage)),
            "target",new ProjectPolicy.ScopeDefinition("",true,java.util.List.of(),java.util.List.of(targetRead))));
        var evaluator=new ProjectAuthorization(new ProjectPolicy(2,java.util.Map.of("p",local)));
        var bounds=new ProjectAuthorization.Directory(local,"p",Set.of());
        var identity=id("ui","manage","target");
        assertTrue(evaluator.authorizeManagement(identity,bounds,ProjectPolicy.ManagementDomain.LOCAL_POLICY,ProjectPolicy.ManagementAction.MANAGE,"p"));
        assertTrue(evaluator.authorizeManagement(identity,bounds,ProjectPolicy.ManagementDomain.LOCAL_POLICY,ProjectPolicy.ManagementAction.READ,"target"));
        assertFalse(evaluator.authorizeManagement(identity,bounds,ProjectPolicy.ManagementDomain.LOCAL_POLICY,ProjectPolicy.ManagementAction.MANAGE,"target"));
        assertFalse(evaluator.authorizeReconciliation(identity,bounds,"target"));
    }
    @Test void managementTargetsMustRemainWithinSameScopeDirectoryUpperBound() {
        var bounded=new ProjectPolicy.ManagementGrant(ProjectPolicy.ManagementDomain.LOCAL_POLICY,Set.of(ProjectPolicy.ManagementAction.MANAGE),Set.of("target"));
        var expanded=new ProjectPolicy.ManagementGrant(ProjectPolicy.ManagementDomain.LOCAL_POLICY,Set.of(ProjectPolicy.ManagementAction.MANAGE),Set.of("target","other"));
        var local=project(java.util.Map.of("manager",new ProjectPolicy.ScopeDefinition("",true,java.util.List.of(),java.util.List.of(expanded))));
        var upper=project(java.util.Map.of("manager",new ProjectPolicy.ScopeDefinition("",true,java.util.List.of(),java.util.List.of(bounded))));
        var evaluator=new ProjectAuthorization(new ProjectPolicy(2,java.util.Map.of("p",local)));
        var bounds=new ProjectAuthorization.Directory(upper,"p",Set.of());
        assertFalse(evaluator.authorizeManagement(id("ui","manager"),bounds,ProjectPolicy.ManagementDomain.LOCAL_POLICY,ProjectPolicy.ManagementAction.MANAGE,"target"));
        assertTrue(evaluator.authorizeReconciliation(id("ui","manager"),bounds,"target"),"Surviving same-scope target may repair withdrawn targets");
        assertFalse(evaluator.authorizeReconciliation(id("ui","manager"),bounds,"other"));
        assertThrows(IllegalArgumentException.class,()->new ProjectPolicy.ManagementGrant(ProjectPolicy.ManagementDomain.IDENTITY,Set.of(ProjectPolicy.ManagementAction.MANAGE),Set.of("target")));
        assertThrows(IllegalArgumentException.class,()->new ProjectPolicy.ManagementGrant(ProjectPolicy.ManagementDomain.LOCAL_POLICY,Set.of(ProjectPolicy.ManagementAction.MANAGE),Set.of("*")));
        assertThrows(IllegalArgumentException.class,()->new ProjectPolicy.ManagementGrant(ProjectPolicy.ManagementDomain.BUSINESS_INSTANCE,Set.of(ProjectPolicy.ManagementAction.MANAGE),Set.of("target")));
    }

}
