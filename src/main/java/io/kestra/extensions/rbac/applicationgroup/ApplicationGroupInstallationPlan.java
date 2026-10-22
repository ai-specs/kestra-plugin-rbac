package io.kestra.extensions.rbac.applicationgroup;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

public record ApplicationGroupInstallationPlan(int formatVersion,String installationId,String issuer,
    String uiClientId,String builtInApplicationGroupId,List<Entry> applicationGroups) {
    private static final ObjectMapper MAPPER=new ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public record Entry(ApplicationGroupDirectory.Group applicationGroup,
        List<ApplicationGroupDirectory.Scope> scopes,List<ApplicationGroupDirectory.Application> applications,
        List<ApplicationGroupDirectory.UserGrant> userGrants) {
        public Entry {
            var snapshot=new ApplicationGroupDirectory.Snapshot(applicationGroup,scopes,applications,userGrants);
            scopes=snapshot.scopes();applications=snapshot.applications();userGrants=snapshot.userGrants();
        }
        public ApplicationGroupDirectory.Snapshot snapshot() {
            return new ApplicationGroupDirectory.Snapshot(applicationGroup,scopes,applications,userGrants);
        }
    }

    public ApplicationGroupInstallationPlan {
        if(formatVersion!=1 || installationId==null || !UUID.fromString(installationId).toString().equals(installationId)
            || issuer==null || uiClientId==null || uiClientId.isBlank() || builtInApplicationGroupId==null
            || applicationGroups==null || applicationGroups.size()!=1)
            throw new IllegalArgumentException("One builtin application group required for new installation");
        new ApplicationGroupDirectory.UserGrant(issuer,"installation-validation",java.util.Set.of(),1);
        applicationGroups=List.copyOf(applicationGroups);
        var entry=applicationGroups.getFirst();var group=entry.applicationGroup();
        if(!builtInApplicationGroupId.equals(group.id()) || !group.active()
            || group.revision()!=1 || group.authorizationVersion()!=1)
            throw new IllegalArgumentException("Invalid builtin application group identity");
        var login=entry.applications().stream().filter(application->application.clientId().equals(uiClientId)).findFirst()
            .orElseThrow(()->new IllegalArgumentException("Configured login application registration required"));
        if(!login.approved() || login.supportedScopeKeys().isEmpty()
            || entry.scopes().stream().anyMatch(scope->scope.revision()!=1)
            || entry.applications().stream().anyMatch(application->application.revision()!=1)
            || entry.userGrants().stream().anyMatch(grant->!issuer.equals(grant.issuer()) || grant.revision()!=1)
            || entry.userGrants().stream().noneMatch(grant->grant.scopeKeys().stream().anyMatch(login.supportedScopeKeys()::contains)))
            throw new IllegalArgumentException("Initial support and user scope grants required");
    }

    public static ApplicationGroupInstallationPlan parse(byte[] bytes) {
        return review(bytes).plan();
    }

    public static Review review(byte[] bytes) {
        if(bytes.length>1024*1024)throw new IllegalArgumentException("Installation plan too large");
        try {
            JsonNode json=MAPPER.readTree(bytes);
            return new Review(MAPPER.treeToValue(json,ApplicationGroupInstallationPlan.class),canonical(json).toString());
        }
        catch(java.io.IOException failure){throw new IllegalArgumentException("Invalid installation plan",failure);}
    }

    public record Review(ApplicationGroupInstallationPlan plan,String canonicalJson) {
        public String approvalHash() {
            try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonicalJson.getBytes(StandardCharsets.UTF_8)));}
            catch(java.security.NoSuchAlgorithmException failure){throw new IllegalStateException(failure);}
        }
    }

    private static JsonNode canonical(JsonNode value) {
        if(value.isObject()) {
            ObjectNode result=MAPPER.createObjectNode();var names=new java.util.TreeSet<String>();
            value.fieldNames().forEachRemaining(names::add);names.forEach(name->result.set(name,canonical(value.get(name))));return result;
        }
        if(value.isArray()) {
            ArrayNode result=MAPPER.createArrayNode();value.forEach(item->result.add(canonical(item)));return result;
        }
        return value;
    }
}
