package io.kestra.extensions.rbac.applicationgroup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ApplicationGroupInstallationPlanTest {
    private static final ObjectMapper MAPPER=new ObjectMapper();
    private static final String PLAN="""
        {"formatVersion":1,"installationId":"28e9352d-ea66-4a24-888f-e76fda93fef0","issuer":"https://issuer.example",
         "uiClientId":"ui","builtInApplicationGroupId":"first","applicationGroups":[{
          "applicationGroup":{"id":"first","displayName":"First","active":true,"revision":1,"authorizationVersion":1},
          "scopes":[{"key":"read","name":"Reader","description":"Read access","revision":1},
                    {"key":"manage","name":"Manager","description":"Manage access","revision":1}],
          "applications":[{"clientId":"ui","approved":true,"audiences":["ui"],"supportedScopeKeys":["read","manage"],"revision":1}],
          "userGrants":[{"issuer":"https://issuer.example","subject":"admin","scopeKeys":["manage"],"revision":1}]}]}
        """;

    private static ObjectNode input()throws Exception { return (ObjectNode)MAPPER.readTree(PLAN); }
    private static ApplicationGroupInstallationPlan.Review review(ObjectNode input) {
        return ApplicationGroupInstallationPlan.review(input.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Test void validatesNewInstallationAndPreservesReviewedArrayOrder()throws Exception {
        var reviewed=review(input());var plan=reviewed.plan();
        assertEquals("first",plan.builtInApplicationGroupId());assertEquals(1,plan.applicationGroups().size());
        assertEquals(java.util.Set.of("manage"),plan.applicationGroups().getFirst().snapshot()
            .eligibleScopeKeys("ui",plan.issuer(),"admin"));
        assertEquals(MAPPER.readTree(PLAN),MAPPER.readTree(reviewed.canonicalJson()));
    }

    @Test void approvalBindsValuesAndArrayOrderButIgnoresObjectKeyOrder()throws Exception {
        var original=input();var reordered=MAPPER.createObjectNode();
        var names=new java.util.ArrayList<String>();original.fieldNames().forEachRemaining(names::add);
        java.util.Collections.reverse(names);names.forEach(name->reordered.set(name,original.get(name).deepCopy()));
        assertEquals(review(original).approvalHash(),review(reordered).approvalHash());
        var scopes=(com.fasterxml.jackson.databind.node.ArrayNode)reordered.at("/applicationGroups/0/scopes");
        var first=scopes.remove(0);scopes.add(first);
        assertNotEquals(review(original).approvalHash(),review(reordered).approvalHash());
        ((ObjectNode)original.at("/applicationGroups/0/applicationGroup")).put("displayName","Changed");
        assertNotEquals(review(original).approvalHash(),review(input()).approvalHash());
    }

    @Test void rejectsLegacyAliasesAndUnknownNestedFields()throws Exception {
        var old=input();old.set("projects",old.remove("applicationGroups"));
        assertThrows(IllegalArgumentException.class,()->review(old));
        var extra=input();((ObjectNode)extra.at("/applicationGroups/0/applications/0")).put("firstPartyUi",true);
        assertThrows(IllegalArgumentException.class,()->review(extra));
    }

    @Test void rejectsDuplicateFieldsTrailingDataAndMissingProperties()throws Exception {
        assertThrows(IllegalArgumentException.class,()->ApplicationGroupInstallationPlan.parse(
            PLAN.replace("\"formatVersion\":1","\"formatVersion\":1,\"formatVersion\":1").getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,()->ApplicationGroupInstallationPlan.parse((PLAN+"{}").getBytes(StandardCharsets.UTF_8)));
        var missing=input();missing.remove("installationId");assertThrows(IllegalArgumentException.class,()->review(missing));
    }

    @Test void requiresApprovedLoginSupportAndAnActualUserGrant()throws Exception {
        var unapproved=input();((ObjectNode)unapproved.at("/applicationGroups/0/applications/0")).put("approved",false);
        assertThrows(IllegalArgumentException.class,()->review(unapproved));
        var unsupported=input();((ObjectNode)unsupported.at("/applicationGroups/0/applications/0")).putArray("supportedScopeKeys");
        assertThrows(IllegalArgumentException.class,()->review(unsupported));
        var noGrant=input();((ObjectNode)noGrant.at("/applicationGroups/0")).putArray("userGrants");
        assertThrows(IllegalArgumentException.class,()->review(noGrant));
    }

    @Test void rejectsForeignIssuerAndUndefinedScopeReferences()throws Exception {
        var foreign=input();((ObjectNode)foreign.at("/applicationGroups/0/userGrants/0")).put("issuer","https://other.example");
        assertThrows(IllegalArgumentException.class,()->review(foreign));
        var undefined=input();((ObjectNode)undefined.at("/applicationGroups/0/userGrants/0")).putArray("scopeKeys").add("unknown");
        assertThrows(IllegalArgumentException.class,()->review(undefined));
    }

    @Test void requiresOneActiveBuiltinGroupAndInitialVersions()throws Exception {
        var different=input();different.put("builtInApplicationGroupId","other");assertThrows(IllegalArgumentException.class,()->review(different));
        var inactive=input();((ObjectNode)inactive.at("/applicationGroups/0/applicationGroup")).put("active",false);
        assertThrows(IllegalArgumentException.class,()->review(inactive));
        var stale=input();((ObjectNode)stale.at("/applicationGroups/0/applicationGroup")).put("revision",2);
        assertThrows(IllegalArgumentException.class,()->review(stale));
        var multiple=input();((com.fasterxml.jackson.databind.node.ArrayNode)multiple.get("applicationGroups")).add(multiple.at("/applicationGroups/0").deepCopy());
        assertThrows(IllegalArgumentException.class,()->review(multiple));
    }

    @Test void rejectsInvalidInstallationIdentityAndOversizedInput()throws Exception {
        var invalid=input();invalid.put("installationId","not-an-installation");assertThrows(IllegalArgumentException.class,()->review(invalid));
        assertThrows(IllegalArgumentException.class,()->ApplicationGroupInstallationPlan.parse(new byte[1024*1024+1]));
    }
}
