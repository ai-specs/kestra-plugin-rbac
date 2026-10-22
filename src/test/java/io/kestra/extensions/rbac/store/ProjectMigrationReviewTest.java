package io.kestra.extensions.rbac.store;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ProjectMigrationReviewTest {
    static final String LEGACY = """
        {"schemaVersion":1,"roles":[{"id":"reader","name":"Reader","description":"Old","grants":[]}],
        "subjects":{"alice":["reader"]},"roleMapping":{"old-reader":["reader"]}}
        """;
    static final String TARGET = """
        {"schemaVersion":2,"applicationGroups":{"kestra":{"issuer":"https://identity.example.invalid",
        "audiences":["kestra-ui"],"enabled":true,"scopes":{}}}}
        """;
    static final String DROP = """
        [{"roleId":"reader","disposition":"DROP","targets":[],"reason":"No approved equivalent"}]
        """;
    static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    ProjectMigrationReview.Review preview(String plan) {
        return new ProjectMigrationReview().preview(7, bytes(LEGACY), bytes(TARGET), bytes(plan), "operator");
    }
    @Test void reportRetainsOldReferencesWithoutGrantingSubjects() {
        var review=preview(DROP);
        assertTrue(review.reportJson().contains("alice")); assertTrue(review.reportJson().contains("old-reader"));
        assertTrue(review.reportJson().contains("No approved equivalent")); assertEquals(64,review.approvalHash().length());
        assertDoesNotThrow(()->new ProjectMigrationReview().verifyApproval(review,review.approvalHash(),7,bytes(LEGACY)));
    }
    @Test void everyOldRoleRequiresExactlyOneExplicitDecision() {
        assertThrows(IllegalArgumentException.class,()->preview("[]"));
        assertThrows(IllegalArgumentException.class,()->preview(DROP.replace("reader","unknown")));
        String one=DROP.strip().substring(1,DROP.strip().length()-1);
        assertThrows(IllegalArgumentException.class,()->preview("["+one+","+one+"]"));
    }
    @Test void mappingCannotInferOrInventTargetScope() {
        assertThrows(IllegalArgumentException.class,()->preview(DROP.replace("DROP","MAP")));
        assertThrows(IllegalArgumentException.class,()->preview(DROP.replace("DROP","MAP").replace("\"targets\":[]","\"targets\":[{\"project\":\"kestra\",\"scope\":\"reader\"}]")));
    }
    @Test void approvalBindsRevisionContentAndOperatorReview() {
        var review=preview(DROP); var service=new ProjectMigrationReview();
        assertThrows(IllegalArgumentException.class,()->service.verifyApproval(review,"0".repeat(64),7,bytes(LEGACY)));
        assertThrows(IllegalArgumentException.class,()->service.verifyApproval(review,review.approvalHash(),8,bytes(LEGACY)));
        assertThrows(IllegalArgumentException.class,()->service.verifyApproval(review,review.approvalHash(),7,bytes(LEGACY.replace("alice","bob"))));
        assertNotEquals(review.approvalHash(),preview(DROP.replace("No approved equivalent","Retire explicitly")).approvalHash());
        var forged=new ProjectMigrationReview.Review(7,review.sourceHash(),review.targetHash(),"other",review.reportJson(),review.approvalHash());
        assertThrows(IllegalArgumentException.class,()->service.verifyApproval(forged,review.approvalHash(),7,bytes(LEGACY)));
    }
    @Test void reportsCrossRoleListLossInsteadOfPreservingItSilently() throws Exception {
        String grant="{\"scope\":\"NAMESPACE\",\"tenant\":\"main\",\"namespace\":\"a\",\"descendants\":true,\"resource\":\"FLOW\",\"actions\":[\"%s\"],\"allIds\":true}";
        String old="{\"schemaVersion\":1,\"roles\":[{\"id\":\"r\",\"name\":\"Read\",\"grants\":["+grant.formatted("READ")+"]},{\"id\":\"l\",\"name\":\"List\",\"grants\":["+grant.formatted("LIST")+"]}],\"subjects\":{\"alice\":[\"r\",\"l\"]},\"roleMapping\":{}}";
        String plan="[{\"roleId\":\"r\",\"disposition\":\"DROP\",\"targets\":[],\"reason\":\"Review\"},{\"roleId\":\"l\",\"disposition\":\"DROP\",\"targets\":[],\"reason\":\"Review\"}]";
        var service=new ProjectMigrationReview();var review=service.preview(9,bytes(old),bytes(TARGET),bytes(plan),"operator");
        var tree=new com.fasterxml.jackson.databind.ObjectMapper().readTree(review.reportJson());
        assertEquals(1,tree.path("legacyCrossRoleListDependencies").size());
        assertFalse(tree.path("legacyCrossRoleListDependencies").get(0).path("equivalentUnderSeparateScopes").asBoolean());
        assertEquals(2,tree.path("grantDifferences").size());
        assertEquals(review,service.readReview(bytes(service.envelope(review))));
    }
    @Test void forgedReportCannotHideLegacyPolicyEvenWithARecomputedEnvelopeHash() throws Exception {
        var service=new ProjectMigrationReview();var review=preview(DROP);
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var report=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(review.reportJson());
        ((com.fasterxml.jackson.databind.node.ObjectNode)report.path("legacyPolicy").path("subjects")).remove("alice");
        // Recompute the outer hash: rejection must come from regenerated evidence, not a stale hash.
        String recomputed=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(report.toString().getBytes(StandardCharsets.UTF_8)));
        var forged=new ProjectMigrationReview.Review(review.sourceRevision(),review.sourceHash(),review.targetHash(),review.operator(),report.toString(),recomputed);
        assertThrows(IllegalArgumentException.class,()->service.readReview(bytes(service.envelope(forged))));
    }
    @Test void formattingAndObjectOrderDoNotChangeApprovalHash()throws Exception {
        var service=new ProjectMigrationReview();var original=preview(DROP);
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var changed=service.preview(7,bytes(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapper.readTree(LEGACY))),
            bytes("{\"applicationGroups\":"+mapper.readTree(TARGET).get("applicationGroups")+",\"schemaVersion\":2}"),
            bytes(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapper.readTree(DROP))),"operator");
        assertEquals(original.sourceHash(),changed.sourceHash());
        assertEquals(original.targetHash(),changed.targetHash());
        assertEquals(original.approvalHash(),changed.approvalHash());
    }
    @Test void unknownFieldsAndTrailingDocumentsDeny() {
        assertThrows(IllegalArgumentException.class,()->preview(DROP.replace("\"reason\":","\"extra\":true,\"reason\":")));
        assertThrows(IllegalArgumentException.class,()->preview(DROP+" []"));
    }
    @Test void tenantGrantsAreExplicitReviewItemsAndNeverNamespaceIntersections()throws Exception {
        String grant="{\"scope\":\"TENANT\",\"tenant\":\"main\",\"namespace\":null,\"descendants\":false,\"resource\":\"MCP_SERVER\",\"actions\":[\"%s\"],\"allIds\":true}";
        String old="{\"schemaVersion\":1,\"roles\":[{\"id\":\"r\",\"name\":\"Read\",\"grants\":["+grant.formatted("READ")+"]},{\"id\":\"l\",\"name\":\"List\",\"grants\":["+grant.formatted("LIST")+"]}],\"subjects\":{},\"roleMapping\":{}}";
        String plan="[{\"roleId\":\"r\",\"disposition\":\"DROP\",\"targets\":[],\"reason\":\"Explicit review\"},{\"roleId\":\"l\",\"disposition\":\"DROP\",\"targets\":[],\"reason\":\"Explicit review\"}]";
        var service=new ProjectMigrationReview();var review=service.preview(1,bytes(old),bytes(TARGET),bytes(plan),"operator");
        var report=new com.fasterxml.jackson.databind.ObjectMapper().readTree(review.reportJson());
        assertTrue(report.path("legacyCrossRoleListDependencies").isEmpty());
        assertEquals(2,report.path("legacyNonNamespaceGrantsRequiringReview").size());
        for(var row:report.path("legacyNonNamespaceGrantsRequiringReview")) {
            assertEquals("TENANT",row.path("grant").path("scope").asText());
            assertFalse(row.path("automaticNamespaceMappingAllowed").asBoolean(true));
        }
        assertEquals(review,service.readReview(bytes(service.envelope(review))));
        assertThrows(IllegalArgumentException.class,()->service.preview(1,bytes(old.replace("TENANT","INSTANCE")),bytes(TARGET),bytes(plan),"operator"));
    }
    @Test void deploymentBypassIsASeparateUnapprovedSourceAndNeverClaimsRealRevocation()throws Exception {
        var service=new ProjectMigrationReview();var review=preview(DROP);
        var report=new com.fasterxml.jackson.databind.ObjectMapper().readTree(review.reportJson());
        assertEquals("LOCAL_POLICY_STORAGE_ONLY",report.path("approvalScope").textValue());
        assertFalse(report.path("productionActivationApproved").booleanValue());
        var deployment=report.path("deploymentAuthorizationSource");
        assertFalse(deployment.path("includedInLegacyPolicy").booleanValue());
        assertTrue(deployment.path("separateSourceHashAndApprovalRequired").booleanValue());
        assertFalse(deployment.path("credentialRevocationVerified").booleanValue());
        assertTrue(deployment.path("approvedReplacementGrants").isEmpty());
        assertEquals(review,service.readReview(bytes(service.envelope(review))));
    }

}
