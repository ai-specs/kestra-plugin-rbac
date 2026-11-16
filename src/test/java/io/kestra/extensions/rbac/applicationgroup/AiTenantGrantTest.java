package io.kestra.extensions.rbac.applicationgroup;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import static io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.*;
import static org.junit.jupiter.api.Assertions.*;

class AiTenantGrantTest {
    private Grant tenant(Set<String> actions) {
        return new Grant("TENANT", "main", null, false, "AI", actions, Set.of(), true);
    }

    @Test void acceptsOnlyExplicitTenantReadExecuteShape() {
        for (var actions : List.of(Set.of("READ"), Set.of("EXECUTE"), Set.of("READ", "EXECUTE")))
            assertEquals(actions, tenant(actions).actions());
        for (String action : List.of("LIST", "CREATE", "UPDATE", "DELETE", "ACCESS_OUTPUTS"))
            assertThrows(IllegalArgumentException.class, () -> tenant(Set.of(action)));
        assertThrows(IllegalArgumentException.class, () -> tenant(Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new Grant("TENANT","other",null,false,"AI",Set.of("READ"),Set.of(),true));
        assertThrows(IllegalArgumentException.class, () -> new Grant("TENANT","main","a",false,"AI",Set.of("READ"),Set.of(),true));
        assertThrows(IllegalArgumentException.class, () -> new Grant("TENANT","main",null,true,"AI",Set.of("READ"),Set.of(),true));
        assertThrows(IllegalArgumentException.class, () -> new Grant("TENANT","main",null,false,"AI",Set.of("READ"),Set.of("id"),false));
    }

    @Test void preservesLegacyNamespaceGrantThroughJsonWithoutExpandingAuthority() throws Exception {
        var legacy = new Grant("NAMESPACE","main","company",true,"AI",Set.of("READ","LIST","UPDATE","EXECUTE"),Set.of("old"),false);
        var mapper = new ObjectMapper();
        assertEquals(legacy, mapper.readValue(mapper.writeValueAsBytes(legacy), Grant.class));
        var probe = new Probe(List.of(legacy));
        assertFalse(probe.allowed("READ"));
        assertFalse(probe.allowed("EXECUTE"));
    }

    @Test void separatesReadExecuteAndBusinessPermissions() {
        var read = new Probe(List.of(tenant(Set.of("READ"))));
        assertTrue(read.allowed("READ"));assertFalse(read.allowed("EXECUTE"));
        var execute = new Probe(List.of(tenant(Set.of("EXECUTE"))));
        assertTrue(execute.allowed("EXECUTE"));assertFalse(execute.allowed("READ"));
        assertFalse(execute.auth.authorize(execute.identity, new ApplicationGroupAuthorization.Request("main","company","FLOW","READ","flow",false),execute.directory).allowed());
        assertFalse(execute.auth.authorize(execute.identity, new ApplicationGroupAuthorization.Request("main","company","AI","EXECUTE",null,false),execute.directory).allowed());
        assertFalse(execute.auth.authorize(execute.identity, new ApplicationGroupAuthorization.Request("main",null,"AI","EXECUTE","thread",false),execute.directory).allowed());
        assertFalse(execute.auth.authorizeList(execute.identity, new ApplicationGroupAuthorization.Request("main",null,"AI","LIST",null,true),execute.directory).allowed());
        assertFalse(new Probe(Map.of(),Set.of()).allowed("EXECUTE"));
    }

    @Test void combinesExplicitAiActionsAcrossScopesButManagementDoesNotSupplyThem() {
        var scopes = Map.of("read", new ScopeDefinition("",true,List.of(tenant(Set.of("READ")))),
            "execute",new ScopeDefinition("",true,List.of(tenant(Set.of("EXECUTE")))));
        var probe = new Probe(scopes,Set.of("read","execute"));
        assertTrue(probe.allowed("READ"));assertTrue(probe.allowed("EXECUTE"));
        var management = new ScopeDefinition("",true,List.of(),List.of(new ManagementGrant(ManagementDomain.BUSINESS_INSTANCE,Set.of(ManagementAction.MANAGE))));
        assertFalse(new Probe(Map.of("manage",management),Set.of("manage")).allowed("EXECUTE"));
        assertFalse(new Probe(scopes,Set.of()).allowed("READ"));
    }

    @Test void tenantGrantCannotSupplyNamespaceAuthorityAndLegacyCannotSupplyTenantRead() {
        var tenant = new Probe(List.of(tenant(Set.of("READ","EXECUTE"))));
        for(String action:List.of("READ","EXECUTE"))
            assertFalse(tenant.auth.authorize(tenant.identity,new ApplicationGroupAuthorization.Request("main","company","AI",action,"flow",false),tenant.directory).allowed());
        var legacy=new Probe(List.of(new Grant("NAMESPACE","main","company",true,"AI",Set.of("READ","EXECUTE"),Set.of(),true)));
        assertFalse(legacy.allowed("READ"));assertFalse(legacy.allowed("EXECUTE"));
    }

    private static final class Probe {
        final ApplicationGroupAuthorization auth;
        final ApplicationGroupAuthorization.Directory directory;
        final ApplicationGroupAuthorization.Identity identity;
        Probe(List<Grant> grants) { this(Map.of("role",new ScopeDefinition("",true,grants)),Set.of("role")); }
        Probe(Map<String,ScopeDefinition> scopes, Set<String> granted) {
            var audiences = Set.of("ui");
            var group = new ApplicationGroup("https://issuer.example",audiences,true,scopes);
            auth = new ApplicationGroupAuthorization(new ApplicationGroupPolicy(2,Map.of("main",group)));
            var definitions = new java.util.HashMap<String,ApplicationGroupAuthorization.DirectoryScope>();
            scopes.keySet().forEach(key -> definitions.put(key,new ApplicationGroupAuthorization.DirectoryScope(key,"")));
            directory = new ApplicationGroupAuthorization.Directory(new ApplicationGroupAuthorization.ScopeCatalog("https://issuer.example",audiences,true,1,definitions),"main",Set.of("openid"));
            identity = new ApplicationGroupAuthorization.Identity("user","https://issuer.example","ui","main",1,granted,Set.of("openid"));
        }
        boolean allowed(String action) {
            return auth.authorize(identity,new ApplicationGroupAuthorization.Request("main",null,"AI",action,null,false),directory).allowed();
        }
    }
}
