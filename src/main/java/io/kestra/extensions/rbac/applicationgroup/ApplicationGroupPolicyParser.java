package io.kestra.extensions.rbac.applicationgroup;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.*;
import java.net.URI;

public final class ApplicationGroupPolicyParser {
    public String canonicalJson(ApplicationGroupPolicy policy) {
        try {
            JsonNode tree = mapper.valueToTree(policy);
            String json = mapper.writeValueAsString(normalize(tree));
            parse(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return json;
        } catch (Exception failure) { throw new IllegalArgumentException("Cannot canonicalize policy", failure); }
    }
    private JsonNode normalize(JsonNode value) {
        if (value.isObject()) {
            var result = mapper.createObjectNode();
            var keys = new TreeSet<String>(); value.fieldNames().forEachRemaining(keys::add);
            keys.forEach(key -> result.set(key, normalize(value.get(key)))); return result;
        }
        if (value.isArray()) {
            var values = new ArrayList<JsonNode>(); value.forEach(item -> values.add(normalize(item)));
            values.sort(Comparator.comparing(JsonNode::toString)); var result = mapper.createArrayNode(); values.forEach(result::add); return result;
        }
        return value;
    }
    private final ObjectMapper mapper = new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    public ApplicationGroupPolicy parse(byte[] input) {
        try {
            if (input == null || input.length > 1024 * 1024) throw new IllegalArgumentException("Policy exceeds byte limit");
            String content = java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(input)).toString();
            JsonNode root;
            try (var parser = mapper.createParser(content)) {
                root = mapper.readTree(parser);
                if (parser.nextToken() != null) throw new IllegalArgumentException("Trailing JSON");
            }
            fields(root, Set.of("schemaVersion", "applicationGroups"));
            object(root.get("applicationGroups"));
            if (!root.path("schemaVersion").isIntegralNumber() || !root.path("schemaVersion").canConvertToInt() || root.path("schemaVersion").intValue() != 2) throw new IllegalArgumentException("schemaVersion must be 2");
            Map<String, ApplicationGroupPolicy.ApplicationGroup> projects = new TreeMap<>();
            root.get("applicationGroups").fields().forEachRemaining(entry -> projects.put(name(entry.getKey()), project(entry.getValue())));
            return new ApplicationGroupPolicy(2, projects);
        } catch (Exception failure) {
            if (failure instanceof IllegalArgumentException invalid) throw invalid;
            throw new IllegalArgumentException("Invalid schemaVersion 2 JSON", failure);
        }
    }
    private ApplicationGroupPolicy.ApplicationGroup project(JsonNode node) {
        fields(node, Set.of("issuer", "audiences", "enabled", "scopes"));
        Set<String> audiences = strings(node.get("audiences"),true);
        Map<String, ApplicationGroupPolicy.ScopeDefinition> scopes = new TreeMap<>();
        object(node.get("scopes"));
        node.get("scopes").fields().forEachRemaining(entry -> {
            scopeName(entry.getKey());
            scopes.put(entry.getKey(), scope(entry.getValue()));
        });
        String issuer = text(node,"issuer");
        URI issuerUri;
        try { issuerUri = URI.create(issuer); }
        catch (RuntimeException failure) { throw new IllegalArgumentException("Invalid issuer URI", failure); }
        if (!issuerUri.isAbsolute() || issuerUri.getHost() == null
            || !Set.of("https", "http").contains(issuerUri.getScheme()) || issuerUri.getFragment() != null
            || issuerUri.getQuery() != null || issuerUri.getUserInfo() != null) {
            throw new IllegalArgumentException("Invalid issuer URI");
        }
        return new ApplicationGroupPolicy.ApplicationGroup(issuer, audiences, flag(node,"enabled"), scopes);
    }
    private ApplicationGroupPolicy.ScopeDefinition scope(JsonNode node) {
        fields(node, Set.of("description", "enabled", "grants", "managementGrants"));
        if (!node.path("description").isTextual()) throw new IllegalArgumentException("Description required");
        List<ApplicationGroupPolicy.Grant> grants = new ArrayList<>();
        if (!node.path("grants").isArray() || node.get("grants").size() > 1000) throw new IllegalArgumentException("grants must be non-empty");
        for (JsonNode grant : node.get("grants")) {
            fields(grant, Set.of("scope", "tenant", "namespace", "descendants", "resource", "actions", "ids", "allIds"));
            grants.add(new ApplicationGroupPolicy.Grant(text(grant, "scope"), text(grant, "tenant"),
                nullableText(grant, "namespace"), flag(grant, "descendants"), text(grant, "resource"),
                strings(grant.get("actions")), strings(grant.get("ids"), true), flag(grant, "allIds")));
        }
        List<ApplicationGroupPolicy.ManagementGrant> management = new ArrayList<>();
        if (node.has("managementGrants")) {
            if (!node.get("managementGrants").isArray() || node.get("managementGrants").size() > 3) throw new IllegalArgumentException("Invalid management grants");
            Set<ApplicationGroupPolicy.ManagementDomain> domains = new HashSet<>();
            for (JsonNode entry : node.get("managementGrants")) {
                fields(entry, Set.of("domain", "actions", "applicationGroupIds"));
                var domain = ApplicationGroupPolicy.ManagementDomain.valueOf(text(entry,"domain"));
                if (!domains.add(domain)) throw new IllegalArgumentException("Duplicate management domain");
                Set<ApplicationGroupPolicy.ManagementAction> actions = new HashSet<>();
                strings(entry.get("actions")).forEach(action -> actions.add(ApplicationGroupPolicy.ManagementAction.valueOf(action)));
                management.add(new ApplicationGroupPolicy.ManagementGrant(domain, actions, entry.has("applicationGroupIds") ? strings(entry.get("applicationGroupIds"), true) : Set.of()));
            }
        }
        return new ApplicationGroupPolicy.ScopeDefinition(node.get("description").textValue(), flag(node,"enabled"), grants, management);
    }
    private static String name(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")) {
            throw new IllegalArgumentException("Invalid application group identifier");
        }
        return value;
    }

    private static void scopeName(String value) {
        if (value == null || !value.matches("[a-z][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException("Invalid scope identifier");
        }
    }

    private static String text(JsonNode node, String key) {
        if (!node.path(key).isTextual() || node.get(key).textValue().isBlank()) {
            throw new IllegalArgumentException("Text required: " + key);
        }
        return node.get(key).textValue();
    }

    private static String nullableText(JsonNode node, String key) {
        if (!node.has(key)) throw new IllegalArgumentException("Required nullable field: " + key);
        return node.get(key).isNull() ? null : text(node, key);
    }

    private static boolean flag(JsonNode node, String key) {
        if (!node.path(key).isBoolean()) throw new IllegalArgumentException("Boolean required: " + key);
        return node.get(key).booleanValue();
    }

    private static Set<String> strings(JsonNode node) { return strings(node, false); }

    private static Set<String> strings(JsonNode node, boolean allowEmpty) {
        if (node == null || !node.isArray() || node.size() > 1000 || !allowEmpty && node.isEmpty()) {
            throw new IllegalArgumentException("Invalid string array");
        }
        Set<String> result = new TreeSet<>();
        for (JsonNode value : node) {
            if (!value.isTextual() || value.textValue().isBlank() || !result.add(nameValue(value.textValue()))) {
                throw new IllegalArgumentException("Invalid or duplicate string");
            }
        }
        return Set.copyOf(result);
    }

    private static String nameValue(String value) {
        if (!value.equals(value.trim()) || value.contains("*") || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Exact value required");
        }
        return value;
    }

    private static void object(JsonNode node) {
        if (node == null || !node.isObject() || node.size() > 1000) throw new IllegalArgumentException("Object required");
    }

    private static void fields(JsonNode node, Set<String> allowed) {
        object(node);
        node.fieldNames().forEachRemaining(key -> {
            if (!allowed.contains(key)) throw new IllegalArgumentException("Unknown field: " + key);
        });
    }
}
