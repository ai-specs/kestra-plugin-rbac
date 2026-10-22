package io.kestra.extensions.rbac.store;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicyParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeSet;

/** Offline review; never writes subject grants or changes a live policy. */
public final class ProjectMigrationReview {
    public static final int MAX_REPORT_BYTES=8*1024*1024;
    private final ObjectMapper mapper = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    public record Review(long sourceRevision, String sourceHash, String targetHash,
                         String operator, String reportJson, String approvalHash) {}

    /** Every old role requires an explicit MAP or DROP; mapping does not grant a subject scopes. */
    public Review preview(long sourceRevision, byte[] legacy, byte[] target, byte[] decisions, String operator) {
        require(sourceRevision > 0 && operator != null && !operator.isBlank(), "Source revision and operator required");
        var source = new PolicyDocumentValidator().parse(legacy);
        var targetParser=new ApplicationGroupPolicyParser();
        JsonNode destination=read(targetParser.canonicalJson(targetParser.parse(target)).getBytes(StandardCharsets.UTF_8)), plan=read(decisions);
        require(plan.isArray(), "Role decisions must be an array");
        var remaining = new TreeSet<String>();
        source.document().roles().forEach(role -> remaining.add(role.id().value()));
        for (JsonNode decision : plan) {
            fields(decision, Set.of("roleId", "disposition", "targets", "reason"));
            require(remaining.remove(text(decision, "roleId")), "Each old role requires exactly one decision");
            String disposition = text(decision, "disposition");
            require(Set.of("MAP", "DROP").contains(disposition), "MAP or DROP required");
            text(decision, "reason");
            JsonNode targets = decision.get("targets");
            require(targets != null && targets.isArray(), "Target array required");
            require(disposition.equals("MAP") ? !targets.isEmpty() : targets.isEmpty(), "MAP needs targets; DROP cannot have targets");
            var unique = new TreeSet<String>();
            for (JsonNode item : targets) {
                fields(item, Set.of("project", "scope"));
                String project = text(item, "project"), scope = text(item, "scope");
                require(unique.add(project + "\n" + scope), "Duplicate target");
                require(destination.path("applicationGroups").path(project).path("scopes").has(scope), "Missing target scope");
            }
        }
        require(remaining.isEmpty(), "Every old role needs an explicit decision");
        var report = mapper.createObjectNode();
        report.put("formatVersion", 1).put("sourceSchemaVersion", 1).put("targetSchemaVersion", 2)
            .put("sourceRevision", sourceRevision).put("operator", operator);
        String sourceHash = hash(source.canonicalJson().getBytes(StandardCharsets.UTF_8));
        String targetHash = hash(canonical(destination).toString().getBytes(StandardCharsets.UTF_8));
        report.put("sourceHash", sourceHash).put("targetHash", targetHash);
        report.set("legacyPolicy", read(source.canonicalJson().getBytes(StandardCharsets.UTF_8)));
        report.set("targetPolicy", destination); report.set("roleDecisions", plan);
        var differences=report.putArray("grantDifferences");
        JsonNode sourceTree=read(source.canonicalJson().getBytes(StandardCharsets.UTF_8));
        for(JsonNode decision:plan) {
            JsonNode oldRole=null;
            for(JsonNode item:sourceTree.path("roles"))if(item.path("id").asText().equals(decision.path("roleId").asText()))oldRole=item;
            var row=differences.addObject();row.put("roleId",decision.path("roleId").asText());
            var before=new TreeSet<String>();for(JsonNode grant:oldRole.path("grants"))before.add(canonical(grant).toString());
            var after=new TreeSet<String>();
            for(JsonNode targetRef:decision.path("targets"))
                for(JsonNode grant:destination.path("applicationGroups").path(targetRef.path("project").asText()).path("scopes").path(targetRef.path("scope").asText()).path("grants"))after.add(canonical(grant).toString());
            var removed=new TreeSet<>(before);removed.removeAll(after);
            var added=new TreeSet<>(after);added.removeAll(before);
            var removedArray=row.putArray("removedGrantDefinitions");removed.forEach(value->removedArray.add(read(value.getBytes(StandardCharsets.UTF_8))));
            var addedArray=row.putArray("addedGrantDefinitions");added.forEach(value->addedArray.add(read(value.getBytes(StandardCharsets.UTF_8))));
            row.put("identicalGrantDefinitions",before.equals(after));
            row.put("manualSemanticReviewRequired",true);
        }
        var combinations=report.putArray("legacyCrossRoleListDependencies");
        var nonNamespace=report.putArray("legacyNonNamespaceGrantsRequiringReview");
        for(JsonNode role:sourceTree.path("roles"))for(JsonNode grant:role.path("grants")) {
            if(!grant.path("scope").asText().equals("NAMESPACE")) {
                var row=nonNamespace.addObject();row.put("roleId",role.path("id").asText());
                row.set("grant",grant.deepCopy());row.put("automaticNamespaceMappingAllowed",false);
            }
        }
        var combinationKeys=new TreeSet<String>();
        for(var readRole:source.document().roles())for(var listRole:source.document().roles()) {
            if(readRole.id().equals(listRole.id()))continue;
            for(var readGrant:readRole.grants())for(var listGrant:listRole.grants()) {
                if(!readGrant.allIds()||!listGrant.allIds()||!readGrant.actions().contains(io.kestra.webserver.rbac.RbacAuthorizer.Action.READ)
                    ||!listGrant.actions().contains(io.kestra.webserver.rbac.RbacAuthorizer.Action.LIST)
                    ||readGrant.resource()!=listGrant.resource()||!java.util.Objects.equals(readGrant.tenant(),listGrant.tenant()))continue;
                String common=commonNamespace(readGrant,listGrant);if(common==null)continue;
                boolean ownRead=readRole.grants().stream().anyMatch(g->coversNamespace(g,common)&&g.resource()==readGrant.resource()&&g.actions().contains(io.kestra.webserver.rbac.RbacAuthorizer.Action.LIST)&&g.allIds());
                boolean ownList=listRole.grants().stream().anyMatch(g->coversNamespace(g,common)&&g.resource()==listGrant.resource()&&g.actions().contains(io.kestra.webserver.rbac.RbacAuthorizer.Action.READ)&&g.allIds());
                if(ownRead||ownList)continue;
                String key=readRole.id().value()+"\n"+listRole.id().value()+"\n"+readGrant.tenant()+"\n"+readGrant.resource().name()+"\n"+common;
                if(!combinationKeys.add(key))continue;
                require(combinationKeys.size()<=10000,"Too many cross-role dependencies for one migration review");
                var row=combinations.addObject();row.put("readRoleId",readRole.id().value()).put("listRoleId",listRole.id().value())
                    .put("tenant",readGrant.tenant()).put("resource",readGrant.resource().name()).put("commonNamespace",common)
                    .put("equivalentUnderSeparateScopes",false);
            }
        }
        // This report approves storage transformation only. The deployment bypass is a separate source,
        // never inferred from subject names or falsely represented as revoked by this offline tool.
        report.put("approvalScope","LOCAL_POLICY_STORAGE_ONLY");
        report.put("productionActivationApproved",false);
        var deployment=report.putObject("deploymentAuthorizationSource");
        deployment.put("source","EXTERNAL_DEPLOYMENT_CONFIGURATION");
        deployment.put("includedInLegacyPolicy",false);
        deployment.put("separateSourceHashAndApprovalRequired",true);
        deployment.put("credentialRevocationVerified",false);
        deployment.putArray("approvedReplacementGrants");
        var warnings = report.putArray("requiredConfirmations");
        warnings.add("Role decisions do not grant subject scopes. Approve subjects and clients through the published identity directory.");
        warnings.add("Cross-role READ/LIST combinations are not preserved. Each target scope must independently authorize its list domain.");
        warnings.add("Review all grant differences, increases, decreases, dropped roles and unapproved subjects.");
        warnings.add("Back up policy and identity databases plus installation receipt; verify rollback with matching old artifacts.");
        warnings.add("Invalidate old code/access/refresh/UI/IdP/MCP credentials; verify new administrator login before enabling traffic.");
        String json = canonical(report).toString();
        require(json.getBytes(StandardCharsets.UTF_8).length<=MAX_REPORT_BYTES-256,"Migration review exceeds report limit");
        return new Review(sourceRevision, sourceHash, targetHash, operator, json, hash(json.getBytes(StandardCharsets.UTF_8)));
    }
    public void verifyApproval(Review review, String approvedHash, long revision, byte[] currentLegacy) {
        require(review != null && hash(review.reportJson().getBytes(StandardCharsets.UTF_8)).equals(review.approvalHash())
            && review.approvalHash().equals(approvedHash), "Approval must match the complete review hash");
        require(readReview(envelope(review).getBytes(StandardCharsets.UTF_8)).equals(review),"Review must match a validated generated report");
        JsonNode report = read(review.reportJson().getBytes(StandardCharsets.UTF_8));
        require(report.path("sourceRevision").longValue() == review.sourceRevision()
            && report.path("sourceHash").asText().equals(review.sourceHash())
            && report.path("targetHash").asText().equals(review.targetHash())
            && report.path("operator").asText().equals(review.operator()), "Review fields do not match report");
        require(review.sourceRevision() == revision, "Source revision changed");
        String source = new PolicyDocumentValidator().parse(currentLegacy).canonicalJson();
        require(review.sourceHash().equals(hash(source.getBytes(StandardCharsets.UTF_8))), "Source content changed");
    }
    public Review readReview(byte[] envelope) {
        JsonNode value=read(envelope);
        fields(value,Set.of("report","approvalHash"));
        JsonNode report=value.get("report");
        require(report.isObject(),"Review report required");
        String json=canonical(report).toString();
        String approved=text(value,"approvalHash");
        require(hash(json.getBytes(StandardCharsets.UTF_8)).equals(approved),"Review hash does not match content");
        require(report.path("formatVersion").isIntegralNumber() && report.path("formatVersion").intValue()==1
            && report.path("sourceSchemaVersion").intValue()==1 && report.path("targetSchemaVersion").intValue()==2
            && report.path("sourceRevision").isIntegralNumber() && report.path("sourceRevision").canConvertToLong(),"Unsupported review version");
        var result=new Review(report.get("sourceRevision").longValue(),text(report,"sourceHash"),text(report,"targetHash"),text(report,"operator"),json,approved);
        require(result.sourceRevision()>0,"Invalid source revision");
        Review rebuilt=preview(result.sourceRevision(),report.path("legacyPolicy").toString().getBytes(StandardCharsets.UTF_8),
            report.path("targetPolicy").toString().getBytes(StandardCharsets.UTF_8),report.path("roleDecisions").toString().getBytes(StandardCharsets.UTF_8),result.operator());
        require(rebuilt.equals(result),"Report content, grant differences or policy hashes do not match source and target");
        return result;
    }
    public String envelope(Review review) {
        var value=mapper.createObjectNode();value.set("report",read(review.reportJson().getBytes(StandardCharsets.UTF_8)));
        value.put("approvalHash",review.approvalHash());return canonical(value).toString();
    }
    private static String commonNamespace(PolicyDocument.Grant a,PolicyDocument.Grant b) {
        if(a.scope()!=io.kestra.webserver.rbac.RbacAuthorizer.Scope.NAMESPACE
            ||b.scope()!=io.kestra.webserver.rbac.RbacAuthorizer.Scope.NAMESPACE
            ||a.namespace()==null||b.namespace()==null)return null;
        if(coversNamespace(a,b.namespace()))return b.namespace();
        if(coversNamespace(b,a.namespace()))return a.namespace();return null;
    }
    private static boolean coversNamespace(PolicyDocument.Grant grant,String namespace) {
        return grant.namespace()==null||grant.namespace().equals(namespace)
            ||grant.descendants()&&namespace.startsWith(grant.namespace()+".");
    }
    private JsonNode read(byte[] bytes) {
        require(bytes != null && bytes.length <= MAX_REPORT_BYTES, "Document exceeds limit");
        try (var parser = mapper.createParser(StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString())) {
            JsonNode node = mapper.readTree(parser);
            require(node != null && parser.nextToken() == null, "One JSON document required"); return node;
        } catch (java.io.IOException failure) { throw new IllegalArgumentException("Invalid migration JSON", failure); }
    }
    private JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = mapper.createObjectNode(); var names = new TreeSet<String>();
            node.fieldNames().forEachRemaining(names::add);
            names.forEach(name -> result.set(name, canonical(node.get(name)))); return result;
        }
        if (node.isArray()) {
            var items=new java.util.ArrayList<JsonNode>();node.forEach(value->items.add(canonical(value)));
            items.sort(java.util.Comparator.comparing(JsonNode::toString));
            ArrayNode result=mapper.createArrayNode();items.forEach(result::add);return result;
        }
        return node.deepCopy();
    }
    private static String text(JsonNode node, String key) {
        require(node.path(key).isTextual() && !node.get(key).textValue().isBlank(), "Non-empty text required: " + key);
        return node.get(key).textValue();
    }
    private static void fields(JsonNode node, Set<String> allowed) {
        require(node.isObject(), "Object required");
        node.fieldNames().forEachRemaining(key -> require(allowed.contains(key), "Unknown migration field"));
        require(node.size() == allowed.size(), "All migration fields required");
    }
    private static void require(boolean value, String message) { if (!value) throw new IllegalArgumentException(message); }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
