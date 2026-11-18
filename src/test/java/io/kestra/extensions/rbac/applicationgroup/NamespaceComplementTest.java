package io.kestra.extensions.rbac.applicationgroup;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.*;

class NamespaceComplementTest {
    static Grant grant(String n, boolean tree, Set<String> actions, String... ids) {
        return new Grant("NAMESPACE","main",n,tree,"FLOW",actions,Set.of(ids),ids.length==0);
    }
    static final Set<String> RL=Set.of("READ","LIST");
    record Evaluation(ApplicationGroupAuthorization auth, ApplicationGroupAuthorization.Identity identity, ApplicationGroupAuthorization.Directory directory) {
        ApplicationGroupAuthorization.ListDecision list() {return auth.authorizeList(identity,new ApplicationGroupAuthorization.Request("main",null,"FLOW","LIST",null,true,ApplicationGroupAuthorization.Domain.all()),directory);}
        boolean scalar(String ns,String id,String action) {return auth.authorize(identity,new ApplicationGroupAuthorization.Request("main",ns,"FLOW",action,id,false),directory).allowed();}
    }
    static Evaluation evaluation(Map<String,List<Grant>> definitions) {
        var scopes=new HashMap<String,ScopeDefinition>();var catalog=new HashMap<String,ApplicationGroupAuthorization.DirectoryScope>();
        definitions.forEach((key,grants)->{scopes.put(key,new ScopeDefinition("",true,grants));catalog.put(key,new ApplicationGroupAuthorization.DirectoryScope(key,""));});
        var policy=new ApplicationGroupPolicy(2,Map.of("main",new ApplicationGroup("https://issuer.example",Set.of("ui"),true,scopes)));
        return new Evaluation(new ApplicationGroupAuthorization(policy),new ApplicationGroupAuthorization.Identity("user","https://issuer.example","ui","main",1,definitions.keySet(),Set.of()),new ApplicationGroupAuthorization.Directory(new ApplicationGroupAuthorization.ScopeCatalog("https://issuer.example",Set.of("ui"),true,1,catalog),"main",Set.of()));
    }
    @Test void positiveAndComplementExactAndSubtreeMatchScalarAndList() {
        for(boolean tree:List.of(false,true))for(boolean negative:List.of(false,true)) {
            var e=evaluation(Map.of("r",List.of(grant((negative?"!":"")+"company",tree,RL))));
            for(String ns:List.of("company","company.sub","company.sub.future","company2","outside")) {
                boolean inside=ns.equals("company")||tree&&ns.startsWith("company.");
                assertEquals(negative?!inside:inside,e.scalar(ns,"id","READ"));
                assertEquals(negative?!inside:inside,e.list().contains(ns,"id"));
            }
            assertFalse(e.scalar(null,"id","READ"));
        }
    }
    @Test void complementAndPositiveTermsPreserveCorrelatedIdsAcrossScopes() {
        var e=evaluation(Map.of("outside",List.of(grant("!company",true,RL,"a")),"inside",List.of(grant("company",true,RL,"b"))));
        var q=e.list();assertTrue(q.contains("outside","a"));assertTrue(q.contains("company.sub","b"));
        assertFalse(q.contains("outside","b"));assertFalse(q.contains("company","a"));assertFalse(q.contains("outside",null));
    }
    @Test void readListIntersectsInsideScopeAndNeverBorrowsAcrossScopes() {
        var reads=grant("!company",true,Set.of("READ"),"a","b");var lists=grant("outside",true,Set.of("LIST"),"b","c");
        var q=evaluation(Map.of("one",List.of(reads,lists))).list();
        assertTrue(q.contains("outside.child","b"));assertFalse(q.contains("outside","a"));assertFalse(q.contains("company2","b"));
        assertFalse(evaluation(Map.of("read",List.of(reads),"list",List.of(lists))).list().allowed());
        assertFalse(evaluation(Map.of("one",List.of(grant("company",true,Set.of("READ")),grant("!company",true,Set.of("LIST"))))).list().allowed());
    }
    @Test void complementWriteRequiresExplicitActionAndDoesNotGrantExcludedTree() {
        var read=evaluation(Map.of("r",List.of(grant("!company",true,RL))));assertFalse(read.scalar("outside","a","UPDATE"));
        var write=evaluation(Map.of("w",List.of(grant("!company",true,Set.of("UPDATE")))));
        assertTrue(write.scalar("outside","a","UPDATE"));assertFalse(write.scalar("company.sub","a","UPDATE"));assertFalse(write.scalar("outside","a","READ"));
    }
    @Test void malformedExpressionsAndTenantExpressionsReject() {
        for(String name:List.of("!","!!company","!company.","!company..sub","! company","!company*"," company"))assertThrows(IllegalArgumentException.class,()->grant(name,true,RL));
        for(String resource:List.of("METADATA","MCP_SERVER","AI"))assertThrows(IllegalArgumentException.class,()->new Grant("TENANT","main","!company",true,resource,Set.of("READ"),Set.of(),true));
    }
    @Test void validLargeStoredGrantSetLoadsButQueryOverLimitFailsClosed() {
        var grants=new ArrayList<Grant>();for(int i=0;i<1000;i++)grants.add(grant("n"+i,false,RL));
        var e=evaluation(Map.of("r",grants));assertTrue(e.scalar("n999","id","READ"));assertFalse(e.list().allowed());
    }
    @Test void semanticCoverageAgreesWithFinitePartitionForAllSignedSelectorsAndIds() {
        var names=List.of("company","company.sub","company.probe","outside","!company","!company.sub","!outside","a","a.probe","a.probe.deep","!a","!a.probe.deep");
        var points=List.of("company","company.sub","company.sub.deep","company.probe","company.probe.child","company.other","company2","outside","outside.child","fresh","a","a.probe","a.probe.deep","a.probe.deep.child","a.probe.other","a.other","a.probex");
        for(String upper:names)for(String local:names)for(boolean ut:List.of(false,true))for(boolean lt:List.of(false,true)) {
            var u=grant(upper,ut,RL);var l=grant(local,lt,RL);
            boolean expected=points.stream().noneMatch(n->evaluation(Map.of("l",List.of(l))).scalar(n,"id","READ")&&!evaluation(Map.of("u",List.of(u))).scalar(n,"id","READ"));
            assertEquals(expected,ApplicationGroupAuthorization.covers(u,l),upper+" / "+local+" / "+ut+" / "+lt);
        }
        var random=new Random(20261118);
        var universe=new ArrayList<String>();universe.add("a");universe.add("probe");
        for(int depth=0;depth<5;depth++)for(String prefix:List.copyOf(universe))
            for(String part:List.of("a","probe","fresh"))if(prefix.split("\\.").length==depth+1)universe.add(prefix+"."+part);
        for(int sample=0;sample<500;sample++) {
            String un=universe.get(random.nextInt(universe.size())),ln=universe.get(random.nextInt(universe.size()));
            var u=grant((random.nextBoolean()?"!":"")+un,random.nextBoolean(),RL);
            var l=grant((random.nextBoolean()?"!":"")+ln,random.nextBoolean(),RL);
            var ue=evaluation(Map.of("u",List.of(u)));var le=evaluation(Map.of("l",List.of(l)));
            var witnesses=new HashSet<>(universe);witnesses.add(un+".new");witnesses.add(ln+".new");witnesses.add("unrelated");
            boolean expected=witnesses.stream().noneMatch(n->le.scalar(n,"id","READ")&&!ue.scalar(n,"id","READ"));
            assertEquals(expected,ApplicationGroupAuthorization.covers(u,l),u+" vs "+l);
        }
        assertFalse(ApplicationGroupAuthorization.covers(grant("!company",true,RL,"a"),grant("outside",true,RL,"b")));
        assertTrue(ApplicationGroupAuthorization.covers(grant("!company",true,RL),grant("outside",true,RL,"b")));
    }
}
